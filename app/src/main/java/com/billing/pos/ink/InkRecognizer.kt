package com.billing.pos.ink

import androidx.compose.ui.geometry.Offset
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Thin wrapper around ML Kit Digital Ink for the handwrite Quick Bill.
 *
 * Works fully offline once the English model has been downloaded. The very first
 * time [ensureReady] runs it fetches that model (~20 MB), which needs internet once.
 */
class InkRecognizer {

    private val model: DigitalInkRecognitionModel =
        DigitalInkRecognitionModel.builder(
            DigitalInkRecognitionModelIdentifier.fromLanguageTag("en-US")!!
        ).build()

    private val recognizer: DigitalInkRecognizer =
        DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build())

    private val remote = RemoteModelManager.getInstance()

    /** Downloads the recognition model if needed. Returns true when ready to recognize. */
    suspend fun ensureReady(): Boolean = withContext(Dispatchers.IO) {
        val already = runCatching { Tasks.await(remote.isModelDownloaded(model)) }.getOrDefault(false)
        if (already) return@withContext true
        runCatching {
            Tasks.await(remote.download(model, DownloadConditions.Builder().build()))
        }.isSuccess
    }

    /** Recognizes the best text candidate for the given strokes (empty on failure). */
    suspend fun recognize(strokes: List<List<Offset>>): String = withContext(Dispatchers.IO) {
        val usable = strokes.filter { it.isNotEmpty() }
        if (usable.isEmpty()) return@withContext ""
        val ink = buildInk(usable)
        runCatching {
            val result = Tasks.await(recognizer.recognize(ink))
            result.candidates.firstOrNull()?.text ?: ""
        }.getOrDefault("")
    }

    fun close() { runCatching { recognizer.close() } }

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
