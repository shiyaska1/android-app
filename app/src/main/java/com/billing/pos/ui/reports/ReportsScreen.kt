package com.billing.pos.ui.reports

import android.app.Application
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.auth.Session
import com.billing.pos.data.Bill
import com.billing.pos.data.Expense
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
import java.util.Calendar

class ReportsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)

    val bills: StateFlow<List<Bill>> =
        repo.allBills.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val receipts: StateFlow<List<Receipt>> =
        repo.allReceipts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val expenses: StateFlow<List<Expense>> =
        repo.allExpenses.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }

    fun receivePayment(bill: Bill, amount: Double, mode: PayMode) {
        if (amount <= 0) { message.value = "Enter a valid amount"; return }
        viewModelScope.launch {
            repo.addReceipt(bill, amount, mode)
            val bal = (bill.balance - amount).coerceAtLeast(0.0)
            message.value = if (bal <= 0.001) "Receipt added — invoice fully paid"
            else "Receipt added — balance ${Format.money(bal)}"
        }
    }

    fun addExpense(description: String, amount: Double, mode: PayMode) {
        if (amount <= 0) { message.value = "Enter a valid amount"; return }
        viewModelScope.launch {
            repo.addExpense(description, amount, mode)
            message.value = "Payment voucher added"
        }
    }
}

private enum class Quick { TODAY, MONTH, ALL, CUSTOM }

private fun startOfDay(millis: Long): Long {
    val c = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    return c.timeInMillis
}

private fun endOfDay(millis: Long): Long = startOfDay(millis) + 24L * 60 * 60 * 1000 - 1

