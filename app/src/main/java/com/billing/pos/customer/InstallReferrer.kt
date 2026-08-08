package com.billing.pos.customer

import android.content.Context
import android.net.Uri
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Reads the Play Store install link's `referrer` parameter, once, right after install.
 *
 * A customer-facing link looks like:
 * `...details?id=com.billing.pos&referrer=mode%3Dcustomer%26shop%3DABC123%26url%3Dhttps%3A%2F%2Fserver%2Fcatalog%26type%3DRestaurant`
 *
 * `type` is optional and free text ("Restaurant", "Medical store", "Medical lab", ...) — it only
 * changes wording on the catalog screen, nothing structural. `premium=1` is also optional — it
 * unlocks attaching a photo to an order (no billing system behind it, just whatever the link says).
 *
 * Play decodes that into a plain query string ("mode=customer&shop=ABC123&url=https://server/catalog&type=Restaurant"),
 * which this parses like any other query string. A normal shop-owner install (no referrer, or a
 * sideloaded/debug build where Play's referrer service isn't available) returns an empty map —
 * the caller just falls through to the regular business-type flow.
 */
object InstallReferrer {

    /** Result of asking Play for the referrer once the service actually answered — as opposed to
     *  the call simply timing out, which [read] represents separately (see below). */
    private sealed class FetchResult {
        /** OK response; the raw referrer query string, blank if none was set on the install link. */
        data class Got(val referrer: String) : FetchResult()
        /** A non-OK response (FEATURE_NOT_SUPPORTED, SERVICE_UNAVAILABLE, ...) or an exception
         *  reading the referrer — as definitive as a "no referrer" answer for our purposes. */
        object Unavailable : FetchResult()
    }

    /**
     * Null means the Play Install Referrer service didn't answer within the timeout — worth
     * retrying on a later boot, since this is commonly just Play Store/Play Services still
     * warming up in the first moments right after a fresh install, not a real "no referrer"
     * answer. A non-null (possibly empty) map means Play gave a definitive answer: empty means a
     * genuine non-customer install (or a sideloaded/debug build with no referrer service at all),
     * safe for the caller to stop checking for good.
     */
    suspend fun read(context: Context): Map<String, String>? {
        val result = withTimeoutOrNull(4000) { fetchReferrer(context) } ?: return null
        val raw = when (result) {
            is FetchResult.Got -> result.referrer
            FetchResult.Unavailable -> return emptyMap()
        }
        if (raw.isBlank()) return emptyMap()
        val uri = Uri.parse("https://x/?$raw")
        return uri.queryParameterNames.associateWith { uri.getQueryParameter(it).orEmpty() }
    }

    private suspend fun fetchReferrer(context: Context): FetchResult = suspendCancellableCoroutine { cont ->
        val client = InstallReferrerClient.newBuilder(context.applicationContext).build()
        client.startConnection(object : InstallReferrerStateListener {
            override fun onInstallReferrerSetupFinished(responseCode: Int) {
                val result = if (responseCode == InstallReferrerClient.InstallReferrerResponse.OK) {
                    runCatching { client.installReferrer.installReferrer.orEmpty() }.getOrNull()
                        ?.let { FetchResult.Got(it) } ?: FetchResult.Unavailable
                } else FetchResult.Unavailable
                runCatching { client.endConnection() }
                if (cont.isActive) cont.resume(result)
            }
            override fun onInstallReferrerServiceDisconnected() {
                // Only matters if setupFinished never fired; the timeout above covers that case.
            }
        })
        cont.invokeOnCancellation { runCatching { client.endConnection() } }
    }
}
