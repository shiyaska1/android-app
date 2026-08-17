package com.billing.pos.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.billing.pos.shared.BackupSync
import com.billing.pos.shared.DesktopDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var pushUrl by remember { mutableStateOf(DesktopDatabase.getSetting("pushUrl")) }
    var pullUrl by remember { mutableStateOf(DesktopDatabase.getSetting("pullUrl")) }
    var orgId by remember { mutableStateOf(DesktopDatabase.getSetting("orgId")) }
    var deviceId by remember { mutableStateOf(DesktopDatabase.getSetting("deviceId")) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showMergeChoice by remember { mutableStateOf(false) }
    var pulledFile by remember { mutableStateOf<java.io.File?>(null) }

    fun save() {
        DesktopDatabase.setSetting("pushUrl", pushUrl)
        DesktopDatabase.setSetting("pullUrl", pullUrl)
        DesktopDatabase.setSetting("orgId", orgId)
        DesktopDatabase.setSetting("deviceId", deviceId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState())) {
            Text("About", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text("Database file: ${DesktopDatabase.databaseFilePath()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            Text(
                "This is an early build — Purchases/Receipts/Payments cover the core workflow, " +
                    "Trial Balance/P&L/Balance Sheet are simplified pending the full accounting engine port.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 4.dp)
            )

            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Text("Cloud backup sync", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                "Push uploads a backup of this PC's data to your server; Pull downloads and restores it. " +
                    "Uses the SAME server endpoint as the Android app — set the same Org ID + Device ID here " +
                    "as on a phone to share one backup slot, or a different Device ID to keep this PC separate.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
            )
            OutlinedTextField(
                value = pushUrl, onValueChange = { pushUrl = it; save() }, label = { Text("Push URL") },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            OutlinedTextField(
                value = pullUrl, onValueChange = { pullUrl = it; save() }, label = { Text("Pull URL") },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            OutlinedTextField(
                value = orgId, onValueChange = { orgId = it; save() }, label = { Text("Org ID") },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            OutlinedTextField(
                value = deviceId, onValueChange = { deviceId = it; save() }, label = { Text("Device ID") },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )

            Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Button(
                    enabled = !busy && pushUrl.isNotBlank() && orgId.isNotBlank() && deviceId.isNotBlank(),
                    onClick = {
                        busy = true; message = null
                        scope.launch {
                            val err = withContext(Dispatchers.IO) {
                                val zip = BackupSync.createBackupZip()
                                BackupSync.push(pushUrl, orgId, deviceId, zip)
                            }
                            message = err ?: "Pushed to server successfully."
                            busy = false
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Push to server") }
                OutlinedButton(
                    enabled = !busy && pullUrl.isNotBlank() && orgId.isNotBlank() && deviceId.isNotBlank(),
                    onClick = {
                        busy = true; message = null
                        scope.launch {
                            val file = withContext(Dispatchers.IO) {
                                BackupSync.pull(pullUrl, orgId, deviceId) { err -> message = err }
                            }
                            busy = false
                            if (file != null) { pulledFile = file; showMergeChoice = true }
                        }
                    },
                    modifier = Modifier.weight(1f).padding(start = 8.dp)
                ) { Text("Pull from server") }
            }
            if (busy) {
                Row(Modifier.padding(top = 12.dp)) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                    Text("Working...", color = MaterialTheme.colorScheme.outline)
                }
            }
            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
            }
        }
    }

    if (showMergeChoice) {
        AlertDialog(
            onDismissRequest = { showMergeChoice = false },
            title = { Text("Restore downloaded backup") },
            text = { Text("Replace all local data with the server's copy, or merge (keep local data, add anything new from the server)?") },
            confirmButton = {
                TextButton(onClick = {
                    val file = pulledFile
                    showMergeChoice = false
                    if (file != null) {
                        busy = true
                        scope.launch {
                            val report = withContext(Dispatchers.IO) { BackupSync.restoreBackupZip(file, merge = false) }
                            message = "Replaced — ${report.rowsRestored} record(s) restored."
                            busy = false
                        }
                    }
                }) { Text("Replace all") }
            },
            dismissButton = {
                TextButton(onClick = {
                    val file = pulledFile
                    showMergeChoice = false
                    if (file != null) {
                        busy = true
                        scope.launch {
                            val report = withContext(Dispatchers.IO) { BackupSync.restoreBackupZip(file, merge = true) }
                            message = "Merged — ${report.rowsRestored} new record(s) added, ${report.rowsSkipped} already existed."
                            busy = false
                        }
                    }
                }) { Text("Merge") }
            }
        )
    }
}
