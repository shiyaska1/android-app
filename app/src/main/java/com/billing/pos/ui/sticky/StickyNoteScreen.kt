package com.billing.pos.ui.sticky

import android.Manifest
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.Path as AndroidPath
import android.graphics.Rect
import android.media.MediaRecorder
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
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
import java.io.File

/** Session flag so the launch sticky-note shows only once per app start. */
object StickyGate { var shown = false }

/** One-shot hand-off of OCR'd items from the sticky note to the sales entry. */
object StickyOcrLink {
    var items: List<com.billing.pos.ocr.ScannedItem>? = null
    fun take(): List<com.billing.pos.ocr.ScannedItem>? { val i = items; items = null; return i }
}

private typealias StrokePts = List<Offset>

/** A page of the note: handwriting strokes over an optional background image. */
class NotePage {
    val strokes = mutableStateListOf<StrokePts>()
    var bg by mutableStateOf<String?>(null)
}

/** Immutable snapshot passed to the VM for rendering. */
data class PageData(val strokes: List<StrokePts>, val bg: String?)

class StickyNoteViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = DiaryRepository(app)
    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }

    var recording by mutableStateOf(false); private set
    private var recorder: MediaRecorder? = null
    private var recordFile: File? = null

    // Re-use one diary entry across repeated saves (e.g. Save-on-share).
    private var savedEntryId: Long = 0
    private var savedCreatedAt: Long = 0
    private var lastPagePaths: List<String> = emptyList()

    fun startRecording() {
        if (recording) return
        val ctx = getApplication<Application>()
        val file = File(AttachmentStore.dir(ctx), "note_voice_${System.nanoTime()}.m4a")
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setOutputFile(file.absolutePath)
            rec.prepare(); rec.start()
            recorder = rec; recordFile = file; recording = true
        } catch (e: Exception) { runCatching { rec.release() }; file.delete(); message.value = "Could not start recording" }
    }

    fun stopRecording(onSaved: (String) -> Unit) {
        val rec = recorder ?: return
        runCatching { rec.stop() }; runCatching { rec.release() }
        recorder = null; recording = false
        recordFile?.let { if (it.exists() && it.length() > 0) onSaved(it.absolutePath) }
    }

    override fun onCleared() { runCatching { recorder?.release() }; recorder = null }

    /** Renders each page (background + strokes) to a picture and creates a diary entry with all attachments. */
    fun save(pages: List<PageData>, w: Int, h: Int, images: List<String>, audios: List<String>, videos: List<String>, onDone: () -> Unit) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            val pagePaths = withContext(Dispatchers.IO) {
                val out = ArrayList<String>()
                if (w > 0 && h > 0) pages.filter { it.strokes.isNotEmpty() || it.bg != null }.forEach { p ->
                    val bmp = renderPage(p, w, h)
                    val f = AttachmentStore.newFile(ctx, "jpg")
                    f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                    bmp.recycle(); out.add(f.absolutePath)
                }
                out
            }
            if (pagePaths.isEmpty() && images.isEmpty() && audios.isEmpty() && videos.isEmpty()) { message.value = "Nothing to save"; return@launch }
            // Remove page images rendered by a previous save of this same note.
            withContext(Dispatchers.IO) { lastPagePaths.forEach { runCatching { File(it).delete() } } }
            val now = System.currentTimeMillis()
            if (savedCreatedAt == 0L) savedCreatedAt = now
            val id = repo.upsert(DiaryEntry(id = savedEntryId, title = "Sticky note - ${Format.dateTime(savedCreatedAt)}", remarks = "", createdAt = savedCreatedAt, updatedAt = now))
            savedEntryId = id
            val blocks = ArrayList<DiaryBlock>()
            pagePaths.forEach { blocks.add(DiaryBlock(entryId = id, position = 0, type = BlockType.IMAGE, path = it, name = "Sticky note.jpg", mime = "image/jpeg")) }
            images.forEach { blocks.add(DiaryBlock(entryId = id, position = 0, type = BlockType.IMAGE, path = it, name = "Photo.jpg", mime = "image/jpeg")) }
            audios.forEach { blocks.add(DiaryBlock(entryId = id, position = 0, type = BlockType.AUDIO, path = it, name = "Voice note.m4a", mime = "audio/mp4")) }
            videos.forEach { blocks.add(DiaryBlock(entryId = id, position = 0, type = BlockType.VIDEO, path = it, name = "Video.mp4", mime = "video/mp4")) }
            repo.replaceBlocks(id, blocks)
            lastPagePaths = pagePaths
            message.value = "Saved to My Diary"
            onDone()
        }
    }

    /**
     * Reads NUMBERS ONLY from every page. Handwriting is recognised with ML Kit Digital Ink
     * (built for handwriting) directly from the strokes; a photo background falls back to
     * image OCR. Each page's number becomes a price (no description).
     */
    fun ocrAllPages(pages: List<PageData>, w: Int, h: Int, onResult: (List<com.billing.pos.ocr.ScannedItem>) -> Unit) {
        val ctx = getApplication<Application>()
        message.value = "Reading numbers…"
        viewModelScope.launch {
            val ink = com.billing.pos.ink.InkRecognizer()
            val ready = ink.ensureReady()
            val numRegex = Regex("[0-9]+(?:[.,][0-9]+)?")
            val result = withContext(Dispatchers.IO) {
                val out = ArrayList<com.billing.pos.ocr.ScannedItem>()
                pages.forEach { p ->
                    var text = ""
                    if (ready && p.strokes.isNotEmpty()) text = ink.recognize(p.strokes)
                    if (text.isBlank() && p.bg != null && w > 0 && h > 0) {
                        val bmp = renderPage(p, w, h)
                        val f = File(ctx.cacheDir, "ocr_${System.nanoTime()}.jpg")
                        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                        bmp.recycle()
                        text = com.billing.pos.ocr.TextOcr.lines(ctx, android.net.Uri.fromFile(f)).joinToString(" ")
                        f.delete()
                    }
                    numRegex.find(text)?.value?.replace(",", ".")?.toDoubleOrNull()?.let { if (it > 0.0) out.add(com.billing.pos.ocr.ScannedItem("", it)) }
                }
                out
            }
            ink.close()
            message.value = when {
                result.isNotEmpty() -> null
                !ready -> "Handwriting model still downloading — connect to the internet once and retry"
                else -> "No number recognised — write the price larger"
            }
            if (result.isNotEmpty()) onResult(result)
        }
    }

    private fun renderPage(p: PageData, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = AndroidCanvas(bmp)
        c.drawColor(0xFFFFFFFF.toInt())
        p.bg?.let { path -> runCatching { BitmapFactory.decodeFile(path) }.getOrNull()?.let { c.drawBitmap(it, null, Rect(0, 0, w, h), null); it.recycle() } }
        val paint = AndroidPaint().apply { color = 0xFF111111.toInt(); isAntiAlias = true; style = AndroidPaint.Style.STROKE; strokeWidth = 4f; strokeCap = AndroidPaint.Cap.ROUND; strokeJoin = AndroidPaint.Join.ROUND }
        p.strokes.forEach { pts ->
            val path = AndroidPath(); pts.forEachIndexed { i, pt -> if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y) }
            c.drawPath(path, paint)
        }
        return bmp
    }
}

