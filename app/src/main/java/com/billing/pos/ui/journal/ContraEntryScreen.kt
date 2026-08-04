package com.billing.pos.ui.journal

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.AccountHead
import com.billing.pos.data.JournalEntry
import com.billing.pos.data.JournalLine
import com.billing.pos.data.JournalVoucherType
import com.billing.pos.data.Repository
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.util.Format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Contra entry: a straight Cash/Bank-to-Cash/Bank transfer (e.g. cash deposited into the bank,
 * cheque withdrawn as cash, transfer between two bank accounts). Stored as a two-line
 * [JournalEntry]/[JournalLine] pair tagged [JournalVoucherType.CONTRA] — same posting mechanics
 * as a manual journal, just a simpler From/To/Amount form restricted to Cash/Bank heads.
 */
class ContraEntryViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)

    val cashBankHeads: StateFlow<List<AccountHead>> =
        repo.cashBankHeads.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    var voucherNo by mutableStateOf("CN-0001"); private set
    var dateMillis by mutableStateOf(System.currentTimeMillis())
    var narration by mutableStateOf("")
    var fromHeadId by mutableStateOf(0L)
    var fromHeadName by mutableStateOf("")
    var toHeadId by mutableStateOf(0L)
    var toHeadName by mutableStateOf("")
    var amountText by mutableStateOf("")
    var editingId by mutableStateOf<Long?>(null); private set

    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }

    private var loaded = false

    init {
        viewModelScope.launch { repo.ensureDefaults(); voucherNo = repo.nextContraNo() }
    }

    val amount: Double get() = amountText.toDoubleOrNull() ?: 0.0

    fun setFrom(head: AccountHead) { fromHeadId = head.id; fromHeadName = head.name }
    fun setTo(head: AccountHead) { toHeadId = head.id; toHeadName = head.name }

    fun load(id: Long) {
        if (loaded) return
        loaded = true
        if (id <= 0) return
        viewModelScope.launch {
            val entry = repo.journalById(id) ?: return@launch
            val jlines = repo.journalLinesFor(id)
            editingId = entry.id
            voucherNo = entry.voucherNo
            dateMillis = entry.dateMillis
            narration = entry.narration
            jlines.firstOrNull { it.isDebit }?.let { toHeadId = it.headId; toHeadName = it.headName; amountText = Format.money(it.amount) }
            jlines.firstOrNull { !it.isDebit }?.let { fromHeadId = it.headId; fromHeadName = it.headName }
        }
    }

    fun save(onDone: () -> Unit) {
        if (fromHeadId <= 0 || toHeadId <= 0) { message.value = "Select both accounts"; return }
        if (fromHeadId == toHeadId) { message.value = "From and To accounts must differ"; return }
        if (amount <= 0.0) { message.value = "Enter an amount"; return }
        viewModelScope.launch {
            val entry = JournalEntry(
                id = editingId ?: 0, voucherNo = voucherNo, dateMillis = dateMillis,
                narration = narration.trim(), voucherType = JournalVoucherType.CONTRA
            )
            val lines = listOf(
                JournalLine(0, 0, toHeadId, toHeadName, amount, isDebit = true),
                JournalLine(0, 0, fromHeadId, fromHeadName, amount, isDebit = false)
            )
            if (editingId != null) repo.updateJournal(entry, lines) else repo.saveJournal(entry, lines)
            message.value = "Contra entry saved"
            onDone()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContraEntryScreen(
    entryId: Long,
    onBack: () -> Unit,
    vm: ContraEntryViewModel = viewModel()
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val heads by vm.cashBankHeads.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()

    LaunchedEffect(Unit) { vm.load(entryId) }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (vm.editingId != null) "Edit Contra Entry" else "New Contra Entry") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(12.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("No: ${vm.voucherNo}", fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = { pickContraDate(context, vm.dateMillis) { vm.dateMillis = it } }) { Text(Format.date(vm.dateMillis)) }
            }

            Text(
                "Transfer between two Cash/Bank accounts — e.g. cash deposited into the bank, or a transfer between two bank accounts.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 6.dp, bottom = 8.dp)
            )

            CashBankHeadField(
                label = "From account", heads = heads, selectedName = vm.fromHeadName,
                onPick = { vm.setFrom(it) }
            )
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.Center) {
                Icon(Icons.Filled.SwapVert, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
            }
            CashBankHeadField(
                label = "To account", heads = heads, selectedName = vm.toHeadName,
                onPick = { vm.setTo(it) }
            )

            var amtText by remember(vm.editingId) { mutableStateOf(vm.amountText) }
            OutlinedTextField(
                value = amtText,
                onValueChange = { v -> val f = v.filter { it.isDigit() || it == '.' }; amtText = f; vm.amountText = f },
                label = { Text("Amount") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            )

            OutlinedTextField(
                value = vm.narration, onValueChange = { vm.narration = it },
                label = { Text("Narration (optional)") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )

            Divider(Modifier.padding(vertical = 12.dp))

            Button(
                onClick = { vm.save { onBack() } },
                enabled = vm.fromHeadId > 0 && vm.toHeadId > 0 && vm.amount > 0.0,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save") }
        }
    }
}

/** Search-to-pick field restricted to Cash/Bank account heads, matching the established
 * clear-on-focus dropdown pattern used across the app's other searchable pickers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CashBankHeadField(
    label: String,
    heads: List<AccountHead>,
    selectedName: String,
    onPick: (AccountHead) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember(selectedName) { mutableStateOf(selectedName) }
    // Plain field + inline list, not ExposedDropdownMenuBox — that popup-based combobox fights
    // with the keyboard on real devices (typing the first character snaps the field back to
    // the old value and blocks further typing).
    Column {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it; expanded = true },
            label = { Text(label) },
            placeholder = { Text("Search Cash/Bank account") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                .onFocusChanged { fs ->
                    if (fs.isFocused) { query = ""; expanded = true }
                    else { expanded = false; query = selectedName }
                }
        )
        if (expanded) {
            val matches = heads.filter { query.isBlank() || it.name.contains(query, true) }.take(6)
            com.billing.pos.ui.common.SearchPickList(
                items = matches,
                itemLabel = { it.name },
                onPick = { h -> onPick(h); query = h.name; expanded = false },
                emptyText = "No Cash/Bank account found"
            )
        }
    }
}

private fun pickContraDate(context: Context, current: Long, onPicked: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = current }
    android.app.DatePickerDialog(
        context,
        { _, y, m, d -> c.set(Calendar.YEAR, y); c.set(Calendar.MONTH, m); c.set(Calendar.DAY_OF_MONTH, d); onPicked(c.timeInMillis) },
        c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
    ).show()
}
