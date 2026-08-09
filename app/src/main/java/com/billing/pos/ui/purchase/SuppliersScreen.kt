package com.billing.pos.ui.purchase

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.Repository
import com.billing.pos.data.Supplier
import com.billing.pos.data.XlsxWriter
import com.billing.pos.pdf.TablePdf
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.ui.common.rememberPdfDownloader
import com.billing.pos.ui.common.rememberXlsxDownloader
import com.billing.pos.util.Format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SuppliersViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)
    val suppliers: StateFlow<List<Supplier>> =
        repo.suppliers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Each supplier's net payable (opening balance + due purchases − payments), keyed by name. */
    val supplierBalances: StateFlow<Map<String, Double>> =
        repo.supplierBalances.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }

    fun save(
        existing: Supplier?, name: String, phone: String, address: String, gstin: String, state: String,
        openingAmount: Double, openingIsDebit: Boolean, onDone: () -> Unit
    ) {
        if (name.isBlank()) { message.value = "Enter a name"; return }
        viewModelScope.launch {
            if (existing == null) repo.addSupplier(name, phone, address, gstin, state)
            else repo.updateSupplier(existing.copy(name = name.trim(), phone = phone.trim(), address = address.trim(), gstin = gstin.trim(), state = state.trim()))
            repo.setSupplierOpeningBalance(name.trim(), openingAmount, openingIsDebit)
            message.value = "Saved"; onDone()
        }
    }

    suspend fun openingBalanceFor(supplier: Supplier) = repo.supplierOpeningBalance(supplier.name)

    fun quickPurchasesFor(supplierId: Long) = repo.quickPurchasesForSupplier(supplierId)

    fun addQuickPurchase(supplier: Supplier, amount: Double, note: String, dateMillis: Long) {
        if (amount <= 0.0) { message.value = "Enter a valid amount"; return }
        viewModelScope.launch {
            repo.addQuickPurchase(supplier, amount, note, dateMillis)
            message.value = "Invoice added"
        }
    }

    fun deleteQuickPurchase(purchase: com.billing.pos.data.Purchase) {
        viewModelScope.launch { repo.deletePurchase(purchase); message.value = "Invoice removed" }
    }

    /** Attach the same quick invoice (amount + note + date) to several suppliers at once. */
    fun addInvoiceToSuppliers(targets: List<Supplier>, amount: Double, note: String, dateMillis: Long) {
        if (amount <= 0.0) { message.value = "Enter a valid amount"; return }
        if (targets.isEmpty()) { message.value = "Select at least one supplier"; return }
        viewModelScope.launch {
            targets.forEach { repo.addQuickPurchase(it, amount, note, dateMillis) }
            message.value = "Invoice added to ${targets.size} supplier(s)"
        }
    }

    fun delete(s: Supplier) {
        viewModelScope.launch {
            val r = repo.deleteSupplier(s)
            message.value = if (r.isSuccess) "Supplier deleted" else r.exceptionOrNull()?.message ?: "Cannot delete"
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SuppliersScreen(
    onBack: () -> Unit,
    vm: SuppliersViewModel = viewModel()
) {
    val snackbar = remember { SnackbarHostState() }
    val suppliers by vm.suppliers.collectAsStateSafe()
    val balances by vm.supplierBalances.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()

    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    var showDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Supplier?>(null) }
    var deleteFor by remember { mutableStateOf<Supplier?>(null) }
    // Picking suppliers to bulk-attach an invoice to. Off by default so the list stays a plain list.
    var selecting by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<Long>() }
    var showBulkInvoice by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    val downloadPdf = rememberPdfDownloader { msg -> vm.message.value = msg }
    val downloadXlsx = rememberXlsxDownloader { msg -> vm.message.value = msg }
    fun buildSuppliersPdf(): java.io.File {
        val cols = listOf(
            TablePdf.Col("Name", 2f), TablePdf.Col("Phone", 1.4f),
            TablePdf.Col("Address", 2.4f), TablePdf.Col("GSTIN", 1.4f), TablePdf.Col("Balance", 1.2f, right = true)
        )
        val data = suppliers.map { listOf(it.name, it.phone, it.address, it.gstin, Format.money(balances[it.name] ?: 0.0)) }
        return TablePdf.generate(context, AppPrefs(context).company, "Suppliers", "Count: ${suppliers.size}", cols, data)
    }
    fun buildSuppliersXlsx(): java.io.File {
        val rows = mutableListOf(
            XlsxWriter.row(XlsxWriter.text("Name"), XlsxWriter.text("Phone"), XlsxWriter.text("Address"), XlsxWriter.text("GSTIN"), XlsxWriter.text("Balance"))
        )
        suppliers.forEach {
            rows.add(XlsxWriter.row(XlsxWriter.text(it.name), XlsxWriter.text(it.phone), XlsxWriter.text(it.address), XlsxWriter.text(it.gstin), XlsxWriter.num(balances[it.name] ?: 0.0)))
        }
        val file = java.io.File(java.io.File(context.cacheDir, "shared").apply { mkdirs() }, "suppliers.xlsx")
        XlsxWriter.write(file, "Suppliers", rows)
        return file
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (selecting) "${selected.size} selected" else "Suppliers") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    if (selecting) {
                        IconButton(onClick = { showBulkInvoice = true }, enabled = selected.isNotEmpty()) {
                            Icon(Icons.Filled.ReceiptLong, "Add invoice to selected")
                        }
                        IconButton(onClick = { selecting = false; selected.clear() }) { Icon(Icons.Filled.Close, "Cancel selection") }
                    } else {
                        IconButton(onClick = { downloadPdf { buildSuppliersPdf() } }) { Icon(Icons.Filled.PictureAsPdf, "Download PDF") }
                        IconButton(onClick = { downloadXlsx { buildSuppliersXlsx() } }) { Icon(Icons.Filled.GridOn, "Download Excel") }
                        IconButton(onClick = { selecting = true }) { Icon(Icons.Filled.Share, "Select suppliers") }
                    }
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
            if (!selecting) FloatingActionButton(onClick = { editing = null; showDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add supplier")
            }
        }
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp)) {
            items(suppliers, key = { it.id }) { s ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable {
                            if (selecting) { if (s.id in selected) selected.remove(s.id) else selected.add(s.id) }
                            else { editing = s; showDialog = true }
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (selecting) {
                        androidx.compose.material3.Checkbox(
                            checked = s.id in selected,
                            onCheckedChange = { if (s.id in selected) selected.remove(s.id) else selected.add(s.id) }
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(s.name + if (s.isDefault) "  (default)" else "", fontWeight = FontWeight.Bold)
                        val sub = listOf(s.phone, s.address).filter { it.isNotBlank() }.joinToString("  •  ")
                        if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                    val balance = balances[s.name] ?: 0.0
                    if (balance > 0.001) {
                        Text(
                            "Due " + Format.rupee(balance),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    } else if (balance < -0.001) {
                        Text(
                            "Adv " + Format.rupee(-balance),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF2E7D32),
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                    if (!s.isDefault && !selecting) {
                        IconButton(onClick = { deleteFor = s }) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                    }
                }
                Divider()
            }
        }
    }

    if (showDialog) {
        SupplierDialog(
            existing = editing,
            loadOpeningBalance = { s -> vm.openingBalanceFor(s) },
            quickPurchasesFor = { id -> vm.quickPurchasesFor(id) },
            onAddQuickPurchase = { s, amt, note, date -> vm.addQuickPurchase(s, amt, note, date) },
            onDeleteQuickPurchase = { p -> vm.deleteQuickPurchase(p) },
            onDismiss = { showDialog = false },
            onSave = { n, p, a, g, st, openingAmt, openingIsDebit ->
                vm.save(editing, n, p, a, g, st, openingAmt, openingIsDebit) { showDialog = false }
            }
        )
    }
    if (showBulkInvoice) {
        BulkPurchaseDialog(
            count = selected.size,
            onDismiss = { showBulkInvoice = false },
            onConfirm = { amount, note, date ->
                vm.addInvoiceToSuppliers(suppliers.filter { it.id in selected }, amount, note, date)
                showBulkInvoice = false
                selecting = false
                selected.clear()
            }
        )
    }
    deleteFor?.let { s ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Delete ${s.name}?") },
            text = { Text("Only suppliers with no purchases can be deleted.") },
            confirmButton = { TextButton(onClick = { vm.delete(s); deleteFor = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun SupplierDialog(
    existing: Supplier?,
    loadOpeningBalance: suspend (Supplier) -> Pair<Double, Boolean>,
    quickPurchasesFor: (Long) -> kotlinx.coroutines.flow.Flow<List<com.billing.pos.data.Purchase>>,
    onAddQuickPurchase: (Supplier, Double, String, Long) -> Unit,
    onDeleteQuickPurchase: (com.billing.pos.data.Purchase) -> Unit,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, String, Double, Boolean) -> Unit
) {
    val dialogContext = androidx.compose.ui.platform.LocalContext.current
    val gstEnabled = remember { AppPrefs(dialogContext).gstEnabled }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var phone by remember { mutableStateOf(existing?.phone ?: "") }
    var address by remember { mutableStateOf(existing?.address ?: "") }
    var gstin by remember { mutableStateOf(existing?.gstin ?: "") }
    var state by remember { mutableStateOf(existing?.state ?: "") }
    var stateMenu by remember { mutableStateOf(false) }

    var openingAmount by remember { mutableStateOf("") }
    var openingIsDebit by remember { mutableStateOf(false) }
    LaunchedEffect(existing?.id) {
        if (existing != null) {
            val (amt, isDebit) = loadOpeningBalance(existing)
            openingAmount = if (amt != 0.0) Format.money(amt) else ""
            openingIsDebit = isDebit
        }
    }

    var showAddInvoice by remember { mutableStateOf(false) }
    val quickPurchases by remember(existing?.id) {
        existing?.id?.let { quickPurchasesFor(it) }
            ?: kotlinx.coroutines.flow.flowOf(emptyList<com.billing.pos.data.Purchase>())
    }.collectAsState(initial = emptyList())
    val outstandingPurchases = quickPurchases.filter { it.balance > 0.001 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New supplier" else "Edit supplier") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = phone, onValueChange = { phone = it.filter { c -> c.isDigit() } },
                    label = { Text("Phone") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                OutlinedTextField(value = gstin, onValueChange = { gstin = it }, label = { Text("GSTIN / TIN") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                if (gstEnabled) {
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        OutlinedTextField(
                            readOnly = true, value = state.ifBlank { "Select state (for IGST vs CGST+SGST)" },
                            onValueChange = {}, label = { Text("State") }, singleLine = true,
                            trailingIcon = {
                                IconButton(onClick = { stateMenu = true }) {
                                    Icon(Icons.Filled.ArrowDropDown, "Pick state")
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        androidx.compose.material3.DropdownMenu(expanded = stateMenu, onDismissRequest = { stateMenu = false }) {
                            com.billing.pos.data.IndianStates.NAMES.forEach { s ->
                                androidx.compose.material3.DropdownMenuItem(text = { Text(s) }, onClick = { state = s; stateMenu = false })
                            }
                        }
                    }
                }
                OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("Address") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))

                // Opening balance: what you already owed (or were owed by) this supplier before this app.
                Text("Opening balance", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp))
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = openingAmount,
                        onValueChange = { openingAmount = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Amount") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
                    androidx.compose.material3.FilterChip(selected = !openingIsDebit, onClick = { openingIsDebit = false }, label = { Text("Credit") })
                    androidx.compose.foundation.layout.Spacer(Modifier.size(4.dp))
                    androidx.compose.material3.FilterChip(selected = openingIsDebit, onClick = { openingIsDebit = true }, label = { Text("Debit") })
                }

                // Quick invoices: legacy dues (amount + note + date), no items — settle them from Payments.
                Text("Invoices", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp))
                if (existing == null) {
                    Text(
                        "Save the supplier first to attach invoices.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                } else {
                    outstandingPurchases.forEach { purchase ->
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    purchase.remarks.ifBlank { "Invoice" } + "  •  " + Format.rupee(purchase.balance),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(Format.date(purchase.dateMillis), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            }
                            IconButton(onClick = { onDeleteQuickPurchase(purchase) }) {
                                Icon(Icons.Filled.Delete, "Remove invoice", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    OutlinedButton(onClick = { showAddInvoice = true }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Text("  Add invoice")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(name, phone, address, gstin, state, openingAmount.toDoubleOrNull() ?: 0.0, openingIsDebit)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )

    if (showAddInvoice && existing != null) {
        QuickPurchaseDialog(
            onDismiss = { showAddInvoice = false },
            onConfirm = { amount, note, date ->
                onAddQuickPurchase(existing, amount, note, date)
                showAddInvoice = false
            }
        )
    }
}

@Composable
private fun QuickPurchaseDialog(onDismiss: () -> Unit, onConfirm: (Double, String, Long) -> Unit) {
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var dateMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    val context = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add invoice") },
        text = {
            Column {
                OutlinedTextField(
                    value = amount, onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Amount *") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = note, onValueChange = { note = it },
                    label = { Text("Note") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                OutlinedButton(
                    onClick = { pickSupplierDate(context, dateMillis) { dateMillis = it } },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) { Text("Date: ${Format.date(dateMillis)}") }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(amount.toDoubleOrNull() ?: 0.0, note, dateMillis) },
                enabled = (amount.toDoubleOrNull() ?: 0.0) > 0.0
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun BulkPurchaseDialog(count: Int, onDismiss: () -> Unit, onConfirm: (Double, String, Long) -> Unit) {
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var dateMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    val context = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add invoice to $count supplier(s)") },
        text = {
            Column {
                OutlinedTextField(
                    value = amount, onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Amount *") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = note, onValueChange = { note = it },
                    label = { Text("Note") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                OutlinedButton(
                    onClick = { pickSupplierDate(context, dateMillis) { dateMillis = it } },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) { Text("Date: ${Format.date(dateMillis)}") }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(amount.toDoubleOrNull() ?: 0.0, note, dateMillis) },
                enabled = (amount.toDoubleOrNull() ?: 0.0) > 0.0
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun pickSupplierDate(context: android.content.Context, current: Long, onPicked: (Long) -> Unit) {
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
