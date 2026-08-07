package com.billing.pos.customer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pulls (lat, lng) out of whatever a customer actually pastes — "share my location" from Google
 * Maps produces several different link shapes depending on how it was shared, not just the plain
 * `?q=lat,lng` this app's own GPS capture writes:
 *  - `https://maps.google.com/?q=12.97,77.59` (this app's own format, and the classic one)
 *  - `https://www.google.com/maps/@12.97,77.59,17z` (viewing a map)
 *  - `https://www.google.com/maps/place/Some+Place/@12.97,77.59,17z/...` (a place page)
 *  - `?ll=12.97,77.59` (older link style)
 *  - `geo:12.97,77.59` (the raw Android geo URI)
 *  - `https://maps.app.goo.gl/xxxx` / `https://goo.gl/maps/xxxx` (the short link the phone's
 *    Maps app "Share" button actually produces on a dropped pin — no coordinates in the link
 *    itself, only reachable by following the redirect, so [resolve] does that over the network)
 */
object GeoLink {

    private val COORD_PATTERN = Regex("(-?\\d{1,3}\\.\\d+),\\s*(-?\\d{1,3}\\.\\d+)")

    fun isShortLink(url: String): Boolean =
        url.contains("goo.gl/maps") || url.contains("maps.app.goo.gl")

    /** Synchronous best-effort parse — works for every format above except the short links,
     *  which have no coordinates in the URL text at all (see [resolve]). */
    fun parseLatLng(url: String): Pair<Double, Double>? {
        val text = url.trim()
        if (text.isBlank()) return null
        // "q=" and "ll=" query params, and the raw "geo:" URI, all read the same way once the
        // parameter/scheme prefix is stripped — reuse one regex for all three.
        for (prefix in listOf("q=", "ll=", "geo:")) {
            val idx = text.indexOf(prefix)
            if (idx >= 0) {
                val after = text.substring(idx + prefix.length)
                COORD_PATTERN.find(after)?.let { m ->
                    val lat = m.groupValues[1].toDoubleOrNull()
                    val lng = m.groupValues[2].toDoubleOrNull()
                    if (lat != null && lng != null) return lat to lng
                }
            }
        }
        // "@lat,lng,zoom" — how a plain map view or a place page encodes its center point.
        val at = text.indexOf('@')
        if (at >= 0) {
            COORD_PATTERN.find(text, at)?.let { m ->
                val lat = m.groupValues[1].toDoubleOrNull()
                val lng = m.groupValues[2].toDoubleOrNull()
                if (lat != null && lng != null) return lat to lng
            }
        }
        return null
    }

    /** Follows a maps.app.goo.gl / goo.gl/maps short link's redirect to get the real URL with
     *  coordinates in it, then parses that — network access, so only call this off the main
     *  thread; null on any failure (offline, link expired, unexpected redirect target, ...). */
    suspend fun resolve(url: String): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        if (!isShortLink(url)) return@withContext parseLatLng(url)
        withTimeoutOrNull(8000) {
            runCatching {
                var current = url.trim()
                // A handful of hops, not one — Google's short links sometimes chain through an
                // intermediate consent/redirect page before landing on the real maps URL.
                repeat(5) {
                    val conn = URL(current).openConnection() as HttpURLConnection
                    conn.instanceFollowRedirects = false
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    conn.requestMethod = "GET"
                    val code = conn.responseCode
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (code in 300..399 && !location.isNullOrBlank()) {
                        current = location
                        parseLatLng(current)?.let { return@runCatching it }
                    } else {
                        return@runCatching parseLatLng(current)
                    }
                }
                parseLatLng(current)
            }.getOrNull()
        }
    }
}
