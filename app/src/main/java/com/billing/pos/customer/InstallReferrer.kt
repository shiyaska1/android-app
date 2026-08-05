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
 * changes wording on the catalog screen, nothing structural.
 *
 * Play decodes that into a plain query string ("mode=customer&shop=ABC123&url=https://server/catalog&type=Restaurant"),
 * which this parses like any other query string. A normal shop-owner install (no referrer, or a
 * sideloaded/debug build where Play's referrer service isn't available) returns an empty map —
 * the caller just falls through to the regular business-type flow.
 */
object InstallReferrer {

    /** Blank/absent for anything that isn't a customer link. */
    suspend fun read(context: Context): Map<String, String> {
        val raw = withTimeoutOrNull(4000) { fetchReferrerUrl(context) } ?: return emptyMap()
        if (raw.isBlank()) return emptyMap()
        val uri = Uri.parse("https://x/?$raw")
        return uri.queryParameterNames.associateWith { uri.getQueryParameter(it).orEmpty() }
    }

    private suspend fun fetchReferrerUrl(context: Context): String? = suspendCancellableCoroutine { cont ->
        val client = InstallReferrerClient.newBuilder(context.applicationContext).build()
        client.startConnection(object : InstallReferrerStateListener {
            override fun onInstallReferrerSetupFinished(responseCode: Int) {
                val result = if (responseCode == InstallReferrerClient.InstallReferrerResponse.OK) {
                    runCatching { client.installReferrer.installReferrer }.getOrNull()
                } else null
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
