package com.billing.pos.ui.backup

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.billing.pos.auth.Session
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.DownloadSaver
import com.billing.pos.data.FullBackup
import com.billing.pos.data.License
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    onBack: () -> Unit,
    onRestored: () -> Unit,
    onOpenMergeLog: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var busy by remember { mutableStateOf(false) }
    // Holds the picker to launch after the user confirms a restore.
    var restoreAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var restoreMerge by remember { mutableStateOf(false) }

    fun saveBackup() {
        scope.launch {
            busy = true
            val zip = withContext(Dispatchers.IO) { FullBackup.create(context) }
            val name = backupFileName()
            val ok = withContext(Dispatchers.IO) {
                DownloadSaver.save(context, zip, name, "application/zip")
            }
            busy = false
            snackbar.showSnackbar(if (ok) "Backup saved to Downloads: $name" else "Could not save backup")
        }
    }
    val storagePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) saveBackup() else scope.launch { snackbar.showSnackbar("Storage permission denied") } }
    fun startBackup() {
        if (DownloadSaver.needsLegacyPermission() &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else saveBackup()
    }

    fun runRestore(uri: Uri) {
        val merge = restoreMerge
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) { FullBackup.restore(context, uri, merge) }
            busy = false
            if (result.isSuccess) {
                if (merge) {
                    snackbar.showSnackbar(result.getOrNull() ?: "Merge complete")
                    onOpenMergeLog()
                } else {
                    snackbar.showSnackbar("Restore complete")
                    onRestored()
                }
            } else {
                snackbar.showSnackbar(result.exceptionOrNull()?.message ?: "Restore failed")
            }
        }
    }

    val restorePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) runRestore(uri) }

    // --- Push/Pull to a server (Settings > Cloud backup sync) ---
    // Org + device pick which server-side folder the backup lives in, so a push always
    // overwrites the one file in that folder rather than piling up.
    fun syncOrgDevice(): Pair<String, String> {
        val prefs = AppPrefs(context)
        val org = prefs.backupOrgId.filter { it.isLetterOrDigit() }.ifBlank { "org" }
        val dev = prefs.backupDeviceId.ifBlank { License.deviceId(context) }
            .filter { it.isLetterOrDigit() }.ifBlank { "device" }
        return org to dev
    }
    fun withOrgDevice(baseUrl: String, org: String, device: String): String {
        val sep = if (baseUrl.contains("?")) "&" else "?"
        return "$baseUrl${sep}org=$org&device=$device"
    }
    fun pushBackup() {
        val url = AppPrefs(context).backupPushUrl
        if (url.isBlank()) { scope.launch { snackbar.showSnackbar("Set the Push URL in Settings first") }; return }
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val zip = FullBackup.create(context)
                    // Verify the zip is structurally sound (has its end-of-central-directory
                    // record) before ever uploading it — catches a corrupt local file at the
                    // source instead of pushing garbage the server can't tell apart from a
                    // network truncation.
                    runCatching { java.util.zip.ZipFile(zip).use { it.size() } }
                        .onFailure { throw IOException("Backup file is corrupt before upload (${it.message}) — try again") }
                    val (org, dev) = syncOrgDevice()
                    val conn = URL(withOrgDevice(url, org, dev)).openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.connectTimeout = 15000
                    conn.readTimeout = 120000
                    conn.setRequestProperty("Content-Type", "application/zip")
                    val expectedLen = zip.length()
                    conn.setFixedLengthStreamingMode(expectedLen)
                    val sent = conn.outputStream.use { out -> zip.inputStream().use { it.copyTo(out) } }
                    if (sent != expectedLen) throw IOException("Upload incomplete: sent $sent of $expectedLen bytes")
                    val code = conn.responseCode
                    val err = if (code !in 200..299) runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull() else null
                    conn.disconnect()
                    if (code !in 200..299) throw IOException("HTTP $code" + (if (!err.isNullOrBlank()) ": $err" else ""))
                }
            }
            busy = false
            snackbar.showSnackbar(if (result.isSuccess) "Backup pushed" else "Push failed: ${result.exceptionOrNull()?.message}")
        }
    }
    fun pullAndRestore() {
        val url = AppPrefs(context).backupPullUrl
        if (url.isBlank()) { scope.launch { snackbar.showSnackbar("Set the Pull URL in Settings first") }; return }
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val (org, dev) = syncOrgDevice()
                    val conn = URL(withOrgDevice(url, org, dev)).openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 15000
                    conn.readTimeout = 120000
                    val code = conn.responseCode
                    if (code !in 200..299) { conn.disconnect(); throw IOException("HTTP $code") }
                    val dir = File(context.cacheDir, "shared").apply { mkdirs() }
                    val dest = File(dir, "pulled-backup.zip")
                    conn.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
                    conn.disconnect()
                    // Same structural check as Push, on the way in: tell the user plainly that
                    // the download is corrupt instead of surfacing a raw ZipException/EOFException
                    // once restore/merge starts unpacking it.
                    runCatching { java.util.zip.ZipFile(dest).use { it.size() } }
                        .onFailure { throw IOException("Downloaded backup is corrupt (${it.message}) — push a fresh backup from the source device and try again") }
                    Uri.fromFile(dest)
                }
            }
            busy = false
            result.onSuccess { uri -> runRestore(uri) }
                .onFailure { snackbar.showSnackbar("Pull failed: ${it.message}") }
        }
    }

    // --- Google Drive (via the system Storage Access Framework picker) ---
    // Upload: writes the backup .zip to the location the user picks (Google Drive, etc.).
    val driveUpload = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val zip = FullBackup.create(context)
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        zip.inputStream().use { it.copyTo(out) }
                    } != null
                }.getOrDefault(false)
            }
            busy = false
            snackbar.showSnackbar(if (ok) "Backup uploaded to Google Drive" else "Upload failed")
        }
    }
    // Restore: reads the .zip the user picks from Google Drive (or anywhere).
    val driveRestore = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) runRestore(uri) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Backup & Restore") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(16.dp)
        ) {
            Text(
                "A full backup contains everything — invoices, customers, items, receipts, " +
                    "payments, users, diary (with photos/voice), and company settings — in one .zip. " +
                    "Restore it on a new phone or after reinstalling to get everything back.\n\n" +
                    "For Google Drive, the system picker opens — choose your Google Drive account/folder " +
                    "to save or pick the backup .zip.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )

            if (Session.canExport) {
                Button(
                    onClick = { startBackup() },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
                ) { Icon(Icons.Filled.Download, null); Text("  Download backup to storage") }
                Button(
                    onClick = { driveUpload.launch(backupFileName()) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) { Icon(Icons.Filled.CloudUpload, null); Text("  Upload to Google Drive") }
                Button(
                    onClick = { pushBackup() },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) { Icon(Icons.Filled.CloudUpload, null); Text("  Push backup to server") }
            }

            if (Session.canImport) {
                OutlinedButton(
                    onClick = { restoreAction = { restorePicker.launch("*/*") } },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) { Icon(Icons.Filled.Restore, null); Text("  Restore from file") }
                OutlinedButton(
                    onClick = { restoreAction = { driveRestore.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) } },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) { Icon(Icons.Filled.CloudDownload, null); Text("  Restore from Google Drive") }
                OutlinedButton(
                    onClick = { restoreAction = { pullAndRestore() } },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) { Icon(Icons.Filled.CloudDownload, null); Text("  Pull backup from server") }
                // Available whenever a merge has been run before, not only right after one.
                if (com.billing.pos.data.MergeReport.load(context) != null) {
                    OutlinedButton(
                        onClick = onOpenMergeLog,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) { Icon(Icons.Filled.Restore, null); Text("  View last merge log") }
                }
            }

            if (busy) {
                CircularProgressIndicator(Modifier.padding(top = 24.dp))
                Text("Working…", modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outline)
            }

            if (!Session.canExport && !Session.canImport) {
                Text(
                    "You don't have backup or restore permission.",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        }
    }

    restoreAction?.let { action ->
        AlertDialog(
            onDismissRequest = { restoreAction = null },
            title = { Text("Restore backup") },
            text = {
                Text(
                    "Replace all: wipes current data (including users) and restores the backup.\n\n" +
                        "Merge (append): keeps current data and adds the backup's invoices, payments, " +
                        "items, etc. — for combining data from another phone. Nothing is lost."
                )
            },
            confirmButton = {
                Column {
                    TextButton(onClick = { restoreMerge = false; restoreAction = null; action() }) { Text("Replace all data") }
                    TextButton(onClick = { restoreMerge = true; restoreAction = null; action() }) { Text("Merge (append)") }
                    TextButton(onClick = { restoreAction = null }) { Text("Cancel") }
                }
            }
        )
    }
}

/**
 * Backup file name stamped with the date and time, e.g. pos-full-backup-2026-07-15_1432.zip.
 * yyyy-MM-dd first so backups sort chronologically in the file list; no spaces or colons,
 * which storage and Drive reject.
 */
private fun backupFileName(): String {
    val stamp = java.text.SimpleDateFormat("yyyy-MM-dd_HHmmss", java.util.Locale.getDefault())
        .format(java.util.Date())
    return "pos-full-backup-$stamp.zip"
}
