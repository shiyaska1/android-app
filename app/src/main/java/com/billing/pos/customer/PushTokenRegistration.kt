package com.billing.pos.customer

import android.content.Context
import android.util.Log
import com.billing.pos.data.AppPrefs
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

/**
 * Registers this device's current FCM token with the server (see server/pos_online_catalog.php,
 * do=registerToken), so a new order/status/message can be pushed instantly instead of waiting for
 * the 30-min poll (see [ShopOrderPoll], [CustomerNotificationPoll] — those still run as a
 * best-effort fallback for whenever a push doesn't arrive, e.g. no internet at the moment it's sent).
 *
 * Best-effort only: a shop or customer with no internet, an old Play Services build, or a server
 * that hasn't set up $SERVICE_ACCOUNT_PATH yet just keeps working off the poll, same as before FCM
 * existed.
 */
object PushTokenRegistration {
    private const val TAG = "PushTokenRegistration"

    /** Call on app open (once online ordering is in use) and from onNewToken. Silently does
     *  nothing until there's a URL, a shop code, and (for a customer) a phone number to register
     *  under — and skips the network call entirely if this exact token+identity was already sent. */
    suspend fun registerIfNeeded(context: Context) = withContext(Dispatchers.IO) {
        try {
            val prefs = AppPrefs(context)
            val base = prefs.onlineCatalogUrl
            val shop = prefs.shopCode
            if (base.isBlank() || shop.isBlank()) return@withContext

            val role = if (prefs.customerMode) "customer" else "shop"
            val phone = if (prefs.customerMode) prefs.customerPhone else ""
            if (prefs.customerMode && phone.isBlank()) return@withContext // not registered as a customer yet

            val token = currentToken()
            if (token.isNullOrBlank()) return@withContext

            val identity = if (role == "customer") "customer:$shop:$phone" else "shop:$shop"
            val already = "$token|$identity"
            if (prefs.fcmTokenRegisteredFor == already) return@withContext // nothing changed since last time

            if (send(base, shop, role, phone, token)) {
                prefs.fcmToken = token
                prefs.fcmTokenRegisteredFor = already
            }
        } catch (e: Exception) {
            Log.w(TAG, "registerIfNeeded failed", e)
        }
    }

    /** Wraps FirebaseMessaging's callback-based token fetch in a suspend call, without pulling in
     *  the kotlinx-coroutines-play-services dependency just for one Task. */
    private suspend fun currentToken(): String? = suspendCancellableCoroutine { cont ->
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            cont.resume(if (task.isSuccessful) task.result else null)
        }
    }

    private fun send(base: String, shop: String, role: String, phone: String, token: String): Boolean {
        val body = JSONObject().apply {
            put("shop", shop)
            put("role", role)
            if (phone.isNotBlank()) put("phone", phone)
            put("token", token)
        }
        val sep = if (base.contains("?")) "&" else "?"
        val url = "$base${sep}do=registerToken"
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
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
