package com.billing.pos.customer

import android.content.Context
import com.billing.pos.data.AppDatabase
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.CustomerNotification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Customer side: polls the server for order-status updates / messages the shop sent (see
 * [com.billing.pos.customer.OrderStatusPush] on the shop side), inserts new ones into the local
 * customer_notifications table, and returns the fresh ones so the caller can post a system
 * notification for each. Read-once on the server (see pos_online_catalog.php's do=notifications).
 */
object NotificationsFetch {

    sealed class Result {
        data class Ok(val fresh: List<CustomerNotification>) : Result()
        data class Failed(val message: String) : Result()
    }

    suspend fun fetch(context: Context): Result = withContext(Dispatchers.IO) {
        val prefs = AppPrefs(context)
        val base = prefs.onlineCatalogUrl
        val shop = prefs.shopCode
        val phone = prefs.customerPhone
        if (base.isBlank() || shop.isBlank() || phone.isBlank()) return@withContext Result.Ok(emptyList())

        val sep = if (base.contains("?")) "&" else "?"
        val url = "$base${sep}do=notifications&shop=${java.net.URLEncoder.encode(shop, "UTF-8")}" +
            "&phone=${java.net.URLEncoder.encode(phone, "UTF-8")}"

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

            val dao = AppDatabase.get(context).customerNotificationDao()
            val fresh = mutableListOf<CustomerNotification>()
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val notification = CustomerNotification(
                    orderId = o.optString("orderId"),
                    status = o.optString("status"),
                    message = o.optString("message"),
                    receivedAt = System.currentTimeMillis()
                )
                dao.insert(notification)
                fresh += notification
            }
            Result.Ok(fresh)
        } catch (e: Exception) {
            Result.Failed(e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        }
    }
}
