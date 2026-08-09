package com.billing.pos.customer

import android.content.Context
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.License
import com.billing.pos.data.Repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Shop owner side: fetches customers who registered their name/phone in the customer app for the
 * first time — before placing any order (see the "Register your details" prompt in
 * CustomerCatalogScreen and do=registerToken/do=newCustomers server-side). Each one is added to
 * this shop's own Customer master, tagged "Online Customer" (same tag [OrdersFetch] uses for a
 * first order from an unknown phone), so the shop owner can see and message them even before they
 * ever buy anything.
 *
 * The customer's typed name always has their phone appended (e.g. "Rahul (98765xxxxx)") — two
 * different customers can easily share a first name, and since [Repository.addCustomer] reuses an
 * existing ledger account of the exact same name (see Repository.ensureCustomerHead), an
 * unqualified name risks silently merging two different people's dues/invoice history onto one
 * account. The phone suffix keeps every online customer's ledger head unique.
 */
object OnlineCustomersFetch {

    sealed class Result {
        data class Ok(val registered: List<String>) : Result()
        data class Failed(val message: String) : Result()
    }

    suspend fun fetch(context: Context): Result = withContext(Dispatchers.IO) {
        val prefs = AppPrefs(context)
        val base = prefs.fetchOrdersUrl.ifBlank { prefs.onlineCatalogUrl }
        val shop = prefs.shopCode.ifBlank { License.deviceId(context) }
        if (base.isBlank()) return@withContext Result.Failed("Set the online catalog URL in Settings first")

        val sep = if (base.contains("?")) "&" else "?"
        val url = "$base${sep}do=newCustomers&shop=${java.net.URLEncoder.encode(shop, "UTF-8")}"

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
            if (array.length() == 0) return@withContext Result.Ok(emptyList())

            val repo = Repository(context)
            val registered = mutableListOf<String>()
            for (i in 0 until array.length()) {
                val c = array.optJSONObject(i) ?: continue
                val phone = c.optString("phone").trim()
                if (phone.isBlank()) continue
                if (repo.customerByPhone(phone) != null) continue // already known (order placed since, or a retried fetch)

                val rawName = c.optString("name").ifBlank { "Online customer" }
                val name = "$rawName ($phone)"
                repo.addCustomer(name = name, phone = phone, address = "", customerType = "Online Customer")
                registered.add(rawName)
            }
            Result.Ok(registered)
        } catch (e: Exception) {
            Result.Failed(e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        }
    }
}
