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
        customerName: String = "",
        amount: Double = 0.0,
        attachments: List<String> = emptyList()
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
            if (amount > 0.0) put("amount", amount)
            if (attachments.isNotEmpty()) put("attachments", org.json.JSONArray(attachments))
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

        val baseText = message?.takeIf { it.isNotBlank() }
            ?: com.billing.pos.data.OnlineOrderStatus.entries.find { it.name == status }?.label
        // ShopMessage has no dedicated amount column — folded into the stored text instead, same
        // as how it's shown to the customer (see NotificationsFetch/NotificationsDialog).
        var text = if (amount > 0.0) {
            (baseText?.plus("\n\n") ?: "") + "Bill amount: " + com.billing.pos.util.Format.rupee(amount)
        } else baseText
        if (attachments.isNotEmpty()) {
            val kind = if (attachments.any { com.billing.pos.util.VoiceAttachment.isAudio(it) }) "attachment/voice note" else "attachment(s)"
            text = (text?.plus("\n\n") ?: "") + "[Sent ${attachments.size} $kind]"
        }
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
