package com.billing.pos.ocr

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Offline OCR using ML Kit's bundled Latin text recognizer (no model download needed). */
object TextOcr {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /** All recognized text lines (roughly top-to-bottom), or empty on failure. */
    suspend fun lines(context: Context, uri: Uri): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            val image = InputImage.fromFilePath(context, uri)
            val result = Tasks.await(recognizer.process(image))
            result.textBlocks.flatMap { block -> block.lines.map { it.text } }
        }.getOrDefault(emptyList())
    }

    /** The whole recognized text collapsed to a single trimmed line. */
    suspend fun singleLine(context: Context, uri: Uri): String =
        lines(context, uri).joinToString(" ").replace(Regex("\\s+"), " ").trim()
}

private fun cropOptions() = CropImageContractOptions(
    uri = null,
    cropImageOptions = CropImageOptions(
        imageSourceIncludeCamera = true,
        imageSourceIncludeGallery = true,
        guidelines = CropImageView.Guidelines.ON
    )
)

/** Camera/gallery → crop → OCR one name. Returns a lambda that launches the flow. */
@Composable
fun rememberNameScanner(onText: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(CropImageContract()) { result ->
        val uri = result.uriContent
        if (result.isSuccessful && uri != null) {
            scope.launch {
                val text = TextOcr.singleLine(context, uri)
                if (text.isNotBlank()) onText(text)
            }
        }
    }
    return { runCatching { launcher.launch(cropOptions()) } }
}

/** Camera/gallery → crop → OCR every line (for the bulk item-list import). */
@Composable
fun rememberListScanner(onLines: (List<String>) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(CropImageContract()) { result ->
        val uri = result.uriContent
        if (result.isSuccessful && uri != null) {
            scope.launch { onLines(TextOcr.lines(context, uri)) }
        }
    }
    return { runCatching { launcher.launch(cropOptions()) } }
}
