package com.billing.pos.ui.receipts

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.billing.pos.data.AppPrefs
import com.billing.pos.pdf.TablePdf
import com.billing.pos.ui.common.DateSearchFilter
import com.billing.pos.ui.common.endOfDay
import com.billing.pos.ui.common.rememberPdfDownloader
import com.billing.pos.ui.common.startOfDay
import com.billing.pos.data.Bill
import com.billing.pos.data.PayMode
import com.billing.pos.data.Receipt
import com.billing.pos.data.Repository
import com.billing.pos.pdf.ReceiptPdf
import com.billing.pos.print.ThermalPrinter
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.util.Format
import com.billing.pos.util.Permissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReceiptsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)

    val receipts: StateFlow<List<Receipt>> =
        repo.allReceipts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val bills: StateFlow<List<Bill>> =
        repo.allBills.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val payFromOptions = MutableStateFlow<List<String>>(emptyList())
    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }

    init { viewModelScope.launch { payFromOptions.value = repo.payFromNames() } }

    fun addAgainstInvoice(bill: Bill, amount: Double, mode: PayMode, dateMillis: Long) {
        if (amount <= 0) { message.value = "Enter a valid amount"; return }
        viewModelScope.launch { repo.addReceipt(bill, amount, mode, dateMillis); message.value = "Receipt added" }
    }

    fun addStandalone(payFrom: String, amount: Double, mode: PayMode, dateMillis: Long) {
        if (amount <= 0) { message.value = "Enter a valid amount"; return }
        viewModelScope.launch {
            repo.addStandaloneReceipt(payFrom.trim().ifBlank { "Cash receipt" }, amount, mode, dateMillis)
            payFromOptions.value = repo.payFromNames()
            message.value = "Receipt added"
        }
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val receipts by vm.receipts.collectAsStateSafe()
    val bills by vm.bills.collectAsStateSafe()
    val payFromOptions by vm.payFromOptions.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()

    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    var showAdd by remember { mutableStateOf(false) }
    var editFor by remember { mutableStateOf<Receipt?>(null) }
    var deleteFor by remember { mutableStateOf<Receipt?>(null) }
    var printFor by remember { mutableStateOf<Receipt?>(null) }

    val printPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val r = printFor
        if (granted && r != null) scope.launch { doPrintReceipt(context, r, snackbar) }
        else if (!granted) scope.launch {
            val res = snackbar.showSnackbar(
                "Allow 'Nearby devices' permission to print",
                actionLabel = "Settings",
                duration = SnackbarDuration.Long
            )
            if (res == SnackbarResult.ActionPerformed) Permissions.openAppSettings(context)
        }
    }

    fun requestPrint(r: Receipt) {
        printFor = r
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !ThermalPrinter.hasConnectPermission(context)) {
            printPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else scope.launch { doPrintReceipt(context, r, snackbar) }
    }

    val outstanding = bills.filter { it.balance > 0.001 }

    var query by remember { mutableStateOf("") }
    var fromMillis by remember { mutableStateOf<Long?>(null) }
    var toMillis by remember { mutableStateOf<Long?>(null) }
    val filtered = receipts.filter {
        (fromMillis == null || it.dateMillis >= startOfDay(fromMillis!!)) &&
            (toMillis == null || it.dateMillis <= endOfDay(toMillis!!)) &&
            (query.isBlank() || it.receiptNo.contains(query, true) || it.payFrom.contains(query, true) ||
                it.customerName.contains(query, true) || it.billNo.contains(query, true))
    }
    val total = filtered.sumOf { it.amount }
    val downloadPdf = rememberPdfDownloader { msg -> scope.launch { snackbar.showSnackbar(msg) } }
    fun buildReceiptsPdf(): java.io.File {
        val cols = listOf(
            TablePdf.Col("No", 1.3f), TablePdf.Col("Date", 1.3f), TablePdf.Col("From", 2.6f),
            TablePdf.Col("Mode", 1f), TablePdf.Col("Amount", 1.3f, right = true)
        )
        val data = filtered.sortedByDescending { it.dateMillis }.map {
            listOf(it.receiptNo, Format.date(it.dateMillis), it.payFrom.ifBlank { it.customerName }, it.paymentMode, Format.money(it.amount))
        }
        val sub = "Count: ${filtered.size}" + (fromMillis?.let { "  From: ${Format.date(it)}" } ?: "") +
            (toMillis?.let { "  To: ${Format.date(it)}" } ?: "") + (if (query.isNotBlank()) "  Search: $query" else "")
        return TablePdf.generate(context, AppPrefs(context).company, "Receipts", sub, cols, data, listOf("TOTAL" to Format.money(total)))
    }

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
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                actions = {
                    if (Session.canViewReceipt) {
                        IconButton(onClick = { downloadPdf { buildReceiptsPdf() } }) {
                            Icon(Icons.Filled.Download, contentDescription = "Download PDF")
                        }
                    }
                }
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
            Column(Modifier.fillMaxSize().padding(pad)) {
                DateSearchFilter(
                    query = query, onQuery = { query = it },
                    from = fromMillis, onFrom = { fromMillis = it },
                    to = toMillis, onTo = { toMillis = it },
                    searchLabel = "Search receipt no / name"
                )
                Divider()
                LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp)) {
                    items(filtered, key = { it.id }) { r ->
                    Row(
                        Modifier.fillMaxWidth().clickable { editFor = r }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("${r.receiptNo}  •  ${r.payFrom.ifBlank { r.customerName }}", fontWeight = FontWeight.Bold)
                            Text(
                                (if (r.billNo.isNotBlank()) "vs ${r.billNo} • " else "Other • ") +
                                    "${r.paymentMode} • ${Format.date(r.dateMillis)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Text("+ " + Format.rupee(r.amount), fontWeight = FontWeight.Bold)
                        IconButton(onClick = { sharePdf(context, r) }) {
                            Icon(Icons.Filled.PictureAsPdf, "Share PDF")
                        }
                        IconButton(onClick = { requestPrint(r) }) {
                            Icon(Icons.Filled.Print, "Print")
                        }
                        if (Session.canDeleteReceipt) {
                            IconButton(onClick = { deleteFor = r }) {
                                Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    Divider()
                    }
                }
                Card(Modifier.fillMaxWidth().padding(12.dp)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Total  (${filtered.size})", fontWeight = FontWeight.Bold)
                        Text(Format.rupee(total), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddReceiptDialog(
            outstanding = outstanding,
            payFromOptions = payFromOptions,
            onDismiss = { showAdd = false },
            onAddInvoice = { bill, amt, mode, date -> vm.addAgainstInvoice(bill, amt, mode, date); showAdd = false },
            onAddOther = { payFrom, amt, mode, date -> vm.addStandalone(payFrom, amt, mode, date); showAdd = false }
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
            text = { Text(if (r.billNo.isNotBlank()) "This reduces the invoice's paid amount by ${Format.rupee(r.amount)}." else "Remove ${Format.rupee(r.amount)} receipt.") },
            confirmButton = { TextButton(onClick = { vm.delete(r); deleteFor = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Cancel") } }
        )
    }
}

private fun pickReceiptDate(context: Context, current: Long, onPicked: (Long) -> Unit) {
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

private fun sharePdf(context: Context, r: Receipt) {
    val company = AppPrefs(context).company
    val uri = ReceiptPdf.generate(context, company, r)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(intent, "Share receipt").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

private suspend fun doPrintReceipt(context: Context, r: Receipt, snackbar: SnackbarHostState) {
    val company = AppPrefs(context).company
    val result = withContext(Dispatchers.IO) { runCatching { ThermalPrinter.printReceipt(context, company, r) } }
    result.onSuccess { snackbar.showSnackbar("Sent to printer") }
        .onFailure { snackbar.showSnackbar(it.message ?: "Print failed") }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddReceiptDialog(
    outstanding: List<Bill>,
    payFromOptions: List<String>,
    onDismiss: () -> Unit,
    onAddInvoice: (Bill, Double, PayMode, Long) -> Unit,
    onAddOther: (String, Double, PayMode, Long) -> Unit
) {
    val context = LocalContext.current
    var againstInvoice by remember { mutableStateOf(outstanding.isNotEmpty()) }
    var selected by remember { mutableStateOf(outstanding.firstOrNull()) }
    var payFrom by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf(outstanding.firstOrNull()?.balance?.let { Format.money(it) } ?: "") }
    var mode by remember { mutableStateOf(PayMode.CASH) }
    var dateMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var billExpanded by remember { mutableStateOf(false) }
    var payFromExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New receipt") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = againstInvoice,
                        onClick = { againstInvoice = true },
                        enabled = outstanding.isNotEmpty(),
                        label = { Text("Against invoice") }
                    )
                    FilterChip(
                        selected = !againstInvoice,
                        onClick = { againstInvoice = false },
                        label = { Text("Other source") }
                    )
                }

                if (againstInvoice) {
                    ExposedDropdownMenuBox(expanded = billExpanded, onExpandedChange = { billExpanded = it }, modifier = Modifier.padding(top = 8.dp)) {
                        OutlinedTextField(
                            readOnly = true,
                            value = selected?.let { "${it.billNo} • ${it.customerName} • bal ${Format.money(it.balance)}" } ?: "",
                            onValueChange = {},
                            label = { Text("Invoice") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(billExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = billExpanded, onDismissRequest = { billExpanded = false }) {
                            outstanding.forEach { b ->
                                DropdownMenuItem(
                                    text = { Text("${b.billNo} • ${b.customerName} • bal ${Format.money(b.balance)}") },
                                    onClick = { selected = b; amount = Format.money(b.balance); billExpanded = false }
                                )
                            }
                        }
                    }
                } else {
                    ExposedDropdownMenuBox(expanded = payFromExpanded, onExpandedChange = { payFromExpanded = it }, modifier = Modifier.padding(top = 8.dp)) {
                        OutlinedTextField(
                            value = payFrom,
                            onValueChange = { payFrom = it; payFromExpanded = true },
                            label = { Text("Pay from (optional)") },
                            placeholder = { Text("Type or pick a name") },
                            singleLine = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(payFromExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        val filtered = payFromOptions.filter { it.contains(payFrom, ignoreCase = true) }
                        if (filtered.isNotEmpty()) {
                            ExposedDropdownMenu(expanded = payFromExpanded, onDismissRequest = { payFromExpanded = false }) {
                                filtered.forEach { name ->
                                    DropdownMenuItem(text = { Text(name) }, onClick = { payFrom = name; payFromExpanded = false })
                                }
                            }
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
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PayMode.values().forEach { m ->
                        FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.label) })
                    }
                }
                OutlinedButton(
                    onClick = { pickReceiptDate(context, dateMillis) { dateMillis = it } },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) { Text("Date: ${Format.date(dateMillis)}") }
            }
        },
        confirmButton = {
            Button(onClick = {
                val amt = amount.toDoubleOrNull() ?: 0.0
                if (againstInvoice) {
                    selected?.let { onAddInvoice(it, amt.coerceAtMost(it.balance), mode, dateMillis) }
                } else {
                    onAddOther(payFrom.trim(), amt, mode, dateMillis)
                }
            }) { Text("Add") }
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
                Text(
                    "From: ${receipt.payFrom.ifBlank { receipt.customerName }}" +
                        if (receipt.billNo.isNotBlank()) "  •  vs ${receipt.billNo}" else "  •  other source",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                )
                OutlinedTextField(
                    value = amount, onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Amount") }, singleLine = true, enabled = canSave,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
