package com.billing.pos.ui.journal

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.JournalEntry
import com.billing.pos.data.Repository
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.util.Format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class JournalListViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)
    val entries: StateFlow<List<JournalEntry>> =
        repo.journalEntries.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }
    fun delete(e: JournalEntry) {
        viewModelScope.launch { repo.deleteJournal(e); message.value = "Journal ${e.voucherNo} deleted" }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalListScreen(
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    vm: JournalListViewModel = viewModel()
) {
    val snackbar = remember { SnackbarHostState() }
    val entries by vm.entries.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()
    var deleteFor by remember { mutableStateOf<JournalEntry?>(null) }

    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Journal") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onOpen(0) }) { Icon(Icons.Filled.Add, contentDescription = "New journal") }
        }
    ) { pad ->
        if (entries.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(pad), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No journal entries", color = MaterialTheme.colorScheme.outline)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp)) {
                items(entries, key = { it.id }) { e ->
                    Row(Modifier.fillMaxWidth().clickable { onOpen(e.id) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(e.voucherNo, fontWeight = FontWeight.Bold)
                            Text(
                                e.narration.ifBlank { "(no narration)" } + "  •  " + Format.date(e.dateMillis),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                            )
                        }
                        IconButton(onClick = { deleteFor = e }) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                    }
                    Divider()
                }
            }
        }
    }

    deleteFor?.let { e ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Delete ${e.voucherNo}?") },
            text = { Text("This cannot be undone.") },
            confirmButton = { TextButton(onClick = { vm.delete(e); deleteFor = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Cancel") } }
        )
    }
}
