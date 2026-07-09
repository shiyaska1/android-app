package com.billing.pos.ui.items

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import android.net.Uri
import com.billing.pos.data.DownloadSaver
import com.billing.pos.data.Item
import com.billing.pos.data.ItemAttachment
import com.billing.pos.data.Repository
import com.billing.pos.items.ItemAttachmentStore
import com.billing.pos.pdf.BarcodePdf
import com.billing.pos.pdf.TablePdf
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.ui.common.rememberPdfDownloader
import com.billing.pos.ui.common.rememberThumbnail
import com.billing.pos.util.Format
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** An item with its computed stock, last purchase rate and last supplier. */
data class ItemStockRow(val item: Item, val stock: Double, val purchaseRate: Double, val lastSupplier: String = "")

/** Common units of measure offered in the item entry dropdown. */
val ITEM_UNITS = listOf(
    "PCS", "NOS", "BOX", "PACK", "SET", "PAIR", "DOZEN",
    "KG", "GRAM", "QUINTAL", "TON",
    "LTR", "ML",
    "METER", "CM", "FEET", "INCH", "SQFT", "ROLL", "BAG", "BOTTLE", "UNIT"
)

class ItemsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)

    val rows: StateFlow<List<ItemStockRow>> =
        combine(repo.items, repo.purchaseLines, repo.soldQty, repo.purchaseLineParties) { items, pLines, sold, parties ->
            val purchasedByName = pLines.groupBy { it.name.lowercase() }
            val soldByName = sold.associate { it.name.lowercase() to it.qty }
            val lastSupplierByName = parties.groupBy { it.name.lowercase() }
                .mapValues { (_, l) -> l.maxByOrNull { it.dateMillis }?.supplierName ?: "" }
            items.map { item ->
                val key = item.name.lowercase()
                val lines = purchasedByName[key].orEmpty()
                val purchasedQty = lines.sumOf { it.qty }
                val lastRate = lines.maxByOrNull { it.dateMillis }?.price ?: 0.0
                val soldQty = soldByName[key] ?: 0.0
                ItemStockRow(item, item.openingStock + purchasedQty - soldQty, lastRate, lastSupplierByName[key] ?: "")
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Distinct categories already in use, for the category dropdown. */
    val categories: StateFlow<List<String>> =
        repo.items.map { list ->
            list.map { it.category.trim() }.filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase() }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }

    /** Attachments (photos / location photo / PDF) staged for the item being edited. */
    val editAttachments: SnapshotStateList<ItemAttachment> = mutableStateListOf()

    /** Load the current item's attachments into the staging list when a dialog opens. */
    fun beginEdit(item: Item?) {
        editAttachments.clear()
        val id = item?.id ?: return
        viewModelScope.launch { editAttachments.addAll(repo.itemAttachmentsFor(id)) }
    }

    fun addUris(context: android.content.Context, uris: List<Uri>, kind: String) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val added = withContext(Dispatchers.IO) { uris.mapNotNull { ItemAttachmentStore.copyIn(context, it, kind) } }
            editAttachments.addAll(added)
        }
    }

    fun addCapturedFile(file: java.io.File, name: String, mime: String, kind: String) {
        if (file.exists() && file.length() > 0) editAttachments.add(ItemAttachmentStore.fromFile(file, name, mime, kind))
        else file.delete()
    }

    fun removeAttachment(attachment: ItemAttachment) {
        editAttachments.remove(attachment)
        viewModelScope.launch {
            if (attachment.id > 0) repo.deleteItemAttachment(attachment)
            else withContext(Dispatchers.IO) { ItemAttachmentStore.delete(attachment) }
        }
    }

    /** Discards the edit: deletes any newly-added (unsaved) attachment files. */
    fun cancelEdit() {
        val unsaved = editAttachments.filter { it.id == 0L }
        editAttachments.clear()
        if (unsaved.isNotEmpty()) viewModelScope.launch(Dispatchers.IO) { unsaved.forEach { ItemAttachmentStore.delete(it) } }
    }

    fun save(
        existing: Item?, name: String, price: Double, tax: Double, barcode: String, hsn: String,
        category: String, openingStock: Double, unit: String, storeLocation: String, onDone: () -> Unit
    ) {
        if (name.isBlank()) { message.value = "Enter a name"; return }
        viewModelScope.launch {
            val id: Long = if (existing == null) {
                repo.addItem(name, price, tax, barcode, hsn, category, openingStock, unit, storeLocation)
            } else {
                repo.updateItem(existing.copy(
                    name = name.trim(), price = price, taxPercent = tax, barcode = barcode.trim(),
                    hsn = hsn.trim(), category = category.trim(), openingStock = openingStock,
                    unit = unit.trim().ifBlank { "PCS" }, storeLocation = storeLocation.trim()
                ))
                existing.id
            }
            editAttachments.filter { it.id == 0L }.forEach { repo.addItemAttachment(it.copy(itemId = id)) }
            editAttachments.clear()
            message.value = "Saved"; onDone()
        }
    }

    fun delete(item: Item) {
        viewModelScope.launch { repo.deleteItem(item); message.value = "Item deleted" }
    }

    /** Imports items from an .xlsx/.csv file, skipping names already in the master. */
    fun importSpreadsheet(context: Context, uri: Uri) {
        viewModelScope.launch {
            val rows = withContext(Dispatchers.IO) { com.billing.pos.data.SpreadsheetImport.read(context, uri) }
            if (rows.isEmpty()) { message.value = "No item rows found in the file"; return@launch }
            var added = 0; var skipped = 0
            rows.forEach { r ->
                if (repo.itemByName(r.name) == null) {
                    repo.addItem(r.name, r.price, r.taxPercent, r.barcode, r.hsn, r.category, r.openingStock, r.unit, r.location)
                    added++
                } else skipped++
            }
            message.value = "Imported $added item(s)" + if (skipped > 0) ", skipped $skipped existing" else ""
        }
    }

    /** Inserts scanned items into the master, skipping any name that already exists. */
    fun importItems(list: List<ImportItem>, onDone: (Int) -> Unit) {
        viewModelScope.launch {
            var added = 0
            list.forEach { p ->
                val name = p.name.trim()
                if (name.isNotBlank() && repo.itemByName(name) == null) {
                    repo.addItem(name, p.price, 0.0, "", "", p.category, p.openingStock, p.unit, "")
                    added++
                }
            }
            message.value = "Imported $added new item(s)"
            onDone(added)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemsScreen(
    onBack: () -> Unit,
    vm: ItemsViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val rows by vm.rows.collectAsStateSafe()
    val categories by vm.categories.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()

    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    // Filters: name contains, category, stock below a number.
    var filterName by remember { mutableStateOf("") }
    var filterCategory by remember { mutableStateOf("") }
    var stockBelow by remember { mutableStateOf("") }
    val stockBelowVal = stockBelow.toDoubleOrNull()
    val filteredRows = rows.filter {
        (filterName.isBlank() || it.item.name.contains(filterName, true)) &&
            (filterCategory.isBlank() || it.item.category.equals(filterCategory, true)) &&
            (stockBelowVal == null || it.stock < stockBelowVal)
    }
    val downloadPdf = rememberPdfDownloader { msg -> scope.launch { snackbar.showSnackbar(msg) } }
    fun buildItemsPdf(): java.io.File {
        val company = com.billing.pos.data.AppPrefs(context).company
        val subtitle = buildString {
            append("Items: ${filteredRows.size}")
            if (filterCategory.isNotBlank()) append("  |  Category: $filterCategory")
            if (filterName.isNotBlank()) append("  |  Name~ $filterName")
            if (stockBelowVal != null) append("  |  Stock < ${Format.qty(stockBelowVal)}")
        }
        val cols = listOf(
            TablePdf.Col("Item", 3f), TablePdf.Col("Category", 1.6f), TablePdf.Col("Unit", 0.9f),
            TablePdf.Col("Stock", 1f, right = true), TablePdf.Col("Buy", 1.1f, right = true),
            TablePdf.Col("Sell", 1.1f, right = true), TablePdf.Col("Last Supplier", 2f)
        )
        val data = filteredRows.map { r ->
            listOf(r.item.name, r.item.category, r.item.unit, Format.qty(r.stock),
                Format.money(r.purchaseRate), Format.money(r.item.price), r.lastSupplier)
        }
        return TablePdf.generate(context, company, "Item List", subtitle, cols, data)
    }

    var showDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Item?>(null) }
    var deleteFor by remember { mutableStateOf<Item?>(null) }
    var printFor by remember { mutableStateOf<Item?>(null) }

    // Import items: Excel/CSV file, camera scan, or gallery photo (OCR → review).
    var scanResult by remember { mutableStateOf<List<com.billing.pos.ocr.ScannedItem>?>(null) }
    var importMenu by remember { mutableStateOf(false) }
    val scanList = com.billing.pos.ocr.rememberListScanner { lines ->
        scanResult = com.billing.pos.ocr.ItemListParser.parse(lines)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importSpreadsheet(context, uri)
    }
    val galleryScan = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            scanResult = com.billing.pos.ocr.ItemListParser.parse(com.billing.pos.ocr.TextOcr.lines(context, uri))
        }
    }

    fun doPrint(item: Item, count: Int) {
        scope.launch {
            val pdf = withContext(Dispatchers.IO) { BarcodePdf.generate(context, item, count) }
            if (pdf == null) { snackbar.showSnackbar("This item has no barcode"); return@launch }
            val ok = withContext(Dispatchers.IO) { DownloadSaver.save(context, pdf, pdf.name, "application/pdf") }
            snackbar.showSnackbar(if (ok) "Barcodes saved to Downloads: ${pdf.name}" else "Could not save")
        }
    }
    var pendingPrint by remember { mutableStateOf<Pair<Item, Int>?>(null) }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val pp = pendingPrint; pendingPrint = null
        if (granted && pp != null) doPrint(pp.first, pp.second)
        else scope.launch { snackbar.showSnackbar("Storage permission denied") }
    }
    fun requestPrint(item: Item, count: Int) {
        if (DownloadSaver.needsLegacyPermission() &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) { pendingPrint = item to count; storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
        else doPrint(item, count)
    }

    // --- Item attachments: photos, location photo, PDF catalogue ---
    var galleryKind by remember { mutableStateOf("PHOTO") }
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris -> vm.addUris(context, uris, galleryKind) }
    val cataloguePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> vm.addUris(context, uris, "CATALOGUE") }

    var pendingCapture by remember { mutableStateOf<java.io.File?>(null) }
    var captureKind by remember { mutableStateOf("PHOTO") }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = pendingCapture; pendingCapture = null
        if (ok && f != null) vm.addCapturedFile(f, "Photo_${f.name}", "image/jpeg", captureKind) else f?.delete()
    }
    fun launchCapture(kind: String) {
        captureKind = kind
        val file = java.io.File(ItemAttachmentStore.dir(context), "cam_${System.nanoTime()}.jpg")
        pendingCapture = file
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        runCatching { takePhoto.launch(uri) }
            .onFailure { pendingCapture?.delete(); pendingCapture = null; scope.launch { snackbar.showSnackbar("No camera app found") } }
    }
    var pendingCameraAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingCameraAction; pendingCameraAction = null
        if (granted) action?.invoke() else scope.launch { snackbar.showSnackbar("Camera permission denied") }
    }
    fun withCamera(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) action()
        else { pendingCameraAction = action; cameraPermission.launch(Manifest.permission.CAMERA) }
    }
    fun pickPhotos(kind: String) {
        galleryKind = kind
        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Items") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                actions = {
                    Box {
                        IconButton(onClick = { importMenu = true }) {
                            Icon(Icons.Filled.DocumentScanner, contentDescription = "Import items")
                        }
                        DropdownMenu(expanded = importMenu, onDismissRequest = { importMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Import Excel / CSV") },
                                onClick = {
                                    importMenu = false
                                    filePicker.launch(arrayOf(
                                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                        "application/vnd.ms-excel", "text/csv", "text/comma-separated-values",
                                        "application/octet-stream", "*/*"
                                    ))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Scan with camera") },
                                onClick = { importMenu = false; scanList() }
                            )
                            DropdownMenuItem(
                                text = { Text("Pick photo (gallery)") },
                                onClick = {
                                    importMenu = false
                                    galleryScan.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                }
                            )
                        }
                    }
                    IconButton(onClick = { downloadPdf { buildItemsPdf() } }) {
                        Icon(Icons.Filled.Download, contentDescription = "Download list PDF")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = null; vm.beginEdit(null); showDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add item")
            }
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            // Filter bar: name, category, stock-below.
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = filterName, onValueChange = { filterName = it },
                        label = { Text("Item name") }, singleLine = true, modifier = Modifier.weight(1.4f)
                    )
                    OutlinedTextField(
                        value = stockBelow, onValueChange = { stockBelow = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Stock <") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f)
                    )
                }
                var catMenu by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = catMenu, onExpandedChange = { catMenu = !catMenu }) {
                    OutlinedTextField(
                        readOnly = true, value = filterCategory.ifBlank { "All categories" }, onValueChange = {},
                        label = { Text("Category") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(catMenu) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = catMenu, onDismissRequest = { catMenu = false }) {
                        DropdownMenuItem(text = { Text("All categories") }, onClick = { filterCategory = ""; catMenu = false })
                        categories.forEach { cat ->
                            DropdownMenuItem(text = { Text(cat) }, onClick = { filterCategory = cat; catMenu = false })
                        }
                    }
                }
            }
            Divider()
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                items(filteredRows, key = { it.item.id }) { row ->
                val item = row.item
                Row(
                    Modifier.fillMaxWidth().clickable { editing = item; vm.beginEdit(item); showDialog = true }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.name + (if (item.category.isNotBlank()) "  ·  ${item.category}" else ""),
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Stock: ${Format.qty(row.stock)} ${item.unit}   •   Buy: ${Format.rupee(row.purchaseRate)}   •   Sell: ${Format.rupee(item.price)}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                        )
                        if (row.lastSupplier.isNotBlank()) {
                            Text(
                                "Last supplier: ${row.lastSupplier}",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline
                            )
                        }
                        if (item.barcode.isNotBlank() || item.taxPercent > 0) {
                            Text(
                                (if (item.taxPercent > 0) "Tax ${Format.money(item.taxPercent)}%" else "") +
                                    (if (item.taxPercent > 0 && item.barcode.isNotBlank()) "  •  " else "") +
                                    (if (item.barcode.isNotBlank()) item.barcode else ""),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                    if (item.barcode.isNotBlank()) {
                        IconButton(onClick = { printFor = item }) { Icon(Icons.Filled.QrCode, "Print barcode") }
                    }
                    IconButton(onClick = { deleteFor = item }) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                }
                Divider()
                }
            }
        }
    }

    if (showDialog) {
        ItemDialog(
            existing = editing,
            categories = categories,
            attachments = vm.editAttachments,
            onAddPhotoGallery = { pickPhotos("PHOTO") },
            onAddPhotoCamera = { withCamera { launchCapture("PHOTO") } },
            onAddLocationPhoto = { withCamera { launchCapture("LOCATION") } },
            onAddCatalogue = { runCatching { cataloguePicker.launch(arrayOf("application/pdf")) } },
            onRemoveAttachment = { vm.removeAttachment(it) },
            onDismiss = { vm.cancelEdit(); showDialog = false },
            onSave = { n, p, t, b, h, cat, os, u, loc -> vm.save(editing, n, p, t, b, h, cat, os, u, loc) { showDialog = false } }
        )
    }
    deleteFor?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Delete ${item.name}?") },
            text = { Text("Existing bills keep their line items; only the master item is removed.") },
            confirmButton = { TextButton(onClick = { vm.delete(item); deleteFor = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Cancel") } }
        )
    }
    printFor?.let { item ->
        PrintCountDialog(item = item, onDismiss = { printFor = null }, onPrint = { count -> printFor = null; requestPrint(item, count) })
    }
    scanResult?.let { parsed ->
        ScanImportDialog(
            initial = parsed,
            existingNames = rows.map { it.item.name.trim().lowercase() }.toSet(),
            categories = categories,
            onDismiss = { scanResult = null },
            onImport = { list -> vm.importItems(list) { scanResult = null } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemDialog(
    existing: Item?,
    categories: List<String>,
    attachments: List<ItemAttachment>,
    onAddPhotoGallery: () -> Unit,
    onAddPhotoCamera: () -> Unit,
    onAddLocationPhoto: () -> Unit,
    onAddCatalogue: () -> Unit,
    onRemoveAttachment: (ItemAttachment) -> Unit,
    onDismiss: () -> Unit,
    onSave: (String, Double, Double, String, String, String, Double, String, String) -> Unit
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var price by remember { mutableStateOf(existing?.price?.let { Format.money(it) } ?: "") }
    var priceForQty by remember { mutableStateOf("1") }
    var taxable by remember { mutableStateOf((existing?.taxPercent ?: 0.0) > 0.0) }
    var taxPercent by remember { mutableStateOf(if ((existing?.taxPercent ?: 0.0) > 0.0) Format.money(existing!!.taxPercent) else "18") }
    var barcode by remember { mutableStateOf(existing?.barcode ?: "") }
    var hsn by remember { mutableStateOf(existing?.hsn ?: "") }
    var category by remember { mutableStateOf(existing?.category ?: "") }
    var catMenu by remember { mutableStateOf(false) }
    var openingStock by remember { mutableStateOf(if ((existing?.openingStock ?: 0.0) != 0.0) Format.qty(existing!!.openingStock) else "") }
    var unit by remember { mutableStateOf(existing?.unit?.ifBlank { "PCS" } ?: "PCS") }
    var unitMenu by remember { mutableStateOf(false) }
    var storeLocation by remember { mutableStateOf(existing?.storeLocation ?: "") }
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { barcode = it }
    }
    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()?.let { if (it.isNotBlank()) name = it }
        }
    }
    fun speakName() {
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Say the item name")
        }
        runCatching { speechLauncher.launch(intent) }
    }
    val scanName = com.billing.pos.ocr.rememberNameScanner { if (it.isNotBlank()) name = it }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New item" else "Edit item") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, label = { Text("Name *") }, singleLine = true,
                    trailingIcon = {
                        Row {
                            IconButton(onClick = { scanName() }) {
                                Icon(Icons.Filled.PhotoCamera, contentDescription = "Scan item name", tint = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(onClick = { speakName() }) {
                                Icon(Icons.Filled.Mic, contentDescription = "Speak item name", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                // Price for a quantity of units (e.g. 120 for 12 => 10 per unit).
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = price, onValueChange = { price = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Price *") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1.4f)
                    )
                    OutlinedTextField(
                        value = priceForQty, onValueChange = { priceForQty = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("for units") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f)
                    )
                }
                run {
                    val pv = price.toDoubleOrNull() ?: 0.0
                    val qv = priceForQty.toDoubleOrNull() ?: 1.0
                    if (qv > 1.0 && pv > 0.0) {
                        Text(
                            "= ${Format.rupee(pv / qv)} per unit",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                // Unit of measure (dropdown with common units).
                ExposedDropdownMenuBox(expanded = unitMenu, onExpandedChange = { unitMenu = !unitMenu }) {
                    OutlinedTextField(
                        value = unit, onValueChange = { unit = it }, label = { Text("Unit") }, singleLine = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = unitMenu) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = unitMenu, onDismissRequest = { unitMenu = false }) {
                        ITEM_UNITS.forEach { u ->
                            DropdownMenuItem(text = { Text(u) }, onClick = { unit = u; unitMenu = false })
                        }
                    }
                }

                // Category: pick an existing one from the dropdown, or type/tap + for a new one.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ExposedDropdownMenuBox(
                        expanded = catMenu,
                        onExpandedChange = { catMenu = !catMenu },
                        modifier = Modifier.weight(1f)
                    ) {
                        OutlinedTextField(
                            value = category,
                            onValueChange = { category = it },
                            label = { Text("Category") },
                            singleLine = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = catMenu) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        if (categories.isNotEmpty()) {
                            ExposedDropdownMenu(expanded = catMenu, onDismissRequest = { catMenu = false }) {
                                categories.forEach { c ->
                                    DropdownMenuItem(text = { Text(c) }, onClick = { category = c; catMenu = false })
                                }
                            }
                        }
                    }
                    IconButton(onClick = { category = ""; catMenu = false }) {
                        Icon(Icons.Filled.Add, contentDescription = "New category")
                    }
                }

                OutlinedTextField(
                    value = openingStock, onValueChange = { openingStock = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Opening stock") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth()
                )

                Row {
                    FilterChip(selected = !taxable, onClick = { taxable = false }, label = { Text("Without tax") })
                    androidx.compose.foundation.layout.Spacer(Modifier.padding(4.dp))
                    FilterChip(selected = taxable, onClick = { taxable = true }, label = { Text("With tax") })
                }
                if (taxable) {
                    OutlinedTextField(
                        value = taxPercent, onValueChange = { taxPercent = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Tax %") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = barcode, onValueChange = { barcode = it },
                    label = { Text("Barcode (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { barcode = System.currentTimeMillis().toString() }, modifier = Modifier.weight(1f)) { Text("Auto") }
                    OutlinedButton(
                        onClick = { scanLauncher.launch(ScanOptions().setPrompt("Scan barcode").setBeepEnabled(true).setOrientationLocked(false)) },
                        modifier = Modifier.weight(1f)
                    ) { Text("Scan") }
                }
                OutlinedTextField(
                    value = hsn, onValueChange = { hsn = it },
                    label = { Text("HSN / SAC (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )

                Divider(Modifier.padding(top = 4.dp))

                // Store location (text) + optional location photo.
                OutlinedTextField(
                    value = storeLocation, onValueChange = { storeLocation = it },
                    label = { Text("Location in store (e.g. Rack A-3)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )

                // Attachment add buttons.
                Text("Photos & catalogue", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = onAddPhotoGallery, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.PhotoLibrary, null, modifier = Modifier.size(18.dp)); Text(" Photos", maxLines = 1)
                    }
                    OutlinedButton(onClick = onAddPhotoCamera, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.PhotoCamera, null, modifier = Modifier.size(18.dp)); Text(" Camera", maxLines = 1)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = onAddLocationPhoto, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Place, null, modifier = Modifier.size(18.dp)); Text(" Loc photo", maxLines = 1)
                    }
                    OutlinedButton(onClick = onAddCatalogue, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.PictureAsPdf, null, modifier = Modifier.size(18.dp)); Text(" PDF", maxLines = 1)
                    }
                }

                // Thumbnails / chips of staged attachments.
                if (attachments.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        attachments.forEach { att -> AttachmentThumb(att, onRemove = { onRemoveAttachment(att) }) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val entered = price.toDoubleOrNull() ?: 0.0
                val forQty = (priceForQty.toDoubleOrNull() ?: 1.0).takeIf { it > 0.0 } ?: 1.0
                val p = entered / forQty            // store the per-unit price
                val t = if (taxable) (taxPercent.toDoubleOrNull() ?: 0.0) else 0.0
                val os = openingStock.toDoubleOrNull() ?: 0.0
                onSave(name, p, t, barcode, hsn, category, os, unit, storeLocation)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** A small square thumbnail (image) or PDF tile for one staged attachment, with a remove badge. */
@Composable
private fun AttachmentThumb(att: ItemAttachment, onRemove: () -> Unit) {
    Box(Modifier.size(72.dp)) {
        val isImage = att.mime.startsWith("image/")
        val bmp = if (isImage) rememberThumbnail(att.path, 200) else null
        Box(
            Modifier.size(72.dp).clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (bmp != null) {
                Image(bmp, contentDescription = att.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.PictureAsPdf, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("PDF", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (att.kind == "LOCATION") {
            Icon(
                Icons.Filled.Place, contentDescription = "Location photo",
                tint = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(2.dp).size(16.dp)
            )
        }
        Box(
            Modifier.align(Alignment.TopEnd).padding(2.dp).size(20.dp).clip(RoundedCornerShape(10.dp))
                .background(Color(0xAA000000)).clickable { onRemove() },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Close, contentDescription = "Remove", tint = Color.White, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun PrintCountDialog(item: Item, onDismiss: () -> Unit, onPrint: (Int) -> Unit) {
    var count by remember { mutableStateOf("10") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Print barcodes") },
        text = {
            Column {
                Text("${item.name} — ${item.barcode}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                OutlinedTextField(
                    value = count, onValueChange = { count = it.filter { c -> c.isDigit() } },
                    label = { Text("Number of labels") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        },
        confirmButton = { Button(onClick = { onPrint((count.toIntOrNull() ?: 1).coerceIn(1, 500)) }) { Text("Generate PDF") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
