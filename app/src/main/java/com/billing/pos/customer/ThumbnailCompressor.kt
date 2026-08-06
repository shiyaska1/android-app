package com.billing.pos.customer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Compresses a photo down to a small JPEG (< [maxBytes]) client-side before it ever reaches the
 * server, so nothing full-resolution is ever uploaded. Used for two different purposes with two
 * different size targets:
 *  - item/banner thumbnails ([maxDim] default 240px, [maxBytes] default 40KB) — embedded directly
 *    in the online catalog JSON, tiny since one shows next to every item in a list.
 *  - order attachments (see [maxDim]=1024 callers) — a customer's prescription/note photo, which
 *    only needs to stay legible to the shop owner reading it, not full camera resolution.
 */
object ThumbnailCompressor {

    /** Returns a compressed JPEG under [maxBytes] with its longest side under [maxDim], or null
     *  if the file can't be read/decoded. */
    fun compress(sourcePath: String, maxBytes: Int = 40 * 1024, maxDim: Int = 240): ByteArray? {
        val bitmap = decodeScaled(sourcePath, maxDim) ?: return null
        try {
            var dim = maxDim
            var bytes = encode(bitmap, 82)
            if (bytes.size <= maxBytes) return bytes

            // Step quality down first — cheaper than re-decoding at a smaller size.
            for (quality in intArrayOf(70, 55, 40)) {
                bytes = encode(bitmap, quality)
                if (bytes.size <= maxBytes) return bytes
            }

            // Still too big (an unusually busy image): shrink dimensions and retry.
            var current = bitmap
            while (bytes.size > maxBytes && dim > 60) {
                dim = (dim * 0.75f).toInt()
                val scaled = Bitmap.createScaledBitmap(current, dim, (dim * current.height / current.width).coerceAtLeast(1), true)
                if (scaled !== current) current.recycle()
                current = scaled
                bytes = encode(current, 55)
            }
            return bytes
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodeScaled(path: String, maxDim: Int): Bitmap? {
        val file = File(path)
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val w = bounds.outWidth; val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        var sample = 1
        while (w / (sample * 2) >= maxDim || h / (sample * 2) >= maxDim) sample *= 2
        val decoded = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = maxDim.toFloat() / maxOf(decoded.width, decoded.height)
        if (scale >= 1f) return decoded
        val scaled = Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true)
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    private fun encode(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out); out.toByteArray() }
}