@Composable
fun StickyNoteScreen(onClose: () -> Unit, onOcrToSales: () -> Unit = {}, vm: StickyNoteViewModel = viewModel()) {
    val context = LocalContext.current
    val message by vm.message.collectAsState()

    val pages = remember { mutableStateListOf(NotePage()) }
    var pageIndex by remember { mutableStateOf(0) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val current = remember { mutableStateListOf<Offset>() }
    val images = remember { mutableStateListOf<String>() }   // non-background photos
    val audios = remember { mutableStateListOf<String>() }
    val videos = remember { mutableStateListOf<String>() }
    var bgMode by remember { mutableStateOf(false) }
    var ocrResult by remember { mutableStateOf<List<com.billing.pos.ocr.ScannedItem>?>(null) }

    LaunchedEffect(message) { message?.let { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show(); vm.consumeMessage() } }

    // Route a picked image either to the page background or to attachments.
    fun handleImage(uri: android.net.Uri) {
        val dest = AttachmentStore.newFile(context, "jpg")
        val ok = runCatching { AttachmentStore.compressImageTo(context, uri, dest) }.getOrDefault(false)
        if (!ok) return
        if (bgMode) pages.getOrNull(pageIndex)?.bg = dest.absolutePath else images.add(dest.absolutePath)
    }
    val camera = com.billing.pos.ocr.rememberImageCamera { uri -> handleImage(uri) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) handleImage(uri) }

    // Video capture.
    var pendingVideo by remember { mutableStateOf<File?>(null) }
    val takeVideo = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
        val f = pendingVideo; pendingVideo = null
        if (ok == true && f != null && f.exists() && f.length() > 0) videos.add(f.absolutePath) else f?.delete()
    }
    fun launchVideo() {
        val f = File(AttachmentStore.dir(context), "note_vid_${System.nanoTime()}.mp4"); pendingVideo = f
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", f)
        runCatching { takeVideo.launch(uri) }.onFailure { pendingVideo = null }
    }
    val camPermForVideo = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) launchVideo() }
    fun startVideo() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) launchVideo()
        else camPermForVideo.launch(Manifest.permission.CAMERA)
    }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) vm.startRecording() }
    fun toggleRecord() {
        if (vm.recording) vm.stopRecording { audios.add(it) }
        else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) vm.startRecording()
        else micPerm.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun shareCurrent() {
        if (canvasSize.width <= 0) return
        val page = pages.getOrNull(pageIndex) ?: return
        val bmp = Bitmap.createBitmap(canvasSize.width, canvasSize.height, Bitmap.Config.ARGB_8888)
        AndroidCanvas(bmp).apply {
            drawColor(0xFFFFFFFF.toInt())
            page.bg?.let { p -> runCatching { BitmapFactory.decodeFile(p) }.getOrNull()?.let { drawBitmap(it, null, Rect(0, 0, canvasSize.width, canvasSize.height), null) } }
            val paint = AndroidPaint().apply { color = 0xFF111111.toInt(); isAntiAlias = true; style = AndroidPaint.Style.STROKE; strokeWidth = 4f; strokeCap = AndroidPaint.Cap.ROUND }
            page.strokes.forEach { pts -> val pa = AndroidPath(); pts.forEachIndexed { i, pt -> if (i == 0) pa.moveTo(pt.x, pt.y) else pa.lineTo(pt.x, pt.y) }; drawPath(pa, paint) }
        }
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val f = File(dir, "stickynote_${System.nanoTime()}.jpg")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", f)
        val intent = Intent(Intent.ACTION_SEND).apply { type = "image/jpeg"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        runCatching { context.startActivity(Intent.createChooser(intent, "Share note").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    // Background image of the current page (loaded for the canvas).
    val curBgPath = pages.getOrNull(pageIndex)?.bg
    val bgImage: ImageBitmap? = remember(curBgPath) { curBgPath?.let { runCatching { BitmapFactory.decodeFile(it)?.asImageBitmap() }.getOrNull() } }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Sticky note", style = MaterialTheme.typography.titleMedium)
            Text("   Page ${pageIndex + 1}/${pages.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            Row(Modifier.padding(start = 12.dp).weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = bgMode, onCheckedChange = { bgMode = it })
                Text("Photo as background", style = MaterialTheme.typography.labelSmall)
            }
            if (vm.recording) Text("● REC", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
        }
        Canvas(
            Modifier.weight(1f).fillMaxWidth().background(Color.White)
                .onSizeChanged { canvasSize = it }
                .pointerInput(pageIndex) {
                    detectDragGestures(
                        onDragStart = { off -> current.clear(); current.add(off) },
                        onDrag = { change, _ -> current.add(change.position); change.consume() },
                        onDragEnd = {
                            if (current.isNotEmpty()) { while (pages.size <= pageIndex) pages.add(NotePage()); pages[pageIndex].strokes.add(current.toList()) }
                            current.clear()
                        }
                    )
                }
        ) {
            bgImage?.let { drawImage(it, srcOffset = IntOffset.Zero, srcSize = IntSize(it.width, it.height), dstOffset = IntOffset.Zero, dstSize = IntSize(size.width.toInt(), size.height.toInt())) }
            val ink = Color.Black
            pages.getOrNull(pageIndex)?.strokes?.forEach { pts ->
                val path = Path().apply { pts.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
                drawPath(path, ink, style = Stroke(width = 4f, cap = StrokeCap.Round))
            }
            if (current.isNotEmpty()) {
                val path = Path().apply { current.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
                drawPath(path, ink, style = Stroke(width = 4f, cap = StrokeCap.Round))
            }
        }
        // Row 1: page nav + clear
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { if (pageIndex > 0) pageIndex-- }, enabled = pageIndex > 0, modifier = Modifier.weight(1f)) { Text("Prev") }
            OutlinedButton(onClick = { if (pageIndex == pages.lastIndex) pages.add(NotePage()); pageIndex++ }, modifier = Modifier.weight(1f)) { Text("Next") }
            OutlinedButton(onClick = { pages.getOrNull(pageIndex)?.let { it.strokes.clear(); it.bg = null } }, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.Clear, null); Text("Clear") }
        }
        // Row 2: attachments
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { camera() }, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.PhotoCamera, null) }
            OutlinedButton(onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.PhotoLibrary, null) }
            OutlinedButton(onClick = { toggleRecord() }, modifier = Modifier.weight(1f)) { Icon(if (vm.recording) Icons.Filled.Stop else Icons.Filled.Mic, null) }
            OutlinedButton(onClick = { startVideo() }, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.Videocam, null) }
            OutlinedButton(onClick = {
                // Share the current page and save the note to My Diary — but keep the window open.
                shareCurrent()
                vm.save(pages.map { PageData(it.strokes.toList(), it.bg) }, canvasSize.width, canvasSize.height, images.toList(), audios.toList(), videos.toList()) { }
            }, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.Share, null) }
            OutlinedButton(onClick = {
                vm.ocrAllPages(pages.map { PageData(it.strokes.toList(), it.bg) }, canvasSize.width, canvasSize.height) { items -> ocrResult = items }
            }, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.TextFields, null) }
        }
        // Row 3: close / save
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f)) { Text("Close") }
            Button(onClick = { vm.save(pages.map { PageData(it.strokes.toList(), it.bg) }, canvasSize.width, canvasSize.height, images.toList(), audios.toList(), videos.toList()) { onClose() } }, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.Save, null); Text("Save") }
        }
    }

    // OCR result — a list of scanned amounts with a big total, shareable, addable to a sale.
    ocrResult?.let { list ->
        val total = list.sumOf { it.price }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { ocrResult = null },
            title = { Text("Scanned amounts (${list.size})") },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                        androidx.compose.foundation.lazy.items(list.size) { i ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("${i + 1}.")
                                Text(Format.money(list[i].price), style = MaterialTheme.typography.titleMedium)
                            }
                            androidx.compose.material3.Divider()
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("TOTAL", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Text(Format.money(total), style = MaterialTheme.typography.headlineSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val text = list.joinToString("\n") { Format.money(it.price) } + "\n\nTotal: ${Format.money(total)}"
                            val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                            runCatching { context.startActivity(Intent.createChooser(intent, "Share amounts").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.Share, null); Text(" Share") }
                        Button(onClick = { StickyOcrLink.items = list; ocrResult = null; onOcrToSales() }, modifier = Modifier.weight(1f)) { Text("Add to Sale") }
                    }
                }
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { ocrResult = null }) { Text("Close") } }
        )
    }
}
