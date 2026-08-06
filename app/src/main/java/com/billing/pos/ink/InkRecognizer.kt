package com.billing.pos.ink

import android.content.Context
import androidx.compose.ui.geometry.Offset
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The handwriting pad reads English only (Malayalam/Arabic were removed to simplify the
 *  writing-pad flow down to one language). */
object InkLang {
    const val ENGLISH = "en-US"

    fun default(context: Context): String = ENGLISH

    fun label(tag: String): String = "English"
}

/**
 * Thin wrapper around ML Kit Digital Ink.
 *
 * Works fully offline once the English model has been downloaded — the first use fetches
 * it (~20 MB), which needs internet that one time.
 */
class InkRecognizer(private val languageTag: String = InkLang.ENGLISH) {

    private val model: DigitalInkRecognitionModel? =
        DigitalInkRecognitionModelIdentifier.fromLanguageTag(languageTag)
            ?.let { DigitalInkRecognitionModel.builder(it).build() }

    private val recognizer: DigitalInkRecognizer? = model?.let {
        DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(it).build())
    }

    private val remote = RemoteModelManager.getInstance()

    /** True when this language's model is already on the device (no internet needed). */
    suspend fun isDownloaded(): Boolean = withContext(Dispatchers.IO) {
        val m = model ?: return@withContext false
        runCatching { Tasks.await(remote.isModelDownloaded(m)) }.getOrDefault(false)
    }

    /** Downloads the recognition model if needed. Returns true when ready to recognize. */
    suspend fun ensureReady(): Boolean = withContext(Dispatchers.IO) {
        val m = model ?: return@withContext false
        if (runCatching { Tasks.await(remote.isModelDownloaded(m)) }.getOrDefault(false)) {
            return@withContext true
        }
        runCatching {
            Tasks.await(remote.download(m, DownloadConditions.Builder().build()))
        }.isSuccess
    }

    /** Recognizes the best text candidate for the given strokes (empty on failure). */
    suspend fun recognize(strokes: List<List<Offset>>): String = withContext(Dispatchers.IO) {
        val r = recognizer ?: return@withContext ""
        val usable = strokes.filter { it.isNotEmpty() }
        if (usable.isEmpty()) return@withContext ""
        val ink = buildInk(usable)
        runCatching {
            val result = Tasks.await(r.recognize(ink))
            result.candidates.firstOrNull()?.text ?: ""
        }.getOrDefault("")
    }

    fun close() { runCatching { recognizer?.close() } }

    private fun buildInk(strokes: List<List<Offset>>): Ink {
        val ink = Ink.builder()
        strokes.forEach { pts ->
            val stroke = Ink.Stroke.builder()
            pts.forEach { p -> stroke.addPoint(Ink.Point.create(p.x, p.y)) }
            ink.addStroke(stroke.build())
        }
        return ink.build()
    }
}
