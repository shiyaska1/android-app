package com.billing.pos.customer

import android.content.Context
import com.billing.pos.data.AppDatabase
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.ShopCatalogItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches the shop's online catalog and caches it locally, so the customer app can keep
 * browsing (and re-order) offline after the first successful fetch.
 *
 * Expected server response — a bare JSON array, or an object with an "items" array plus the
 * shop's own contact details (so the customer can reach them to confirm an order, ahead of a
 * real place-order flow):
 * ```
 * { "shopName": "Anand Stores", "shopPhone": "9198XXXXXXX",
 *   "bannerImage": "data:image/jpeg;base64,..." (optional, shown atop the customer catalog),
 *   "items": [{ "id": "SKU1", "name": "Chicken Biryani", "category": "Main Course",
 *               "price": 180.0, "unit": "plate", "imageUrl": "", "description": "" }, ...] }
 * ```
 * Only "id", "name" and "price" are required per item; the rest default to blank/zero.
 * "shopName"/"shopPhone" are optional and only read when the root is an object, not a bare array.
 */
object ShopCatalogSync {

    sealed class Result {
        data class Ok(val count: Int) : Result()
        data class Failed(val message: String) : Result()
    }

    suspend fun refresh(context: Context): Result = withContext(Dispatchers.IO) {
        val prefs = AppPrefs(context)
        val base = prefs.onlineCatalogUrl
        val shop = prefs.shopCode
        if (base.isBlank() || shop.isBlank()) return@withContext Result.Failed("Not linked to a shop yet")

        val sep = if (base.contains("?")) "&" else "?"
        val url = "$base${sep}shop=${java.net.URLEncoder.encode(shop, "UTF-8")}"

        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return@withContext Result.Failed("Server returned HTTP $code")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val root = runCatching { JSONObject(body.trim()) }.getOrNull()
            if (root != null) {
                root.optString("shopName").takeIf { it.isNotBlank() }?.let { prefs.shopDisplayName = it }
                root.optString("shopPhone").takeIf { it.isNotBlank() }?.let { prefs.shopContactPhone = it }
                prefs.shopBannerImage = root.optString("bannerImage", "")
            }

            val array = parseItemsArray(body)
                ?: return@withContext Result.Failed("Unrecognised response from server")

            val items = (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").ifBlank { return@mapNotNull null }
                val name = o.optString("name").ifBlank { return@mapNotNull null }
                ShopCatalogItem(
                    serverId = id,
                    name = name,
                    category = o.optString("category", ""),
                    price = o.optDouble("price", 0.0),
                    unit = o.optString("unit", ""),
                    imageUrl = o.optString("imageUrl", ""),
                    description = o.optString("description", "")
                )
            }

            AppDatabase.get(context).shopCatalogDao().replaceAll(items)
            prefs.catalogLastFetchedAt = System.currentTimeMillis()
            Result.Ok(items.size)
        } catch (e: Exception) {
            Result.Failed(e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        }
    }

    private fun parseItemsArray(body: String): JSONArray? = runCatching {
        val trimmed = body.trim()
        when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            trimmed.startsWith("{") -> JSONObject(trimmed).optJSONArray("items")
            else -> null
        }
    }.getOrNull()
}
