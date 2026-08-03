package com.billing.pos.ui.deliverynote

import android.Manifest
import android.app.Application
import android.app.DatePickerDialog
import android.content.Intent
import android.os.Build
import java.util.Calendar
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.Customer
import com.billing.pos.data.DeliveryNote
import com.billing.pos.data.DeliveryNoteItem
import com.billing.pos.data.DeliveryNoteWithItems
import com.billing.pos.data.Item
import com.billing.pos.data.Repository
import com.billing.pos.data.hasTwoUnits
import com.billing.pos.data.primaryChoice
import com.billing.pos.pdf.DeliveryNotePdf
import com.billing.pos.pdf.TablePdf
import com.billing.pos.data.XlsxWriter
import com.billing.pos.print.ThermalPrinter
import com.billing.pos.ui.billing.CartLine
import com.billing.pos.ui.billing.ItemPickerDialog
import com.billing.pos.ui.billing.NewItemDialog
import com.billing.pos.ui.billing.UnitPickDialog
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.ui.common.rememberPdfDownloader
import com.billing.pos.ui.common.rememberXlsxDownloader
import com.billing.pos.util.Format
import com.billing.pos.util.Permissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DeliveryNoteViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)

    val customers: StateFlow<List<Customer>> = repo.customers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val items: StateFlow<List<Item>> = repo.items.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val allBatches: StateFlow<List<com.billing.pos.data.ItemBatch>> = repo.itemBatches.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val notes: StateFlow<List<DeliveryNote>> = repo.deliveryNotes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    var selectedCustomer by mutableStateOf<Customer?>(null); private set
    val cart: SnapshotStateList<CartLine> = mutableStateListOf()
    var remarks by mutableStateOf("")
    var deliveryNo by mutableStateOf("DN-0001"); private set
    var dateMillis by mutableStateOf(System.currentTimeMillis())
    var editingId by mutableStateOf<Long?>(null); private set
    private var loaded = false

    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }

    init {
        viewModelScope.launch { repo.ensureDefaults(); deliveryNo = repo.nextDeliveryNo() }
    }

    val subTotal get() = cart.sumOf { it.base }
    val taxTotal get() = cart.sumOf { it.tax }
    val grandTotal get() = subTotal + taxTotal

    fun selectCustomer(c: Customer) { selectedCustomer = c }

    fun addCustomer(name: String, phone: String, address: String, onCreated: () -> Unit) {
        if (name.isBlank()) { message.value = "Enter customer name"; return }
        viewModelScope.launch {
            val id = repo.addCustomer(name, phone, address)
            selectedCustomer = Customer(id = id, name = name.trim(), phone = phone.trim(), address = address.trim())
            message.value = "Customer added"
            onCreated()
        }
    }

    fun addItem(form: com.billing.pos.ui.billing.NewItemForm, addToCart: Boolean, onCreated: () -> Unit) {
        if (form.name.isBlank()) { message.value = "Enter item name"; return }
        viewModelScope.launch {
            val id = repo.addItem(
                name = form.name, price = form.price, taxPercent = form.taxPercent,
                barcode = form.barcode, hsn = form.hsn, category = form.category,
                openingStock = form.openingStock, unit = form.unit, storeLocation = form.storeLocation,
                secondaryUnit = form.secondaryUnit, conversionFactor = form.conversionFactor,
                purchasePrice = form.purchasePrice
            )
            form.attachments.forEach { repo.addItemAttachment(it.copy(itemId = id)) }
            if (addToCart) addItemToCart(
                Item(
                    id = id, name = form.name.trim(), price = form.price, taxPercent = form.taxPercent,
                    barcode = form.barcode.trim(), hsn = form.hsn.trim(), category = form.category.trim(),
                    openingStock = form.openingStock, unit = form.unit,
                    secondaryUnit = form.secondaryUnit, conversionFactor = form.conversionFactor,
                    storeLocation = form.storeLocation.trim()
                )
            )
            message.value = "Item added"
            onCreated()
        }
    }

    fun addItemToCart(item: Item) = addItemWithUnit(item, item.primaryChoice())
    fun addItemWithUnit(item: Item, choice: com.billing.pos.data.UnitChoice) {
        val idx = cart.indexOfFirst { it.itemId == item.id && it.batchNo.isBlank() && it.unit == choice.unit && item.id != 0L }
        if (idx >= 0) cart[idx] = cart[idx].copy(qty = cart[idx].qty + 1)
        else cart.add(CartLine(item.id, item.name, choice.price, item.taxPercent, 1.0, unit = choice.unit, primaryPerUnit = choice.primaryPerUnit))
    }
    /** Delivers [qty] out of an existing batch; capped to what that batch actually holds. */
    fun addBatchLine(item: Item, batchNo: String, qty: Double, price: Double, available: Double) {
        val capped = qty.coerceAtMost(available).takeIf { it > 0 } ?: return
        cart.add(CartLine(item.id, item.name, price, item.taxPercent, capped, batchNo = batchNo.trim(), unit = item.unit))
    }
    fun setQty(i: Int, q: Double) { val l = cart.getOrNull(i) ?: return; if (q <= 0) cart.removeAt(i) else cart[i] = l.copy(qty = q) }
    fun setLinePrice(i: Int, p: Double) { val l = cart.getOrNull(i) ?: return; cart[i] = l.copy(price = p) }
    fun removeLine(i: Int) { cart.removeAt(i) }

    fun load(id: Long) {
        if (loaded || id <= 0) { loaded = true; return }
        loaded = true
        viewModelScope.launch {
            val d = repo.deliveryNoteById(id) ?: return@launch
            editingId = d.id; deliveryNo = d.deliveryNo; dateMillis = d.dateMillis; remarks = d.remarks
            selectedCustomer = customers.value.firstOrNull { it.id == d.customerId } ?: Customer(id = d.customerId, name = d.customerName)
            cart.clear()
            repo.deliveryNoteLines(id).forEach { cart.add(CartLine(it.itemId, it.name, it.price, it.taxPercent, it.qty, batchNo = it.batchNo, unit = it.unit)) }
        }
    }

    fun newVoucher() {
        cart.clear(); remarks = ""; dateMillis = System.currentTimeMillis(); editingId = null
        viewModelScope.launch { deliveryNo = repo.nextDeliveryNo() }
    }

    /** Converts the ticked delivery notes (must be one customer) into a prefilled sales bill. */
    fun convertToBill(ids: List<Long>, onReady: () -> Unit) {
        viewModelScope.launch {
            val chosen = notes.value.filter { it.id in ids }
            if (chosen.isEmpty()) { message.value = "Tick at least one delivery note"; return@launch }
            if (chosen.map { it.customerId }.distinct().size > 1) { message.value = "Tick delivery notes of one customer only"; return@launch }
            val custId = chosen.first().customerId
            val custName = chosen.first().customerName
            val agg = LinkedHashMap<String, com.billing.pos.ui.billing.BillPrefillLine>()
            chosen.forEach { d ->
                repo.deliveryNoteLines(d.id).forEach { l ->
                    val key = l.name.lowercase() + "|" + l.price + "|" + l.unit
                    val ex = agg[key]
                    agg[key] = if (ex == null) com.billing.pos.ui.billing.BillPrefillLine(l.itemId, l.name, l.qty, l.price, l.unit, l.taxPercent)
                    else ex.copy(qty = ex.qty + l.qty)
                }
            }
            if (agg.isEmpty()) { message.value = "These delivery notes have no items"; return@launch }
            com.billing.pos.ui.billing.OrderToBillLink.set(custId, custName, agg.values.toList(), "delivery", chosen.map { it.id })
            onReady()
        }
    }

    /** Saves the current voucher and returns it with its lines, for printing/PDF/share. */
    suspend fun saveCurrent(): DeliveryNoteWithItems? {
        val customer = selectedCustomer
        if (customer == null) { message.value = "Select a customer"; return null }
        if (cart.isEmpty()) { message.value = "Add at least one item"; return null }
        val d = DeliveryNote(
            id = editingId ?: 0, deliveryNo = deliveryNo, dateMillis = dateMillis,
            customerId = customer.id, customerName = customer.name, remarks = remarks.trim()
        )
        // Store the PRIMARY-unit quantity so stock decreases correctly.
        val lines = cart.map {
            DeliveryNoteItem(0, d.id, it.itemId, it.name, it.primaryQty, it.price, it.taxPercent, it.total, it.batchNo, it.unit)
        }
        val savedId = if (editingId != null) { repo.updateDeliveryNote(d, lines); d.id } else repo.saveDeliveryNote(d, lines).also { editingId = it }
        // Deduct batch stock for batch lines.
        cart.filter { it.batchNo.isNotBlank() && it.itemId > 0 }.forEach { repo.deductBatch(it.itemId, it.batchNo, it.primaryQty) }
        message.value = "Delivery note $deliveryNo saved"
        return DeliveryNoteWithItems(d.copy(id = savedId), lines.map { it.copy(deliveryId = savedId) })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeliveryNoteScreen(editId: Long?, onBack: () -> Unit, vm: DeliveryNoteViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(editId) { if (editId != null && editId > 0) vm.load(editId) }
    val customers by vm.customers.collectAsStateSafe()
    val items by vm.items.collectAsStateSafe()
    val allBatches by vm.allBatches.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()
    val requireBatch = remember { AppPrefs(context).requireItemBatch }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    var showItemPicker by remember { mutableStateOf(false) }
    var unitPickFor by remember { mutableStateOf<Item?>(null) }
    var batchFor by remember { mutableStateOf<Item?>(null) }
    var showNewCustomer by remember { mutableStateOf(false) }
    var showNewItem by remember { mutableStateOf(false) }
    var newItemName by remember { mutableStateOf("") }

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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (vm.editingId != null) "Edit Delivery Note" else "Delivery Note") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { vm.newVoucher() }) { Icon(Icons.Filled.Add, "New") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(vm.deliveryNo, fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = { pickDeliveryDate(context, vm.dateMillis) { vm.dateMillis = it } }) { Text(Format.date(vm.dateMillis)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                com.billing.pos.ui.common.CustomerPickField(
                    customers = customers,
                    selectedName = vm.selectedCustomer?.name ?: "",
                    onPick = { vm.selectCustomer(it) },
                    label = "Customer *",
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { showNewCustomer = true }) { Icon(Icons.Filled.PersonAdd, "New customer") }
            }

            Button(onClick = { showItemPicker = true }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Icon(Icons.Filled.Add, null); Text("  Add delivered item") }

            Card(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
                if (vm.cart.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No items delivered yet", color = MaterialTheme.colorScheme.outline) }
                else LazyColumn(Modifier.fillMaxWidth().padding(8.dp)) {
                    itemsIndexed(vm.cart) { i, line ->
                        var qtyText by remember(line.uid) { mutableStateOf(Format.qty(line.qty)) }
                        var priceText by remember(line.uid) { mutableStateOf(Format.money(line.price)) }
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(line.name + if (line.batchNo.isNotBlank()) "  [${line.batchNo}]" else "", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                                IconButton(onClick = { vm.removeLine(i) }) { Icon(Icons.Filled.Delete, "Remove", tint = MaterialTheme.colorScheme.error) }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(value = qtyText, onValueChange = { v -> val f = v.filter { it.isDigit() || it == '.' }; qtyText = f; f.toDoubleOrNull()?.let { if (it > 0) vm.setQty(i, it) } }, label = { Text("Qty") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.width(130.dp))
                                OutlinedTextField(value = priceText, onValueChange = { v -> val f = v.filter { it.isDigit() || it == '.' }; priceText = f; vm.setLinePrice(i, f.toDoubleOrNull() ?: 0.0) }, label = { Text("Rate") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.width(120.dp))
                            }
                            Divider(Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }

            OutlinedTextField(value = vm.remarks, onValueChange = { vm.remarks = it }, label = { Text("Remarks") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { scope.launch { vm.saveCurrent()?.let { onBack() } } }, modifier = Modifier.weight(1f)) { Text("Save (stock out)") }
                OutlinedButton(
                    onClick = { scope.launch { val saved = vm.saveCurrent() ?: return@launch; sharePdf(context, saved) } },
                    modifier = Modifier.weight(1f)
                ) { Icon(Icons.Filled.Share, null); Text("PDF") }
                OutlinedButton(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !ThermalPrinter.hasConnectPermission(context)) {
                            printPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                        } else scope.launch { doPrint(context, vm, snackbar) }
                    },
                    modifier = Modifier.weight(1f)
                ) { Icon(Icons.Filled.Print, null); Text("Print") }
            }
        }
    }

    if (showNewCustomer) {
        NewCustomerDialog(
            onDismiss = { showNewCustomer = false },
            onSave = { n, p, a -> vm.addCustomer(n, p, a) { showNewCustomer = false } }
        )
    }
    if (showNewItem) {
        NewItemDialog(
            onDismiss = { showNewItem = false },
            initialName = newItemName,
            categories = items.map { it.category }.filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase() },
            onSave = { form -> vm.addItem(form, addToCart = true) { showNewItem = false } }
        )
    }
    if (showItemPicker) {
        ItemPickerDialog(
            items = items,
            onDismiss = { showItemPicker = false },
            onPick = { picked ->
                showItemPicker = false
                when {
                    requireBatch -> batchFor = picked
                    picked.hasTwoUnits -> unitPickFor = picked
                    else -> vm.addItemToCart(picked)
                }
            },
            onNewItem = { q -> showItemPicker = false; newItemName = q; showNewItem = true }
        )
    }
    unitPickFor?.let { item ->
        UnitPickDialog(item = item, useCost = false, onPick = { choice -> unitPickFor = null; vm.addItemWithUnit(item, choice) }, onDismiss = { unitPickFor = null })
    }
    batchFor?.let { item ->
        DeliverBatchDialog(
            item = item,
            existing = allBatches.filter { it.itemId == item.id && it.quantity > 0.0 },
            onAdd = { no, qty, price, available -> vm.addBatchLine(item, no, qty, price, available); batchFor = null },
            onDismiss = { batchFor = null }
        )
    }
}

/** Delivering from stock: pick an existing batch with quantity on hand, then how much to send. */
@Composable
private fun DeliverBatchDialog(
    item: Item,
    existing: List<com.billing.pos.data.ItemBatch>,
    onAdd: (String, Double, Double, Double) -> Unit,
    onDismiss: () -> Unit
) {
    var batchNo by remember { mutableStateOf("") }
    var available by remember { mutableStateOf(0.0) }
    var qty by remember { mutableStateOf("") }
    var price by remember { mutableStateOf(Format.money(item.price)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Deliver batch — ${item.name}") },
        text = {
            Column {
                if (existing.isEmpty()) {
                    Text("No batch stock available for this item.", color = MaterialTheme.colorScheme.error)
                } else {
                    Text("Pick a batch:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    existing.forEach { b ->
                        Text(
                            "• ${b.batchNo}  (${Format.qty(b.quantity)} available)",
                            Modifier.clickable { batchNo = b.batchNo; available = b.quantity; qty = Format.qty(b.quantity) }.padding(vertical = 4.dp),
                            color = if (batchNo == b.batchNo) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = qty, onValueChange = { qty = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Qty *") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                        OutlinedTextField(value = price, onValueChange = { price = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Rate") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                    }
                    if ((qty.toDoubleOrNull() ?: 0.0) > available) {
                        Text("Only ${Format.qty(available)} available in this batch", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = batchNo.isNotBlank() && (qty.toDoubleOrNull() ?: 0.0) > 0.0 && (qty.toDoubleOrNull() ?: 0.0) <= available,
                onClick = { onAdd(batchNo, qty.toDoubleOrNull() ?: 0.0, price.toDoubleOrNull() ?: item.price, available) }
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun NewCustomerDialog(onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Customer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = phone, onValueChange = { phone = it.filter { c -> c.isDigit() } }, label = { Text("Phone") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("Address") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, phone, address) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private suspend fun doPrint(context: android.content.Context, vm: DeliveryNoteViewModel, snackbar: SnackbarHostState) {
    val saved = vm.saveCurrent() ?: return
    val company = AppPrefs(context).company
    val result = withContext(Dispatchers.IO) { runCatching { ThermalPrinter.printDeliveryNote(context, company, saved.note, saved.lines) } }
    result.onSuccess { snackbar.showSnackbar("Sent to printer") }.onFailure { snackbar.showSnackbar(it.message ?: "Print failed") }
}

private fun sharePdf(context: android.content.Context, saved: DeliveryNoteWithItems) {
    val company = AppPrefs(context).company
    val uri = DeliveryNotePdf.generate(context, company, saved.note, saved.lines)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share delivery note").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeliveryNoteListScreen(
    onBack: () -> Unit, onOpen: (Long) -> Unit, onNew: () -> Unit, onConvertToBill: () -> Unit,
    vm: DeliveryNoteViewModel = viewModel()
) {
    val context = LocalContext.current
    val notes by vm.notes.collectAsStateSafe()
    val customers by vm.customers.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    var voucherQuery by remember { mutableStateOf("") }
    var customerFilter by remember { mutableStateOf("") }
    var fromMillis by remember { mutableStateOf<Long?>(com.billing.pos.ui.common.oneMonthAgoMillis()) }
    var toMillis by remember { mutableStateOf<Long?>(System.currentTimeMillis()) }
    // Selecting delivery notes to convert to one sales bill. Off by default, plain list otherwise.
    var selecting by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<Long>() }

    val shown = notes.filter {
        (voucherQuery.isBlank() || it.deliveryNo.contains(voucherQuery.trim(), ignoreCase = true)) &&
            (customerFilter.isBlank() || it.customerName.equals(customerFilter, ignoreCase = true)) &&
            (fromMillis == null || it.dateMillis >= com.billing.pos.ui.common.startOfDay(fromMillis!!)) &&
            (toMillis == null || it.dateMillis <= com.billing.pos.ui.common.endOfDay(toMillis!!))
    }

    val downloadPdf = rememberPdfDownloader { msg -> vm.message.value = msg }
    val downloadXlsx = rememberXlsxDownloader { msg -> vm.message.value = msg }
    fun buildDeliveryNotesPdf(): java.io.File {
        val cols = listOf(
            TablePdf.Col("Delivery No", 1.6f), TablePdf.Col("Date", 1.2f),
            TablePdf.Col("Customer", 2f), TablePdf.Col("Status", 1.6f)
        )
        val data = shown.map { listOf(it.deliveryNo, Format.date(it.dateMillis), it.customerName, if (it.convertedBillNo.isNotBlank()) "Billed as ${it.convertedBillNo}" else "") }
        return TablePdf.generate(context, AppPrefs(context).company, "Delivery Notes", "Count: ${shown.size}", cols, data)
    }
    fun buildDeliveryNotesXlsx(): java.io.File {
        val rows = mutableListOf(XlsxWriter.row(XlsxWriter.text("Delivery No"), XlsxWriter.text("Date"), XlsxWriter.text("Customer"), XlsxWriter.text("Status")))
        shown.forEach { rows.add(XlsxWriter.row(XlsxWriter.text(it.deliveryNo), XlsxWriter.text(Format.date(it.dateMillis)), XlsxWriter.text(it.customerName), XlsxWriter.text(if (it.convertedBillNo.isNotBlank()) "Billed as ${it.convertedBillNo}" else ""))) }
        val file = java.io.File(java.io.File(context.cacheDir, "shared").apply { mkdirs() }, "delivery_notes.xlsx")
        XlsxWriter.write(file, "Delivery Notes", rows)
        return file
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (selecting) "${selected.size} selected" else "Delivery Notes") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (selecting) {
                        IconButton(onClick = { selecting = false; selected.clear() }) { Icon(Icons.Filled.Delete, "Cancel selection") }
                    } else {
                        IconButton(onClick = { downloadPdf { buildDeliveryNotesPdf() } }) { Icon(Icons.Filled.PictureAsPdf, "Download PDF") }
                        IconButton(onClick = { downloadXlsx { buildDeliveryNotesXlsx() } }) { Icon(Icons.Filled.GridOn, "Download Excel") }
                        IconButton(onClick = { selecting = true }) { Icon(Icons.Filled.Add, "Convert delivery notes to a bill") }
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
        floatingActionButton = { FloatingActionButton(onClick = onNew) { Icon(Icons.Filled.Add, "New delivery note") } }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            OutlinedTextField(
                value = voucherQuery, onValueChange = { voucherQuery = it },
                label = { Text("Voucher no") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            )
            com.billing.pos.ui.common.PartyFilterField(
                names = customers.map { it.name }, selected = customerFilter,
                onSelect = { customerFilter = it }, label = "Customer", allLabel = "All customers",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = { pickDeliveryDate(context, fromMillis ?: System.currentTimeMillis()) { fromMillis = it } }, modifier = Modifier.weight(1f)) {
                    Text("From: " + (fromMillis?.let { Format.date(it) } ?: "any"))
                }
                OutlinedButton(onClick = { pickDeliveryDate(context, toMillis ?: System.currentTimeMillis()) { toMillis = it } }, modifier = Modifier.weight(1f)) {
                    Text("To: " + (toMillis?.let { Format.date(it) } ?: "any"))
                }
                if (fromMillis != null || toMillis != null) TextButton(onClick = { fromMillis = null; toMillis = null }) { Text("Clear") }
            }
            Divider()
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No delivery notes match", color = MaterialTheme.colorScheme.outline) }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(shown, key = { it.id }) { d ->
                        val converted = d.convertedBillNo.isNotBlank()
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    if (selecting) { if (converted) Unit else if (d.id in selected) selected.remove(d.id) else selected.add(d.id) }
                                    else onOpen(d.id)
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (selecting) {
                                androidx.compose.material3.Checkbox(
                                    checked = d.id in selected, enabled = !converted,
                                    onCheckedChange = { if (d.id in selected) selected.remove(d.id) else selected.add(d.id) }
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    d.deliveryNo, fontWeight = FontWeight.SemiBold,
                                    color = if (converted) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    "${d.customerName} • ${Format.date(d.dateMillis)}" + (if (converted) "  •  Billed as ${d.convertedBillNo}" else ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (converted) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                        Divider()
                    }
                }
            }
            if (selecting) {
                Divider(thickness = 2.dp)
                Button(
                    onClick = { vm.convertToBill(selected.toList()) { selecting = false; selected.clear(); onConvertToBill() } },
                    enabled = selected.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(14.dp)
                ) { Text("Convert ${selected.size} delivery note(s) to a sales bill") }
            }
        }
    }
}

private fun pickDeliveryDate(context: android.content.Context, current: Long, onPicked: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = current }
    DatePickerDialog(context, { _, y, m, d -> onPicked(Calendar.getInstance().apply { set(y, m, d, 0, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis) }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
}
