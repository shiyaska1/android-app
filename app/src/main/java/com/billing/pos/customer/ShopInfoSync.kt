package com.billing.pos.customer

import android.content.Context
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.License
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pushes the shop's own name/phone/address/UPI/location to the server, independent of the
 * product catalog (see server/pos_online_catalog.php, do=updateShopInfo). A shop with no
 * browsable items (a service-by-note/prescription shop — see ShopCatalogSync's 404 handling)
 * never calls [OnlineCatalogUpload.upload], so without this its name and phone would never reach
 * a customer at all. Called right after Settings is saved or online-ordering settings are
 * imported, so this reaches the server the moment it's entered rather than only if/when the shop
 * owner happens to also upload a product catalog.
 */
object ShopInfoSync {

    suspend fun push(context: Context): Boolean = withContext(Dispatchers.IO) {
        val prefs = AppPrefs(context)
        val base = prefs.onlineCatalogUrl
        val shop = prefs.shopCode.ifBlank { License.deviceId(context) }
        if (base.isBlank()) return@withContext false

        val body = JSONObject().apply {
            put("shop", shop)
            put("shopName", prefs.companyName)
            put("shopPhone", prefs.companyPhone)
            put("shopCategory", prefs.businessType)
            put("shopAddress", prefs.companyAddress)
            if (prefs.upiId.isNotBlank()) {
                put("shopUpi", prefs.upiId)
                put("shopUpiName", prefs.upiName.ifBlank { prefs.companyName })
            }
            if (prefs.shopLocationCaptured) {
                put("shopLat", prefs.shopLatitude)
                put("shopLng", prefs.shopLongitude)
            }
        }
        val sep = if (base.contains("?")) "&" else "?"
        val url = "$base${sep}do=updateShopInfo"

        try {
            val conn = URL(url).openConnection() as HttpURLConnection
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
            code in 200..299
        } catch (e: Exception) {
            false
        }
    }
}
