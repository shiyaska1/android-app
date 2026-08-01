package com.billing.pos.ui.bundle

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.Item
import com.billing.pos.data.ItemBundle
import com.billing.pos.data.ItemBundleComponent
import com.billing.pos.data.Repository
import com.billing.pos.ui.billing.CartLine
import com.billing.pos.ui.billing.ItemPickerDialog
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.util.Format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class BundleViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)
    val items: StateFlow<List<Item>> = repo.items.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val bundles: StateFlow<List<ItemBundle>> = repo.bundles.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }

    suspend fun componentsFor(bundleId: Long): List<ItemBundleComponent> = repo.bundleComponents(bundleId)
    suspend fun bundleById(id: Long): ItemBundle? = repo.bundleById(id)

    fun saveBundle(
        existingId: Long?, name: String, unit: String, price: Double, remarks: String,
        components: List<ItemBundleComponent>, onDone: () -> Unit
    ) {
        if (name.isBlank()) { message.value = "Enter a bundle name"; return }
        if (components.isEmpty()) { message.value = "Add at least one item"; return }
        viewModelScope.launch {
            val b = ItemBundle(id = existingId ?: 0, name = name.trim(), unit = unit, price = price, remarks = remarks.trim())
            if (existingId != null) repo.updateBundle(b, components) else repo.saveBundle(b, components)
            message.value = "Bundle saved"
            onDone()
        }
    }
    fun deleteBundle(b: ItemBundle) { viewModelScope.launch { repo.deleteBundle(b); message.value = "Bundle deleted" } }
}

/**
 * Turns [multiplier] bundle units into real item cart lines (each item's own current price),
 * merging into any matching existing line the same way a normal add-item does. Used from both
 * Sales and Quotation entry — a bundle carries no stock of its own, only its components do.
 */
fun expandBundleAndAdd(
    cart: androidx.compose.runtime.snapshots.SnapshotStateList<CartLine>,
    components: List<ItemBundleComponent>,
    items: List<Item>,
    multiplier: Double
) {
    components.forEach { c ->
        val item = items.firstOrNull { it.id == c.itemId }
        val price = item?.price ?: 0.0
        val tax = item?.taxPercent ?: 0.0
        val qty = c.qty * multiplier
        if (qty <= 0) return@forEach
        val idx = cart.indexOfFirst { it.itemId == c.itemId && it.unit == c.unit && it.batchNo.isBlank() }
        if (idx >= 0) cart[idx] = cart[idx].copy(qty = cart[idx].qty + qty)
        else cart.add(CartLine(c.itemId, c.name, price, tax, qty, unit = c.unit))
    }
}

