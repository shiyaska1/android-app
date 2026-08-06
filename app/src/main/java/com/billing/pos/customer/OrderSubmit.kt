package com.billing.pos.customer

import android.content.Context
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.ShopCatalogItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Places an order: POSTs the selected items to [AppPrefs.onlineCatalogUrl] with `do=order`
 * added, so the shop owner's "Orders" fetch picks it up later. Same URL as the catalog itself —
 * see server/pos_online_catalog.php.
 */
object OrderSubmit {

    sealed class Result {
        data class Ok(val orderId: String) : Result()
        data class Failed(val message: String) : Result()
    }

    suspend fun submit(
        context: Context,
        selection: List<Pair<ShopCatalogItem, Int>>,
        customerName: String,
        customerPhone: String,
        locationLink: String? = null,
        customerAddress: String? = null,
        note: String? = null,
        attachmentImage: String? = null
    ): Result = withContext(Dispatchers.IO) {
        val prefs = AppPrefs(context)
        val base = prefs.onlineCatalogUrl
        val shop = prefs.shopCode
        if (base.isBlank() || shop.isBlank()) return@withContext Result.Failed("Not linked to a shop yet")
        // A customer who doesn't want to pick from the catalog can just write what they want in
        // the note instead — only reject if there's genuinely nothing to send either way.
        if (selection.isEmpty() && note.isNullOrBlank()) return@withContext Result.Failed("Nothing selected")

        val total = selection.sumOf { (item, qty) -> item.price * qty }
        val body = JSONObject().apply {
            put("shop", shop)
            put("customerName", customerName)
            put("customerPhone", customerPhone)
            put("total", total)
            if (!locationLink.isNullOrBlank()) put("location", locationLink)
            if (!customerAddress.isNullOrBlank()) put("customerAddress", customerAddress)
            if (!note.isNullOrBlank()) put("note", note)
            if (!attachmentImage.isNullOrBlank()) put("attachmentImage", attachmentImage)
            put("items", JSONArray().apply {
                selection.forEach { (item, qty) ->
                    put(JSONObject().apply {
                        put("id", item.serverId)
                        put("name", item.name)
                        put("qty", qty)
                        put("price", item.price)
                    })
                }
            })
        }

        val sep = if (base.contains("?")) "&" else "?"
        val url = "$base${sep}do=order"

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
                val orderId = runCatching { JSONObject(responseBody.orEmpty()).optString("id") }.getOrNull().orEmpty()
                Result.Ok(orderId)
            } else {
                Result.Failed("Server returned HTTP $code")
            }
        } catch (e: Exception) {
            Result.Failed(e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        }
    }
}
