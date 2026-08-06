package com.billing.pos.customer

import android.content.Context
import com.billing.pos.data.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * "Nearby shops" — every OTHER shop hosted on the SAME server deployment as the one currently
 * open (see pos_online_catalog.php's do=directory), sorted by distance from the customer's
 * current GPS fix. This is scoped to one deployment, not a directory of every shop that has ever
 * used this app anywhere — different shops can (and often will) run their own separate copy of
 * the server script on their own domain, and there's no central registry across those.
 */
object NearbyShops {

    data class Entry(
        val shop: String, val name: String, val category: String, val address: String,
        val lat: Double, val lng: Double, val distanceKm: Double
    )

    sealed class Result {
        data class Ok(val shops: List<Entry>) : Result()
        data class Failed(val message: String) : Result()
    }

    suspend fun fetch(context: Context): Result = withContext(Dispatchers.IO) {
        val prefs = AppPrefs(context)
        val base = prefs.onlineCatalogUrl
        if (base.isBlank()) return@withContext Result.Failed("Not linked to a shop yet")
        val here = LocationHelper.currentLatLng(context)
            ?: return@withContext Result.Failed("Could not get your location — turn on Location and try again")
        val (myLat, myLng) = here

        val sep = if (base.contains("?")) "&" else "?"
        val url = "$base${sep}do=directory"
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return@withContext Result.Failed("Server returned HTTP $code")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val array = runCatching { JSONArray(body.trim()) }.getOrNull()
                ?: return@withContext Result.Failed("Unrecognised response from server")
            val entries = (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val shop = o.optString("shop").ifBlank { return@mapNotNull null }
                if (shop == prefs.shopCode) return@mapNotNull null // that's the shop already open
                val lat = o.optDouble("shopLat", Double.NaN)
                val lng = o.optDouble("shopLng", Double.NaN)
                if (lat.isNaN() || lng.isNaN()) return@mapNotNull null
                Entry(
                    shop = shop,
                    name = o.optString("shopName"),
                    category = o.optString("shopCategory"),
                    address = o.optString("shopAddress"),
                    lat = lat, lng = lng,
                    distanceKm = haversineKm(myLat, myLng, lat, lng)
                )
            }.sortedBy { it.distanceKm }
            Result.Ok(entries)
        } catch (e: Exception) {
            Result.Failed(e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        }
    }

    private fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
