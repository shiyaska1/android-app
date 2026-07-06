package com.billing.pos.ui.diary

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.AttachmentType
import com.billing.pos.data.DiaryAttachment
import com.billing.pos.data.DiaryExport
import com.billing.pos.data.DownloadSaver
import com.billing.pos.diary.AttachmentStore
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiaryEditScreen(
    entryId: Long,
    onBack: () -> Unit,
    vm: DiaryEditViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val message by vm.message.collectAsStateSafe()

    LaunchedEffect(Unit) { vm.load(entryId) }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    var confirmDelete by remember { mutableStateOf(false) }
    var searchMode by remember { mutableStateOf(false) }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris -> vm.addUris(context, uris) }

    val docPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> vm.addUris(context, uris) }

    // Camera capture — file the camera writes into, then attaches.
    var pendingCapture by remember { mutableStateOf<File?>(null) }
    val takePhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        val f = pendingCapture; pendingCapture = null
        if (ok && f != null) vm.addCapturedFile(f, "Photo_${f.name}", "image/jpeg") else f?.delete()
    }
    val takeVideo = rememberLauncherForActivityResult(
        ActivityResultContracts.CaptureVideo()
    ) { ok ->
        val f = pendingCapture; pendingCapture = null
        if (ok && f != null) vm.addCapturedFile(f, "Video_${f.name}", "video/mp4") else f?.delete()
    }

    fun captureUri(ext: String): android.net.Uri {
        val file = File(AttachmentStore.dir(context), "cam_${System.nanoTime()}.$ext")
        pendingCapture = file
        return FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    }
    fun launchPhoto() {
        runCatching { takePhoto.launch(captureUri("jpg")) }
            .onFailure { pendingCapture?.delete(); pendingCapture = null; vm.message.value = "No camera app found" }
    }
    fun launchVideo() {
        runCatching { takeVideo.launch(captureUri("mp4")) }
            .onFailure { pendingCapture?.delete(); pendingCapture = null; vm.message.value = "No camera app found" }
    }

    // The app now declares CAMERA (barcode scanner), so capture needs it granted.
    var pendingCameraAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val action = pendingCameraAction; pendingCameraAction = null
        if (granted) action?.invoke() else vm.message.value = "Camera permission denied"
    }
    fun withCamera(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) action()
        else { pendingCameraAction = action; cameraPermission.launch(Manifest.permission.CAMERA) }
    }

    // Location attach
    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) fetchLocation(context, vm) else vm.message.value = "Location permission denied"
    }
    fun requestLocation() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) fetchLocation(context, vm)
        else locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    // Download this entry as a .zip into the Downloads folder
    fun doDownloadEntry() {
        scope.launch {
            val zip = withContext(Dispatchers.IO) { DiaryExport.exportEntry(context, vm.loadedId) }
            if (zip == null) { vm.message.value = "Nothing to export"; return@launch }
            val ok = withContext(Dispatchers.IO) { DownloadSaver.save(context, zip, zip.name, "application/zip") }
            vm.message.value = if (ok) "Saved to Downloads: ${zip.name}" else "Could not save"
        }
    }
    val storagePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) doDownloadEntry() else vm.message.value = "Storage permission denied" }
    fun downloadEntry() {
        if (DownloadSaver.needsLegacyPermission() &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else doDownloadEntry()
    }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) vm.startRecording(context) }

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { vm.save(context) { onBack() } }

    // Speech-to-text: which field the recognised text goes into.
    var speechTarget by remember { mutableStateOf("") }
    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                .orEmpty()
            if (spoken.isNotBlank()) {
                when (speechTarget) {
                    "title" -> vm.title = appendText(vm.title, spoken)
                    "remarks" -> vm.remarks = appendText(vm.remarks, spoken)
                }
            }
        }
    }

    fun startSpeech(target: String) {
        speechTarget = target
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now")
        }
        runCatching { speechLauncher.launch(intent) }
            .onFailure { vm.message.value = "Speech input not available on this device" }
    }

    fun doSave() {
        if (vm.reminderEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.save(context) { onBack() }
        }
    }

    fun toggleRecord() {
        if (vm.recording) vm.stopRecording()
        else {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) vm.startRecording(context)
            else micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (vm.loadedId == 0L) "New Diary Entry" else "Diary Entry") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                actions = {
                    if (vm.loadedId != 0L) {
                        IconButton(onClick = { downloadEntry() }) {
                            Icon(Icons.Filled.Download, contentDescription = "Download entry (zip)")
                        }
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
                        }
                    }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(12.dp)
                .verticalScroll(rememberScrollState())
        ) {
            OutlinedTextField(
                value = vm.title, onValueChange = { vm.title = it },
                label = { Text("Title") }, singleLine = true,
                trailingIcon = {
                    IconButton(onClick = { startSpeech("title") }) {
                        Icon(Icons.Filled.Mic, contentDescription = "Speak title")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("Remarks / notes", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { searchMode = !searchMode }) {
                    Icon(
                        if (searchMode) Icons.Filled.Edit else Icons.Filled.Search,
                        contentDescription = if (searchMode) "Edit notes" else "Search notes"
                    )
                }
            }
            if (searchMode) {
                RemarksSearchView(text = vm.remarks)
            } else {
                OutlinedTextField(
                    value = vm.remarks, onValueChange = { vm.remarks = it },
                    label = { Text("Remarks / notes") },
                    minLines = 4,
                    trailingIcon = {
                        IconButton(onClick = { startSpeech("remarks") }) {
                            Icon(Icons.Filled.Mic, contentDescription = "Speak remarks")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // --- Attachments ---
            Text("Attachments", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                        )
                    },
                    modifier = Modifier.weight(1f)
                ) { Icon(Icons.Filled.PhotoLibrary, null); Text("Media") }
                OutlinedButton(onClick = { docPicker.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Description, null); Text("Files")
                }
                OutlinedButton(onClick = { toggleRecord() }, modifier = Modifier.weight(1f)) {
                    if (vm.recording) { Icon(Icons.Filled.Stop, null); Text("Stop") }
                    else { Icon(Icons.Filled.Mic, null); Text("Voice") }
                }
            }
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { withCamera { launchPhoto() } }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.PhotoCamera, null); Text("Take photo")
                }
                OutlinedButton(onClick = { withCamera { launchVideo() } }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Videocam, null); Text("Record video")
                }
            }
            OutlinedButton(onClick = { requestLocation() }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Icon(Icons.Filled.Place, null); Text("Attach location")
            }
            if (vm.recording) {
                Text("● Recording…", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
            }

            vm.attachments.forEach { att ->
                AttachmentRow(
                    att = att,
                    onOpen = { openAttachment(context, att) { vm.message.value = it } },
                    onRemove = { vm.removeAttachment(att) }
                )
            }

            // --- Reminder ---
            Card(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Reminder", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Switch(checked = vm.reminderEnabled, onCheckedChange = { vm.reminderEnabled = it })
                    }
                    if (vm.reminderEnabled) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                            Text("Repeat every day", Modifier.weight(1f))
                            Switch(checked = vm.reminderDaily, onCheckedChange = { vm.reminderDaily = it })
                        }
                        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!vm.reminderDaily) {
                                OutlinedButton(onClick = { pickDate(context, vm.reminderAt) { vm.setReminderDateTime(it) } }) {
                                    Text(Format.date(vm.reminderAt))
                                }
                            }
                            OutlinedButton(onClick = { pickTime(context, vm.reminderAt) { vm.setReminderDateTime(it) } }) {
                                Text(timeLabel(vm.reminderAt))
                            }
                        }
                        Text(
                            if (vm.reminderDaily) "Every day at ${timeLabel(vm.reminderAt)}"
                            else "Once on ${Format.dateTime(vm.reminderAt)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            Button(onClick = { doSave() }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Text("Save")
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete entry?") },
            text = { Text("This removes the entry and its attachments. Cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.delete(context) { onBack() } }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun AttachmentRow(att: DiaryAttachment, onOpen: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onOpen() }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val icon = when (att.type) {
            AttachmentType.IMAGE -> Icons.Filled.Image
            AttachmentType.VIDEO -> Icons.Filled.Videocam
            AttachmentType.AUDIO -> Icons.Filled.Mic
            AttachmentType.DOCUMENT -> Icons.Filled.Description
            AttachmentType.LOCATION -> Icons.Filled.Place
        }
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(att.name, Modifier.weight(1f).padding(start = 8.dp), maxLines = 1)
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Delete, "Remove", tint = MaterialTheme.colorScheme.error)
        }
    }
    Divider()
}

