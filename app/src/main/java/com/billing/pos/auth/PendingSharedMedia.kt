package com.billing.pos.auth

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Files shared into the app from another app (e.g. a WhatsApp photo, voice note, video or
 * document). Nothing auto-navigates on a share arriving — a screen that cares (the diary, while
 * already open; the customer order screen's payment dialog, while its QR-code fallback is
 * showing) reacts to [generation] and [consume]s what it wants. Separate from [PendingImport],
 * which is only for a shared backup .zip.
 */
object PendingSharedMedia {
    @Volatile private var uris: List<Uri> = emptyList()

    /** Friendly name of the app the files came from (e.g. "WhatsApp"); blank if unknown. */
    @Volatile var sourceLabel: String = ""
        private set

    /** Set (and cleared) by the customer order screen's payment dialog while its QR-code fallback
     *  is showing — e.g. sharing a UPI app's payment-success screenshot straight to this app
     *  (instead of picking it from gallery) should attach as that order's payment proof. */
    var awaitingPaymentProof by mutableStateOf(false)

    /** Bumps on every share, so an already-open screen can react and pick up extra files. */
    var generation by mutableStateOf(0)
        private set

    /** Plain text shared in (a forwarded WhatsApp message), when there is no file. */
    @Volatile var sharedText: String = ""
        private set

    val hasItems: Boolean get() = uris.isNotEmpty()
    val hasText: Boolean get() = sharedText.isNotBlank()

    fun set(list: List<Uri>, source: String = "") {
        uris = list.filterNotNull()
        sourceLabel = source
        generation++
    }

    fun setText(text: String, source: String = "") {
        sharedText = text.trim()
        sourceLabel = source
        generation++
    }

    fun consumeText(): String {
        val t = sharedText
        sharedText = ""
        return t
    }

    fun consume(): List<Uri> {
        val u = uris
        uris = emptyList()
        return u
    }
}
