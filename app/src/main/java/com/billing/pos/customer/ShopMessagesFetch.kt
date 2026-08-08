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

            val db = AppDatabase.get(context)
            val dao = db.shopMessageDao()
            val orderDao = db.onlineOrderDao()
            val fresh = mutableListOf<ShopMessage>()
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val phone = o.optString("customerPhone")
                val text = o.optString("message")
                if (phone.isBlank() || text.isBlank()) continue
                val orderId = o.optString("orderId")
                val attachmentsArr = o.optJSONArray("attachments")
                val attachments = if (attachmentsArr != null) {
                    (0 until attachmentsArr.length()).map { attachmentsArr.optString(it) }.filter { it.isNotBlank() }
                } else emptyList()
                val message = ShopMessage(
                    customerPhone = phone,
                    customerName = o.optString("customerName"),
                    orderId = orderId,
                    direction = "IN",
                    text = text,
                    sentAt = System.currentTimeMillis(),
                    read = false,
                    attachments = ShopMessage.packAttachments(attachments),
                    customerPremium = o.optBoolean("premium", false)
                )
                dao.insert(message)
                fresh += message
                // The customer's own report of paying via UPI for this order (see
                // CustomerCatalogViewModel.markNotificationPaid) — flip the local Online Orders
                // list's payment status too, not just leave it as a chat message the shop owner
                // has to read and act on manually. A no-op if the order's already been
                // Accepted/converted (no longer in online_orders).
                if (o.optString("paymentStatus") == "UPI" && orderId.isNotBlank()) {
                    orderDao.updatePaymentStatus(orderId, "UPI")
                }
            }
            Result.Ok(fresh)
        } catch (e: Exception) {
            Result.Failed(e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        }
    }
}