private fun openAttachment(context: Context, att: DiaryAttachment, onError: (String) -> Unit) {
    runCatching {
        if (att.type == AttachmentType.LOCATION) {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(att.path)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        val uri = AttachmentStore.uriFor(context, att)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, att.mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }.onFailure { onError("Can't open this attachment") }
}

@SuppressLint("MissingPermission")
private fun fetchLocation(context: Context, vm: DiaryEditViewModel) {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    if (lm == null) { vm.message.value = "Location not available"; return }
    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

    var best: Location? = null
    for (pr in providers) {
        if (lm.isProviderEnabled(pr)) {
            val l = runCatching { lm.getLastKnownLocation(pr) }.getOrNull()
            if (l != null && (best == null || l.time > best!!.time)) best = l
        }
    }
    if (best != null) {
        vm.addLocation(best!!.latitude, best!!.longitude)
        vm.message.value = "Location attached"
        return
    }

    val enabled = providers.firstOrNull { lm.isProviderEnabled(it) }
    if (enabled == null) { vm.message.value = "Turn on GPS / location"; return }
    vm.message.value = "Getting current location…"
    runCatching {
        lm.requestSingleUpdate(enabled, object : LocationListener {
            override fun onLocationChanged(location: Location) {
                vm.addLocation(location.latitude, location.longitude)
                vm.message.value = "Location attached"
            }
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }, Looper.getMainLooper())
    }.onFailure { vm.message.value = "Could not get location" }
}

private fun pickDate(context: Context, current: Long, onPicked: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = current }
    android.app.DatePickerDialog(
        context,
        { _, y, m, d -> c.set(Calendar.YEAR, y); c.set(Calendar.MONTH, m); c.set(Calendar.DAY_OF_MONTH, d); onPicked(c.timeInMillis) },
        c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
    ).show()
}

