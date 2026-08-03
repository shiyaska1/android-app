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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.RecurringFrequency
import com.billing.pos.data.RecurringJournal
import com.billing.pos.data.Repository
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.util.Format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RecurringJournalListViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)
    val templates: StateFlow<List<RecurringJournal>> =
        repo.recurringJournals.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }

    fun setActive(t: RecurringJournal, active: Boolean) {
        viewModelScope.launch { repo.updateRecurringJournal(t.copy(active = active), repo.recurringJournalLinesFor(t.id)) }
    }

    fun delete(t: RecurringJournal) {
        viewModelScope.launch { repo.deleteRecurringJournal(t); message.value = "${t.name} deleted" }
    }

    /** Catches up any due templates immediately — also runs automatically each time this
     * screen opens, so a user who skips a few days doesn't have to remember to tap it. */
    fun generateDueNow() {
        viewModelScope.launch {
            val n = repo.generateDueRecurringJournals()
            message.value = if (n > 0) "$n journal voucher(s) posted" else "Nothing due right now"
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecurringJournalListScreen(
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    vm: RecurringJournalListViewModel = viewModel()
) {
    val templates by vm.templates.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { vm.generateDueNow() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Recurring Journals") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { vm.generateDueNow() }) { Icon(Icons.Filled.PlayArrow, "Generate due now") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onOpen(0) }) { Icon(Icons.Filled.Add, "New recurring journal") }
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Text(
                "Templates for regular postings (rent, EMI, ...) — each generates a real journal entry when it comes due.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(12.dp)
            )
            if (templates.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(24.dp)) {
                    Text("No recurring journals yet.", color = MaterialTheme.colorScheme.outline)
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    items(templates, key = { it.id }) { t ->
                        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onOpen(t.id) }) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(t.name, fontWeight = FontWeight.Bold)
                                    Text(
                                        "${RecurringFrequency.label(t.frequency)} · Next: ${Format.date(t.nextDueDate)}",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                                    )
                                }
                                Switch(checked = t.active, onCheckedChange = { vm.setActive(t, it) })
                            }
                        }
                    }
                }
            }
        }
    }
}
