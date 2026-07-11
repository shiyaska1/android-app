package com.billing.pos.ui.billing

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.auth.Session
import com.billing.pos.data.BillWithItems
import com.billing.pos.data.PaymentMethod
import com.billing.pos.data.hasTwoUnits
import com.billing.pos.data.primaryChoice
import com.billing.pos.data.unitChoices
import com.billing.pos.pdf.InvoicePdf
import com.billing.pos.pdf.ThermalPdf
import com.billing.pos.print.ThermalPrinter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.billing.pos.util.Format
import com.billing.pos.util.Permissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillingScreen(
    editBillId: Long? = null,
    onBack: () -> Unit = {},
    onOpenReports: () -> Unit,
    onOpenInvoices: () -> Unit,
    onOpenUsers: () -> Unit,
    onOpenDiary: () -> Unit,
    onOpenReceipts: () -> Unit,
    onOpenExpenses: () -> Unit,
    onOpenCashbook: () -> Unit,
    onOpenCustomers: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBackup: () -> Unit,
    onLogout: () -> Unit,
    vm: BillingViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(editBillId) { if (editBillId != null && editBillId > 0) vm.startEditing(editBillId) }

    val customers by vm.customers.collectAsStateSafe()
    val items by vm.items.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()

    var showNewCustomer by remember { mutableStateOf(false) }
    var showNewItem by remember { mutableStateOf(false) }
    var showItemPicker by remember { mutableStateOf(false) }
    var showCustomLine by remember { mutableStateOf(false) }
    var showWhatsApp by remember { mutableStateOf(false) }
    var showBillInfo by remember { mutableStateOf(false) }
    var showNotes by remember { mutableStateOf(false) }
    var showHandwrite by remember { mutableStateOf(false) }
    var capturedPhotoUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var showPhotoOptions by remember { mutableStateOf(false) }
    var showSetTotal by remember { mutableStateOf(false) }
    var ocrReview by remember { mutableStateOf<List<com.billing.pos.ocr.ScannedItem>?>(null) }
    val requireBatch = remember { com.billing.pos.data.AppPrefs(context).requireItemBatch }
    var batchPickFor by remember { mutableStateOf<com.billing.pos.data.Item?>(null) }
    var sizePickFor by remember { mutableStateOf<com.billing.pos.data.Item?>(null) }
    var unitPickFor by remember { mutableStateOf<com.billing.pos.data.Item?>(null) }
    // Unit chosen for the item currently going through the batch picker.
    var pendingChoice by remember { mutableStateOf<com.billing.pos.data.UnitChoice?>(null) }
    // Cart-line index whose name is being edited (tap the name to rename with autocomplete).
    var editNameFor by remember { mutableStateOf<Int?>(null) }

    val prefs = remember { com.billing.pos.data.AppPrefs(context) }
    var licensed by remember { mutableStateOf(prefs.licensed) }
    var showBuy by remember { mutableStateOf(false) }

    // View-only: an existing invoice opened by a user without edit permission.
    val readOnly = vm.editingBillId != null && !Session.canEdit

    // Show one-off messages.
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }

    // Bluetooth permission → print when granted.
    val printPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) scope.launch { doPrint(context, vm, snackbar) }
        else scope.launch {
            val res = snackbar.showSnackbar(
                "Allow 'Nearby devices' permission to print",
                actionLabel = "Settings",
                duration = SnackbarDuration.Long
            )
            if (res == SnackbarResult.ActionPerformed) Permissions.openAppSettings(context)
        }
    }

    // Barcode scan → add matching item to the cart.
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { vm.onBarcodeScanned(it) }
    }

    // Attach documents to the invoice.
    val attachPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> vm.addAttachmentUris(context, uris) }

    // Photo bill: take a picture, then choose picture-bill or OCR-into-cart.
    val photoCapture = com.billing.pos.ocr.rememberImageCamera { uri ->
        capturedPhotoUri = uri; showPhotoOptions = true
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (vm.editingBillId != null) "Edit Bill" else "New Bill") },
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
                    IconButton(onClick = { showBillInfo = !showBillInfo }) {
                        Icon(Icons.Filled.Info, contentDescription = "Bill no & date",
                            tint = if (showBillInfo) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f))
                    }
                    IconButton(onClick = { showNotes = !showNotes }) {
                        Icon(Icons.Filled.EditNote, contentDescription = "Remarks & attachments",
                            tint = if (showNotes) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f))
                    }
                    IconButton(onClick = { vm.newBill() }) {
                        Icon(Icons.Filled.NoteAdd, contentDescription = "New bill")
                    }
                    var menu by remember { mutableStateOf(false) }
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Invoices") },
                            onClick = { menu = false; onOpenInvoices() }
                        )
                        DropdownMenuItem(
                            text = { Text("Sales Report") },
                            onClick = { menu = false; onOpenReports() }
                        )
                        if (Session.canViewReceipt) {
                            DropdownMenuItem(
                                text = { Text("Receipts") },
                                onClick = { menu = false; onOpenReceipts() }
                            )
                        }
                        if (Session.canViewPayment) {
                            DropdownMenuItem(
                                text = { Text("Payments / Expenses") },
                                onClick = { menu = false; onOpenExpenses() }
                            )
                        }
                        if (Session.canViewCashbook) {
                            DropdownMenuItem(
                                text = { Text("Cash Book") },
                                onClick = { menu = false; onOpenCashbook() }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Customers") },
                            onClick = { menu = false; onOpenCustomers() }
                        )
                        DropdownMenuItem(
                            text = { Text("My Diary") },
                            onClick = { menu = false; onOpenDiary() }
                        )
                        if (Session.canManageUsers) {
                            DropdownMenuItem(
                                text = { Text("Manage Users") },
                                onClick = { menu = false; onOpenUsers() }
                            )
                            DropdownMenuItem(
                                text = { Text("Company Settings") },
                                onClick = { menu = false; onOpenSettings() }
                            )
                        }
                        if (Session.canExport || Session.canImport) {
                            DropdownMenuItem(
                                text = { Text("Backup & Restore") },
                                onClick = { menu = false; onOpenBackup() }
                            )
                        }
                        if (!licensed) {
                            DropdownMenuItem(
                                text = { Text("Buy app") },
                                onClick = { menu = false; showBuy = true }
                            )
                        }
                        Divider()
                        DropdownMenuItem(
                            text = { Text("Logout (${Session.current?.username ?: ""})") },
                            onClick = { menu = false; onLogout() }
                        )
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
            // --- Customer (searchable) + New + Payment, all one line ---
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                var expanded by remember { mutableStateOf(false) }
                var custQuery by remember { mutableStateOf("") }
                val focusManager = LocalFocusManager.current
                LaunchedEffect(vm.selectedCustomer?.id) { custQuery = vm.selectedCustomer?.name ?: "" }
                val filteredCustomers = remember(custQuery, customers) {
                    if (custQuery.isBlank()) customers
                    else customers.filter { it.name.contains(custQuery, ignoreCase = true) || it.phone.contains(custQuery) }
                }
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = Modifier.weight(1.5f)) {
                    OutlinedTextField(
                        value = custQuery, onValueChange = { custQuery = it; expanded = true },
                        label = { Text("Customer") }, placeholder = { Text("Search") }, singleLine = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth().onFocusChanged { fs ->
                            if (fs.isFocused) { custQuery = ""; expanded = true } else custQuery = vm.selectedCustomer?.name ?: ""
                        }
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        filteredCustomers.forEach { c ->
                            DropdownMenuItem(
                                text = { Text(c.name + if (c.isDefault) "  (default)" else "") },
                                onClick = { vm.selectCustomer(c); custQuery = c.name; expanded = false; focusManager.clearFocus() }
                            )
                        }
                        if (filteredCustomers.isEmpty()) DropdownMenuItem(text = { Text("No match") }, onClick = { expanded = false })
                    }
                }
                IconButton(onClick = { showNewCustomer = true }) { Icon(Icons.Filled.PersonAdd, "New customer") }
                var payExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = payExpanded, onExpandedChange = { payExpanded = it }, modifier = Modifier.weight(1f)) {
                    OutlinedTextField(
                        readOnly = true, value = vm.payment.label, onValueChange = {},
                        label = { Text("Pay") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(payExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = payExpanded, onDismissRequest = { payExpanded = false }) {
                        PaymentMethod.values().forEach { m ->
                            DropdownMenuItem(text = { Text(m.label) }, onClick = { vm.selectPayment(m); payExpanded = false })
                        }
                    }
                }
            }

            // --- Item actions (one line) ---
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                ToolAction(Icons.Filled.Add, "Item") { showItemPicker = true }
                ToolAction(Icons.Filled.Dialpad, "Price") { showCustomLine = true }
                ToolAction(Icons.Filled.NoteAdd, "New") { showNewItem = true }
                ToolAction(Icons.Filled.Gesture, "Write") { showHandwrite = true }
                ToolAction(Icons.Filled.PhotoCamera, "Photo") { photoCapture() }
                ToolAction(Icons.Filled.QrCodeScanner, "Scan") {
                    scanLauncher.launch(ScanOptions().setPrompt("Scan item barcode").setBeepEnabled(true).setOrientationLocked(false))
                }
            }

            Spacer(Modifier.padding(2.dp))

            // --- Cart grid (scrollable, editable rate) ---
            Card(Modifier.weight(1f).fillMaxWidth()) {
              Column(Modifier.fillMaxSize()) {
                val billPhotos = vm.editAttachments.filter { it.mime.startsWith("image/") }
                if (billPhotos.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        billPhotos.forEach { att -> BillPhotoThumb(att) { vm.removeBillAttachment(att) } }
                    }
                    Divider()
                }
                if (vm.cart.isEmpty()) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text(
                            if (billPhotos.isEmpty()) "No items added" else "Picture bill — enter the total below",
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                } else {
                    LazyColumn(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        itemsIndexed(vm.cart, key = { _, line -> line.uid }) { index, line ->
                            var priceText by remember(line.uid) { mutableStateOf(Format.money(line.price)) }
                            var qtyText by remember(line.uid, line.qty) { mutableStateOf(Format.qty(line.qty)) }
                            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        line.name,
                                        Modifier.weight(1f).clickable { editNameFor = index },
                                        fontWeight = FontWeight.SemiBold, maxLines = 1
                                    )
                                    if (line.batchNo.isNotBlank()) Text("B:${line.batchNo}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    if (line.taxPercent > 0) Text("  +${Format.money(line.taxPercent)}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                    IconButton(onClick = { vm.removeLine(index) }) { Icon(Icons.Filled.Delete, "Remove", tint = MaterialTheme.colorScheme.error) }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = priceText,
                                        onValueChange = { v -> val f = v.filter { it.isDigit() || it == '.' }; priceText = f; vm.setLinePrice(index, f.toDoubleOrNull() ?: 0.0) },
                                        label = { Text("Rate") }, singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                        modifier = Modifier.width(116.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    IconButton(onClick = { vm.changeQty(index, -1.0) }) { Icon(Icons.Filled.Remove, "Decrease") }
                                    OutlinedTextField(
                                        value = qtyText,
                                        onValueChange = { v ->
                                            val f = v.filter { it.isDigit() || it == '.' }
                                            qtyText = f
                                            f.toDoubleOrNull()?.let { if (it > 0) vm.setQty(index, it) }
                                        },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                        modifier = Modifier.width(64.dp)
                                    )
                                    IconButton(onClick = { vm.changeQty(index, 1.0) }) { Icon(Icons.Filled.Add, "Increase") }
                                    Spacer(Modifier.weight(1f))
                                    Text(Format.rupee(line.total), fontWeight = FontWeight.Bold)
                                }
                                Divider()
                            }
                        }
                    }
                }
              }
            }

            Spacer(Modifier.padding(2.dp))

            // --- Totals (one line) + grand total ---
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("Sub Total", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            Text(Format.rupee(vm.subTotal + vm.taxTotal), fontWeight = FontWeight.SemiBold)
                        }
                        OutlinedTextField(
                            value = vm.additionalChargeText,
                            onValueChange = { vm.setAdditionalCharge(it.filter { c -> c.isDigit() || c == '.' }) },
                            singleLine = true, label = { Text("Add.") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.width(96.dp)
                        )
                        OutlinedTextField(
                            value = vm.discountText,
                            onValueChange = { vm.setDiscount(it.filter { c -> c.isDigit() || c == '.' }) },
                            singleLine = true, label = { Text("Disc.") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.width(96.dp)
                        )
                    }
                    Divider(Modifier.padding(vertical = 6.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("GRAND TOTAL", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        if (vm.isManualTotal) {
                            Text("  (manual)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.weight(1f))
                        Text(Format.rupee(vm.grandTotal), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        IconButton(onClick = { showSetTotal = true }) {
                            Icon(Icons.Filled.Edit, contentDescription = "Type total manually", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            Spacer(Modifier.padding(4.dp))

            if (readOnly) {
                Text(
                    "View only — you don't have permission to edit invoices.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.padding(2.dp))
            }

            // --- Optional: Bill No + editable Date (toggle) ---
            if (showBillInfo) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Label("Bill No")
                        Text(vm.billNo, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(onClick = { pickBillDate(context, vm.dateMillis) { vm.updateDate(it) } }) {
                        Text("Date: ${Format.date(vm.dateMillis)}")
                    }
                }
            }

            // --- Optional: Remarks + Attach documents on one line (toggle) ---
            if (showNotes) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    OutlinedTextField(
                        value = vm.remarks,
                        onValueChange = { vm.updateRemarks(it) },
                        label = { Text("Remarks (prints only if filled)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { runCatching { attachPicker.launch(arrayOf("*/*")) } }) {
                        Icon(Icons.Filled.AttachFile, contentDescription = "Attach document")
                    }
                }
                if (vm.editAttachments.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        vm.editAttachments.forEach { att ->
                            AssistChip(
                                onClick = { com.billing.pos.bills.BillAttachmentStore.open(context, att) },
                                label = { Text(att.name, maxLines = 1) },
                                trailingIcon = {
                                    Icon(Icons.Filled.Close, contentDescription = "Remove",
                                        modifier = Modifier.size(16.dp).clickable { vm.removeBillAttachment(att) })
                                }
                            )
                        }
                    }
                }
            }

            if (readOnly) {
                Spacer(Modifier.padding(2.dp))
                Text(
                    "View only — you don't have permission to edit invoices.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // --- Actions: Save / PDF / WhatsApp / Print, one line ---
            Spacer(Modifier.padding(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { scope.launch { vm.saveCurrent() } },
                    enabled = !readOnly, contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.weight(1f)
                ) { Text("Save") }
                OutlinedButton(
                    onClick = { scope.launch { vm.saveCurrent()?.let { sharePdf(context, it) } } },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.weight(1f)
                ) { Text("PDF") }
                Button(
                    onClick = {
                        scope.launch {
                            if (vm.needsWhatsAppInfo()) showWhatsApp = true
                            else {
                                val saved = vm.saveCurrent() ?: return@launch
                                sendWhatsApp(context, vm.selectedCustomer?.phone ?: "", saved) { scope.launch { snackbar.showSnackbar(it) } }
                            }
                        }
                    },
                    enabled = !readOnly, contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.weight(1f)
                ) { Icon(Icons.Filled.Share, null, modifier = Modifier.size(18.dp)) }
                Button(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !ThermalPrinter.hasConnectPermission(context)) {
                            printPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                        } else scope.launch { doPrint(context, vm, snackbar) }
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.weight(1f)
                ) { Icon(Icons.Filled.Print, null, modifier = Modifier.size(18.dp)); Text(" Print", maxLines = 1) }
            }
        }
    }

    if (showBuy) {
        BuyDialog(
            deviceId = com.billing.pos.data.License.deviceId(context),
            onDismiss = { showBuy = false },
            onActivated = { prefs.licensed = true; licensed = true; showBuy = false }
        )
    }
    if (showWhatsApp) {
        WhatsAppDialog(
            defaultName = vm.selectedCustomer?.name?.takeIf { it != "Cash Customer" } ?: "",
            onDismiss = { showWhatsApp = false },
            onSend = { name, number ->
                showWhatsApp = false
                scope.launch {
                    val saved = vm.prepareWhatsApp(name, number) ?: return@launch
                    sendWhatsApp(context, vm.selectedCustomer?.phone ?: number, saved) {
                        scope.launch { snackbar.showSnackbar(it) }
                    }
                }
            }
        )
    }

    if (showNewCustomer) {
        NewCustomerDialog(
            onDismiss = { showNewCustomer = false },
            onSave = { n, p, a -> vm.addCustomer(n, p, a) { showNewCustomer = false } }
        )
    }
    if (showNewItem) {
        val itemCategories = remember(items) { items.map { it.category }.filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase() } }
        NewItemDialog(
            onDismiss = { showNewItem = false },
            categories = itemCategories,
            onSave = { form -> vm.addItem(form, addToCart = true) { showNewItem = false } }
        )
    }
    if (showCustomLine) {
        CustomLineDialog(
            onDismiss = { showCustomLine = false },
            onAdd = { desc, price, tax, saveToMaster, sellingPrice -> vm.addCustomLine(desc, price, tax, saveToMaster, sellingPrice) }
        )
    }
    if (showItemPicker) {
        val stockByItem by vm.stockByItem.collectAsStateSafe()
        val allSizes by vm.allSizes.collectAsStateSafe()
        val allBatches by vm.allBatches.collectAsStateSafe()
        ItemPickerDialog(
            items = items,
            onDismiss = { showItemPicker = false },
            onPick = { picked ->
                showItemPicker = false
                when {
                    allSizes.any { s -> s.itemId == picked.id } -> sizePickFor = picked
                    // Two different units → ask which one. Same unit → no prompt.
                    picked.hasTwoUnits -> unitPickFor = picked
                    requireBatch && allBatches.any { b -> b.itemId == picked.id } -> {
                        pendingChoice = picked.primaryChoice(); batchPickFor = picked
                    }
                    else -> vm.addItemToCart(picked)
                }
            },
            onNewItem = { showItemPicker = false; showNewItem = true },
            stockByItem = stockByItem
        )
    }
    unitPickFor?.let { item ->
        val allBatches by vm.allBatches.collectAsStateSafe()
        UnitPickDialog(
            item = item,
            onPick = { choice ->
                unitPickFor = null
                if (requireBatch && allBatches.any { it.itemId == item.id }) {
                    pendingChoice = choice; batchPickFor = item
                } else vm.addItemWithUnit(item, choice)
            },
            onDismiss = { unitPickFor = null }
        )
    }
    editNameFor?.let { idx ->
        val current = vm.cart.getOrNull(idx)
        if (current != null) EditLineNameDialog(
            initial = current.name,
            allNames = items.map { it.name },
            onDone = { newName -> vm.setLineName(idx, newName); editNameFor = null },
            onDismiss = { editNameFor = null }
        ) else editNameFor = null
    }
    sizePickFor?.let { item ->
        val allSizes by vm.allSizes.collectAsStateSafe()
        SaleSizePickDialog(
            item = item,
            sizes = allSizes.filter { it.itemId == item.id },
            onPick = { size -> vm.addItemWithSize(item, size); sizePickFor = null },
            onDismiss = { sizePickFor = null }
        )
    }
    batchPickFor?.let { item ->
        val allBatches by vm.allBatches.collectAsStateSafe()
        SaleBatchPickDialog(
            item = item,
            batches = allBatches.filter { it.itemId == item.id },
            onPick = { batch ->
                vm.addItemWithBatch(item, batch, pendingChoice ?: item.primaryChoice())
                pendingChoice = null; batchPickFor = null
            },
            onDismiss = { pendingChoice = null; batchPickFor = null },
            onNoBatch = {
                vm.addItemWithUnit(item, pendingChoice ?: item.primaryChoice())
                pendingChoice = null; batchPickFor = null
            }
        )
    }
    if (showHandwrite) {
        HandwriteQuickBillDialog(
            vm = vm,
            onDismiss = { showHandwrite = false },
            onReview = { list -> showHandwrite = false; ocrReview = list }
        )
    }

    // After taking a photo: choose picture-bill or OCR-into-cart.
    if (showPhotoOptions && capturedPhotoUri != null) {
        val uri = capturedPhotoUri!!
        AlertDialog(
            onDismissRequest = { showPhotoOptions = false },
            title = { Text("Use this photo as…") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("• Picture bill — attach the photo as the item and type the total yourself.")
                    Text("• Read items — read each line, add them to the bill (new names are saved to items), and total automatically.")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.addAttachmentUris(context, listOf(uri))
                    showPhotoOptions = false
                    showSetTotal = true
                }) { Text("Picture bill") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPhotoOptions = false
                    scope.launch {
                        val lines = com.billing.pos.ocr.TextOcr.lines(context, uri)
                        ocrReview = com.billing.pos.ocr.ItemListParser.parse(lines)
                    }
                }) { Text("Read items") }
            }
        )
    }

    // Type the grand total manually (photo / no-line bills).
    if (showSetTotal) {
        var totalText by remember { mutableStateOf(vm.manualTotalText) }
        AlertDialog(
            onDismissRequest = { showSetTotal = false },
            title = { Text("Enter total amount") },
            text = {
                OutlinedTextField(
                    value = totalText,
                    onValueChange = { totalText = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Grand total") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = { TextButton(onClick = { vm.setManualTotal(totalText); showSetTotal = false }) { Text("Set") } },
            dismissButton = {
                TextButton(onClick = { vm.clearManualTotal(); showSetTotal = false }) { Text("Auto") }
            }
        )
    }

    // Verify + edit OCR-read items before adding them to the bill.
    ocrReview?.let { parsed ->
        BillOcrReviewDialog(
            initial = parsed,
            masterItems = items,
            onDismiss = { ocrReview = null },
            onConfirm = { edited -> vm.addOcrItemsToCart(edited); ocrReview = null }
        )
    }
}

private suspend fun doPrint(
    context: android.content.Context,
    vm: BillingViewModel,
    snackbar: SnackbarHostState
) {
    val saved = vm.saveCurrent() ?: return
    val company = com.billing.pos.data.AppPrefs(context).company
    val result = withContext(Dispatchers.IO) {
        runCatching { ThermalPrinter.printBill(context, company, saved.bill, saved.lines) }
    }
    result.onSuccess { snackbar.showSnackbar("Sent to printer") }
        .onFailure { snackbar.showSnackbar(it.message ?: "Print failed") }
}

private fun pickBillDate(context: android.content.Context, current: Long, onPicked: (Long) -> Unit) {
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

private fun sharePdf(context: android.content.Context, saved: BillWithItems) {
    val company = com.billing.pos.data.AppPrefs(context).company
    val uri = InvoicePdf.make(context, company, saved.bill, saved.lines)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share invoice").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    })
}

/** Opens WhatsApp for [phone] with the invoice PDF attached (user taps send). */
private fun sendWhatsApp(
    context: android.content.Context,
    phone: String,
    saved: BillWithItems,
    onInfo: (String) -> Unit
) {
    val company = com.billing.pos.data.AppPrefs(context).company
    val uri = InvoicePdf.make(context, company, saved.bill, saved.lines)
    val digits = phone.filter { it.isDigit() }

    fun tryPackage(pkg: String?): Boolean {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            if (digits.isNotEmpty()) putExtra("jid", "$digits@s.whatsapp.net")
            if (pkg != null) setPackage(pkg)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    if (tryPackage("com.whatsapp")) return
    if (tryPackage("com.whatsapp.w4b")) return
    // WhatsApp not installed — fall back to a generic share chooser.
    val chooser = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(
            Intent.createChooser(chooser, "Share invoice").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure { onInfo("Could not open WhatsApp") }
    onInfo("WhatsApp not found — shared via chooser")
}

@Composable
private fun BuyDialog(
    deviceId: String,
    onDismiss: () -> Unit,
    onActivated: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var key by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Buy / Activate app") },
        text = {
            Column {
                Text(
                    "Purchase to remove the trial limit. Send us your Device ID, then enter the activation key.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Text("Device ID", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 6.dp))
                Text(deviceId, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                OutlinedButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(deviceId)) }) { Text("Copy") }
                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(com.billing.pos.data.License.BUY_URL))
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) { Text("Open buy link") }
                OutlinedTextField(
                    value = key, onValueChange = { key = it; error = null },
                    label = { Text("License key") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp)) }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (com.billing.pos.data.License.isValid(deviceId, key)) onActivated()
                else error = "Invalid activation key"
            }) { Text("Activate") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun WhatsAppDialog(
    defaultName: String,
    onDismiss: () -> Unit,
    onSend: (name: String, number: String) -> Unit
) {
    var name by remember { mutableStateOf(defaultName) }
    var number by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send on WhatsApp") },
        text = {
            Column {
                Text(
                    "Saved to customer list for next time.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Customer name (optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                OutlinedTextField(
                    value = number,
                    onValueChange = { number = it.filter { c -> c.isDigit() || c == '+' } },
                    label = { Text("WhatsApp number (with country code)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSend(name, number) }, enabled = number.isNotBlank()) { Text("Save & Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ToolAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick) { Icon(icon, contentDescription = label) }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

/** Pick which size/variant to sell (each has its own price). */
@Composable
internal fun SaleSizePickDialog(
    item: com.billing.pos.data.Item,
    sizes: List<com.billing.pos.data.ItemSize>,
    onPick: (com.billing.pos.data.ItemSize) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Size — ${item.name}") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                items(sizes, key = { it.id }) { s ->
                    Column(Modifier.fillMaxWidth().clickable { onPick(s) }.padding(vertical = 12.dp)) {
                        Text(s.name, fontWeight = FontWeight.SemiBold)
                        Text(Format.rupee(s.price), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        Divider(Modifier.padding(top = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

/**
 * Rename a cart line with type-ahead suggestions from the item master. Typing a name that
 * isn't in the master keeps it as a new item (created on save).
 */
@Composable
internal fun EditLineNameDialog(
    initial: String,
    allNames: List<String>,
    onDone: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Start cleared and ready for a new item; the current name shows as a placeholder.
    var text by remember { mutableStateOf("") }
    // Camera → OCR → name.
    val scanName = com.billing.pos.ocr.rememberNameScanner { if (it.isNotBlank()) text = it }
    // Gallery image → OCR → name.
    val galleryOcr = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) scope.launch {
            val t = com.billing.pos.ocr.TextOcr.singleLine(context, uri)
            if (t.isNotBlank()) text = t
        }
    }
    val suggestions = remember(text, allNames) {
        val q = text.trim()
        if (q.isBlank()) emptyList()
        else allNames.distinct().filter { it.contains(q, ignoreCase = true) && !it.equals(q, ignoreCase = true) }.take(6)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Item name") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text("Name") },
                    placeholder = { if (initial.isNotBlank()) Text(initial) },
                    // Tall + multiline so full OCR text is visible and easy to trim.
                    singleLine = false, minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { scanName() }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.PhotoCamera, null); Text(" Photo")
                    }
                    OutlinedButton(
                        onClick = {
                            galleryOcr.launch(
                                androidx.activity.result.PickVisualMediaRequest(
                                    androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        },
                        modifier = Modifier.weight(1f)
                    ) { Icon(Icons.Filled.PhotoLibrary, null); Text(" Gallery") }
                }
                // Plain column (not lazy) — the list is capped at 6 and lazy keys can
                // collide when the master has duplicate names.
                suggestions.forEach { s ->
                    Text(
                        s,
                        Modifier.fillMaxWidth().clickable { onDone(s) }.padding(vertical = 10.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Divider()
                }
            }
        },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onDone(text) else onDismiss() }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Pick which unit to bill an item in. Only shown when the item's primary and secondary
 * units differ; the secondary rate is the primary price / conversion factor, rounded to 2dp.
 */
@Composable
internal fun UnitPickDialog(
    item: com.billing.pos.data.Item,
    onPick: (com.billing.pos.data.UnitChoice) -> Unit,
    onDismiss: () -> Unit
) {
    val choices = item.unitChoices()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Unit — ${item.name}") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "1 ${item.unit} = ${Format.qty(item.conversionFactor)} ${item.secondaryUnit}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.height(8.dp))
                choices.forEach { ch ->
                    Column(Modifier.fillMaxWidth().clickable { onPick(ch) }.padding(vertical = 10.dp)) {
                        Text(ch.unit, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Rate ${Format.rupee(ch.price)} per ${ch.unit}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Divider(Modifier.padding(top = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Pick which batch to sell from (shows each batch's stock + expiry). */
@Composable
internal fun SaleBatchPickDialog(
    item: com.billing.pos.data.Item,
    batches: List<com.billing.pos.data.ItemBatch>,
    onPick: (com.billing.pos.data.ItemBatch) -> Unit,
    onDismiss: () -> Unit,
    onNoBatch: (() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Batch — ${item.name}") },
        text = {
            if (batches.isEmpty()) {
                Text("No batch stock for this item. Add a batch in the item form, purchase, or import.", color = MaterialTheme.colorScheme.outline)
            } else {
                val fifo = batches.sortedBy { if (it.expiryMillis <= 0) Long.MAX_VALUE else it.expiryMillis }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                    items(fifo, key = { it.id }) { b ->
                        val out = b.quantity <= 0.0
                        Column(
                            Modifier.fillMaxWidth().clickable(enabled = !out) { onPick(b) }.padding(vertical = 10.dp)
                        ) {
                            Text(b.batchNo.ifBlank { "(no batch no)" }, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Stock ${Format.qty(b.quantity)}" + (if (b.expiryMillis > 0) "   •   Exp ${Format.date(b.expiryMillis)}" else "") + (if (out) "   •   OUT OF STOCK" else ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (out) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
                            )
                            Divider(Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            // Batch is optional: let the user add the item without picking one.
            if (onNoBatch != null) TextButton(onClick = onNoBatch) { Text("Add without batch") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

/** A thumbnail of a photo attached as the "picture bill", with a remove badge. */
@Composable
private fun BillPhotoThumb(att: com.billing.pos.data.BillAttachment, onRemove: () -> Unit) {
    val context = LocalContext.current
    val bmp = com.billing.pos.ui.common.rememberThumbnail(att.path, 300)
    Box(Modifier.size(96.dp)) {
        Box(
            Modifier.size(96.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                .clickable { com.billing.pos.bills.BillAttachmentStore.open(context, att) },
            contentAlignment = Alignment.Center
        ) {
            if (bmp != null) {
                androidx.compose.foundation.Image(
                    bmp, contentDescription = att.name,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(Icons.Filled.PhotoCamera, null, tint = MaterialTheme.colorScheme.primary)
            }
        }
        Box(
            Modifier.align(Alignment.TopEnd).padding(2.dp).size(22.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(11.dp))
                .background(androidx.compose.ui.graphics.Color(0xAA000000)).clickable { onRemove() },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Close, contentDescription = "Remove", tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(15.dp))
        }
    }
}

@Composable
private fun Label(text: String) =
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)

@Composable
private fun TotalRow(label: String, value: String) =
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(value)
    }
