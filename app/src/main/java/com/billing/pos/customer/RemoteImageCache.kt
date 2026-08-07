package com.billing.pos.customer

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads a direct-image URL (see [Item.driveLink]) to a stable local cache file, named by a
 * hash of the URL so a repeat call for the same link reuses the file instead of re-downloading
 * every time the customer reopens the catalog. Once it's a local file, the existing local-file
 * thumbnail decoder ([com.billing.pos.ui.common.rememberThumbnail]) and full-screen viewer
 * ([com.billing.pos.ui.common.ImageViewerDialog]) both just work on it unchanged — no separate
 * network-image code path needed for either.
 */
object RemoteImageCache {
    suspend fun fetch(context: Context, url: String): File? = withContext(Dispatchers.IO) {
        runCatching {
            // Must be under cacheDir/shared/ — the only cache location file_paths.xml exposes
            // through the FileProvider, same constraint every other cached photo in this app has.
            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            val name = MessageDigest.getInstance("MD5").digest(url.toByteArray())
                .joinToString("") { "%02x".format(it) }
            val file = File(dir, "catalog_link_$name")
            if (file.exists() && file.length() > 0L) return@withContext file

            val conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            val code = conn.responseCode
            if (code !in 200..299) { conn.disconnect(); return@withContext null }
            conn.inputStream.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
            conn.disconnect()
            file
        }.getOrNull()
    }
}
