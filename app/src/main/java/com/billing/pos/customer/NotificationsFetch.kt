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
 * Customer side: polls the server for order-status updates / messages / promotions the shop sent
 * (see [OrderStatusPush] and [BroadcastPromotion] on the shop side), inserts new ones into the
 * local customer_notifications table, and returns the fresh ones so the caller can post a system
 * notification for each. Read-once on the server (see pos_online_catalog.php's do=notifications).
 *
 * A customer can be connected to several shops (see [ShopSwitch]), each potentially on its own
 * server — so this always checks every known shop, not just whichever one is currently active,
 * otherwise a promotion from a shop the customer isn't currently viewing would never arrive.
 * [ShopSwitch.isMuted] shops are skipped entirely.
 */
object NotificationsFetch {

    sealed class Result {
        data class Ok(val fresh: List<CustomerNotification>) : Result()
        data class Failed(val message: String) : Result()
    }

    /** Checks every shop this device has ever connected to (skipping muted ones), tagging each
     *  notification with which shop it came from. Any single shop's failure doesn't stop the
     *  others from being checked. */
    suspend fun fetchAllKnownShops(context: Context): Result = withContext(Dispatchers.IO) {
        val phone = AppPrefs(context).customerPhone
        if (phone.isBlank()) return@withContext Result.Ok(emptyList())

        val targets = ShopSwitch.known(context).filter { !ShopSwitch.isMuted(context, it.shop) }
        if (targets.isEmpty()) return@withContext Result.Ok(emptyList())

        val fresh = mutableListOf<CustomerNotification>()
        var lastFailure: String? = null
        for (target in targets) {
            when (val result = fetchOne(context, target, phone)) {
                is Result.Ok -> fresh += result.fresh
                is Result.Failed -> lastFailure = result.message
            }
        }
        if (fresh.isEmpty() && lastFailure != null && targets.size == 1) Result.Failed(lastFailure) else Result.Ok(fresh)
    }

    private suspend fun fetchOne(context: Context, target: ShopSwitch.Shop, phone: String): Result = withContext(Dispatchers.IO) {
        val base = target.url
        val shop = target.shop
        if (base.isBlank() || shop.isBlank()) return@withContext Result.Ok(emptyList())

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
                    shop = shop,
                    shopName = target.name.ifBlank { shop },
                    receivedAt = System.currentTimeMillis(),
                    amount = o.optDouble("amount", 0.0)
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
