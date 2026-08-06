package com.billing.pos.customer

import android.content.Context
import com.billing.pos.data.AppDatabase
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.License
import com.billing.pos.data.ShopMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Shop owner side: polls for messages customers have sent (see [CustomerMessageSend] on the
 * customer side), inserting each as an "IN" [ShopMessage] row. Read-once on the server (see
 * pos_online_catalog.php's do=customerMessages) — this table is the permanent copy.
 */
object ShopMessagesFetch {

    sealed class Result {
        data class Ok(val fresh: List<ShopMessage>) : Result()
        data class Failed(val message: String) : Result()
    }

    suspend fun fetch(context: Context): Result = withContext(Dispatchers.IO) {
        val prefs = AppPrefs(context)
        val base = prefs.fetchOrdersUrl.ifBlank { prefs.onlineCatalogUrl }
        val shop = prefs.shopCode.ifBlank { License.deviceId(context) }
        if (base.isBlank()) return@withContext Result.Ok(emptyList())

        val sep = if (base.contains("?")) "&" else "?"
        val url = "$base${sep}do=customerMessages&shop=${java.net.URLEncoder.encode(shop, "UTF-8")}"

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

            val array = runCatching { JSONArray(body.trim()) }.getOrNull()
                ?: return@withContext Result.Failed("Unrecognised response from server")

            val dao = AppDatabase.get(context).shopMessageDao()
            val fresh = mutableListOf<ShopMessage>()
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val phone = o.optString("customerPhone")
                val text = o.optString("message")
                if (phone.isBlank() || text.isBlank()) continue
                val message = ShopMessage(
                    customerPhone = phone,
                    customerName = o.optString("customerName"),
                    orderId = o.optString("orderId"),
                    direction = "IN",
                    text = text,
                    sentAt = System.currentTimeMillis(),
                    read = false
                )
                dao.insert(message)
                fresh += message
            }
            Result.Ok(fresh)
        } catch (e: Exception) {
            Result.Failed(e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        }
    }
}
