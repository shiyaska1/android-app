package com.billing.pos.ui.expenses

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.auth.Session
import com.billing.pos.data.Expense
import com.billing.pos.data.PayMode
import com.billing.pos.data.Purchase
import com.billing.pos.data.Repository
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.util.Format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ExpensesViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)

    val expenses: StateFlow<List<Expense>> =
        repo.allExpenses.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val purchases: StateFlow<List<com.billing.pos.data.Purchase>> =
        repo.allPurchases.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }

    fun add(description: String, amount: Double, mode: PayMode, dateMillis: Long) {
        if (amount <= 0) { message.value = "Enter a valid amount"; return }
        viewModelScope.launch { repo.addExpense(description, amount, mode, dateMillis); message.value = "Payment added" }
    }

    fun addAgainstPurchase(purchase: com.billing.pos.data.Purchase, amount: Double, mode: PayMode) {
        if (amount <= 0) { message.value = "Enter a valid amount"; return }
        viewModelScope.launch { repo.addPaymentForPurchase(purchase, amount, mode); message.value = "Payment added" }
    }

    fun edit(e: Expense, description: String, amount: Double, mode: PayMode) {
        if (amount <= 0) { message.value = "Enter a valid amount"; return }
        viewModelScope.launch { repo.updateExpense(e, description, amount, mode); message.value = "Payment updated" }
    }

    fun delete(e: Expense) {
        viewModelScope.launch { repo.deleteExpense(e); message.value = "Payment deleted" }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpensesScreen(
    onBack: () -> Unit,
    vm: ExpensesViewModel = viewModel()
) {
    val snackbar = remember { SnackbarHostState() }
    val expenses by vm.expenses.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()

    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    var showAdd by remember { mutableStateOf(false) }
    var editFor by remember { mutableStateOf<Expense?>(null) }
    var deleteFor by remember { mutableStateOf<Expense?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Payments / Expenses") },
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
            if (Session.canCreatePayment) {
                FloatingActionButton(onClick = { showAdd = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add payment")
                }
            }
        }
    ) { pad ->
        if (!Session.canViewPayment) {
            Column(Modifier.fillMaxSize().padding(pad), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("You don't have permission to view payments", color = MaterialTheme.colorScheme.outline)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp)) {
                items(expenses, key = { it.id }) { e ->
                    Row(
                        Modifier.fillMaxWidth().clickable { editFor = e }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("${e.voucherNo}  •  ${e.payTo.ifBlank { e.description.ifBlank { "Expense" } }}", fontWeight = FontWeight.Bold)
                            Text(
                                (if (e.purchaseNo.isNotBlank()) "vs ${e.purchaseNo} • " else "") +
                                    "${e.paymentMode} • ${Format.date(e.dateMillis)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Text("- " + Format.rupee(e.amount), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                        if (Session.canDeletePayment) {
                            IconButton(onClick = { deleteFor = e }) {
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
        val purchases by vm.purchases.collectAsStateSafe()
        val outstanding = purchases.filter { it.balance > 0.001 }
        AddPaymentDialog(
            outstanding = outstanding,
            onDismiss = { showAdd = false },
            onGeneral = { desc, amt, mode, date -> vm.add(desc, amt, mode, date); showAdd = false },
            onAgainstPurchase = { pur, amt, mode -> vm.addAgainstPurchase(pur, amt, mode); showAdd = false }
        )
    }
    editFor?.let { e ->
        ExpenseEditDialog(
            initial = e,
            canSave = Session.canEditPayment,
            onDismiss = { editFor = null },
            onSave = { desc, amt, mode -> vm.edit(e, desc, amt, mode); editFor = null }
        )
    }
    deleteFor?.let { e ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Delete payment ${e.voucherNo}?") },
            text = { Text("This removes ${Format.rupee(e.amount)} from expenses.") },
            confirmButton = { TextButton(onClick = { vm.delete(e); deleteFor = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Cancel") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddPaymentDialog(
    outstanding: List<Purchase>,
    onDismiss: () -> Unit,
    onGeneral: (String, Double, PayMode, Long) -> Unit,
    onAgainstPurchase: (Purchase, Double, PayMode) -> Unit
) {
    val context = LocalContext.current
    var againstPurchase by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(outstanding.firstOrNull()) }
    var description by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(PayMode.CASH) }
    var dateMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var expanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New payment") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !againstPurchase, onClick = { againstPurchase = false }, label = { Text("General expense") })
                    FilterChip(selected = againstPurchase, onClick = { againstPurchase = true }, enabled = outstanding.isNotEmpty(), label = { Text("Against purchase") })
                }
                if (againstPurchase) {
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = Modifier.padding(top = 8.dp)) {
                        OutlinedTextField(
                            readOnly = true,
                            value = selected?.let { "${it.purchaseNo} • ${it.supplierName} • bal ${Format.money(it.balance)}" } ?: "",
                            onValueChange = {}, label = { Text("Purchase") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            outstanding.forEach { p ->
                                DropdownMenuItem(
                                    text = { Text("${p.purchaseNo} • ${p.supplierName} • bal ${Format.money(p.balance)}") },
                                    onClick = { selected = p; amount = Format.money(p.balance); expanded = false }
                                )
                            }
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = description, onValueChange = { description = it },
                        label = { Text("Description") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
                OutlinedTextField(
                    value = amount, onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Amount") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PayMode.values().forEach { m -> FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.label) }) }
                }
                OutlinedButton(
                    onClick = { pickPaymentDate(context, dateMillis) { dateMillis = it } },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) { Text("Date: ${Format.date(dateMillis)}") }
            }
        },
        confirmButton = {
            Button(onClick = {
                val amt = amount.toDoubleOrNull() ?: 0.0
                if (againstPurchase) selected?.let { onAgainstPurchase(it, amt.coerceAtMost(it.balance), mode) }
                else onGeneral(description, amt, mode, dateMillis)
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ExpenseEditDialog(
    initial: Expense?,
    canSave: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, Double, PayMode) -> Unit
) {
    var description by remember { mutableStateOf(initial?.description ?: "") }
    var amount by remember { mutableStateOf(initial?.amount?.let { Format.money(it) } ?: "") }
    var mode by remember { mutableStateOf(PayMode.values().firstOrNull { it.label == initial?.paymentMode } ?: PayMode.CASH) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New payment" else if (canSave) "Edit payment" else "Payment ${initial.voucherNo}") },
        text = {
            Column {
                OutlinedTextField(
                    value = description, onValueChange = { description = it },
                    label = { Text("Description") }, singleLine = true, enabled = canSave,
                    modifier = Modifier.fillMaxWidth()
                )
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
            Button(onClick = { onSave(description, amount.toDoubleOrNull() ?: 0.0, mode) }, enabled = canSave) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

private fun pickPaymentDate(context: android.content.Context, current: Long, onPicked: (Long) -> Unit) {
    val c = java.util.Calendar.getInstance().apply { timeInMillis = current }
    android.app.DatePickerDialog(
        context,
        { _, y, m, d ->
            c.set(java.util.Calendar.YEAR, y); c.set(java.util.Calendar.MONTH, m); c.set(java.util.Calendar.DAY_OF_MONTH, d)
            onPicked(c.timeInMillis)
        },
        c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH), c.get(java.util.Calendar.DAY_OF_MONTH)
    ).show()
}
