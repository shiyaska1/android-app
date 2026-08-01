package com.billing.pos.ui.quicknote

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.billing.pos.data.QuickNote
import com.billing.pos.data.QuickNoteAttachment
import com.billing.pos.data.QuickNoteRepository
import com.billing.pos.ui.common.HandwriteTextDialog
import com.billing.pos.ui.common.ImageViewerDialog
import com.billing.pos.ui.common.rememberThumbnail
import com.billing.pos.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * List of quick notes, most-recently-updated first. Tapping "+" or an existing note opens the
 * same popup form (text + photos + optional reminder); everything is written back on Save.
 */
@Composable
fun QuickNoteListScreen(onBack: () -> Unit, initialEditId: Long? = null) {
    val context = LocalContext.current
    val repo = remember { QuickNoteRepository(context) }
    val notes by repo.notes.collectAsState(initial = emptyList())

    var editing by remember { mutableStateOf<QuickNote?>(null) }
    var showEditor by remember { mutableStateOf(false) }

    // Deep link (from a reminder notification): open this note in edit mode once loaded.
    var editLinkDone by remember { mutableStateOf(false) }
    LaunchedEffect(notes, initialEditId) {
        if (!editLinkDone && initialEditId != null && initialEditId > 0) {
            notes.firstOrNull { it.id == initialEditId }?.let { n ->
                editing = n; showEditor = true; editLinkDone = true
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Quick notes") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = null; showEditor = true }) {
                Icon(Icons.Filled.Add, "New note")
            }
        }
    ) { pad ->
        if (notes.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text("No quick notes yet — tap + to add one", color = MaterialTheme.colorScheme.outline)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp)) {
                items(notes, key = { it.id }) { n ->
                    Row(
                        Modifier.fillMaxWidth().clickable { editing = n; showEditor = true }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (n.reminderEnabled) {
                                    Icon(
                                        Icons.Filled.Notifications, "Reminder set",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(end = 4.dp)
                                    )
                                }
                                Text(
                                    n.text.ifBlank { "(empty note)" },
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                Format.dateTime(n.updatedAt),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                    Divider()
                }
            }
        }
    }

    if (showEditor) {
        QuickNoteEditDialog(
            repo = repo,
            initial = editing,
            onClose = { showEditor = false; editing = null }
        )
    }
}

