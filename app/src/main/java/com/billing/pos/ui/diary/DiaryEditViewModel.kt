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
import com.billing.pos.data.DiaryAttachment
import com.billing.pos.data.DiaryEntry
import com.billing.pos.data.DiaryRepository
import com.billing.pos.diary.AttachmentStore
import com.billing.pos.diary.ReminderScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class DiaryEditViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = DiaryRepository(app)

    var loadedId by mutableStateOf(0L); private set
    var title by mutableStateOf("")
    var remarks by mutableStateOf("")
    var reminderEnabled by mutableStateOf(false)
    var reminderDaily by mutableStateOf(false)
    var reminderAt by mutableStateOf(System.currentTimeMillis())

    val attachments: SnapshotStateList<DiaryAttachment> = mutableStateListOf()

    var recording by mutableStateOf(false); private set
    private var recorder: MediaRecorder? = null
    private var recordFile: File? = null
    private var createdAt = System.currentTimeMillis()
    private var loaded = false

    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }

    fun load(id: Long) {
        if (loaded) return
        loaded = true
        if (id <= 0) return
        viewModelScope.launch {
            val entry = repo.byId(id) ?: return@launch
            loadedId = entry.id
            title = entry.title
            remarks = entry.remarks
            reminderEnabled = entry.reminderEnabled
            reminderDaily = entry.reminderDaily
            reminderAt = if (entry.reminderAt > 0) entry.reminderAt else System.currentTimeMillis()
            createdAt = entry.createdAt
            attachments.clear()
            attachments.addAll(repo.attachmentsFor(entry.id))
        }
    }

    fun addUris(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val added = withContext(Dispatchers.IO) { uris.mapNotNull { AttachmentStore.copyIn(context, it) } }
            attachments.addAll(added)
        }
    }

    fun removeAttachment(attachment: DiaryAttachment) {
        attachments.remove(attachment)
        viewModelScope.launch {
            if (attachment.id > 0) repo.deleteAttachment(attachment)
            else withContext(Dispatchers.IO) { AttachmentStore.delete(attachment) }
        }
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
        recordFile?.let { f ->
            if (f.exists() && f.length() > 0) {
                attachments.add(AttachmentStore.fromFile(f, "Voice note.m4a", "audio/mp4"))
            }
        }
        recordFile = null
    }

    fun save(context: Context, onDone: () -> Unit) {
        if (title.isBlank() && remarks.isBlank() && attachments.isEmpty()) {
            message.value = "Nothing to save"
            return
        }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val entry = DiaryEntry(
                id = loadedId,
                title = title.trim(),
                remarks = remarks.trim(),
                createdAt = if (loadedId == 0L) now else createdAt,
                updatedAt = now,
                reminderEnabled = reminderEnabled,
                reminderAt = if (reminderEnabled) reminderAt else 0L,
                reminderDaily = reminderDaily
            )
            val id = repo.upsert(entry)
            loadedId = id
            attachments.filter { it.id == 0L }.forEach { repo.insertAttachment(it.copy(entryId = id)) }
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
}
