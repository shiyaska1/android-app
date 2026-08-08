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
 * Shop owner side: sends one message to every customer who has ever registered a push token for
 * this shop (see server/pos_online_catalog.php, do=broadcast) — a sales offer, a new-stock
 * announcement, etc. It lands in each customer's normal notification list, the same as an
 * order-status update, and gets pushed instantly the same way.
 */
object BroadcastPromotion {

    sealed class Result {
        data class Ok(val count: Int) : Result()
        data class Failed(val message: String) : Result()
    }

    suspend fun send(context: Context, message: String): Result = withContext(Dispatchers.IO) {
        val prefs = AppPrefs(context)
        val base = prefs.onlineCatalogUrl
        // Same fallback every other server call in the app uses (registerToken, catalog upload,
        // orders fetch, ...) — prefs.shopCode alone is blank unless the shop owner has explicitly
        // typed something into Settings > Online ordering's "Shop code" field, even though that
        // field visually shows the device id as a placeholder default. Reading prefs.shopCode
        // directly here (as this used to) silently broadcasts under the wrong/empty shop code —
        // the server either 400s or, worse, queues it in a folder no customer's token was ever
        // registered under, so nobody receives it and it looks like a silent no-op.
        val shop = prefs.shopCode.ifBlank { License.deviceId(context) }
        if (base.isBlank()) return@withContext Result.Failed("Set the online catalog URL in Settings first")
        if (message.isBlank()) return@withContext Result.Failed("Write a message first")

        val body = JSONObject().apply {
            put("shop", shop)
            put("message", message)
        }
        val sep = if (base.contains("?")) "&" else "?"
        val url = "$base${sep}do=broadcast"

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
            val responseBody = runCatching {
                (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            conn.disconnect()
            if (code in 200..299) {
                val count = runCatching { JSONObject(responseBody.orEmpty()).optInt("count", 0) }.getOrDefault(0)
                Result.Ok(count)
            } else {
                Result.Failed("Server returned HTTP $code")
            }
        } catch (e: Exception) {
            Result.Failed(e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        }
    }
}