private fun firstOfMonth(now: Long): Long {
    val c = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    return c.timeInMillis
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(
    onBack: () -> Unit,
    vm: ReportsViewModel = viewModel()
) {
    val snackbar = remember { SnackbarHostState() }
    val bills by vm.bills.collectAsStateSafe()
    val receipts by vm.receipts.collectAsStateSafe()
    val expenses by vm.expenses.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()

    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    val now = remember { System.currentTimeMillis() }
    var quick by remember { mutableStateOf(Quick.MONTH) }
    var fromMillis by remember { mutableStateOf(firstOfMonth(now)) }
    var toMillis by remember { mutableStateOf(now) }
    var paymentFilter by remember { mutableStateOf<String?>(null) }   // null = all
    var customerFilter by remember { mutableStateOf<String?>(null) }

    // Effective date bounds.
    val lo = if (quick == Quick.ALL) Long.MIN_VALUE else startOfDay(fromMillis)
    val hi = endOfDay(if (quick == Quick.ALL) now else toMillis)

    fun matchDate(d: Long) = d in lo..hi

    val fBills = bills.filter {
        matchDate(it.dateMillis) &&
            (paymentFilter == null || it.paymentMethod == paymentFilter) &&
            (customerFilter == null || it.customerName == customerFilter)
    }
    // Receipts/expenses only make sense for cash modes; hide them if a "Credit" filter is on.
    val modeFilterActive = paymentFilter != null && paymentFilter != "Credit"
    val fReceipts = receipts.filter {
        matchDate(it.dateMillis) &&
            (paymentFilter == null || (modeFilterActive && it.paymentMode == paymentFilter)) &&
            (customerFilter == null || it.customerName == customerFilter)
    }
    val fExpenses = expenses.filter {
        matchDate(it.dateMillis) &&
            (paymentFilter == null || (modeFilterActive && it.paymentMode == paymentFilter)) &&
            customerFilter == null
    }

    val salesTotal = fBills.sumOf { it.grandTotal }
    val cashSalesIn = fBills.filter { it.paymentMethod != "Credit" }.sumOf { it.grandTotal }
    val outstanding = fBills.sumOf { it.balance }
    val receiptsTotal = fReceipts.sumOf { it.amount }
    val expensesTotal = fExpenses.sumOf { it.amount }
    val netFund = cashSalesIn + receiptsTotal - expensesTotal

    val customerNames = remember(bills) { bills.map { it.customerName }.distinct().sorted() }

    var receiveFor by remember { mutableStateOf<Bill?>(null) }
    var showExpense by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Sales Report") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                actions = {
                    if (Session.canEdit) {
                        IconButton(onClick = { showExpense = true }) {
                            Icon(Icons.Filled.Payments, contentDescription = "Add expense")
                        }
                    }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(12.dp)
        ) {
            // Quick range chips
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Quick.values().forEach { q ->
                    FilterChip(
                        selected = quick == q,
                        onClick = {
                            quick = q
                            when (q) {
                                Quick.TODAY -> { fromMillis = now; toMillis = now }
                                Quick.MONTH -> { fromMillis = firstOfMonth(now); toMillis = now }
                                Quick.ALL -> {}
                                Quick.CUSTOM -> {}
                            }
                        },
                        label = { Text(q.name.lowercase().replaceFirstChar { it.uppercase() }) }
                    )
                }
            }

            if (quick == Quick.CUSTOM) {
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DateField("From", fromMillis, Modifier.weight(1f)) { fromMillis = it }
                    DateField("To", toMillis, Modifier.weight(1f)) { toMillis = it }
                }
            }

            // Filter dropdowns
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterDropdown(
                    label = "Payment",
                    selected = paymentFilter ?: "All",
                    options = listOf("All", "Cash", "UPI", "Card", "Credit"),
                    onSelect = { paymentFilter = if (it == "All") null else it },
                    modifier = Modifier.weight(1f)
                )
                FilterDropdown(
                    label = "Customer",
                    selected = customerFilter ?: "All",
                    options = listOf("All") + customerNames,
                    onSelect = { customerFilter = if (it == "All") null else it },
                    modifier = Modifier.weight(1f)
                )
            }

            // Summary
            Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Column(Modifier.padding(16.dp)) {
                    SummaryRow("Sales (invoiced)", Format.rupee(salesTotal))
                    SummaryRow("Receipts received", "+ " + Format.rupee(receiptsTotal), Color(0xFF2E7D32))
                    SummaryRow("Payments / expenses", "- " + Format.rupee(expensesTotal), MaterialTheme.colorScheme.error)
                    SummaryRow("Outstanding (credit)", Format.rupee(outstanding), MaterialTheme.colorScheme.outline)
                    Divider(Modifier.padding(vertical = 8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Net fund in period", fontWeight = FontWeight.Bold)
                        Text(Format.rupee(netFund), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        "Net = paid sales + receipts − payments",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            // Ledger
            val txns = remember(fBills, fReceipts, fExpenses) {
                buildList {
                    fBills.forEach { add(Txn(it.dateMillis, "SALE", it, null, null)) }
                    fReceipts.forEach { add(Txn(it.dateMillis, "RECEIPT", null, it, null)) }
                    fExpenses.forEach { add(Txn(it.dateMillis, "PAYMENT", null, null, it)) }
                }.sortedByDescending { it.date }
            }

            if (txns.isEmpty()) {
                Text(
                    "No transactions in this period.",
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 16.dp)
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp)) {
                    items(txns) { t ->
                        when (t.kind) {
                            "SALE" -> SaleRow(t.bill!!, canReceive = Session.canEdit) { receiveFor = t.bill }
                            "RECEIPT" -> ReceiptRow(t.receipt!!)
                            "PAYMENT" -> ExpenseRow(t.expense!!)
                        }
                        Divider()
                    }
                }
            }
        }
    }

    receiveFor?.let { bill ->
        ReceiveDialog(
            bill = bill,
            onDismiss = { receiveFor = null },
            onReceive = { amount, mode -> vm.receivePayment(bill, amount, mode); receiveFor = null }
        )
    }
    if (showExpense) {
        ExpenseDialog(
            onDismiss = { showExpense = false },
            onAdd = { desc, amount, mode -> vm.addExpense(desc, amount, mode); showExpense = false }
        )
    }
}

