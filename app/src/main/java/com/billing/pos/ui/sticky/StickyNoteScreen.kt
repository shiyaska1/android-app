package com.billing.pos.ui.sticky

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.Path as AndroidPath
import androidx.core.content.FileProvider
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.BlockType
import com.billing.pos.data.DiaryBlock
import com.billing.pos.data.DiaryEntry
import com.billing.pos.data.DiaryRepository
import com.billing.pos.diary.AttachmentStore
import com.billing.pos.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Session flag so the launch sticky-note shows only once per app start. */
object StickyGate { var shown = false }

private typealias StrokePts = List<Offset>

class StickyNoteViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = DiaryRepository(app)
    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }

    /** Renders each non-empty page to an image, plus any camera photos, into a new diary entry. */
    fun save(pages: List<List<StrokePts>>, w: Int, h: Int, photoPaths: List<String>, onDone: () -> Unit) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            val paths = withContext(Dispatchers.IO) {
                val out = ArrayList<String>()
                if (w > 0 && h > 0) pages.filter { it.isNotEmpty() }.forEach { strokes ->
                    val bmp = renderPage(strokes, w, h)
                    val f = AttachmentStore.newFile(ctx, "jpg")
                    f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                    bmp.recycle()
                    out.add(f.absolutePath)
                }
                out.addAll(photoPaths)
                out
            }
            if (paths.isEmpty()) { message.value = "Nothing written to save"; return@launch }
            val now = System.currentTimeMillis()
            val id = repo.upsert(DiaryEntry(title = "Sticky note - ${Format.dateTime(now)}", remarks = "", createdAt = now, updatedAt = now))
            repo.replaceBlocks(id, paths.map { DiaryBlock(entryId = id, position = 0, type = BlockType.IMAGE, path = it, name = "Sticky note.jpg", mime = "image/jpeg") })
            message.value = "Saved to My Diary"
            onDone()
        }
    }

    private fun renderPage(strokes: List<StrokePts>, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = AndroidCanvas(bmp)
        c.drawColor(0xFFFFFFFF.toInt())
        val paint = AndroidPaint().apply {
            color = 0xFF111111.toInt(); isAntiAlias = true; style = AndroidPaint.Style.STROKE
            strokeWidth = 4f; strokeCap = AndroidPaint.Cap.ROUND; strokeJoin = AndroidPaint.Join.ROUND
        }
        strokes.forEach { pts ->
            if (pts.size == 1) { c.drawPoint(pts[0].x, pts[0].y, paint); return@forEach }
            val path = AndroidPath()
            pts.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
            c.drawPath(path, paint)
        }
        return bmp
    }
}

@Composable
fun StickyNoteScreen(onClose: () -> Unit, vm: StickyNoteViewModel = viewModel()) {
    val context = LocalContext.current
    val message by vm.message.collectAsState()

    // pages: each page is a list of committed strokes (each stroke = list of points, in px).
    val pages = remember { mutableStateListOf<SnapshotStateList<StrokePts>>(mutableStateListOf<StrokePts>()) }
    var pageIndex by remember { mutableStateOf(0) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val current = remember { mutableStateListOf<Offset>() }
    val photoPaths = remember { mutableStateListOf<String>() }

    LaunchedEffect(message) { message?.let { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show(); vm.consumeMessage() } }

    val camera = com.billing.pos.ocr.rememberImageCamera { uri ->
        val dest = AttachmentStore.newFile(context, "jpg")
        val ok = runCatching { AttachmentStore.compressImageTo(context, uri, dest) }.getOrDefault(false)
        if (ok) photoPaths.add(dest.absolutePath)
    }

    fun shareCurrent() {
        if (canvasSize.width <= 0) return
        val strokes = pages.getOrNull(pageIndex)?.toList() ?: emptyList()
        val bmp = Bitmap.createBitmap(canvasSize.width, canvasSize.height, Bitmap.Config.ARGB_8888)
        AndroidCanvas(bmp).apply {
            drawColor(0xFFFFFFFF.toInt())
            val paint = AndroidPaint().apply { color = 0xFF111111.toInt(); isAntiAlias = true; style = AndroidPaint.Style.STROKE; strokeWidth = 4f; strokeCap = AndroidPaint.Cap.ROUND }
            strokes.forEach { pts -> val p = AndroidPath(); pts.forEachIndexed { i, pt -> if (i == 0) p.moveTo(pt.x, pt.y) else p.lineTo(pt.x, pt.y) }; drawPath(p, paint) }
        }
        val dir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
        val f = java.io.File(dir, "stickynote_${System.nanoTime()}.jpg")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", f)
        val intent = Intent(Intent.ACTION_SEND).apply { type = "image/jpeg"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        runCatching { context.startActivity(Intent.createChooser(intent, "Share note").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Sticky note", style = MaterialTheme.typography.titleMedium)
            Text("   Page ${pageIndex + 1}/${pages.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                if (photoPaths.isNotEmpty()) Text("${photoPaths.size} photo(s)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        Canvas(
            Modifier.weight(1f).fillMaxWidth().background(Color.White)
                .onSizeChanged { canvasSize = it }
                .pointerInput(pageIndex) {
                    detectDragGestures(
                        onDragStart = { off -> current.clear(); current.add(off) },
                        onDrag = { change, _ -> current.add(change.position); change.consume() },
                        onDragEnd = {
                            if (current.isNotEmpty()) { while (pages.size <= pageIndex) pages.add(mutableStateListOf()); pages[pageIndex].add(current.toList()) }
                            current.clear()
                        }
                    )
                }
        ) {
            val ink = Color.Black
            pages.getOrNull(pageIndex)?.forEach { pts ->
                val path = Path().apply { pts.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
                drawPath(path, ink, style = Stroke(width = 4f, cap = StrokeCap.Round))
            }
            if (current.isNotEmpty()) {
                val path = Path().apply { current.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
                drawPath(path, ink, style = Stroke(width = 4f, cap = StrokeCap.Round))
            }
        }
        // Bottom controls
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { if (pageIndex > 0) pageIndex-- }, enabled = pageIndex > 0, modifier = Modifier.weight(1f)) { Text("Prev") }
            OutlinedButton(onClick = { if (pageIndex == pages.lastIndex) pages.add(mutableStateListOf()); pageIndex++ }, modifier = Modifier.weight(1f)) { Text("Next") }
            OutlinedButton(onClick = { pages.getOrNull(pageIndex)?.clear() }, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.Clear, null); Text("Clear") }
        }
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { camera() }, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.PhotoCamera, null); Text("Photo") }
            OutlinedButton(onClick = { shareCurrent() }, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.Share, null); Text("Share") }
            OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f)) { Text("Close") }
            Button(onClick = { vm.save(pages.map { it.toList() }, canvasSize.width, canvasSize.height, photoPaths.toList()) { onClose() } }, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.Save, null); Text("Save") }
        }
    }
}