private fun pickTime(context: Context, current: Long, onPicked: (Long) -> Unit) {
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

/** Appends spoken text to existing field content with a separating space. */
private fun appendText(existing: String, spoken: String): String =
    if (existing.isBlank()) spoken else "$existing $spoken"

/**
 * Read-only, searchable view of the notes. Type a word to highlight (yellow) every match,
 * jump between matches with up/down, and copy the focused line.
 */
@Composable
private fun RemarksSearchView(text: String) {
    val clipboard = LocalClipboardManager.current
    val lines = remember(text) { if (text.isEmpty()) listOf("") else text.split("\n") }
    var query by remember { mutableStateOf("") }
    var pos by remember { mutableStateOf(0) }
    val matches = remember(query, lines) {
        if (query.isBlank()) emptyList()
        else lines.indices.filter { lines[it].contains(query, ignoreCase = true) }
    }
    LaunchedEffect(matches.size) { if (pos >= matches.size) pos = 0 }
    val currentLine = matches.getOrNull(pos)
    val listState = rememberLazyListState()
    LaunchedEffect(pos, currentLine) {
        currentLine?.let { runCatching { listState.animateScrollToItem(it) } }
    }

    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = query, onValueChange = { query = it; pos = 0 },
            label = { Text("Search in notes") }, singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    query.isBlank() -> "Type to search"
                    matches.isEmpty() -> "No match"
                    else -> "${pos + 1} / ${matches.size}"
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline
            )
            IconButton(onClick = { if (matches.isNotEmpty()) pos = (pos - 1 + matches.size) % matches.size }, enabled = matches.isNotEmpty()) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Previous match")
            }
            IconButton(onClick = { if (matches.isNotEmpty()) pos = (pos + 1) % matches.size }, enabled = matches.isNotEmpty()) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Next match")
            }
            IconButton(
                onClick = { currentLine?.let { clipboard.setText(AnnotatedString(lines[it])) } },
                enabled = currentLine != null
            ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy line", tint = MaterialTheme.colorScheme.primary)
            }
        }
        Box(
            Modifier.fillMaxWidth().height(300.dp)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
        ) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(8.dp)) {
                itemsIndexed(lines) { i, line ->
                    val isCurrent = i == currentLine
                    Text(
                        highlightMatches(line, query),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth()
                            .background(if (isCurrent) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .padding(vertical = 3.dp, horizontal = 2.dp)
                    )
                }
            }
        }
    }
}

/** Marks every case-insensitive occurrence of [query] in [line] with a yellow background. */
private fun highlightMatches(line: String, query: String): AnnotatedString = buildAnnotatedString {
    if (query.isBlank()) { append(line); return@buildAnnotatedString }
    val lower = line.lowercase()
    val q = query.lowercase()
    var start = 0
    while (true) {
        val idx = lower.indexOf(q, start)
        if (idx < 0) { append(line.substring(start)); break }
        append(line.substring(start, idx))
        withStyle(SpanStyle(background = Color(0xFFFFEB3B), color = Color.Black)) {
            append(line.substring(idx, idx + q.length))
        }
        start = idx + q.length
    }
}
