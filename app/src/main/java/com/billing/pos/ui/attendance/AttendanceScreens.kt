package com.billing.pos.ui.attendance

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.AttendanceRecord
import com.billing.pos.data.AttendanceRepository
import com.billing.pos.data.Employee
import com.billing.pos.face.FaceRecognizer
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.ui.common.rememberThumbnail
import com.billing.pos.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar

/** Stores enrolled employees' face thumbnails. */
private object EmployeePhotoStore {
    fun dir(context: Context): File = File(context.filesDir, "employees").apply { mkdirs() }
    fun save(context: Context, bmp: Bitmap): String {
        val f = File(dir(context), "emp_${System.nanoTime()}.jpg")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        return f.absolutePath
    }
}

private fun loadBitmap(context: Context, uri: Uri, maxDim: Int = 1000): Bitmap? {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
}

private fun timeLabel(millis: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = millis }
    return String.format("%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
}

private fun isToday(millis: Long): Boolean {
    val now = Calendar.getInstance()
    val d = Calendar.getInstance().apply { timeInMillis = millis }
    return now.get(Calendar.YEAR) == d.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == d.get(Calendar.DAY_OF_YEAR)
}

class AttendanceViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = AttendanceRepository(app)

    val employees: StateFlow<List<Employee>> =
        repo.employees.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val attendance: StateFlow<List<AttendanceRecord>> =
        repo.attendance.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }
    var busy by mutableStateOf(false); private set

    fun registerEmployee(context: Context, uri: Uri, name: String, phone: String, role: String, onDone: () -> Unit) {
        if (name.isBlank()) { message.value = "Enter a name"; return }
        busy = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val bmp = loadBitmap(context, uri) ?: return@withContext null
                FaceRecognizer.analyze(context, bmp)
            }
            if (result == null) { busy = false; message.value = "No face detected — try again, face the camera"; return@launch }
            val path = withContext(Dispatchers.IO) { EmployeePhotoStore.save(context, result.crop) }
            repo.upsertEmployee(
                Employee(name = name.trim(), phone = phone.trim(), role = role.trim(),
                    embedding = FaceRecognizer.encode(result.embedding), photoPath = path)
            )
            busy = false
            message.value = "Enrolled ${name.trim()}"
            onDone()
        }
    }

    /** Scans a photo, matches to an employee and punches IN/OUT. onResult(name, type) or null. */
    fun scanAndPunch(context: Context, uri: Uri, onResult: (String?) -> Unit) {
        busy = true
        viewModelScope.launch {
            val emb = withContext(Dispatchers.IO) {
                val bmp = loadBitmap(context, uri) ?: return@withContext null
                FaceRecognizer.analyze(context, bmp)?.embedding
            }
            if (emb == null) { busy = false; message.value = "No face detected"; onResult(null); return@launch }
            val list = employees.value.filter { it.embedding.isNotBlank() }
            var best: Employee? = null; var bestScore = -1f
            list.forEach { e ->
                val ref = FaceRecognizer.decode(e.embedding) ?: return@forEach
                val s = FaceRecognizer.similarity(emb, ref)
                if (s > bestScore) { bestScore = s; best = e }
            }
            val matched = best
            if (matched != null && bestScore >= FaceRecognizer.MATCH_THRESHOLD) {
                val type = repo.punch(matched, System.currentTimeMillis())
                busy = false
                message.value = "${matched.name}: marked $type at ${timeLabel(System.currentTimeMillis())}"
                onResult(matched.name)
            } else {
                busy = false
                message.value = "Face not recognized — use manual, or re-enroll"
                onResult(null)
            }
        }
    }

    fun manualPunch(employee: Employee, type: String) {
        viewModelScope.launch {
            repo.punchExplicit(employee, System.currentTimeMillis(), type)
            message.value = "${employee.name}: marked $type at ${timeLabel(System.currentTimeMillis())}"
        }
    }

    fun deleteEmployee(e: Employee) {
        viewModelScope.launch { repo.deleteEmployee(e); message.value = "Removed ${e.name}" }
    }

    fun deleteAttendance(r: AttendanceRecord) {
        viewModelScope.launch { repo.deleteAttendance(r) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmployeesScreen(onBack: () -> Unit, vm: AttendanceViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val employees by vm.employees.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    var showAdd by remember { mutableStateOf(false) }
    var deleteFor by remember { mutableStateOf<Employee?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Employees") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) { Icon(Icons.Filled.PersonAdd, "Enroll employee") }
        }
    ) { pad ->
        if (employees.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text("No employees yet. Tap + to enroll a face.", color = MaterialTheme.colorScheme.outline)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp)) {
                items(employees, key = { it.id }) { e ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        EmployeeAvatar(e.photoPath, 48)
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.name, fontWeight = FontWeight.Bold)
                            Text(
                                listOf(e.role, e.phone).filter { it.isNotBlank() }.joinToString("  •  ")
                                    .ifBlank { if (e.embedding.isNotBlank()) "Face enrolled" else "No face" },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                            )
                        }
                        IconButton(onClick = { deleteFor = e }) { Icon(Icons.Filled.Delete, "Remove", tint = MaterialTheme.colorScheme.error) }
                    }
                    Divider()
                }
            }
        }
    }

    if (showAdd) EnrollDialog(vm = vm, onDismiss = { showAdd = false })

    deleteFor?.let { e ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Remove ${e.name}?") },
            text = { Text("Their enrolled face and attendance history stay unless you clear data.") },
            confirmButton = { TextButton(onClick = { vm.deleteEmployee(e); deleteFor = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun EnrollDialog(vm: AttendanceViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("") }
    var captured by remember { mutableStateOf<Uri?>(null) }
    val capture = com.billing.pos.ocr.rememberImageCamera { uri -> captured = uri }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enroll employee") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = role, onValueChange = { role = it }, label = { Text("Role (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("Phone (optional)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { capture() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.CameraAlt, null); Text(if (captured == null) "  Capture face" else "  Retake face")
                }
                if (captured != null) Text("Face photo ready — tap Enroll", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                if (vm.busy) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.size(8.dp)); Text("Reading face…")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !vm.busy && captured != null && name.isNotBlank(),
                onClick = { captured?.let { vm.registerEmployee(context, it, name, phone, role) { onDismiss() } } }
            ) { Text("Enroll") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttendanceScreen(onBack: () -> Unit, onEmployees: () -> Unit, vm: AttendanceViewModel = viewModel()) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val employees by vm.employees.collectAsStateSafe()
    val records by vm.attendance.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    val scan = com.billing.pos.ocr.rememberImageCamera { uri -> vm.scanAndPunch(context, uri) {} }
    var showManual by remember { mutableStateOf(false) }
    val today = records.filter { isToday(it.timeMillis) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Attendance") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = onEmployees) { Icon(Icons.Filled.Person, "Employees") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(12.dp)) {
            Button(onClick = { scan() }, enabled = !vm.busy && employees.any { it.embedding.isNotBlank() }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                if (vm.busy) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.size(8.dp)); Text("Reading…") }
                else { Icon(Icons.Filled.CameraAlt, null); Text("  Scan face to mark attendance") }
            }
            if (employees.none { it.embedding.isNotBlank() }) {
                Text("Enroll employees first (tap the person icon).", color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 6.dp))
            }
            OutlinedButton(onClick = { showManual = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Manual mark (no face)") }

            Text("Today  •  ${today.size} punches", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
            Divider()
            if (today.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { Text("No attendance yet today", color = MaterialTheme.colorScheme.outline) }
            } else {
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    items(today, key = { it.id }) { r ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.employeeName, fontWeight = FontWeight.SemiBold)
                                Text("${r.type} • ${timeLabel(r.timeMillis)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                            }
                            Text(
                                r.type,
                                fontWeight = FontWeight.Bold,
                                color = if (r.type == "IN") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                            IconButton(onClick = { vm.deleteAttendance(r) }) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                        }
                        Divider()
                    }
                }
            }
        }
    }

    if (showManual) {
        AlertDialog(
            onDismissRequest = { showManual = false },
            title = { Text("Manual mark") },
            text = {
                LazyColumn(Modifier.fillMaxWidth().height(360.dp)) {
                    items(employees, key = { it.id }) { e ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(e.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            TextButton(onClick = { vm.manualPunch(e, "IN"); showManual = false }) { Text("IN") }
                            TextButton(onClick = { vm.manualPunch(e, "OUT"); showManual = false }) { Text("OUT") }
                        }
                        Divider()
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showManual = false }) { Text("Close") } }
        )
    }
}

@Composable
private fun EmployeeAvatar(path: String, sizeDp: Int) {
    val bmp = if (path.isNotBlank()) rememberThumbnail(path, 200) else null
    Box(
        Modifier.size(sizeDp.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (bmp != null) Image(bmp, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(Icons.Filled.Person, null, tint = MaterialTheme.colorScheme.outline)
    }
}
