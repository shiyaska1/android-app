package com.billing.pos.ui.journal

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
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
import com.billing.pos.data.RecurringFrequency
import com.billing.pos.data.RecurringJournal
import com.billing.pos.data.RecurringJournalLine
import com.billing.pos.data.Repository
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.util.Format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.math.abs

class RecurringJournalEntryViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)

    val heads: StateFlow<List<AccountHead>> =
        repo.accountHeads.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    var name by mutableStateOf("")
    var narration by mutableStateOf("")
    var frequency by mutableStateOf(RecurringFrequency.MONTHLY)
    var startDate by mutableStateOf(System.currentTimeMillis())
    var hasEndDate by mutableStateOf(false)
    var endDate by mutableStateOf(System.currentTimeMillis())
    var active by mutableStateOf(true)
    var editingId by mutableStateOf<Long?>(null); private set
    /** The template's current due date, preserved as-is on edit so changing the start date or
     * lines doesn't accidentally re-trigger already-generated past occurrences. Null = new template. */
    private var loadedNextDueDate: Long? = null
    val lines: SnapshotStateList<JLine> = mutableStateListOf()

    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }

    private var loaded = false

    val totalDr: Double get() = lines.filter { it.isDebit }.sumOf { it.amount }
    val totalCr: Double get() = lines.filter { !it.isDebit }.sumOf { it.amount }
    val balanced: Boolean get() = totalDr > 0.001 && abs(totalDr - totalCr) < 0.01

    fun addLine(isDebit: Boolean) { lines.add(JLine(JLine.next(), 0, "", 0.0, isDebit)) }
    fun removeLine(index: Int) { lines.removeAt(index) }
    fun setHead(index: Int, head: AccountHead) {
        lines.getOrNull(index)?.let { lines[index] = it.copy(headId = head.id, headName = head.name) }
    }
    fun setAmount(index: Int, amount: Double) {
        lines.getOrNull(index)?.let { lines[index] = it.copy(amount = amount) }
    }
    fun setDrCr(index: Int, isDebit: Boolean) {
        lines.getOrNull(index)?.let { lines[index] = it.copy(isDebit = isDebit) }
    }

    fun load(id: Long) {
        if (loaded) return
        loaded = true
        if (id <= 0) { if (lines.isEmpty()) { addLine(true); addLine(false) }; return }
        viewModelScope.launch {
            val t = repo.recurringJournals.first().firstOrNull { it.id == id } ?: return@launch
            val jLines = repo.recurringJournalLinesFor(id)
            editingId = t.id
            loadedNextDueDate = t.nextDueDate
            name = t.name
            narration = t.narration
            frequency = t.frequency
            startDate = t.startDate
            hasEndDate = t.endDate != 0L
            endDate = if (t.endDate != 0L) t.endDate else System.currentTimeMillis()
            active = t.active
            lines.clear()
            jLines.forEach { lines.add(JLine(JLine.next(), it.headId, it.headName, it.amount, it.isDebit)) }
        }
    }

    fun save(onDone: () -> Unit) {
        if (name.isBlank()) { message.value = "Enter a name"; return }
        val valid = lines.filter { it.headId > 0 && it.amount > 0 }
        if (valid.size < 2) { message.value = "Add at least two lines"; return }
        if (!balanced) { message.value = "Debit and credit must be equal"; return }
        viewModelScope.launch {
            val t = RecurringJournal(
                id = editingId ?: 0, name = name.trim(), narration = narration.trim(),
                frequency = frequency, startDate = startDate,
                nextDueDate = loadedNextDueDate ?: startDate,
                endDate = if (hasEndDate) endDate else 0L, active = active
            )
            val jLines = valid.map { RecurringJournalLine(0, 0, it.headId, it.headName, it.amount, it.isDebit) }
            if (editingId != null) repo.updateRecurringJournal(t, jLines) else repo.saveRecurringJournal(t, jLines)
            message.value = "Recurring journal saved"
            onDone()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecurringJournalEntryScreen(
    templateId: Long,
    onBack: () -> Unit,
    vm: RecurringJournalEntryViewModel = viewModel()
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val heads by vm.heads.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()

    LaunchedEffect(Unit) { vm.load(templateId) }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (vm.editingId != null) "Edit Recurring Journal" else "New Recurring Journal") },
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
            OutlinedTextField(
                value = vm.name, onValueChange = { vm.name = it },
                label = { Text("Name (e.g. Office Rent) *") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = vm.narration, onValueChange = { vm.narration = it },
                label = { Text("Narration (optional)") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )

            Text("Repeats", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp))
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RecurringFrequency.ALL.forEach { f ->
                    FilterChip(selected = vm.frequency == f, onClick = { vm.frequency = f }, label = { Text(RecurringFrequency.label(f)) })
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickRecurringDate(context, vm.startDate) { vm.startDate = it } }, modifier = Modifier.weight(1f)) {
                    Text("Starts: ${Format.date(vm.startDate)}")
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = vm.hasEndDate, onCheckedChange = { vm.hasEndDate = it })
                Text("End date", modifier = Modifier.padding(start = 4.dp))
                if (vm.hasEndDate) {
                    OutlinedButton(onClick = { pickRecurringDate(context, vm.endDate) { vm.endDate = it } }, modifier = Modifier.padding(start = 8.dp)) {
                        Text(Format.date(vm.endDate))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = vm.active, onCheckedChange = { vm.active = it })
                Text("Active", modifier = Modifier.padding(start = 4.dp))
            }

            Divider(Modifier.padding(vertical = 12.dp))
            Text("Posting lines", style = MaterialTheme.typography.titleSmall)

            vm.lines.forEachIndexed { index, line ->
                key(line.uid) {
                    var amtText by remember(line.uid) { mutableStateOf(if (line.amount != 0.0) Format.money(line.amount) else "") }
                    var expanded by remember { mutableStateOf(false) }
                    var headQuery by remember(line.uid) { mutableStateOf(line.headName) }
                    LaunchedEffect(line.headName) { if (!expanded) headQuery = line.headName }
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = Modifier.weight(1f)) {
                                OutlinedTextField(
                                    value = headQuery,
                                    onValueChange = { headQuery = it; expanded = true },
                                    label = { Text("Account head") },
                                    placeholder = { Text("Search account") },
                                    singleLine = true,
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                                    modifier = Modifier.menuAnchor().fillMaxWidth()
                                        .onFocusChanged { fs ->
                                            if (fs.isFocused) { headQuery = ""; expanded = true }
                                            else if (!expanded) headQuery = line.headName
                                        }
                                )
                                val matches = heads.filter { headQuery.isBlank() || it.name.contains(headQuery, true) }.take(5)
                                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false; headQuery = line.headName }) {
                                    matches.forEach { h ->
                                        DropdownMenuItem(text = { Text(h.name) }, onClick = { vm.setHead(index, h); headQuery = h.name; expanded = false })
                                    }
                                    if (matches.isEmpty()) DropdownMenuItem(text = { Text("No match") }, onClick = { expanded = false })
                                }
                            }
                            IconButton(onClick = { vm.removeLine(index) }) { Icon(Icons.Filled.Delete, "Remove", tint = MaterialTheme.colorScheme.error) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                            OutlinedButton(onClick = { vm.setDrCr(index, !line.isDebit) }) { Text(if (line.isDebit) "Dr" else "Cr") }
                            OutlinedTextField(
                                value = amtText,
                                onValueChange = { v -> val f = v.filter { it.isDigit() || it == '.' }; amtText = f; vm.setAmount(index, f.toDoubleOrNull() ?: 0.0) },
                                label = { Text("Amount") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.width(160.dp).padding(start = 8.dp)
                            )
                        }
                    }
                    Divider()
                }
            }

            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.addLine(true) }, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.Add, null); Text("Debit line") }
                OutlinedButton(onClick = { vm.addLine(false) }, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.Add, null); Text("Credit line") }
            }

            Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Total Debit"); Text(Format.rupee(vm.totalDr)) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Total Credit"); Text(Format.rupee(vm.totalCr)) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Difference", fontWeight = FontWeight.Bold)
                        Text(Format.rupee(vm.totalDr - vm.totalCr), fontWeight = FontWeight.Bold,
                            color = if (vm.balanced) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    }
                }
            }

            Button(onClick = { vm.save { onBack() } }, enabled = vm.balanced && vm.name.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Text("Save")
            }
        }
    }
}

private fun pickRecurringDate(context: Context, current: Long, onPicked: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = current }
    android.app.DatePickerDialog(
        context,
        { _, y, m, d -> c.set(Calendar.YEAR, y); c.set(Calendar.MONTH, m); c.set(Calendar.DAY_OF_MONTH, d); onPicked(c.timeInMillis) },
        c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
    ).show()
}
