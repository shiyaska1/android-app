package com.billing.pos.ui.receipts

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import com.billing.pos.auth.Session
import com.billing.pos.data.Bill
import com.billing.pos.data.PayMode
import com.billing.pos.data.Receipt
import com.billing.pos.data.Repository
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.util.Format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ReceiptsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)

    val receipts: StateFlow<List<Receipt>> =
        repo.allReceipts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val bills: StateFlow<List<Bill>> =
        repo.allBills.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }

    fun add(bill: Bill, amount: Double, mode: PayMode) {
        if (amount <= 0) { message.value = "Enter a valid amount"; return }
        viewModelScope.launch { repo.addReceipt(bill, amount, mode); message.value = "Receipt added" }
    }

    fun edit(old: Receipt, amount: Double, mode: PayMode) {
        if (amount <= 0) { message.value = "Enter a valid amount"; return }
        viewModelScope.launch { repo.updateReceipt(old, amount, mode); message.value = "Receipt updated" }
    }

    fun delete(r: Receipt) {
        viewModelScope.launch { repo.deleteReceipt(r); message.value = "Receipt deleted" }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptsScreen(
    onBack: () -> Unit,
    vm: ReceiptsViewModel = viewModel()
) {
    val snackbar = remember { SnackbarHostState() }
    val receipts by vm.receipts.collectAsStateSafe()
    val bills by vm.bills.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()

    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    var showAdd by remember { mutableStateOf(false) }
    var editFor by remember { mutableStateOf<Receipt?>(null) }
    var deleteFor by remember { mutableStateOf<Receipt?>(null) }

    val outstanding = bills.filter { it.balance > 0.001 }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Receipts") },
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
        },
        floatingActionButton = {
            if (Session.canCreateReceipt) {
                FloatingActionButton(onClick = { showAdd = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add receipt")
                }
            }
        }
    ) { pad ->
        if (!Session.canViewReceipt) {
            Column(Modifier.fillMaxSize().padding(pad), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("You don't have permission to view receipts", color = MaterialTheme.colorScheme.outline)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp)) {
                items(receipts, key = { it.id }) { r ->
                    Row(
                        Modifier.fillMaxWidth().clickable { editFor = r }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("${r.receiptNo}  •  ${r.customerName}", fontWeight = FontWeight.Bold)
                            Text(
                                "vs ${r.billNo} • ${r.paymentMode} • ${Format.date(r.dateMillis)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Text("+ " + Format.rupee(r.amount), fontWeight = FontWeight.Bold)
                        if (Session.canDeleteReceipt) {
                            IconButton(onClick = { deleteFor = r }) {
                                Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    Divider()
                }
            }
        }
    }

    if (showAdd) {
        AddReceiptDialog(
            outstanding = outstanding,
            onDismiss = { showAdd = false },
            onAdd = { bill, amt, mode -> vm.add(bill, amt, mode); showAdd = false }
        )
    }
    editFor?.let { r ->
        ReceiptDialog(
            receipt = r,
            canSave = Session.canEditReceipt,
            onDismiss = { editFor = null },
            onSave = { amt, mode -> vm.edit(r, amt, mode); editFor = null }
        )
    }
    deleteFor?.let { r ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Delete receipt ${r.receiptNo}?") },
            text = { Text("This reduces the invoice's paid amount by ${Format.rupee(r.amount)}.") },
            confirmButton = { TextButton(onClick = { vm.delete(r); deleteFor = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Cancel") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddReceiptDialog(
    outstanding: List<Bill>,
    onDismiss: () -> Unit,
    onAdd: (Bill, Double, PayMode) -> Unit
) {
    var selected by remember { mutableStateOf(outstanding.firstOrNull()) }
    var amount by remember { mutableStateOf(outstanding.firstOrNull()?.balance?.let { Format.money(it) } ?: "") }
    var mode by remember { mutableStateOf(PayMode.CASH) }
    var expanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New receipt") },
        text = {
            if (outstanding.isEmpty()) {
                Text("No outstanding (credit) invoices to receive against.")
            } else {
                Column {
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                        OutlinedTextField(
                            readOnly = true,
                            value = selected?.let { "${it.billNo} • ${it.customerName} • bal ${Format.money(it.balance)}" } ?: "",
                            onValueChange = {},
                            label = { Text("Invoice") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            outstanding.forEach { b ->
                                DropdownMenuItem(
                                    text = { Text("${b.billNo} • ${b.customerName} • bal ${Format.money(b.balance)}") },
                                    onClick = { selected = b; amount = Format.money(b.balance); expanded = false }
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = amount,
                        onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Amount received") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PayMode.values().forEach { m ->
                            FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.label) })
                        }
                    }
                }
            }
        },
        confirmButton = {
            val bill = selected
            Button(
                onClick = {
                    if (bill != null) onAdd(bill, (amount.toDoubleOrNull() ?: 0.0).coerceAtMost(bill.balance), mode)
                },
                enabled = bill != null
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ReceiptDialog(
    receipt: Receipt,
    canSave: Boolean,
    onDismiss: () -> Unit,
    onSave: (Double, PayMode) -> Unit
) {
    var amount by remember { mutableStateOf(Format.money(receipt.amount)) }
    var mode by remember { mutableStateOf(PayMode.values().firstOrNull { it.label == receipt.paymentMode } ?: PayMode.CASH) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (canSave) "Edit receipt ${receipt.receiptNo}" else "Receipt ${receipt.receiptNo}") },
        text = {
            Column {
                Text("Customer: ${receipt.customerName}  •  vs ${receipt.billNo}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                OutlinedTextField(
                    value = amount, onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Amount") }, singleLine = true, enabled = canSave,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PayMode.values().forEach { m ->
                        FilterChip(selected = mode == m, onClick = { if (canSave) mode = m }, label = { Text(m.label) })
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(amount.toDoubleOrNull() ?: 0.0, mode) }, enabled = canSave) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
