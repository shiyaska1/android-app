package com.billing.pos.ui.materialreceipt

import android.Manifest
import android.app.Application
import android.app.DatePickerDialog
import android.content.Intent
import android.os.Build
import java.util.Calendar
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.billing.pos.data.Item
import com.billing.pos.data.MaterialReceipt
import com.billing.pos.data.MaterialReceiptItem
import com.billing.pos.data.MaterialReceiptWithItems
import com.billing.pos.data.PurchaseQuotation
import com.billing.pos.data.Repository
import com.billing.pos.data.Supplier
import com.billing.pos.data.XlsxWriter
import com.billing.pos.data.costRate
import com.billing.pos.data.hasTwoUnits
import com.billing.pos.data.primaryCostChoice
import com.billing.pos.pdf.MaterialReceiptPdf
import com.billing.pos.pdf.TablePdf
import com.billing.pos.print.ThermalPrinter
import com.billing.pos.ui.billing.CartLine
import com.billing.pos.ui.billing.ItemPickerDialog
import com.billing.pos.ui.billing.NewItemDialog
import com.billing.pos.ui.billing.UnitPickDialog
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.ui.common.LpoPickerField
import com.billing.pos.ui.common.endOfDay
import com.billing.pos.ui.common.oneMonthAgoMillis
import com.billing.pos.ui.common.rememberPdfDownloader
import com.billing.pos.ui.common.rememberXlsxDownloader
import com.billing.pos.ui.common.startOfDay
import com.billing.pos.util.Format
import com.billing.pos.util.Permissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MaterialReceiptViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)

    val suppliers: StateFlow<List<Supplier>> = repo.suppliers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val items: StateFlow<List<Item>> = repo.items.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val allBatches: StateFlow<List<com.billing.pos.data.ItemBatch>> = repo.itemBatches.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val lpos: StateFlow<List<PurchaseQuotation>> = repo.purchaseQuotations.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val receipts: StateFlow<List<MaterialReceipt>> = repo.materialReceipts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    var selectedSupplier by mutableStateOf<Supplier?>(null); private set
    val cart: SnapshotStateList<CartLine> = mutableStateListOf()
    var lpoId by mutableStateOf(0L); private set
    var lpoNo by mutableStateOf("")
    var remarks by mutableStateOf("")
    var receiptNo by mutableStateOf("MRN-0001"); private set
    var dateMillis by mutableStateOf(System.currentTimeMillis())
    var editingId by mutableStateOf<Long?>(null); private set
    private var loaded = false

    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }

    init {
        viewModelScope.launch { repo.ensureDefaults(); receiptNo = repo.nextMrnNo() }
        viewModelScope.launch { suppliers.collect { list -> if (selectedSupplier == null && list.isNotEmpty()) selectedSupplier = list.firstOrNull { it.isDefault } ?: list.first() } }
    }

    val subTotal get() = cart.sumOf { it.base }
    val taxTotal get() = cart.sumOf { it.tax }
    val grandTotal get() = subTotal + taxTotal

    fun selectSupplier(s: Supplier) { selectedSupplier = s }

    fun addSupplier(name: String, phone: String, address: String, onCreated: () -> Unit) {
        if (name.isBlank()) { message.value = "Enter supplier name"; return }
        viewModelScope.launch {
            selectedSupplier = repo.addSupplier(name, phone, address)
            message.value = "Supplier added"
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
    fun setLpo(l: PurchaseQuotation) {
        lpoId = l.id; lpoNo = l.lpoNo
        selectedSupplier = suppliers.value.firstOrNull { it.id == l.supplierId } ?: Supplier(l.supplierId, l.supplierName)
        viewModelScope.launch {
            val lines = repo.purchaseQuotationLines(l.id)
            cart.clear()
            lines.forEach {
                val itemId = items.value.firstOrNull { m -> m.name.equals(it.name, ignoreCase = true) }?.id ?: it.itemId
                cart.add(CartLine(itemId, it.name, it.price, it.taxPercent, it.qty, unit = it.unit))
            }
            if (lines.isEmpty()) message.value = "That LPO has no items"
        }
    }

    fun addItemToCart(item: Item) = addItemWithUnit(item, item.primaryCostChoice())
    fun addItemWithUnit(item: Item, choice: com.billing.pos.data.UnitChoice) {
        val idx = cart.indexOfFirst { it.itemId == item.id && it.batchNo.isBlank() && it.unit == choice.unit && item.id != 0L }
        if (idx >= 0) cart[idx] = cart[idx].copy(qty = cart[idx].qty + 1)
        else cart.add(CartLine(item.id, item.name, choice.price, item.taxPercent, 1.0, unit = choice.unit, primaryPerUnit = choice.primaryPerUnit))
    }
    fun addBatchLine(item: Item, batchNo: String, expiryMillis: Long, qty: Double, price: Double) {
        cart.add(CartLine(item.id, item.name, price, item.taxPercent, qty.takeIf { it > 0 } ?: 1.0, batchNo = batchNo.trim(), batchExpiry = expiryMillis, unit = item.unit))
    }
    fun setQty(i: Int, q: Double) { val l = cart.getOrNull(i) ?: return; if (q <= 0) cart.removeAt(i) else cart[i] = l.copy(qty = q) }
    fun setLinePrice(i: Int, p: Double) { val l = cart.getOrNull(i) ?: return; cart[i] = l.copy(price = p) }
    fun removeLine(i: Int) { cart.removeAt(i) }

    fun load(id: Long) {
        if (loaded || id <= 0) { loaded = true; return }
        loaded = true
        viewModelScope.launch {
            val m = repo.materialReceiptById(id) ?: return@launch
            editingId = m.id; receiptNo = m.receiptNo; dateMillis = m.dateMillis
            lpoId = m.lpoId; lpoNo = m.lpoNo; remarks = m.remarks
            selectedSupplier = suppliers.value.firstOrNull { it.id == m.supplierId } ?: Supplier(m.supplierId, m.supplierName)
            cart.clear()
            repo.materialReceiptLines(id).forEach { cart.add(CartLine(it.itemId, it.name, it.price, it.taxPercent, it.qty, batchNo = it.batchNo, unit = it.unit)) }
        }
    }

    fun newVoucher() {
        cart.clear(); lpoId = 0; lpoNo = ""; remarks = ""; dateMillis = System.currentTimeMillis(); editingId = null
        viewModelScope.launch { receiptNo = repo.nextMrnNo() }
    }

    /** Converts the ticked material receipts (must be one supplier) into a prefilled purchase. */
    fun convertToPurchase(ids: List<Long>, onReady: () -> Unit) {
        viewModelScope.launch {
            val chosen = receipts.value.filter { it.id in ids }
            if (chosen.isEmpty()) { message.value = "Tick at least one material receipt"; return@launch }
            if (chosen.map { it.supplierId }.distinct().size > 1) { message.value = "Tick receipts of one supplier only"; return@launch }
            val supId = chosen.first().supplierId
            val supName = chosen.first().supplierName
            val agg = LinkedHashMap<String, com.billing.pos.ui.billing.BillPrefillLine>()
            chosen.forEach { r ->
                repo.materialReceiptLines(r.id).forEach { l ->
                    val key = l.name.lowercase() + "|" + l.price + "|" + l.unit
                    val ex = agg[key]
                    agg[key] = if (ex == null) com.billing.pos.ui.billing.BillPrefillLine(l.itemId, l.name, l.qty, l.price, l.unit, l.taxPercent)
                    else ex.copy(qty = ex.qty + l.qty)
                }
            }
            if (agg.isEmpty()) { message.value = "These material receipts have no items"; return@launch }
            com.billing.pos.ui.purchase.MaterialReceiptToPurchaseLink.set(supId, supName, agg.values.toList(), chosen.map { it.id })
            onReady()
        }
    }

    /** Saves the current voucher and returns it with its lines, for printing/PDF/share. */
    suspend fun saveCurrent(): com.billing.pos.data.MaterialReceiptWithItems? {
        val supplier = selectedSupplier
        if (supplier == null) { message.value = "Select a supplier"; return null }
        if (cart.isEmpty()) { message.value = "Add at least one item"; return null }
        for (i in cart.indices) {
            val l = cart[i]
            if (l.itemId == 0L && l.name.isNotBlank()) {
                val existing = items.value.firstOrNull { it.name.equals(l.name, true) }
                val id = existing?.id ?: repo.addItem(l.name, 0.0, 0.0, unit = l.unit.ifBlank { "PCS" }, purchasePrice = l.price)
                cart[i] = l.copy(itemId = id)
            }
        }
        val m = MaterialReceipt(
            id = editingId ?: 0, receiptNo = receiptNo, dateMillis = dateMillis,
            supplierId = supplier.id, supplierName = supplier.name, lpoId = lpoId, lpoNo = lpoNo, remarks = remarks.trim()
        )
        // Store the PRIMARY-unit quantity so stock increases correctly.
        val lines = cart.map {
            MaterialReceiptItem(0, m.id, it.itemId, it.name, it.primaryQty, it.price, it.taxPercent, it.total, it.batchNo, it.unit)
        }
        val savedId = if (editingId != null) { repo.updateMaterialReceipt(m, lines); m.id } else repo.saveMaterialReceipt(m, lines).also { editingId = it }
        // Receive batch stock (add to existing / create new) for batch lines.
        cart.filter { it.batchNo.isNotBlank() && it.itemId > 0 }.forEach { repo.receiveBatch(it.itemId, it.batchNo, it.batchExpiry, it.primaryQty) }
        message.value = "Material receipt $receiptNo saved"
        return com.billing.pos.data.MaterialReceiptWithItems(m.copy(id = savedId), lines.map { it.copy(receiptId = savedId) })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaterialReceiptScreen(editId: Long?, onBack: () -> Unit, vm: MaterialReceiptViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(editId) { if (editId != null && editId > 0) vm.load(editId) }
    val suppliers by vm.suppliers.collectAsStateSafe()
    val items by vm.items.collectAsStateSafe()
    val lpos by vm.lpos.collectAsStateSafe()
    val allBatches by vm.allBatches.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()
    val requireBatch = remember { AppPrefs(context).requireItemBatch }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    var showItemPicker by remember { mutableStateOf(false) }
    var unitPickFor by remember { mutableStateOf<Item?>(null) }
    var batchFor by remember { mutableStateOf<Item?>(null) }
    var showNewSupplier by remember { mutableStateOf(false) }
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
                title = { Text(if (vm.editingId != null) "Edit Receipt" else "Material Receipt") },
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
                Text(vm.receiptNo, fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = { pickDate(context, vm.dateMillis) { vm.dateMillis = it } }) { Text(Format.date(vm.dateMillis)) }
            }
            var supMenu by remember { mutableStateOf(false) }
            var supQuery by remember { mutableStateOf(vm.selectedSupplier?.name ?: "") }
            LaunchedEffect(vm.selectedSupplier?.id) { if (!supMenu) supQuery = vm.selectedSupplier?.name ?: "" }
            val supMatches = remember(supQuery, suppliers) {
                if (supQuery.isBlank()) suppliers else suppliers.filter { it.name.contains(supQuery, ignoreCase = true) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                // Plain field + inline list, not ExposedDropdownMenuBox — that popup-based
                // combobox fights with the keyboard on real devices (typing the first
                // character snaps the field back to the old value and blocks further typing).
                Column(Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = supQuery, onValueChange = { supQuery = it; supMenu = true },
                        label = { Text("Supplier *") },
                        modifier = Modifier.fillMaxWidth()
                            .onFocusChanged { fs ->
                                if (fs.isFocused) { supQuery = ""; supMenu = true }
                                else { supMenu = false; supQuery = vm.selectedSupplier?.name ?: "" }
                            }
                    )
                    if (supMenu) {
                        com.billing.pos.ui.common.SearchPickList(
                            items = supMatches,
                            itemLabel = { it.name },
                            onPick = { s -> vm.selectSupplier(s); supQuery = s.name; supMenu = false }
                        )
                    }
                }
                IconButton(onClick = { showNewSupplier = true }) { Icon(Icons.Filled.PersonAdd, "New supplier") }
            }
            LpoPickerField(lpos = lpos, supplierId = vm.selectedSupplier?.id ?: 0L, selectedNo = vm.lpoNo, onPick = { vm.setLpo(it) })

            Button(onClick = { showItemPicker = true }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Icon(Icons.Filled.Add, null); Text("  Add received item") }

            Card(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
                if (vm.cart.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No items received yet", color = MaterialTheme.colorScheme.outline) }
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
                                OutlinedTextField(value = qtyText, onValueChange = { v -> val f = v.filter { it.isDigit() || it == '.' }; qtyText = f; f.toDoubleOrNull()?.let { if (it > 0) vm.setQty(i, it) } }, label = { Text("Received qty") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.width(130.dp))
                                OutlinedTextField(value = priceText, onValueChange = { v -> val f = v.filter { it.isDigit() || it == '.' }; priceText = f; vm.setLinePrice(i, f.toDoubleOrNull() ?: 0.0) }, label = { Text("Rate") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.width(120.dp))
                            }
                            Divider(Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }

            OutlinedTextField(value = vm.remarks, onValueChange = { vm.remarks = it }, label = { Text("Remarks") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { scope.launch { vm.saveCurrent()?.let { onBack() } } }, modifier = Modifier.weight(1f)) { Text("Save (stock in)") }
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

    if (showNewSupplier) {
        NewSupplierDialog(
            onDismiss = { showNewSupplier = false },
            onSave = { n, p, a -> vm.addSupplier(n, p, a) { showNewSupplier = false } }
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
        UnitPickDialog(item = item, useCost = true, onPick = { choice -> unitPickFor = null; vm.addItemWithUnit(item, choice) }, onDismiss = { unitPickFor = null })
    }
    batchFor?.let { item ->
        ReceiptBatchDialog(
            item = item,
            existing = allBatches.filter { it.itemId == item.id },
            onAdd = { no, exp, qty, price -> vm.addBatchLine(item, no, exp, qty, price); batchFor = null },
            onDismiss = { batchFor = null }
        )
    }
}

/** Batch capture when receiving: batch no, expiry, qty and rate. */
@Composable
private fun ReceiptBatchDialog(
    item: Item,
    existing: List<com.billing.pos.data.ItemBatch>,
    onAdd: (String, Long, Double, Double) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var batchNo by remember { mutableStateOf("") }
    var expiry by remember { mutableStateOf(0L) }
    var qty by remember { mutableStateOf("1") }
    var price by remember { mutableStateOf(if (item.costRate > 0) Format.money(item.costRate) else "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Receive batch — ${item.name}") },
        text = {
            Column {
                if (existing.isNotEmpty()) {
                    Text("Existing:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    existing.take(4).forEach { b ->
                        Text("• ${b.batchNo}  (${Format.qty(b.quantity)})", Modifier.clickable { batchNo = b.batchNo; expiry = b.expiryMillis }.padding(vertical = 2.dp), color = MaterialTheme.colorScheme.primary)
                    }
                }
                OutlinedTextField(value = batchNo, onValueChange = { batchNo = it }, label = { Text("Batch no *") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                OutlinedButton(onClick = { pickDate(context, if (expiry > 0) expiry else System.currentTimeMillis()) { expiry = it } }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text(if (expiry > 0) "Expiry: ${Format.date(expiry)}" else "Set expiry (optional)")
                }
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = qty, onValueChange = { qty = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Qty *") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                    OutlinedTextField(value = price, onValueChange = { price = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Rate") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = batchNo.isNotBlank() && (qty.toDoubleOrNull() ?: 0.0) > 0.0, onClick = { onAdd(batchNo.trim(), expiry, qty.toDoubleOrNull() ?: 0.0, price.toDoubleOrNull() ?: item.costRate) }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun NewSupplierDialog(onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Supplier") },
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

private suspend fun doPrint(context: android.content.Context, vm: MaterialReceiptViewModel, snackbar: SnackbarHostState) {
    val saved = vm.saveCurrent() ?: return
    val company = AppPrefs(context).company
    val result = withContext(Dispatchers.IO) { runCatching { ThermalPrinter.printMaterialReceipt(context, company, saved.receipt, saved.lines) } }
    result.onSuccess { snackbar.showSnackbar("Sent to printer") }.onFailure { snackbar.showSnackbar(it.message ?: "Print failed") }
}

private fun sharePdf(context: android.content.Context, saved: MaterialReceiptWithItems) {
    val company = AppPrefs(context).company
    val uri = MaterialReceiptPdf.generate(context, company, saved.receipt, saved.lines)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share material receipt").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaterialReceiptListScreen(
    onBack: () -> Unit, onOpen: (Long) -> Unit, onNew: () -> Unit, onConvertToPurchase: () -> Unit,
    vm: MaterialReceiptViewModel = viewModel()
) {
    val context = LocalContext.current
    val receipts by vm.receipts.collectAsStateSafe()
    val suppliers by vm.suppliers.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    var voucherQuery by remember { mutableStateOf("") }
    var supplierFilter by remember { mutableStateOf("") }
    var fromMillis by remember { mutableStateOf<Long?>(oneMonthAgoMillis()) }
    var toMillis by remember { mutableStateOf<Long?>(System.currentTimeMillis()) }
    // Selecting receipts to convert to one purchase entry. Off by default, plain list otherwise.
    var selecting by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<Long>() }

    val shown = receipts.filter {
        (voucherQuery.isBlank() || it.receiptNo.contains(voucherQuery.trim(), ignoreCase = true)) &&
            (supplierFilter.isBlank() || it.supplierName.equals(supplierFilter, ignoreCase = true)) &&
            (fromMillis == null || it.dateMillis >= startOfDay(fromMillis!!)) &&
            (toMillis == null || it.dateMillis <= endOfDay(toMillis!!))
    }

    val downloadPdf = rememberPdfDownloader { msg -> vm.message.value = msg }
    val downloadXlsx = rememberXlsxDownloader { msg -> vm.message.value = msg }
    fun buildReceiptsPdf(): java.io.File {
        val cols = listOf(
            TablePdf.Col("Receipt No", 1.6f), TablePdf.Col("Date", 1.2f),
            TablePdf.Col("Supplier", 2f), TablePdf.Col("Status", 1.8f)
        )
        val data = shown.map {
            listOf(it.receiptNo, Format.date(it.dateMillis), it.supplierName, if (it.convertedPurchaseNo.isNotBlank()) "Purchased as ${it.convertedPurchaseNo}" else "")
        }
        return TablePdf.generate(context, AppPrefs(context).company, "Material Receipts", "Count: ${shown.size}", cols, data)
    }
    fun buildReceiptsXlsx(): java.io.File {
        val rows = mutableListOf(XlsxWriter.row(XlsxWriter.text("Receipt No"), XlsxWriter.text("Date"), XlsxWriter.text("Supplier"), XlsxWriter.text("Status")))
        shown.forEach {
            rows.add(XlsxWriter.row(
                XlsxWriter.text(it.receiptNo), XlsxWriter.text(Format.date(it.dateMillis)), XlsxWriter.text(it.supplierName),
                XlsxWriter.text(if (it.convertedPurchaseNo.isNotBlank()) "Purchased as ${it.convertedPurchaseNo}" else "")
            ))
        }
        val file = java.io.File(java.io.File(context.cacheDir, "shared").apply { mkdirs() }, "material_receipts.xlsx")
        XlsxWriter.write(file, "Material Receipts", rows)
        return file
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (selecting) "${selected.size} selected" else "Material Receipts") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (selecting) {
                        IconButton(onClick = { selecting = false; selected.clear() }) { Icon(Icons.Filled.Delete, "Cancel selection") }
                    } else {
                        IconButton(onClick = { selecting = true }) { Icon(Icons.Filled.Add, "Convert receipts to a purchase") }
                        IconButton(onClick = { downloadPdf { buildReceiptsPdf() } }) { Icon(Icons.Filled.PictureAsPdf, "Download PDF") }
                        IconButton(onClick = { downloadXlsx { buildReceiptsXlsx() } }) { Icon(Icons.Filled.GridOn, "Download Excel") }
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
        floatingActionButton = { FloatingActionButton(onClick = onNew) { Icon(Icons.Filled.Add, "New receipt") } }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            OutlinedTextField(
                value = voucherQuery, onValueChange = { voucherQuery = it },
                label = { Text("Voucher no") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            )
            com.billing.pos.ui.common.PartyFilterField(
                names = suppliers.map { it.name }, selected = supplierFilter,
                onSelect = { supplierFilter = it }, label = "Supplier", allLabel = "All suppliers",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = { pickDate(context, fromMillis ?: System.currentTimeMillis()) { fromMillis = it } }, modifier = Modifier.weight(1f)) {
                    Text("From: " + (fromMillis?.let { Format.date(it) } ?: "any"))
                }
                OutlinedButton(onClick = { pickDate(context, toMillis ?: System.currentTimeMillis()) { toMillis = it } }, modifier = Modifier.weight(1f)) {
                    Text("To: " + (toMillis?.let { Format.date(it) } ?: "any"))
                }
                if (fromMillis != null || toMillis != null) TextButton(onClick = { fromMillis = null; toMillis = null }) { Text("Clear") }
            }
            Divider()
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No material receipts match", color = MaterialTheme.colorScheme.outline) }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(shown, key = { it.id }) { r ->
                        val converted = r.convertedPurchaseNo.isNotBlank()
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    if (selecting) { if (converted) Unit else if (r.id in selected) selected.remove(r.id) else selected.add(r.id) }
                                    else onOpen(r.id)
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (selecting) {
                                androidx.compose.material3.Checkbox(
                                    checked = r.id in selected, enabled = !converted,
                                    onCheckedChange = { if (r.id in selected) selected.remove(r.id) else selected.add(r.id) }
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    r.receiptNo + (if (r.lpoNo.isNotBlank()) "  •  vs ${r.lpoNo}" else ""),
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (converted) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    "${r.supplierName} • ${Format.date(r.dateMillis)}" + (if (converted) "  •  Purchased as ${r.convertedPurchaseNo}" else ""),
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
                    onClick = { vm.convertToPurchase(selected.toList()) { selecting = false; selected.clear(); onConvertToPurchase() } },
                    enabled = selected.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(14.dp)
                ) { Text("Convert ${selected.size} receipt(s) to a purchase") }
            }
        }
    }
}

private fun pickDate(context: android.content.Context, current: Long, onPicked: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = current }
    DatePickerDialog(context, { _, y, m, d -> onPicked(Calendar.getInstance().apply { set(y, m, d, 0, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis) }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
}