/** Popup form for a new or existing note: text + handwrite + photos + optional reminder. */
@Composable
private fun QuickNoteEditDialog(repo: QuickNoteRepository, initial: QuickNote?, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var text by remember { mutableStateOf(initial?.text ?: "") }
    var reminderEnabled by remember { mutableStateOf(initial?.reminderEnabled ?: false) }
    var reminderDaily by remember { mutableStateOf(initial?.reminderDaily ?: false) }
    var reminderAt by remember {
        mutableStateOf(if ((initial?.reminderAt ?: 0L) > 0L) initial!!.reminderAt else System.currentTimeMillis())
    }
    var drawing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val attachments: SnapshotStateList<QuickNoteAttachment> = remember { mutableStateListOf() }
    var viewImages by remember { mutableStateOf<List<String>?>(null) }
    var viewStart by remember { mutableStateOf(0) }

    LaunchedEffect(initial?.id) {
        if (initial != null && initial.id > 0) {
            attachments.clear()
            attachments.addAll(repo.attachmentsFor(initial.id))
        }
    }

    fun append(spoken: String) {
        if (spoken.isBlank()) return
        text = if (text.isBlank()) spoken.trim() else (text.trimEnd() + "\n" + spoken.trim())
    }

    fun addPhoto(uri: android.net.Uri?) {
        if (uri == null) return
        scope.launch {
            val att = withContext(Dispatchers.IO) { QuickNoteAttachmentStore.copyIn(context, uri) }
            if (att != null) attachments.add(att)
        }
    }

    val camera = com.billing.pos.ocr.rememberImageCamera { uri -> addPhoto(uri) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> addPhoto(uri) }

    fun removeAttachment(att: QuickNoteAttachment) {
        attachments.remove(att)
        scope.launch(Dispatchers.IO) {
            if (att.id > 0) repo.deleteAttachment(att) else QuickNoteAttachmentStore.delete(att)
        }
    }

    fun doSave(afterSave: () -> Unit) {
        val now = System.currentTimeMillis()
        val note = (initial ?: QuickNote()).copy(
            text = text.trim(),
            createdAt = initial?.createdAt ?: now,
            updatedAt = now,
            reminderEnabled = reminderEnabled,
            reminderAt = reminderAt,
            reminderDaily = reminderDaily
        )
        scope.launch {
            val id = repo.upsert(note)
            attachments.filter { it.id == 0L }.forEach { repo.addAttachment(it.copy(noteId = id)) }
            runCatching { QuickNoteReminderScheduler.schedule(context, note.copy(id = id)) }
            afterSave()
        }
    }

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { doSave(onClose) }

    fun save() {
        if (reminderEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            doSave(onClose)
        }
    }

    fun delete() {
        val id = initial?.id ?: return
        scope.launch {
            repo.delete(id)
            runCatching { QuickNoteReminderScheduler.cancel(context, id) }
            confirmDelete = false
            onClose()
        }
    }

    if (drawing) {
        HandwriteTextDialog(
            onResult = { append(it); drawing = false },
            onDismiss = { drawing = false }
        )
    }
    viewImages?.let { paths ->
        ImageViewerDialog(paths = paths, startIndex = viewStart, onDismiss = { viewImages = null })
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Column(
            Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (initial == null) "New note" else "Edit note",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { drawing = true }) { Icon(Icons.Filled.Draw, "Draw") }
                IconButton(onClick = { camera() }) { Icon(Icons.Filled.PhotoCamera, "Camera") }
                IconButton(onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Icon(Icons.Filled.PhotoLibrary, "Gallery")
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Type your note…") },
                textStyle = LocalTextStyle.current.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp),
                minLines = 6,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )

            if (attachments.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    attachments.forEach { att ->
                        QuickPhotoThumb(
                            att,
                            onRemove = { removeAttachment(att) },
                            onOpen = {
                                val paths = attachments.map { it.path }
                                viewStart = paths.indexOf(att.path).coerceAtLeast(0)
                                viewImages = paths
                            }
                        )
                    }
                }
            }

            Card(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Reminder", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Switch(checked = reminderEnabled, onCheckedChange = { reminderEnabled = it })
                    }
                    if (reminderEnabled) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                            Text("Repeat every day", Modifier.weight(1f))
                            Switch(checked = reminderDaily, onCheckedChange = { reminderDaily = it })
                        }
                        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!reminderDaily) {
                                OutlinedButton(onClick = { pickDate(context, reminderAt) { reminderAt = it } }) {
                                    Text(Format.date(reminderAt))
                                }
                            }
                            OutlinedButton(onClick = { pickTime(context, reminderAt) { reminderAt = it } }) {
                                Text(timeLabel(reminderAt))
                            }
                        }
                        Text(
                            if (reminderDaily) "Every day at ${timeLabel(reminderAt)}" else "Once on ${Format.dateTime(reminderAt)}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (initial != null) {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                    }
                }
                OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f)) { Text("Close") }
                Button(onClick = ::save, enabled = text.isNotBlank(), modifier = Modifier.weight(1f)) { Text("Save") }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this note?") },
            confirmButton = { TextButton(onClick = ::delete) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun QuickPhotoThumb(att: QuickNoteAttachment, onRemove: () -> Unit, onOpen: () -> Unit) {
    Box(Modifier.size(72.dp)) {
        val bmp = rememberThumbnail(att.path, 200)
        Box(
            Modifier.size(72.dp).clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { onOpen() },
            contentAlignment = Alignment.Center
        ) {
            if (bmp != null) {
                Image(bmp, contentDescription = att.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Box(
            Modifier.align(Alignment.TopEnd).padding(2.dp).size(20.dp).clip(RoundedCornerShape(10.dp))
                .background(Color(0xAA000000)).clickable { onRemove() },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Close, contentDescription = "Remove", tint = Color.White, modifier = Modifier.size(14.dp))
        }
    }
}

private fun pickDate(context: android.content.Context, current: Long, onPicked: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = current }
    android.app.DatePickerDialog(
        context,
        { _, y, m, d -> c.set(Calendar.YEAR, y); c.set(Calendar.MONTH, m); c.set(Calendar.DAY_OF_MONTH, d); onPicked(c.timeInMillis) },
        c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
    ).show()
}

private fun pickTime(context: android.content.Context, current: Long, onPicked: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = current }
    android.app.TimePickerDialog(
        context,
        { _, h, min -> c.set(Calendar.HOUR_OF_DAY, h); c.set(Calendar.MINUTE, min); c.set(Calendar.SECOND, 0); onPicked(c.timeInMillis) },
        c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), false
    ).show()
}

private fun timeLabel(millis: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = millis }
    return String.format("%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
}
