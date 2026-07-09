package com.billing.pos.ui.diary

import android.app.Application
import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.billing.pos.data.AttachmentType
import com.billing.pos.data.BlockType
import com.billing.pos.data.DiaryBlock
import com.billing.pos.data.DiaryEntry
import com.billing.pos.data.DiaryRepository
import com.billing.pos.diary.AttachmentStore
import com.billing.pos.diary.ReminderScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One editable block in the diary body. Text blocks hold typed text; media blocks a file. */
class BlockUi(
    val id: Long,
    val type: BlockType,
    text: String = "",
    val path: String = "",
    val name: String = "",
    val mime: String = "",
    val durationMs: Long = 0
) {
    var text by mutableStateOf(text)
    val isMedia: Boolean get() = type != BlockType.TEXT && type != BlockType.LOCATION
}

class DiaryEditViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = DiaryRepository(app)

    var loadedId by mutableStateOf(0L); private set
    var title by mutableStateOf("")

    var reminderEnabled by mutableStateOf(false)
    var reminderDaily by mutableStateOf(false)
    var reminderAt by mutableStateOf(System.currentTimeMillis())

    // Text formatting (color 0 = default).
    var titleSize by mutableStateOf(20)
    var titleColor by mutableStateOf(0)
    var titleBold by mutableStateOf(true)
    var titleItalic by mutableStateOf(false)
    var bodySize by mutableStateOf(15)
    var bodyColor by mutableStateOf(0)
    var bodyBold by mutableStateOf(false)
    var bodyItalic by mutableStateOf(false)

    /** The ordered body: text / image / voice / … blocks. */
    val blocks: SnapshotStateList<BlockUi> = mutableStateListOf()
    private val removedFiles = mutableListOf<String>()

    var recording by mutableStateOf(false); private set
    private var recorder: MediaRecorder? = null
    private var recordFile: File? = null
    private var recordStart = 0L
    private var createdAt = System.currentTimeMillis()
    private var loaded = false

    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }

    /** Combined text of all text blocks, for the read-only search view and previews. */
    fun notesText(): String = blocks.filter { it.type == BlockType.TEXT }.joinToString("\n") { it.text }

    fun load(id: Long) {
        if (loaded) return
        loaded = true
        if (id <= 0) { blocks.add(BlockUi(0, BlockType.TEXT)); return }
        viewModelScope.launch {
            val entry = repo.byId(id) ?: run { blocks.add(BlockUi(0, BlockType.TEXT)); return@launch }
            loadedId = entry.id
            title = entry.title
            reminderEnabled = entry.reminderEnabled
            reminderDaily = entry.reminderDaily
            reminderAt = if (entry.reminderAt > 0) entry.reminderAt else System.currentTimeMillis()
            titleSize = entry.titleSize; titleColor = entry.titleColor
            titleBold = entry.titleBold; titleItalic = entry.titleItalic
            bodySize = entry.bodySize; bodyColor = entry.bodyColor
            bodyBold = entry.bodyBold; bodyItalic = entry.bodyItalic
            createdAt = entry.createdAt

            blocks.clear()
            val stored = repo.blocksFor(entry.id)
            if (stored.isNotEmpty()) {
                stored.forEach { blocks.add(it.toUi()) }
            } else {
                // Legacy entry (pre-blocks): seed from remarks + any old attachments.
                if (entry.remarks.isNotBlank()) blocks.add(BlockUi(0, BlockType.TEXT, entry.remarks))
                repo.attachmentsFor(entry.id).forEach { att ->
                    blocks.add(BlockUi(0, attToBlockType(att.type), if (att.type == AttachmentType.LOCATION) att.path else "", att.path, att.name, att.mime))
                }
            }
            if (blocks.isEmpty()) blocks.add(BlockUi(0, BlockType.TEXT))
        }
    }

    // ---- block edits ----
    fun addTextBlock() { blocks.add(BlockUi(0, BlockType.TEXT)) }

    fun moveUp(index: Int) { if (index > 0) blocks.add(index - 1, blocks.removeAt(index)) }
    fun moveDown(index: Int) { if (index < blocks.size - 1) blocks.add(index + 1, blocks.removeAt(index)) }

    fun removeBlock(index: Int) {
        val b = blocks.getOrNull(index) ?: return
        blocks.removeAt(index)
        if (b.path.isNotBlank()) removedFiles.add(b.path)
        if (blocks.isEmpty()) blocks.add(BlockUi(0, BlockType.TEXT))
    }

    /** Crops-then-compresses images are added here (from camera or gallery). */
    fun addImageUri(context: Context, uri: Uri) {
        viewModelScope.launch {
            val dest = AttachmentStore.newFile(context, "jpg")
            val ok = withContext(Dispatchers.IO) { AttachmentStore.compressImageTo(context, uri, dest) }
            if (ok) blocks.add(BlockUi(0, BlockType.IMAGE, path = dest.absolutePath, name = "Photo.jpg", mime = "image/jpeg"))
            else message.value = "Could not add image"
        }
    }

    fun addFileUris(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val added = withContext(Dispatchers.IO) { uris.mapNotNull { AttachmentStore.copyIn(context, it) } }
            added.forEach { att ->
                blocks.add(BlockUi(0, attToBlockType(att.type), path = att.path, name = att.name, mime = att.mime))
            }
        }
    }

    /** Registers a file the camera just wrote (e.g. a video) as a block. */
    fun addCapturedFile(file: File, name: String, mime: String) {
        if (file.exists() && file.length() > 0) {
            blocks.add(BlockUi(0, attToBlockType(AttachmentStore.typeOf(mime)), path = file.absolutePath, name = name, mime = mime))
        } else file.delete()
    }

    /** Appends dictated text to the last text block, or starts a new one. */
    fun appendSpokenToBody(spoken: String) {
        val last = blocks.lastOrNull { it.type == BlockType.TEXT }
        if (last != null) last.text = if (last.text.isBlank()) spoken else "${last.text} $spoken"
        else blocks.add(BlockUi(0, BlockType.TEXT, spoken))
    }

    fun addLocation(lat: Double, lng: Double) {
        val url = "https://maps.google.com/?q=$lat,$lng"
        val label = "Location " + String.format("%.5f, %.5f", lat, lng)
        blocks.add(BlockUi(0, BlockType.LOCATION, text = url, name = label, mime = "text/uri-list"))
    }

    fun setReminderDateTime(millis: Long) { reminderAt = millis }

    fun startRecording(context: Context) {
        if (recording) return
        val file = File(AttachmentStore.dir(context), "voice_${System.nanoTime()}.m4a")
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context)
        else @Suppress("DEPRECATION") MediaRecorder()
        try {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            recordFile = file
            recordStart = System.currentTimeMillis()
            recording = true
        } catch (e: Exception) {
            runCatching { rec.release() }
            file.delete()
            message.value = "Could not start recording"
        }
    }

    fun stopRecording() {
        val rec = recorder ?: return
        runCatching { rec.stop() }
        runCatching { rec.release() }
        recorder = null
        recording = false
        val dur = System.currentTimeMillis() - recordStart
        recordFile?.let { f ->
            if (f.exists() && f.length() > 0) {
                blocks.add(BlockUi(0, BlockType.AUDIO, path = f.absolutePath, name = "Voice note.m4a", mime = "audio/mp4", durationMs = dur))
            }
        }
        recordFile = null
    }

    fun save(context: Context, onDone: () -> Unit) {
        val remarks = notesText().trim()
        val hasMedia = blocks.any { it.type != BlockType.TEXT }
        if (title.isBlank() && remarks.isBlank() && !hasMedia) {
            message.value = "Nothing to save"
            return
        }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val entry = DiaryEntry(
                id = loadedId,
                title = title.trim(),
                remarks = remarks,
                createdAt = if (loadedId == 0L) now else createdAt,
                updatedAt = now,
                reminderEnabled = reminderEnabled,
                reminderAt = if (reminderEnabled) reminderAt else 0L,
                reminderDaily = reminderDaily,
                titleSize = titleSize, titleColor = titleColor, titleBold = titleBold, titleItalic = titleItalic,
                bodySize = bodySize, bodyColor = bodyColor, bodyBold = bodyBold, bodyItalic = bodyItalic
            )
            val id = repo.upsert(entry)
            loadedId = id
            // Drop empty trailing text blocks so we don't persist blank paragraphs.
            val toSave = blocks.filter { it.type != BlockType.TEXT || it.text.isNotBlank() }
            repo.replaceBlocks(id, toSave.mapIndexed { index, b -> b.toEntity(index) })
            withContext(Dispatchers.IO) { removedFiles.forEach { runCatching { File(it).delete() } } }
            removedFiles.clear()
            val scheduled = runCatching { ReminderScheduler.schedule(context, entry.copy(id = id)) }
                .getOrDefault(false)
            message.value =
                if (entry.reminderEnabled && !scheduled) "Saved (reminder could not be set)" else "Saved"
            onDone()
        }
    }

    fun delete(context: Context, onDone: () -> Unit) {
        val id = loadedId
        if (id == 0L) { onDone(); return }
        viewModelScope.launch {
            repo.byId(id)?.let { repo.deleteEntry(it) }
            ReminderScheduler.cancel(context, id)
            onDone()
        }
    }

    override fun onCleared() {
        runCatching { recorder?.release() }
        recorder = null
    }

    private fun DiaryBlock.toUi() = BlockUi(id, type, text, path, name, mime, durationMs)
    private fun BlockUi.toEntity(index: Int) =
        DiaryBlock(id = 0, entryId = 0, position = index, type = type, text = text, path = path, name = name, mime = mime, durationMs = durationMs)

    private fun attToBlockType(t: AttachmentType): BlockType = when (t) {
        AttachmentType.IMAGE -> BlockType.IMAGE
        AttachmentType.VIDEO -> BlockType.VIDEO
        AttachmentType.AUDIO -> BlockType.AUDIO
        AttachmentType.DOCUMENT -> BlockType.DOCUMENT
        AttachmentType.LOCATION -> BlockType.LOCATION
    }
}
