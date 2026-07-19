package com.billing.pos.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Malayalam OCR.
 *
 * ML Kit has no Malayalam model — its text recognizer only covers Latin, Chinese,
 * Devanagari, Japanese and Korean — so Malayalam goes through Tesseract instead. The
 * `mal.traineddata` model ships inside the APK (assets/tessdata) and is copied to app
 * storage on first use, because Tesseract reads it from a real path, not from assets.
 * Nothing is downloaded and nothing leaves the phone.
 */
object TesseractOcr {
    private const val LANG = "mal"
    /** Guards the copy + the API itself; TessBaseAPI is not safe to share across threads. */
    private val lock = Any()
    @Volatile private var dataDir: File? = null

    /** True once the model is on disk and usable. Copies it out of assets on first call. */
    private fun ensureModel(context: Context): File? = synchronized(lock) {
        dataDir?.let { return it }
        return runCatching {
            // Tesseract wants <dir>/tessdata/<lang>.traineddata and is given <dir>.
            val root = File(context.filesDir, "tesseract")
            val tessdata = File(root, "tessdata").apply { mkdirs() }
            val target = File(tessdata, "$LANG.traineddata")
            val asset = "tessdata/$LANG.traineddata"
            val expected = context.assets.openFd(asset).use { it.length }
            // Re-copy when missing or truncated by an interrupted first run.
            if (!target.exists() || target.length() != expected) {
                context.assets.open(asset).use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
            }
            root.also { dataDir = it }
        }.getOrNull()
    }

    /**
     * Recognises Malayalam text in [uri].
     *
     * [singleLine] picks the page-segmentation mode: a cropped box holding one item name
     * reads far better as SINGLE_LINE, while a whole printed list needs AUTO.
     */
    suspend fun text(context: Context, uri: Uri, singleLine: Boolean): List<String> =
        withContext(Dispatchers.IO) {
            val root = ensureModel(context) ?: return@withContext emptyList()
            val bitmap = decodeUpright(context, uri) ?: return@withContext emptyList()
            synchronized(lock) {
                val api = TessBaseAPI()
                try {
                    if (!api.init(root.absolutePath, LANG)) return@withContext emptyList()
                    api.pageSegMode =
                        if (singleLine) TessBaseAPI.PageSegMode.PSM_SINGLE_LINE
                        else TessBaseAPI.PageSegMode.PSM_AUTO
                    api.setImage(bitmap)
                    val out = api.utF8Text.orEmpty()
                    out.split('\n').map { it.trim() }.filter { it.isNotBlank() }
                } catch (t: Throwable) {
                    emptyList()
                } finally {
                    runCatching { api.recycle() }
                    bitmap.recycle()
                }
            }
        }

    /**
     * Decodes [uri] at a size Tesseract works well with and applies the EXIF rotation —
     * a sideways photo otherwise recognises as nothing at all.
     */
    private fun decodeUpright(context: Context, uri: Uri): Bitmap? = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        // Tesseract likes roughly 1500px on the long edge; bigger is slower, not better.
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return null

        val degrees = runCatching {
            when (bytes.inputStream().use { ExifInterface(it) }
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)

        if (degrees == 0f) bmp
        else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees) }, true)
            .also { if (it != bmp) bmp.recycle() }
    }.getOrNull()
}
