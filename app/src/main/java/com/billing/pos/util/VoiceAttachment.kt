package com.billing.pos.util

import android.content.Context
import android.util.Base64
import java.io.File

/**
 * Shared mechanics for the "voice note as an attachment" convention: a `data:audio/mp4;base64,...`
 * string travels the exact same channel as a `data:image/...` photo attachment (order attachments,
 * a shop's reply to a customer) — this only handles the encode/decode side, not anything ordering-
 * or messaging-specific, so both the customer order form and a shop owner's reply can share it.
 */
object VoiceAttachment {
    fun isAudio(dataUri: String) = dataUri.startsWith("data:audio")

    /** Reads a just-recorded .m4a file into the same data-URI shape a photo attachment uses. */
    fun encode(file: File): String {
        val bytes = file.readBytes()
        return "data:audio/mp4;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /** Decodes a voice-note data URI to a fresh temp file under [context]'s shared cache dir,
     *  ready for [android.media.MediaPlayer.setDataSource] or an ACTION_VIEW intent. Caller
     *  deletes it once playback stops/finishes. */
    fun decodeToTempFile(context: Context, dataUri: String): File? {
        val comma = dataUri.indexOf(',')
        if (comma < 0) return null
        return runCatching {
            val bytes = Base64.decode(dataUri.substring(comma + 1), Base64.DEFAULT)
            val sharedDir = File(context.cacheDir, "shared").apply { mkdirs() }
            val file = File(sharedDir, "voice_${System.nanoTime()}.m4a")
            file.writeBytes(bytes)
            file
        }.getOrNull()
    }
}
