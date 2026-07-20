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
 * [generation] is compose state that bumps on each new share, so a warm start (app already
 * open, arriving via onNewIntent) can react and navigate. Cold starts are routed through the
 * boot / login redirects instead, to avoid racing boot's own navigation.
 */
object PendingSharedMedia {
    @Volatile private var uris: List<Uri> = emptyList()

    /** Friendly name of the app the files came from (e.g. "WhatsApp"); blank if unknown. */
    @Volatile var sourceLabel: String = ""
        private set

    var generation by mutableStateOf(0)
        private set

    val hasItems: Boolean get() = uris.isNotEmpty()

    fun set(list: List<Uri>, source: String = "") {
        uris = list.filterNotNull()
        sourceLabel = source
        generation++
    }

    fun consume(): List<Uri> {
        val u = uris
        uris = emptyList()
        return u
    }
}
