package com.billing.pos.ui.cheque

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.ChequeEntry
import com.billing.pos.data.ChequeStatus
import com.billing.pos.data.Repository
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.util.Format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

class ChequeViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)
    val cheques: StateFlow<List<ChequeEntry>> =
        repo.cheques.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }

    fun save(
        existing: ChequeEntry?, chequeNo: String, bankName: String, partyName: String, amount: Double,
        dateMillis: Long, isReceived: Boolean, status: String, remarks: String, onDone: () -> Unit
    ) {
        if (chequeNo.isBlank()) { message.value = "Enter a cheque number"; return }
        if (partyName.isBlank()) { message.value = "Enter a party name"; return }
        if (amount <= 0.0) { message.value = "Enter a valid amount"; return }
        viewModelScope.launch {
            val cheque = (existing ?: ChequeEntry(chequeNo = "", partyName = "", amount = 0.0, dateMillis = dateMillis, isReceived = isReceived)).copy(
                chequeNo = chequeNo.trim(), bankName = bankName.trim(), partyName = partyName.trim(),
                amount = amount, dateMillis = dateMillis, isReceived = isReceived, status = status, remarks = remarks.trim()
            )
            if (existing == null) repo.saveCheque(cheque) else repo.updateCheque(cheque)
            message.value = "Saved"; onDone()
        }
    }

    fun setStatus(cheque: ChequeEntry, status: String) {
        viewModelScope.launch { repo.updateCheque(cheque.copy(status = status)); message.value = "Marked $status" }
    }

    fun delete(cheque: ChequeEntry) {
        viewModelScope.launch { repo.deleteCheque(cheque); message.value = "Cheque deleted" }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChequeRegisterScreen(onBack: () -> Unit, vm: ChequeViewModel = viewModel()) {
    val snackbar = remember { SnackbarHostState() }
    val cheques by vm.cheques.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    var statusFilter by remember { mutableStateOf("All") }
    var showDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ChequeEntry?>(null) }
    var deleteFor by remember { mutableStateOf<ChequeEntry?>(null) }
    var bounceWarningFor by remember { mutableStateOf<ChequeEntry?>(null) }

    val visible = cheques.filter { statusFilter == "All" || it.status == statusFilter }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Cheques") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = null; showDialog = true }) { Icon(Icons.Filled.Add, "Add cheque") }
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                (listOf("All") + ChequeStatus.ALL).forEach { s ->
                    FilterChip(selected = statusFilter == s, onClick = { statusFilter = s }, label = { Text(s) })
                }
            }
            Divider()
            if (visible.isEmpty()) {
                Text(
                    "No cheques", color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.fillMaxWidth().padding(24.dp)
                )
            } else LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                items(visible, key = { it.id }) { c ->
                    Row(
                        Modifier.fillMaxWidth().clickable { editing = c; showDialog = true }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("${c.chequeNo}  •  ${c.partyName}", fontWeight = FontWeight.Bold)
                            Text(
                                (if (c.isReceived) "Received" else "Issued") +
                                    (if (c.bankName.isNotBlank()) "  •  ${c.bankName}" else "") +
                                    "  •  ${Format.date(c.dateMillis)}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(Format.rupee(c.amount), fontWeight = FontWeight.Bold)
                            Text(
                                c.status, style = MaterialTheme.typography.labelSmall,
                                color = when (c.status) {
                                    ChequeStatus.CLEARED -> MaterialTheme.colorScheme.primary
                                    ChequeStatus.BOUNCED -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.outline
                                }
                            )
                        }
                        IconButton(onClick = { deleteFor = c }) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                    }
                    // Quick status transitions, one tap.
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (c.status != ChequeStatus.DEPOSITED && c.status != ChequeStatus.CLEARED)
                            OutlinedButton(onClick = { vm.setStatus(c, ChequeStatus.DEPOSITED) }) { Text("Mark Deposited") }
                        if (c.status != ChequeStatus.CLEARED)
                            OutlinedButton(onClick = { vm.setStatus(c, ChequeStatus.CLEARED) }) { Text("Mark Cleared") }
                        if (c.status != ChequeStatus.BOUNCED)
                            OutlinedButton(onClick = { bounceWarningFor = c }) { Text("Mark Bounced") }
                    }
                    Divider()
                }
            }
        }
    }

    if (showDialog) {
        ChequeDialog(existing = editing, onDismiss = { showDialog = false }, onSave = { no, bank, party, amt, date, recv, status, remarks ->
            vm.save(editing, no, bank, party, amt, date, recv, status, remarks) { showDialog = false }
        })
    }
    deleteFor?.let { c ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Delete cheque ${c.chequeNo}?") },
            confirmButton = { TextButton(onClick = { vm.delete(c); deleteFor = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Cancel") } }
        )
    }
    bounceWarningFor?.let { c ->
        AlertDialog(
            onDismissRequest = { bounceWarningFor = null },
            title = { Text("Mark ${c.chequeNo} as Bounced?") },
            text = {
                Text(
                    "This only updates the cheque's status. If this cheque was recorded against a " +
                        "Receipt or Payment, you'll need to delete or correct that entry yourself so " +
                        "your accounts reflect the bounce — it isn't reversed automatically."
                )
            },
            confirmButton = { TextButton(onClick = { vm.setStatus(c, ChequeStatus.BOUNCED); bounceWarningFor = null }) { Text("Mark Bounced") } },
            dismissButton = { TextButton(onClick = { bounceWarningFor = null }) { Text("Cancel") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChequeDialog(
    existing: ChequeEntry?,
    onDismiss: () -> Unit,
    onSave: (String, String, String, Double, Long, Boolean, String, String) -> Unit
) {
    var chequeNo by remember { mutableStateOf(existing?.chequeNo ?: "") }
    var bankName by remember { mutableStateOf(existing?.bankName ?: "") }
    var partyName by remember { mutableStateOf(existing?.partyName ?: "") }
    var amount by remember { mutableStateOf(if ((existing?.amount ?: 0.0) != 0.0) Format.money(existing!!.amount) else "") }
    var dateMillis by remember { mutableStateOf(existing?.dateMillis ?: System.currentTimeMillis()) }
    var isReceived by remember { mutableStateOf(existing?.isReceived ?: true) }
    var status by remember { mutableStateOf(existing?.status ?: ChequeStatus.PENDING) }
    var remarks by remember { mutableStateOf(existing?.remarks ?: "") }
    var statusMenu by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New cheque" else "Edit cheque") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = isReceived, onClick = { isReceived = true }, label = { Text("Received") })
                    FilterChip(selected = !isReceived, onClick = { isReceived = false }, label = { Text("Issued") })
                }
                OutlinedTextField(value = chequeNo, onValueChange = { chequeNo = it }, label = { Text("Cheque no *") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedTextField(value = bankName, onValueChange = { bankName = it }, label = { Text("Bank") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedTextField(
                    value = partyName, onValueChange = { partyName = it },
                    label = { Text(if (isReceived) "From (customer)" else "To (supplier)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                OutlinedTextField(
                    value = amount, onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Amount *") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                OutlinedButton(
                    onClick = { pickChequeDate(context, dateMillis) { dateMillis = it } },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) { Text("Date: ${Format.date(dateMillis)}") }
                ExposedDropdownMenuBox(expanded = statusMenu, onExpandedChange = { statusMenu = it }, modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedTextField(readOnly = true, value = status, onValueChange = {}, label = { Text("Status") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(statusMenu) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                    ExposedDropdownMenu(expanded = statusMenu, onDismissRequest = { statusMenu = false }) {
                        ChequeStatus.ALL.forEach { s -> DropdownMenuItem(text = { Text(s) }, onClick = { status = s; statusMenu = false }) }
                    }
                }
                OutlinedTextField(value = remarks, onValueChange = { remarks = it }, label = { Text("Remarks") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(chequeNo, bankName, partyName, amount.toDoubleOrNull() ?: 0.0, dateMillis, isReceived, status, remarks) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun pickChequeDate(context: android.content.Context, current: Long, onPicked: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = current }
    android.app.DatePickerDialog(
        context,
        { _, y, m, d -> c.set(Calendar.YEAR, y); c.set(Calendar.MONTH, m); c.set(Calendar.DAY_OF_MONTH, d); onPicked(c.timeInMillis) },
        c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
    ).show()
}
