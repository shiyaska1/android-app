package com.billing.pos.face

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.sqrt

/** A detected face turned into an embedding, plus the crop (for saving a thumbnail). */
class FaceResult(val embedding: FloatArray, val crop: Bitmap)

/**
 * Offline face recognition: ML Kit finds the face, a bundled FaceNet TFLite model turns it
 * into an L2-normalized embedding, and cosine similarity matches it to enrolled employees.
 */
object FaceRecognizer {

    /** Cosine-similarity threshold for a confident match (tune on real devices). */
    const val MATCH_THRESHOLD = 0.62f

    private var interpreter: Interpreter? = null
    private var inputSize = 160
    private var outputDim = 128

    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setMinFaceSize(0.15f)
                .build()
        )
    }

    private fun ensureModel(context: Context) {
        if (interpreter != null) return
        val fd = context.assets.openFd("facenet.tflite")
        val model: ByteBuffer = java.io.FileInputStream(fd.fileDescriptor).use { input ->
            input.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
        val itp = Interpreter(model, Interpreter.Options().apply { setNumThreads(2) })
        interpreter = itp
        inputSize = itp.getInputTensor(0).shape().getOrElse(1) { 160 }
        outputDim = itp.getOutputTensor(0).shape().getOrElse(1) { 128 }
    }

    /** Detects the largest face in [bitmap] and returns its embedding + crop, or null. */
    fun analyze(context: Context, bitmap: Bitmap): FaceResult? {
        ensureModel(context)
        val faces: List<Face> = runCatching {
            Tasks.await(detector.process(InputImage.fromBitmap(bitmap, 0)))
        }.getOrDefault(emptyList())
        val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() } ?: return null

        val b = face.boundingBox
        val left = b.left.coerceIn(0, bitmap.width - 1)
        val top = b.top.coerceIn(0, bitmap.height - 1)
        val right = b.right.coerceIn(left + 1, bitmap.width)
        val bottom = b.bottom.coerceIn(top + 1, bitmap.height)
        val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        val embedding = embed(crop) ?: return null
        return FaceResult(embedding, crop)
    }

    private fun embed(crop: Bitmap): FloatArray? {
        val itp = interpreter ?: return null
        val scaled = Bitmap.createScaledBitmap(crop, inputSize, inputSize, true)
        val n = inputSize * inputSize
        val pixels = IntArray(n)
        scaled.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        // Per-image standardization ("prewhiten"), the normalization FaceNet expects.
        var sum = 0.0
        for (p in pixels) { sum += (p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF) }
        val mean = sum / (n * 3)
        var sq = 0.0
        for (p in pixels) {
            val r = (p shr 16 and 0xFF); val g = (p shr 8 and 0xFF); val bl = (p and 0xFF)
            sq += (r - mean) * (r - mean) + (g - mean) * (g - mean) + (bl - mean) * (bl - mean)
        }
        val std = max(sqrt(sq / (n * 3)), 1.0 / sqrt((n * 3).toDouble()))

        val buffer = ByteBuffer.allocateDirect(n * 3 * 4).order(ByteOrder.nativeOrder())
        for (p in pixels) {
            buffer.putFloat((((p shr 16 and 0xFF) - mean) / std).toFloat())
            buffer.putFloat((((p shr 8 and 0xFF) - mean) / std).toFloat())
            buffer.putFloat((((p and 0xFF) - mean) / std).toFloat())
        }
        buffer.rewind()
        val out = Array(1) { FloatArray(outputDim) }
        itp.run(buffer, out)
        return l2normalize(out[0])
    }

    private fun l2normalize(v: FloatArray): FloatArray {
        var s = 0.0
        for (x in v) s += x * x
        val norm = sqrt(s).toFloat().coerceAtLeast(1e-10f)
        for (i in v.indices) v[i] = v[i] / norm
        return v
    }

    /** Cosine similarity of two L2-normalized embeddings (= dot product). */
    fun similarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return -1f
        var d = 0f
        for (i in a.indices) d += a[i] * b[i]
        return d
    }

    fun encode(v: FloatArray): String = v.joinToString(",")
    fun decode(s: String): FloatArray? =
        if (s.isBlank()) null else runCatching { s.split(",").map { it.toFloat() }.toFloatArray() }.getOrNull()
}
