package com.billing.pos.customer

import android.content.Context
import com.billing.pos.data.AppDatabase
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.License
import com.billing.pos.data.ShopMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Shop owner side: pushes an order's status change (or a plain message) to the server so the
 * customer app picks it up on its next [NotificationsFetch] poll, and files a local "OUT"
 * [ShopMessage] row either way — so the Messages thread shows what was sent even if the push
 * itself failed (best-effort: a failure here doesn't undo the local status change, see
 * OnlineOrdersViewModel.setStatus, it just means the customer won't be notified this time).
 */
object OrderStatusPush {

    suspend fun push(
        context: Context,
        customerPhone: String,
        orderId: String,
        status: String? = null,
        message: String? = null,
        customerName: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        if (customerPhone.isBlank()) return@withContext false
        val prefs = AppPrefs(context)
        val base = prefs.fetchOrdersUrl.ifBlank { prefs.onlineCatalogUrl }
        val shop = prefs.shopCode.ifBlank { License.deviceId(context) }
        if (base.isBlank()) return@withContext false

        val body = JSONObject().apply {
            put("shop", shop)
            put("customerPhone", customerPhone)
            if (orderId.isNotBlank()) put("orderId", orderId)
            if (!status.isNullOrBlank()) put("status", status)
            if (!message.isNullOrBlank()) put("message", message)
        }
        val sep = if (base.contains("?")) "&" else "?"
        val url = "$base${sep}do=status"

        val ok = try {
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

        val text = message?.takeIf { it.isNotBlank() }
            ?: com.billing.pos.data.OnlineOrderStatus.entries.find { it.name == status }?.label
        if (!text.isNullOrBlank()) {
            AppDatabase.get(context).shopMessageDao().insert(
                ShopMessage(
                    customerPhone = customerPhone, customerName = customerName, orderId = orderId,
                    direction = "OUT", text = text, sentAt = System.currentTimeMillis(), read = true
                )
            )
        }
        ok
    }
}
