package com.billing.pos.customer

import android.content.Context
import com.billing.pos.data.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Customer side: sends a reply to the shop — the customer->shop mirror of [OrderStatusPush].
 * Picked up by the shop owner's next [ShopMessagesFetch] poll (or opening the Messages screen).
 */
object CustomerMessageSend {

    suspend fun send(context: Context, message: String, orderId: String = ""): Boolean = withContext(Dispatchers.IO) {
        if (message.isBlank()) return@withContext false
        val prefs = AppPrefs(context)
        val base = prefs.onlineCatalogUrl
        val shop = prefs.shopCode
        if (base.isBlank() || shop.isBlank()) return@withContext false

        val body = JSONObject().apply {
            put("shop", shop)
            put("customerPhone", prefs.customerPhone)
            put("customerName", prefs.customerName)
            if (orderId.isNotBlank()) put("orderId", orderId)
            put("message", message)
        }
        val sep = if (base.contains("?")) "&" else "?"
        val url = "$base${sep}do=message"

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
