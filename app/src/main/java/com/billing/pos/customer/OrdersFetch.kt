package com.billing.pos.customer

import android.content.Context
import com.billing.pos.data.AppDatabase
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.Customer
import com.billing.pos.data.License
import com.billing.pos.data.OnlineOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Shop owner side: fetches orders customers have placed (see [OrderSubmit] on the customer side),
 * matching each one to an existing [Customer] by phone or creating a new one, then keeps its own
 * permanent local copy — the server only ever holds them briefly (pos_online_catalog.php's
 * do=orders clears them once fetched).
 */
object OrdersFetch {

    sealed class Result {
        data class Ok(val count: Int) : Result()
        data class Failed(val message: String) : Result()
    }

    suspend fun fetch(context: Context): Result = withContext(Dispatchers.IO) {
        val prefs = AppPrefs(context)
        val base = prefs.fetchOrdersUrl.ifBlank { prefs.onlineCatalogUrl }
        val shop = prefs.shopCode.ifBlank { License.deviceId(context) }
        if (base.isBlank()) return@withContext Result.Failed("Set the online catalog URL in Settings first")

        val sep = if (base.contains("?")) "&" else "?"
        val url = "$base${sep}do=orders&shop=${java.net.URLEncoder.encode(shop, "UTF-8")}"

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
            val customerDao = db.customerDao()
            val orderDao = db.onlineOrderDao()
            val known = orderDao.allServerIds().toHashSet()

            var saved = 0
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val serverId = o.optString("id")
                if (serverId.isBlank() || serverId in known) continue // no id, or already have this one (a retried fetch, etc.)

                val name = o.optString("customerName").ifBlank { "Online customer" }
                val phone = o.optString("customerPhone")
                // A brand-new customer (no existing match by phone) is tagged "Online Customer" in
                // the shop's own Customer master, so the shop owner can tell at a glance who came
                // in through online ordering versus a walk-in — an existing customer who happens
                // to also order online keeps whatever type they already had (VIP, Wholesale, ...).
                val customerId = if (phone.isNotBlank()) {
                    customerDao.byPhone(phone)?.id
                        ?: customerDao.insert(Customer(name = name, phone = phone, customerType = "Online Customer"))
                } else {
                    customerDao.insert(Customer(name = name, phone = phone, customerType = "Online Customer"))
                }

                val itemsArr = o.optJSONArray("items")
                val lines = itemsArr?.let { arr ->
                    (0 until arr.length()).map { j ->
                        val lineObj = arr.getJSONObject(j)
                        OnlineOrder.Line(lineObj.optString("name"), lineObj.optInt("qty", 1), lineObj.optDouble("price", 0.0))
                    }
                } ?: emptyList()

                val attachmentsArr = o.optJSONArray("attachments")
                val attachments = if (attachmentsArr != null) {
                    (0 until attachmentsArr.length()).map { attachmentsArr.optString(it) }.filter { it.isNotBlank() }
                } else {
                    // Older customer app build (single attachmentImage, before multi-attachment support).
                    o.optString("attachmentImage").takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList()
                }

                orderDao.insert(
                    OnlineOrder(
                        serverId = serverId,
                        customerId = customerId,
                        customerName = name,
                        customerPhone = phone,
                        itemsJson = OnlineOrder.packItems(lines),
                        total = o.optDouble("total", lines.sumOf { it.price * it.qty }),
                        receivedAt = o.optString("receivedAt"),
                        fetchedAt = System.currentTimeMillis(),
                        location = o.optString("location"),
                        customerAddress = o.optString("customerAddress"),
                        note = o.optString("note"),
                        attachmentImages = OnlineOrder.packAttachments(attachments),
                        paymentStatus = o.optString("paymentStatus")
                    )
                )
                saved++
            }
            Result.Ok(saved)
        } catch (e: Exception) {
            Result.Failed(e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        }
    }
}
