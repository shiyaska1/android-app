package com.billing.pos.customer

import android.content.Context
import android.util.Base64
import com.billing.pos.data.AppDatabase
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.Item
import com.billing.pos.data.License
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Shop owner side of the online catalog: POSTs the items marked [Item.isOnline] to
 * [AppPrefs.onlineCatalogUrl], for the customer app ([ShopCatalogSync]) to fetch. The item's own
 * id (a Long, local to this device) becomes the "id" the customer app hands back on an order —
 * good enough while there's one catalog per shop; a multi-device shop would need something more
 * durable, but that's not built yet.
 *
 * The shop "code" is just this device's [License.deviceId] — the same id already shown on the
 * license/activation screen, so there's nothing extra for the shop owner to set up. [AppPrefs.shopCode]
 * can still override it (e.g. a shop that reinstalls and wants to keep the same customer links).
 */
object OnlineCatalogUpload {

    sealed class Result {
        data class Ok(val count: Int) : Result()
        data class Failed(val message: String) : Result()
    }

    suspend fun upload(context: Context, items: List<Item>): Result = withContext(Dispatchers.IO) {
        val prefs = AppPrefs(context)
        val base = prefs.onlineCatalogUrl
        val shop = prefs.shopCode.ifBlank { License.deviceId(context) }
        if (base.isBlank()) return@withContext Result.Failed("Set the online catalog URL in Settings first")

        val attachmentDao = AppDatabase.get(context).itemAttachmentDao()
        val body = JSONObject().apply {
            put("shop", shop)
            put("shopName", prefs.companyName)
            put("shopPhone", prefs.companyPhone)
            // Shown at the top of the customer catalog screen — same compressed-thumbnail
            // convention as item photos, picked once in Settings > Online ordering.
            val bannerPath = prefs.onlineBannerPath
            val bannerThumb = if (bannerPath.isNotBlank()) runCatching { ThumbnailCompressor.compress(bannerPath) }.getOrNull() else null
            if (bannerThumb != null) {
                put("bannerImage", "data:image/jpeg;base64," + Base64.encodeToString(bannerThumb, Base64.NO_WRAP))
            }
            put("items", JSONArray().apply {
                items.forEach { item ->
                    put(JSONObject().apply {
                        put("id", item.id.toString())
                        put("name", item.name)
                        put("category", item.category)
                        put("price", if (item.onlineOfferPrice > 0.0) item.onlineOfferPrice else item.price)
                        put("unit", item.unit)
                        // Whatever photo is already attached to this item (Items > photo), shrunk to a
                        // small thumbnail and embedded directly — the customer app gets it in the same
                        // fetch that gets the item list, no separate image hosting needed.
                        val photoPath = attachmentDao.forItem(item.id).firstOrNull { it.kind == "PHOTO" }?.path
                        val thumb = photoPath?.let { runCatching { ThumbnailCompressor.compress(it) }.getOrNull() }
                        if (thumb != null) {
                            val base64 = Base64.encodeToString(thumb, Base64.NO_WRAP)
                            put("imageUrl", "data:image/jpeg;base64,$base64")
                        }
                    })
                }
            })
        }

        try {
            val conn = URL(base).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty("Content-Type", "application/json")
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            conn.disconnect()
            if (code in 200..299) Result.Ok(items.size) else Result.Failed("Server returned HTTP $code")
        } catch (e: Exception) {
            Result.Failed(e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        }
    }
}