private data class Txn(
    val date: Long,
    val kind: String,
    val bill: Bill?,
    val receipt: Receipt?,
    val expense: Expense?
)

@Composable
private fun SummaryRow(label: String, value: String, color: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(value, color = color, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SaleRow(bill: Bill, canReceive: Boolean, onReceive: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("${bill.billNo}  •  ${bill.customerName}", fontWeight = FontWeight.SemiBold)
            Text(
                "${bill.paymentMethod} • ${bill.paymentStatus} • ${Format.date(bill.dateMillis)}" +
                    if (bill.balance > 0.001) " • bal ${Format.money(bill.balance)}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
        Text(Format.rupee(bill.grandTotal), fontWeight = FontWeight.Bold)
        if (canReceive && bill.balance > 0.001) {
            TextButton(onClick = onReceive) { Text("Receive") }
        }
    }
}

@Composable
private fun ReceiptRow(r: Receipt) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.weight(1f)) {
            Text("${r.receiptNo}  •  ${r.customerName}", fontWeight = FontWeight.SemiBold, color = Color(0xFF2E7D32))
            Text(
                "Receipt vs ${r.billNo} • ${r.paymentMode} • ${Format.date(r.dateMillis)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
        Text("+ " + Format.rupee(r.amount), fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
    }
}

@Composable
private fun ExpenseRow(e: Expense) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.weight(1f)) {
            Text("${e.voucherNo}  •  ${e.description.ifBlank { "Expense" }}", fontWeight = FontWeight.SemiBold)
            Text(
                "Payment • ${e.paymentMode} • ${Format.date(e.dateMillis)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
        Text("- " + Format.rupee(e.amount), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterDropdown(
    label: String,
    selected: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            readOnly = true,
            value = selected,
            onValueChange = {},
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(text = { Text(opt) }, onClick = { onSelect(opt); expanded = false })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(label: String, millis: Long, modifier: Modifier = Modifier, onPick: (Long) -> Unit) {
    var show by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { show = true }, modifier = modifier) {
        Text("$label: ${Format.date(millis)}")
    }
    if (show) {
        val state = androidx.compose.material3.rememberDatePickerState(initialSelectedDateMillis = millis)
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { show = false },
            confirmButton = {
                TextButton(onClick = { state.selectedDateMillis?.let(onPick); show = false }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { show = false }) { Text("Cancel") } }
        ) {
            androidx.compose.material3.DatePicker(state = state)
        }
    }
}

@Composable
private fun ReceiveDialog(bill: Bill, onDismiss: () -> Unit, onReceive: (Double, PayMode) -> Unit) {
    var amount by remember { mutableStateOf(Format.money(bill.balance)) }
    var mode by remember { mutableStateOf(PayMode.CASH) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Receipt — ${bill.billNo}") },
        text = {
            Column {
                Text("Balance: ${Format.rupee(bill.balance)}", color = MaterialTheme.colorScheme.outline)
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Amount received") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Text("Payment mode", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PayMode.values().forEach { m ->
                        FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.label) })
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val amt = (amount.toDoubleOrNull() ?: 0.0).coerceAtMost(bill.balance)
                onReceive(amt, mode)
            }) { Text("Receive") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ExpenseDialog(onDismiss: () -> Unit, onAdd: (String, Double, PayMode) -> Unit) {
    var description by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(PayMode.CASH) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Payment / Expense") },
        text = {
            Column {
                OutlinedTextField(
                    value = description, onValueChange = { description = it },
                    label = { Text("Description") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Amount") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Text("Payment mode", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PayMode.values().forEach { m ->
                        FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.label) })
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onAdd(description, amount.toDoubleOrNull() ?: 0.0, mode) }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
