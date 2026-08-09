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
 *   "shopCategory": "Restaurant" (the shop owner's Settings > Business type),
 *   "shopAddress": "..." (the shop owner's Company address),
 *   "shopUpi": "..." (optional, the shop's UPI ID — lets the customer pay online at order time),
 *   "shopUpiName": "..." (optional, payee name for the UPI app),
 *   "bannerImage": "data:image/jpeg;base64,..." (optional, shown atop the customer catalog),
 *   "items": [{ "id": "SKU1", "name": "Chicken Biryani", "category": "Main Course",
 *               "price": 180.0, "unit": "plate", "imageUrl": "", "description": "",
 *               "driveLink": "" (optional — one or more links, comma-separated; direct image
 *               links become a tap-to-zoom gallery for the customer, others a plain clickable
 *               link) }, ...] }
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
            if (code == 404) {
                // Not every shop sells a browsable product list — a service-by-note/prescription
                // shop (see CustomerCatalogScreen's manual note+attachment order) may never open
                // Online Items to upload one at all, so the server has never written a catalog.json
                // for this shop code. That's a normal, working setup, not a failure: showing the
                // "Technical error" dialog here would scare off a brand-new customer before they
                // ever see the note-order option, even though nothing is actually broken.
                conn.disconnect()
                AppDatabase.get(context).shopCatalogDao().replaceForShop(shop, emptyList())
                return@withContext Result.Ok(0)
            }
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
                prefs.shopDisplayCategory = root.optString("shopCategory", "")
                prefs.shopDisplayAddress = root.optString("shopAddress", "")
                prefs.shopUpiId = root.optString("shopUpi", "")
                prefs.shopUpiName = root.optString("shopUpiName", "")
            }

            val array = parseItemsArray(body)
                ?: return@withContext Result.Failed("Unrecognised response from server")

            val items = (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").ifBlank { return@mapNotNull null }
                val name = o.optString("name").ifBlank { return@mapNotNull null }
                ShopCatalogItem(
                    shop = shop,
                    serverId = id,
                    name = name,
                    category = o.optString("category", ""),
                    price = o.optDouble("price", 0.0),
                    unit = o.optString("unit", ""),
                    imageUrl = o.optString("imageUrl", ""),
                    description = o.optString("description", ""),
                    driveLink = o.optString("driveLink", "")
                )
            }

            AppDatabase.get(context).shopCatalogDao().replaceForShop(shop, items)
            prefs.catalogLastFetchedAt = System.currentTimeMillis()

            // Keep this shop's entry in the "Browse shops" directory — and its cached snapshot
            // for instant switch-back — fresh, same as any other shop this device has connected to.
            ShopSwitch.rememberKnown(
                context,
                ShopSwitch.Shop(
                    shop = shop, url = base,
                    type = prefs.customerBusinessType, premium = prefs.customerPremiumShop,
                    name = prefs.shopDisplayName,
                    category = prefs.shopDisplayCategory.ifBlank { prefs.customerBusinessType },
                    address = prefs.shopDisplayAddress,
                    phone = prefs.shopContactPhone,
                    bannerImage = prefs.shopBannerImage,
                    lastFetchedAt = prefs.catalogLastFetchedAt,
                    upi = prefs.shopUpiId,
                    upiName = prefs.shopUpiName
                )
            )
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
