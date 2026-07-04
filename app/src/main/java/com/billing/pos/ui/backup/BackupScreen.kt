package com.billing.pos.ui.backup

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Share
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
import androidx.core.content.FileProvider
import com.billing.pos.auth.Session
import com.billing.pos.data.FullBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    onBack: () -> Unit,
    onRestored: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var busy by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }

    val restorePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                val result = withContext(Dispatchers.IO) { FullBackup.restore(context, uri) }
                busy = false
                if (result.isSuccess) {
                    snackbar.showSnackbar("Restore complete — please sign in")
                    onRestored()
                } else {
                    snackbar.showSnackbar(result.exceptionOrNull()?.message ?: "Restore failed")
                }
            }
        }
    }

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
                    "Restore it on a new phone or after reinstalling to get everything back.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )

            if (Session.canExport) {
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            val zip = withContext(Dispatchers.IO) { FullBackup.create(context) }
                            busy = false
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", zip)
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/zip"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(
                                Intent.createChooser(intent, "Backup — save or share")
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
                ) { Icon(Icons.Filled.Share, null); Text("  Create backup & share") }
            }

            if (Session.canImport) {
                OutlinedButton(
                    onClick = { confirmRestore = true },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) { Icon(Icons.Filled.Restore, null); Text("  Restore from backup") }
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

    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text("Restore backup?") },
            text = { Text("This REPLACES all current data (including users) with the backup. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmRestore = false; restorePicker.launch("*/*") }) { Text("Choose file") }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("Cancel") } }
        )
    }
}
