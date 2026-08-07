package com.billing.pos.auth

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Files shared into the app from another app (e.g. a WhatsApp photo, voice note, video or
 * document), held until the diary can open a new entry and attach them. Separate from
 * [PendingImport], which is only for a shared backup .zip.
 *
 * Two separate pieces of state, deliberately:
 *  - the file list, which the diary [consume]s as soon as it opens;
 *  - [awaitingDiary], the routing decision, which only [markRouted] clears.
 *
 * They must not be the same flag. The router (boot / login) decides where to send the user
 * *after* the diary may already have consumed the files — if routing keyed off the file list
 * it would see it empty and send the user to the dashboard instead, replacing the diary.
 */
object PendingSharedMedia {
    @Volatile private var uris: List<Uri> = emptyList()

    /** Friendly name of the app the files came from (e.g. "WhatsApp"); blank if unknown. */
    @Volatile var sourceLabel: String = ""
        private set

    /** True from the moment a share arrives until a router has sent the user to the diary. */
    @Volatile var awaitingDiary: Boolean = false
        private set

    /** Set (and cleared) by the customer order screen's payment dialog while its QR-code fallback
     *  is showing — e.g. sharing a UPI app's payment-success screenshot straight to this app
     *  (instead of picking it from gallery) should attach as that order's payment proof, not fall
     *  through to the diary's generic "new entry with this attachment" handling below. Checked
     *  before [awaitingDiary] routing fires. */
    var awaitingPaymentProof by mutableStateOf(false)
        private set

    fun setAwaitingPaymentProof(active: Boolean) { awaitingPaymentProof = active }

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
        awaitingDiary = uris.isNotEmpty()
        generation++
    }

    fun setText(text: String, source: String = "") {
        sharedText = text.trim()
        sourceLabel = source
        awaitingDiary = sharedText.isNotEmpty()
        generation++
    }

    fun consumeText(): String {
        val t = sharedText
        sharedText = ""
        return t
    }

    /** Takes the files. Leaves [awaitingDiary] alone — that is the router's business. */
    fun consume(): List<Uri> {
        val u = uris
        uris = emptyList()
        return u
    }

    /** Called by whichever router actually navigated to the diary. */
    fun markRouted() { awaitingDiary = false }
}
