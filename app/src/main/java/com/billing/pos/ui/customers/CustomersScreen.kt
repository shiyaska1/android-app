package com.billing.pos.ui.customers

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.flow.combine
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.Customer
import com.billing.pos.data.CustomerAttachment
import com.billing.pos.data.Repository
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

class CustomersViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)

    val customers: StateFlow<List<Customer>> =
        repo.customers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Each customer's net receivable (opening balance + due invoices − receipts), keyed by name. */
    val customerBalances: StateFlow<Map<String, Double>> =
        repo.customerBalances.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }

    private val prefs = AppPrefs(app)
    private val addedTypes = MutableStateFlow(prefs.customerTypes)

    /** Types offered: "General" first, then any saved or already in use, distinct. */
    val customerTypes: StateFlow<List<String>> = combine(customers, addedTypes) { list, added ->
        (listOf("General") + added + list.map { it.customerType })
            .map { it.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), listOf("General"))

    fun addType(name: String) {
        prefs.addCustomerType(name)
        addedTypes.value = prefs.customerTypes
    }

    fun save(
        existing: Customer?, name: String, phone: String, address: String, gstin: String, state: String,
        customerType: String, attachments: List<CustomerAttachment>,
        openingAmount: Double, openingIsDebit: Boolean, onDone: () -> Unit
    ) {
        if (name.isBlank()) { message.value = "Enter a name"; return }
        val type = customerType.trim().ifBlank { "General" }
        viewModelScope.launch {
            // A new customer has no id until it is saved, so the files are filed afterwards.
            val id = if (existing == null) repo.addCustomer(name, phone, address, gstin, type, state)
            else {
                repo.updateCustomer(existing.copy(name = name.trim(), phone = phone.trim(), address = address.trim(), gstin = gstin.trim(), customerType = type, state = state.trim()))
                existing.id
            }
            repo.replaceCustomerAttachments(id, attachments)
            repo.setCustomerOpeningBalance(name.trim(), openingAmount, openingIsDebit)
            message.value = "Saved"
            onDone()
        }
    }

    suspend fun attachmentsFor(customerId: Long) = repo.customerAttachmentsFor(customerId)

    suspend fun openingBalanceFor(customer: Customer) = repo.customerOpeningBalance(customer.name)

    fun quickInvoicesFor(customerId: Long) = repo.quickInvoicesForCustomer(customerId)

    fun addQuickInvoice(customer: Customer, amount: Double, note: String, dateMillis: Long) {
        if (amount <= 0.0) { message.value = "Enter a valid amount"; return }
        viewModelScope.launch {
            repo.addQuickInvoice(customer, amount, note, dateMillis)
            message.value = "Invoice added"
        }
    }

    fun deleteQuickInvoice(bill: com.billing.pos.data.Bill) {
        viewModelScope.launch { repo.deleteBill(bill); message.value = "Invoice removed" }
    }

    /** Attach the same quick invoice (amount + note + date) to several customers at once. */
    fun addInvoiceToCustomers(targets: List<Customer>, amount: Double, note: String, dateMillis: Long) {
        if (amount <= 0.0) { message.value = "Enter a valid amount"; return }
        if (targets.isEmpty()) { message.value = "Select at least one customer"; return }
        viewModelScope.launch {
            targets.forEach { repo.addQuickInvoice(it, amount, note, dateMillis) }
            message.value = "Invoice added to ${targets.size} customer(s)"
        }
    }

    /** Moves several customers to a different customer type at once. */
    fun moveToType(targets: List<Customer>, type: String) {
        if (targets.isEmpty()) { message.value = "Select at least one customer"; return }
        viewModelScope.launch {
            targets.forEach { repo.updateCustomer(it.copy(customerType = type)) }
            message.value = "Moved ${targets.size} customer(s) to $type"
        }
    }

    fun delete(customer: Customer) {
        viewModelScope.launch {
            val result = repo.deleteCustomer(customer)
            message.value = if (result.isSuccess) "Customer deleted"
            else result.exceptionOrNull()?.message ?: "Cannot delete"
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun CustomersScreen(
    onBack: () -> Unit,
    vm: CustomersViewModel = viewModel()
) {
    val snackbar = remember { SnackbarHostState() }
    val customers by vm.customers.collectAsStateSafe()
    val balances by vm.customerBalances.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()

    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    var showDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Customer?>(null) }
    var deleteFor by remember { mutableStateOf<Customer?>(null) }
    var historyFor by remember { mutableStateOf<Customer?>(null) }
    // Picking customers to share. Off by default so the list stays a plain list.
    var selecting by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<Long>() }
    var showBulkInvoice by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var query by remember { mutableStateOf("") }
    val customerTypes by vm.customerTypes.collectAsStateSafe()
    var typeFilter by remember { mutableStateOf("All") }   // "All" = every type
    // Marketing broadcast: pick media + text, then send to each ticked customer one by one.
    var marketing by remember { mutableStateOf(false) }
    val marketMedia = remember { mutableStateListOf<android.net.Uri>() }
    var marketText by remember { mutableStateOf("") }
    var sendQueue by remember { mutableStateOf<List<Customer>>(emptyList()) }
    var sendIndex by remember { mutableStateOf(0) }

    // One box searches every field, so a part of a phone number finds the customer too.
    val visible = customers.filter { c ->
        val q = query.trim()
        (q.isBlank() || listOf(c.name, c.phone, c.address, c.gstin).any { it.contains(q, ignoreCase = true) }) &&
            (typeFilter == "All" || c.customerType.equals(typeFilter, true))
    }
    var showMoveType by remember { mutableStateOf(false) }

    val downloadPdf = rememberPdfDownloader { msg -> vm.message.value = msg }
    val downloadXlsx = rememberXlsxDownloader { msg -> vm.message.value = msg }
    fun buildCustomersPdf(): java.io.File {
        val cols = listOf(
            TablePdf.Col("Name", 2f), TablePdf.Col("Type", 1.2f), TablePdf.Col("Phone", 1.4f),
            TablePdf.Col("Address", 2.2f), TablePdf.Col("GSTIN", 1.4f), TablePdf.Col("Balance", 1.2f, right = true)
        )
        val data = visible.map {
            listOf(it.name, it.customerType, it.phone, it.address, it.gstin, Format.money(balances[it.name] ?: 0.0))
        }
        return TablePdf.generate(context, AppPrefs(context).company, "Customers", "Count: ${visible.size}", cols, data)
    }
    fun buildCustomersXlsx(): java.io.File {
        val rows = mutableListOf(
            XlsxWriter.row(
                XlsxWriter.text("Name"), XlsxWriter.text("Type"), XlsxWriter.text("Phone"),
                XlsxWriter.text("Address"), XlsxWriter.text("GSTIN"), XlsxWriter.text("Balance")
            )
        )
        visible.forEach {
            rows.add(
                XlsxWriter.row(
                    XlsxWriter.text(it.name), XlsxWriter.text(it.customerType), XlsxWriter.text(it.phone),
                    XlsxWriter.text(it.address), XlsxWriter.text(it.gstin), XlsxWriter.num(balances[it.name] ?: 0.0)
                )
            )
        }
        val file = java.io.File(java.io.File(context.cacheDir, "shared").apply { mkdirs() }, "customers.xlsx")
        XlsxWriter.write(file, "Customers", rows)
        return file
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Customers") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    when {
                        marketing -> {
                            Text("${selected.size}", fontWeight = FontWeight.Bold)
                            IconButton(onClick = { marketing = false; selected.clear(); marketMedia.clear(); marketText = "" }) {
                                Icon(Icons.Filled.Close, "Cancel marketing")
                            }
                        }
                        selecting -> {
                            Text("${selected.size}", fontWeight = FontWeight.Bold)
                            IconButton(
                                onClick = { showBulkInvoice = true },
                                enabled = selected.isNotEmpty()
                            ) { Icon(Icons.Filled.ReceiptLong, "Add invoice to selected") }
                            IconButton(
                                onClick = { showMoveType = true },
                                enabled = selected.isNotEmpty()
                            ) { Icon(Icons.Filled.DriveFileMove, "Move to customer type") }
                            IconButton(
                                onClick = {
                                    val chosen = customers.filter { it.id in selected }
                                    if (chosen.isNotEmpty()) com.billing.pos.util.ShareText.share(context, customerShareText(chosen), "Customer details")
                                },
                                enabled = selected.isNotEmpty()
                            ) { Icon(Icons.Filled.Share, "Share selected") }
                            IconButton(onClick = { selecting = false; selected.clear() }) {
                                Icon(Icons.Filled.Close, "Cancel selection")
                            }
                        }
                        else -> {
                            IconButton(onClick = { downloadPdf { buildCustomersPdf() } }) {
                                Icon(Icons.Filled.PictureAsPdf, "Download PDF")
                            }
                            IconButton(onClick = { downloadXlsx { buildCustomersXlsx() } }) {
                                Icon(Icons.Filled.GridOn, "Download Excel")
                            }
                            IconButton(onClick = { marketing = true; selected.clear() }) {
                                Icon(Icons.Filled.Campaign, "WhatsApp marketing")
                            }
                            IconButton(onClick = { selecting = true }) {
                                Icon(Icons.Filled.Share, "Share customers")
                            }
                        }
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
            FloatingActionButton(onClick = { editing = null; showDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add customer")
            }
        }
    ) { pad ->
        val pickMode = selecting || marketing
        // Media pickers for the marketing broadcast; copied into cache so they can be shared.
        val galleryPick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
            uris.forEach { u -> com.billing.pos.marketing.MarketingMedia.copyIn(context, u)?.let(marketMedia::add) }
        }
        val filePick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            uris.forEach { u -> com.billing.pos.marketing.MarketingMedia.copyIn(context, u)?.let(marketMedia::add) }
        }
        // Camera: the helper returns a shareable content URI already, so add it straight in.
        val cameraCapture = com.billing.pos.ocr.rememberImageCamera { uri -> marketMedia.add(uri) }

        Column(Modifier.fillMaxSize().padding(pad)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search name, phone, address or GSTIN") },
            singleLine = true,
            trailingIcon = {
                if (query.isNotBlank()) IconButton(onClick = { query = "" }) {
                    Icon(Icons.Filled.Close, "Clear search")
                }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
        )
        // Filter by customer type, defaulting to All.
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            var filterMenu by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { filterMenu = true }) {
                    Text("Type: " + typeFilter)
                    Icon(Icons.Filled.ArrowDropDown, null)
                }
                androidx.compose.material3.DropdownMenu(expanded = filterMenu, onDismissRequest = { filterMenu = false }) {
                    androidx.compose.material3.DropdownMenuItem(text = { Text("All") }, onClick = { typeFilter = "All"; filterMenu = false })
                    customerTypes.forEach { t ->
                        androidx.compose.material3.DropdownMenuItem(text = { Text(t) }, onClick = { typeFilter = t; filterMenu = false })
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Text("${visible.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            items(visible, key = { it.id }) { c ->
                Row(
                    Modifier.fillMaxWidth()
                        .combinedClickable(
                            onClick = {
                                if (pickMode) {
                                    if (c.id in selected) selected.remove(c.id) else selected.add(c.id)
                                } else { editing = c; showDialog = true }
                            },
                            // Long press: the customer's full history, grouped.
                            onLongClick = { historyFor = c }
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (pickMode) {
                        androidx.compose.material3.Checkbox(
                            checked = c.id in selected,
                            onCheckedChange = {
                                if (c.id in selected) selected.remove(c.id) else selected.add(c.id)
                            }
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(c.name + if (c.isDefault) "  (default)" else "", fontWeight = FontWeight.Bold)
                        val sub = listOf(c.phone, c.address).filter { it.isNotBlank() }.joinToString("  •  ")
                        if (sub.isNotBlank()) {
                            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                    val balance = balances[c.name] ?: 0.0
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
                    if (!c.isDefault && !pickMode) {
                        IconButton(onClick = { deleteFor = c }) {
                            Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                Divider()
            }
        }

        // ---- Marketing composer: attach media, write text, then send one by one ----
        if (marketing) {
            Divider()
            Column(Modifier.fillMaxWidth().padding(12.dp).navigationBarsPadding()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { cameraCapture() }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.PhotoCamera, "Camera", Modifier.size(18.dp))
                    }
                    OutlinedButton(
                        onClick = { galleryPick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                        modifier = Modifier.weight(1f)
                    ) { Icon(Icons.Filled.PhotoLibrary, "Image/video", Modifier.size(18.dp)) }
                    OutlinedButton(onClick = { filePick.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.UploadFile, "File", Modifier.size(18.dp))
                    }
                    if (marketMedia.isNotEmpty()) {
                        Text("${marketMedia.size} file(s)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        IconButton(onClick = { marketMedia.clear() }) { Icon(Icons.Filled.Close, "Clear files", Modifier.size(18.dp)) }
                    }
                }
                OutlinedTextField(
                    value = marketText, onValueChange = { marketText = it },
                    label = { Text("Message") }, minLines = 2, maxLines = 4,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                )
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Send on the left, as requested.
                    Button(
                        onClick = {
                            val chosen = customers.filter { it.id in selected && it.phone.isNotBlank() }
                            if (chosen.isEmpty()) { vm.message.value = "Tick customers that have a phone number" }
                            else if (marketMedia.isEmpty() && marketText.isBlank()) { vm.message.value = "Add a file or a message" }
                            else { sendQueue = chosen; sendIndex = 0 }
                        }
                    ) { Icon(Icons.Filled.Chat, "Send on WhatsApp"); Text("  Send") }
                    Spacer(Modifier.weight(1f))
                    Text("${selected.size} selected", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                if (customers.any { it.id in selected && it.phone.isBlank() }) Text(
                    "Customers with no phone number are skipped.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline
                )
            }
        }
        }
    }

    // Sequential broadcast: one WhatsApp chat at a time, until finished or cancelled.
    if (sendQueue.isNotEmpty() && sendIndex in sendQueue.indices) {
        val cust = sendQueue[sendIndex]
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Send ${sendIndex + 1} of ${sendQueue.size}") },
            text = {
                Column {
                    Text(cust.name, fontWeight = FontWeight.Bold)
                    Text(cust.phone, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    Text(
                        "Open WhatsApp, pick this contact, send, then come back and tap Next. " +
                            "The files are attached; the message is also on the clipboard to paste if needed.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        com.billing.pos.marketing.MarketingMedia.sendToWhatsApp(context, cust.phone, marketText, marketMedia.toList())
                    }) { Text("Open WhatsApp") }
                    TextButton(onClick = {
                        if (sendIndex + 1 < sendQueue.size) sendIndex++ else { sendQueue = emptyList(); vm.message.value = "Broadcast finished" }
                    }) { Text(if (sendIndex + 1 < sendQueue.size) "Next" else "Done") }
                }
            },
            dismissButton = {
                TextButton(onClick = { sendQueue = emptyList() }) { Text("Cancel") }
            }
        )
    }

    if (showDialog) {
        CustomerDialog(
            existing = editing,
            types = customerTypes,
            onAddType = { vm.addType(it) },
            loadAttachments = { id -> vm.attachmentsFor(id) },
            loadOpeningBalance = { c -> vm.openingBalanceFor(c) },
            quickInvoicesFor = { id -> vm.quickInvoicesFor(id) },
            onAddQuickInvoice = { c, amt, note, date -> vm.addQuickInvoice(c, amt, note, date) },
            onDeleteQuickInvoice = { bill -> vm.deleteQuickInvoice(bill) },
            onMessage = { vm.message.value = it },
            onDismiss = { showDialog = false },
            onSave = { name, phone, addr, gstin, state, custType, atts, openingAmt, openingIsDebit ->
                vm.save(editing, name, phone, addr, gstin, state, custType, atts, openingAmt, openingIsDebit) { showDialog = false }
            }
        )
    }
    if (showBulkInvoice) {
        BulkInvoiceDialog(
            count = selected.size,
            onDismiss = { showBulkInvoice = false },
            onConfirm = { amount, note, date ->
                vm.addInvoiceToCustomers(customers.filter { it.id in selected }, amount, note, date)
                showBulkInvoice = false
                selecting = false
                selected.clear()
            }
        )
    }
    if (showMoveType) {
        MoveToTypeDialog(
            count = selected.size,
            types = customerTypes,
            onDismiss = { showMoveType = false },
            onConfirm = { type ->
                vm.moveToType(customers.filter { it.id in selected }, type)
                showMoveType = false
                selecting = false
                selected.clear()
            }
        )
    }
    deleteFor?.let { c ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Delete ${c.name}?") },
            text = { Text("Only customers with no invoices can be deleted.") },
            confirmButton = { TextButton(onClick = { vm.delete(c); deleteFor = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Cancel") } }
        )
    }
    historyFor?.let { c -> CustomerHistoryDialog(customer = c, onDismiss = { historyFor = null }) }
}

@Composable
private fun CustomerDialog(
    existing: Customer?,
    types: List<String>,
    onAddType: (String) -> Unit,
    loadAttachments: suspend (Long) -> List<CustomerAttachment>,
    loadOpeningBalance: suspend (Customer) -> Pair<Double, Boolean>,
    quickInvoicesFor: (Long) -> kotlinx.coroutines.flow.Flow<List<com.billing.pos.data.Bill>>,
    onAddQuickInvoice: (Customer, Double, String, Long) -> Unit,
    onDeleteQuickInvoice: (com.billing.pos.data.Bill) -> Unit,
    onMessage: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, String, String, List<CustomerAttachment>, Double, Boolean) -> Unit
) {
    val dialogContext = androidx.compose.ui.platform.LocalContext.current
    val gstEnabled = remember { com.billing.pos.data.AppPrefs(dialogContext).gstEnabled }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var phone by remember { mutableStateOf(existing?.phone ?: "") }
    var address by remember { mutableStateOf(existing?.address ?: "") }
    var gstin by remember { mutableStateOf(existing?.gstin ?: "") }
    var state by remember { mutableStateOf(existing?.state ?: "") }
    var stateMenu by remember { mutableStateOf(false) }
    var custType by remember { mutableStateOf(existing?.customerType ?: "General") }
    var typeMenu by remember { mutableStateOf(false) }
    var newType by remember { mutableStateOf(false) }
    var newTypeName by remember { mutableStateOf("") }
    val attachments = remember { mutableStateListOf<CustomerAttachment>() }
    LaunchedEffect(existing?.id) {
        attachments.clear()
        existing?.id?.let { if (it > 0) attachments.addAll(loadAttachments(it)) }
    }

    var openingAmount by remember { mutableStateOf("") }
    var openingIsDebit by remember { mutableStateOf(true) }
    LaunchedEffect(existing?.id) {
        if (existing != null) {
            val (amt, isDebit) = loadOpeningBalance(existing)
            openingAmount = if (amt != 0.0) Format.money(amt) else ""
            openingIsDebit = isDebit
        }
    }

    var showAddInvoice by remember { mutableStateOf(false) }
    val quickInvoices by remember(existing?.id) {
        existing?.id?.let { quickInvoicesFor(it) }
            ?: kotlinx.coroutines.flow.flowOf(emptyList<com.billing.pos.data.Bill>())
    }.collectAsState(initial = emptyList())
    val outstandingInvoices = quickInvoices.filter { it.balance > 0.001 }

    if (newType) {
        AlertDialog(
            onDismissRequest = { newType = false },
            title = { Text("New customer type") },
            text = {
                OutlinedTextField(value = newTypeName, onValueChange = { newTypeName = it }, label = { Text("Type name") }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    val t = newTypeName.trim()
                    if (t.isNotBlank()) { onAddType(t); custType = t }
                    newTypeName = ""; newType = false
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { newType = false }) { Text("Cancel") } }
        )
    }

    var drawName by remember { mutableStateOf(false) }
    if (drawName) {
        com.billing.pos.ui.common.HandwriteTextDialog(
            onResult = { if (it.isNotBlank()) name = it; drawName = false },
            onDismiss = { drawName = false }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New customer" else "Edit customer") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name *") }, singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = { drawName = true }) { Icon(Icons.Filled.Gesture, "Write name") }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                // Customer type: pick from the list, or "+" to add a new one.
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Box(Modifier.weight(1f)) {
                        OutlinedTextField(
                            readOnly = true, value = custType, onValueChange = {},
                            label = { Text("Customer type") }, singleLine = true,
                            trailingIcon = {
                                IconButton(onClick = { typeMenu = true }) { Icon(Icons.Filled.ArrowDropDown, "Pick type") }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        androidx.compose.material3.DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                            types.forEach { t ->
                                androidx.compose.material3.DropdownMenuItem(text = { Text(t) }, onClick = { custType = t; typeMenu = false })
                            }
                        }
                    }
                    IconButton(onClick = { newType = true }) {
                        Icon(Icons.Filled.Add, "New type", tint = MaterialTheme.colorScheme.primary)
                    }
                }
                OutlinedTextField(
                    value = phone, onValueChange = { phone = it.filter { c -> c.isDigit() } },
                    label = { Text("Phone / WhatsApp") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                OutlinedTextField(
                    value = gstin, onValueChange = { gstin = it },
                    label = { Text("GSTIN / TIN") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                if (gstEnabled) {
                    Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        OutlinedTextField(
                            readOnly = true, value = state.ifBlank { "Select state (for IGST vs CGST+SGST)" },
                            onValueChange = {}, label = { Text("State") }, singleLine = true,
                            trailingIcon = { IconButton(onClick = { stateMenu = true }) { Icon(Icons.Filled.ArrowDropDown, "Pick state") } },
                            modifier = Modifier.fillMaxWidth()
                        )
                        androidx.compose.material3.DropdownMenu(expanded = stateMenu, onDismissRequest = { stateMenu = false }) {
                            com.billing.pos.data.IndianStates.NAMES.forEach { s ->
                                androidx.compose.material3.DropdownMenuItem(text = { Text(s) }, onClick = { state = s; stateMenu = false })
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = address, onValueChange = { address = it },
                    label = { Text("Address") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )

                // Opening balance: what this customer already owed (or was owed) before this app.
                Text("Opening balance", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp))
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = openingAmount,
                        onValueChange = { openingAmount = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Amount") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.size(8.dp))
                    androidx.compose.material3.FilterChip(selected = openingIsDebit, onClick = { openingIsDebit = true }, label = { Text("Debit") })
                    Spacer(Modifier.size(4.dp))
                    androidx.compose.material3.FilterChip(selected = !openingIsDebit, onClick = { openingIsDebit = false }, label = { Text("Credit") })
                }

                // Quick invoices: legacy dues (amount + note + date), no items — settle them from Receipts.
                Text("Invoices", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp))
                if (existing == null) {
                    Text(
                        "Save the customer first to attach invoices.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                } else {
                    outstandingInvoices.forEach { bill ->
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    bill.remarks.ifBlank { "Invoice" } + "  •  " + Format.rupee(bill.balance),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(Format.date(bill.dateMillis), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            }
                            IconButton(onClick = { onDeleteQuickInvoice(bill) }) {
                                Icon(Icons.Filled.Delete, "Remove invoice", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    OutlinedButton(onClick = { showAddInvoice = true }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Text("  Add invoice")
                    }
                }

                CustomerAttachments(attachments, onMessage)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(name, phone, address, gstin, state, custType, attachments.toList(), openingAmount.toDoubleOrNull() ?: 0.0, openingIsDebit)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )

    if (showAddInvoice && existing != null) {
        QuickInvoiceDialog(
            onDismiss = { showAddInvoice = false },
            onConfirm = { amount, note, date ->
                onAddQuickInvoice(existing, amount, note, date)
                showAddInvoice = false
            }
        )
    }
}

@Composable
private fun QuickInvoiceDialog(onDismiss: () -> Unit, onConfirm: (Double, String, Long) -> Unit) {
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
                    onClick = { pickCustomerDate(context, dateMillis) { dateMillis = it } },
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
private fun BulkInvoiceDialog(count: Int, onDismiss: () -> Unit, onConfirm: (Double, String, Long) -> Unit) {
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var dateMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    val context = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add invoice to $count customer(s)") },
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
                    onClick = { pickCustomerDate(context, dateMillis) { dateMillis = it } },
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
private fun MoveToTypeDialog(count: Int, types: List<String>, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var type by remember { mutableStateOf(types.firstOrNull { it != "All" } ?: "General") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move $count customer(s) to type") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                types.filter { it != "All" }.forEach { t ->
                    Row(
                        Modifier.fillMaxWidth().clickable { type = t }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.RadioButton(selected = type == t, onClick = { type = t })
                        Text(t)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(type) }) { Text("Move") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun pickCustomerDate(context: android.content.Context, current: Long, onPicked: (Long) -> Unit) {
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

/** The chosen customers as plain text, ready to paste into WhatsApp. */
private fun customerShareText(customers: List<Customer>): String = buildString {
    customers.forEachIndexed { i, c ->
        if (i > 0) appendLine()
        appendLine(c.name)
        if (c.phone.isNotBlank()) appendLine("Phone: " + c.phone)
        if (c.address.isNotBlank()) appendLine("Address: " + c.address)
        if (c.gstin.isNotBlank()) appendLine("GSTIN: " + c.gstin)
    }
}
