package com.billing.pos.ui.items

import android.Manifest
import android.app.Application
import android.content.Context
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
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ItemsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)
    val items: StateFlow<List<Item>> =
        repo.items.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }

    fun save(existing: Item?, name: String, price: Double, tax: Double, barcode: String, onDone: () -> Unit) {
        if (name.isBlank()) { message.value = "Enter a name"; return }
        viewModelScope.launch {
            if (existing == null) repo.addItem(name, price, tax, barcode)
            else repo.updateItem(existing.copy(name = name.trim(), price = price, taxPercent = tax, barcode = barcode.trim()))
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
    val items by vm.items.collectAsStateSafe()
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
            items(items, key = { it.id }) { it0 ->
                Row(
                    Modifier.fillMaxWidth().clickable { editing = it0; showDialog = true }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(it0.name, fontWeight = FontWeight.Bold)
                        Text(
                            Format.rupee(it0.price) +
                                (if (it0.taxPercent > 0) "  •  ${Format.money(it0.taxPercent)}% tax" else "") +
                                (if (it0.barcode.isNotBlank()) "  •  ${it0.barcode}" else ""),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                        )
                    }
                    if (it0.barcode.isNotBlank()) {
                        IconButton(onClick = { printFor = it0 }) { Icon(Icons.Filled.QrCode, "Print barcode") }
                    }
                    IconButton(onClick = { deleteFor = it0 }) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                }
                Divider()
            }
        }
    }

    if (showDialog) {
        ItemDialog(existing = editing, onDismiss = { showDialog = false }, onSave = { n, p, t, b -> vm.save(editing, n, p, t, b) { showDialog = false } })
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

@Composable
private fun ItemDialog(existing: Item?, onDismiss: () -> Unit, onSave: (String, Double, Double, String) -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var price by remember { mutableStateOf(existing?.price?.let { Format.money(it) } ?: "") }
    var taxable by remember { mutableStateOf((existing?.taxPercent ?: 0.0) > 0.0) }
    var taxPercent by remember { mutableStateOf(if ((existing?.taxPercent ?: 0.0) > 0.0) Format.money(existing!!.taxPercent) else "18") }
    var barcode by remember { mutableStateOf(existing?.barcode ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New item" else "Edit item") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = price, onValueChange = { price = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Price *") }, singleLine = true,
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = barcode, onValueChange = { barcode = it },
                        label = { Text("Barcode (optional)") }, singleLine = true, modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { barcode = System.currentTimeMillis().toString() }) { Text("Auto") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val p = price.toDoubleOrNull() ?: 0.0
                val t = if (taxable) (taxPercent.toDoubleOrNull() ?: 0.0) else 0.0
                onSave(name, p, t, barcode)
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
