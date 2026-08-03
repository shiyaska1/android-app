package com.billing.pos.ui.common

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.min

/**
 * Shows [uri] full-screen so the user can drag a box around just the product — releasing the
 * drag immediately searches using only that marked area, no separate crop/confirm step. A photo
 * that's mostly background (a shop counter, a hand, packaging clutter) otherwise dominates the
 * "find item by photo" embedding and hides the real match. "Search whole photo" skips marking
 * for photos that are already a tight close-up.
 */
@Composable
fun RegionMatchDialog(uri: Uri, onResult: (Bitmap) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val bitmap = remember(uri) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytes()
                val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opt)
                var s = 1
                while (opt.outWidth / s > 1600 || opt.outHeight / s > 1600) s *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
            }
        }.getOrNull()
    }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var start by remember { mutableStateOf<Offset?>(null) }
    var end by remember { mutableStateOf<Offset?>(null) }

    fun finish(useWholePhoto: Boolean) {
        val bmp = bitmap ?: run { onDismiss(); return }
        val result = if (useWholePhoto) bmp else cropToSelection(bmp, start, end, canvasSize)
        onResult(result)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).safeDrawingPadding().padding(8.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                OutlinedButton(onClick = { finish(useWholePhoto = true) }, modifier = Modifier.weight(1f)) { Text("Search whole photo") }
            }
            Text(
                "Drag a box around just the product — releasing it searches right away.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp, bottom = 6.dp)
            )
            Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
                if (bitmap == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Could not open image", color = Color.White) }
                else Canvas(
                    Modifier.fillMaxSize()
                        .onSizeChanged { canvasSize = it }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { start = it; end = it },
                                onDrag = { change, _ -> end = change.position; change.consume() },
                                onDragEnd = {
                                    val s = start; val e = end
                                    // Ignore an accidental tap/tiny jitter — require a real box.
                                    if (s != null && e != null && (kotlin.math.abs(e.x - s.x) > 16 || kotlin.math.abs(e.y - s.y) > 16)) {
                                        finish(useWholePhoto = false)
                                    } else {
                                        start = null; end = null
                                    }
                                }
                            )
                        }
                ) {
                    val bw = bitmap.width.toFloat(); val bh = bitmap.height.toFloat()
                    val scale = min(size.width / bw, size.height / bh)
                    val dw = bw * scale; val dh = bh * scale
                    val ox = (size.width - dw) / 2f; val oy = (size.height - dh) / 2f
                    drawImage(bitmap.asImageBitmap(), srcOffset = IntOffset.Zero, srcSize = IntSize(bitmap.width, bitmap.height),
                        dstOffset = IntOffset(ox.toInt(), oy.toInt()), dstSize = IntSize(dw.toInt(), dh.toInt()))
                    val s = start; val e = end
                    if (s != null && e != null) {
                        val l = kotlin.math.min(s.x, e.x); val t = kotlin.math.min(s.y, e.y)
                        val r = kotlin.math.max(s.x, e.x); val b = kotlin.math.max(s.y, e.y)
                        drawRect(Color(0xFF00E5FF), topLeft = Offset(l, t), size = Size(r - l, b - t), style = Stroke(width = 4f))
                    }
                }
            }
        }
    }
}
