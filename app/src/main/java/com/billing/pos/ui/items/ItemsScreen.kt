package com.billing.pos.ui.items

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.filled.QrCode
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.DownloadSaver
import com.billing.pos.data.Item
import com.billing.pos.data.Repository
import com.billing.pos.pdf.BarcodePdf
import com.billing.pos.ui.billing.collectAsStateSafe
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

/** An item with its computed stock and last purchase rate. */
data class ItemStockRow(val item: Item, val stock: Double, val purchaseRate: Double)

class ItemsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)

    val rows: StateFlow<List<ItemStockRow>> =
        combine(repo.items, repo.purchaseLines, repo.soldQty) { items, pLines, sold ->
            val purchasedByName = pLines.groupBy { it.name.lowercase() }
            val soldByName = sold.associate { it.name.lowercase() to it.qty }
            items.map { item ->
                val key = item.name.lowercase()
                val lines = purchasedByName[key].orEmpty()
                val purchasedQty = lines.sumOf { it.qty }
                val lastRate = lines.maxByOrNull { it.dateMillis }?.price ?: 0.0
                val soldQty = soldByName[key] ?: 0.0
                ItemStockRow(item, item.openingStock + purchasedQty - soldQty, lastRate)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Distinct categories already in use, for the category dropdown. */
    val categories: StateFlow<List<String>> =
        repo.items.map { list ->
            list.map { it.category.trim() }.filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase() }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }

    fun save(
        existing: Item?, name: String, price: Double, tax: Double, barcode: String, hsn: String,
        category: String, openingStock: Double, onDone: () -> Unit
    ) {
        if (name.isBlank()) { message.value = "Enter a name"; return }
        viewModelScope.launch {
            if (existing == null) repo.addItem(name, price, tax, barcode, hsn, category, openingStock)
            else repo.updateItem(existing.copy(
                name = name.trim(), price = price, taxPercent = tax, barcode = barcode.trim(),
                hsn = hsn.trim(), category = category.trim(), openingStock = openingStock
            ))
            message.value = "Saved"; onDone()
        }
    }

    fun delete(item: Item) {
        viewModelScope.launch { repo.deleteItem(item); message.value = "Item deleted" }
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

    var showDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Item?>(null) }
    var deleteFor by remember { mutableStateOf<Item?>(null) }
    var printFor by remember { mutableStateOf<Item?>(null) }

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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Items") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = null; showDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add item")
            }
        }
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp)) {
            items(rows, key = { it.item.id }) { row ->
                val item = row.item
                Row(
                    Modifier.fillMaxWidth().clickable { editing = item; showDialog = true }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.name + (if (item.category.isNotBlank()) "  ·  ${item.category}" else ""),
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Stock: ${Format.qty(row.stock)}   •   Buy: ${Format.rupee(row.purchaseRate)}   •   Sell: ${Format.rupee(item.price)}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                        )
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

    if (showDialog) {
        ItemDialog(
            existing = editing,
            categories = categories,
            onDismiss = { showDialog = false },
            onSave = { n, p, t, b, h, cat, os -> vm.save(editing, n, p, t, b, h, cat, os) { showDialog = false } }
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemDialog(
    existing: Item?,
    categories: List<String>,
    onDismiss: () -> Unit,
    onSave: (String, Double, Double, String, String, String, Double) -> Unit
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var price by remember { mutableStateOf(existing?.price?.let { Format.money(it) } ?: "") }
    var taxable by remember { mutableStateOf((existing?.taxPercent ?: 0.0) > 0.0) }
    var taxPercent by remember { mutableStateOf(if ((existing?.taxPercent ?: 0.0) > 0.0) Format.money(existing!!.taxPercent) else "18") }
    var barcode by remember { mutableStateOf(existing?.barcode ?: "") }
    var hsn by remember { mutableStateOf(existing?.hsn ?: "") }
    var category by remember { mutableStateOf(existing?.category ?: "") }
    var catMenu by remember { mutableStateOf(false) }
    var openingStock by remember { mutableStateOf(if ((existing?.openingStock ?: 0.0) != 0.0) Format.qty(existing!!.openingStock) else "") }
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { barcode = it }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New item" else "Edit item") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = price, onValueChange = { price = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Price *") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth()
                )

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
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val p = price.toDoubleOrNull() ?: 0.0
                val t = if (taxable) (taxPercent.toDoubleOrNull() ?: 0.0) else 0.0
                val os = openingStock.toDoubleOrNull() ?: 0.0
                onSave(name, p, t, barcode, hsn, category, os)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
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