// ============================== List / Edit ==============================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BundleListScreen(onBack: () -> Unit, onOpen: (Long) -> Unit, onNew: () -> Unit, vm: BundleViewModel = viewModel()) {
    val snackbar = remember { SnackbarHostState() }
    val bundles by vm.bundles.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }
    var deleteFor by remember { mutableStateOf<ItemBundle?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Item Bundles") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = onNew) { Icon(Icons.Filled.Add, "New bundle") } }
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp)) {
            items(bundles, key = { it.id }) { b ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(b.id) }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(b.name, fontWeight = FontWeight.Bold)
                        Text(
                            "Price ${Format.rupee(b.price)}" + (if (b.unit.isNotBlank()) " / ${b.unit}" else ""),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                        )
                    }
                    IconButton(onClick = { deleteFor = b }) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                }
                Divider()
            }
            if (bundles.isEmpty()) item { Text("No bundles yet. Tap + to create one.", color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(16.dp)) }
        }
    }

    deleteFor?.let { b ->
        AlertDialog(onDismissRequest = { deleteFor = null }, title = { Text("Delete ${b.name}?") },
            confirmButton = { TextButton(onClick = { vm.deleteBundle(b); deleteFor = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Cancel") } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BundleEditScreen(editId: Long?, onBack: () -> Unit, vm: BundleViewModel = viewModel()) {
    val snackbar = remember { SnackbarHostState() }
    val items by vm.items.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    var name by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("") }
    var priceText by remember { mutableStateOf("0") }
    var remarks by remember { mutableStateOf("") }
    val components = remember { mutableStateListOf<ItemBundleComponent>() }
    var pickingItem by remember { mutableStateOf(false) }

    LaunchedEffect(editId) {
        if (editId != null && editId > 0) {
            val b = vm.bundleById(editId) ?: return@LaunchedEffect
            name = b.name; unit = b.unit; priceText = Format.money(b.price); remarks = b.remarks
            components.clear(); components.addAll(vm.componentsFor(editId))
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (editId != null && editId > 0) "Edit Bundle" else "New Bundle") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(12.dp).verticalScroll(rememberScrollState())) {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Bundle name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(value = unit, onValueChange = { unit = it }, label = { Text("Unit") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(
                    value = priceText,
                    onValueChange = { v -> priceText = v.filter { it.isDigit() || it == '.' } },
                    label = { Text("Price") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
            }

            Divider(Modifier.padding(vertical = 12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Items in this bundle", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { pickingItem = true }) { Icon(Icons.Filled.Add, null); Text(" Add") }
            }
            Card(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                if (components.isEmpty()) {
                    Text("No items added.", color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(12.dp))
                } else {
                    Column(Modifier.padding(8.dp)) {
                        components.forEachIndexed { index, c ->
                            var qtyText by remember(c.itemId, index) { mutableStateOf(Format.qty(c.qty)) }
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(c.name, Modifier.weight(1f), maxLines = 1)
                                OutlinedTextField(
                                    value = qtyText,
                                    onValueChange = { v ->
                                        val f = v.filter { it.isDigit() || it == '.' }; qtyText = f
                                        components[index] = components[index].copy(qty = f.toDoubleOrNull() ?: 0.0)
                                    },
                                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    modifier = Modifier.width(90.dp)
                                )
                                Text(" " + c.unit, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 4.dp))
                                IconButton(onClick = { components.removeAt(index) }) { Icon(Icons.Filled.Delete, "Remove", tint = MaterialTheme.colorScheme.error) }
                            }
                            Divider()
                        }
                    }
                }
            }

            OutlinedTextField(
                value = remarks, onValueChange = { remarks = it }, label = { Text("Remarks") },
                minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            )

            Button(
                onClick = {
                    vm.saveBundle(
                        if (editId != null && editId > 0) editId else null,
                        name, unit, priceText.toDoubleOrNull() ?: 0.0, remarks, components.toList()
                    ) { onBack() }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
            ) { Text("Save bundle") }
        }
    }

    if (pickingItem) {
        ItemPickerDialog(
            items = items, onDismiss = { pickingItem = false },
            onPick = { picked -> components.add(ItemBundleComponent(0, 0, picked.id, picked.name, 1.0, picked.unit)); pickingItem = false },
            onNewItem = { pickingItem = false }
        )
    }
}

// ============================== Picker (for Sales / Quotation) ==============================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BundlePickerDialog(
    bundles: List<ItemBundle>,
    onDismiss: () -> Unit,
    onPick: (ItemBundle, Double) -> Unit
) {
    var chosen by remember { mutableStateOf<ItemBundle?>(null) }
    var qtyText by remember { mutableStateOf("1") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(chosen?.let { "Quantity" } ?: "Choose a bundle") },
        text = {
            val c = chosen
            if (c == null) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (bundles.isEmpty()) Text("No bundles defined yet.", color = MaterialTheme.colorScheme.outline)
                    bundles.forEach { b ->
                        Row(
                            Modifier.fillMaxWidth().clickable { chosen = b }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(b.name, fontWeight = FontWeight.SemiBold)
                                Text(Format.rupee(b.price), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                            }
                        }
                        Divider()
                    }
                }
            } else {
                Column {
                    Text(c.name, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = qtyText,
                        onValueChange = { v -> qtyText = v.filter { it.isDigit() || it == '.' } },
                        label = { Text("Quantity" + (if (c.unit.isNotBlank()) " (${c.unit})" else "")) },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            val c = chosen
            if (c != null) {
                Button(onClick = { val q = qtyText.toDoubleOrNull() ?: 0.0; if (q > 0) onPick(c, q) }) { Text("Add") }
            } else {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = { if (chosen != null) TextButton(onClick = { chosen = null }) { Text("Back") } }
    )
}
