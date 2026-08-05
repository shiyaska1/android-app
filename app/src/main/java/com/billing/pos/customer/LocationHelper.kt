package com.billing.pos.customer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One-shot location for a customer order — "roughly where should the shop deliver this" — not a
 * dependency on Play Services location, just plain [LocationManager] since that's all this needs.
 */
object LocationHelper {

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** A `https://maps.google.com/?q=lat,lng` link, or null if permission's missing or no fix could be had. */
    suspend fun currentLocationLink(context: Context): String? {
        if (!hasPermission(context)) return null
        val loc = withTimeoutOrNull(8000) { freshLocation(context) } ?: lastKnown(context)
        return loc?.let { "https://maps.google.com/?q=${it.latitude},${it.longitude}" }
    }

    private fun lastKnown(context: Context): Location? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return runCatching {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { provider -> runCatching { lm.getLastKnownLocation(provider) }.getOrNull() }
                .maxByOrNull { it.time }
        }.getOrNull()
    }

    private suspend fun freshLocation(context: Context): Location? = suspendCancellableCoroutine { cont ->
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) { cont.resume(null); return@suspendCancellableCoroutine }
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
        if (provider == null) { cont.resume(null); return@suspendCancellableCoroutine }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                runCatching { lm.removeUpdates(this) }
                if (cont.isActive) cont.resume(location)
            }
        }
        try {
            lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
        } catch (e: SecurityException) {
            if (cont.isActive) cont.resume(null)
            return@suspendCancellableCoroutine
        }
        cont.invokeOnCancellation { runCatching { lm.removeUpdates(listener) } }
    }
}
