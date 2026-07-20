package com.billing.pos.auth

import android.net.Uri

/**
 * Files shared into the app from another app (e.g. a WhatsApp photo, voice note, video or
 * document), held until the diary can open a new entry and attach them. Separate from
 * [PendingImport], which is only for a shared backup .zip.
 */
object PendingSharedMedia {
    @Volatile private var uris: List<Uri> = emptyList()

    /** Friendly name of the app the files came from (e.g. "WhatsApp"); blank if unknown. */
    @Volatile var sourceLabel: String = ""
        private set

    val hasItems: Boolean get() = uris.isNotEmpty()

    fun set(list: List<Uri>, source: String = "") {
        uris = list.filterNotNull()
        sourceLabel = source
    }

    fun consume(): List<Uri> {
        val u = uris
        uris = emptyList()
        return u
    }
}
