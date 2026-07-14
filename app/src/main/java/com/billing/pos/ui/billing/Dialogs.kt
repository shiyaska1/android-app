package com.billing.pos.ui.billing

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.billing.pos.ui.common.rememberVoiceInput
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.billing.pos.data.Item
import com.billing.pos.util.Format
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

@Composable
fun NewCustomerDialog(
    onDismiss: () -> Unit,
    onSave: (name: String, phone: String, address: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Customer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name *") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = phone, onValueChange = { phone = it },
                    label = { Text("Phone") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = address, onValueChange = { address = it },
                    label = { Text("Address") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, phone, address) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** All fields captured by the sales "New item" dialog (mirrors the item master form). */
data class NewItemForm(
    val name: String,
    val price: Double,
    val taxPercent: Double,
    val barcode: String,
    val category: String,
    val hsn: String,
    val openingStock: Double,
    val unit: String,
    val secondaryUnit: String,
    val conversionFactor: Double,
    val storeLocation: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewItemDialog(
    onDismiss: () -> Unit,
    categories: List<String> = emptyList(),
    onSave: (NewItemForm) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var taxable by remember { mutableStateOf(false) }
    var taxPercent by remember { mutableStateOf("18") }
    var barcode by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var catMenu by remember { mutableStateOf(false) }
    var hsn by remember { mutableStateOf("") }
    var openingStock by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("PCS") }
    var unitMenu by remember { mutableStateOf(false) }
    var secondaryUnit by remember { mutableStateOf("PCS") }
    var secUnitMenu by remember { mutableStateOf(false) }
    var factorText by remember { mutableStateOf("1") }
    var storeLocation by remember { mutableStateOf("") }
    val unitsDiffer = !secondaryUnit.trim().equals(unit.trim(), ignoreCase = true) &&
        secondaryUnit.isNotBlank() && unit.isNotBlank()
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { barcode = it }
    }
    val scanName = com.billing.pos.ocr.rememberNameScanner { if (it.isNotBlank()) name = it }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Item") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())
            ) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Item name *") },
                    // Tall + multiline so full OCR text is visible and easy to trim.
                    singleLine = false, minLines = 3, maxLines = 6,
                    trailingIcon = {
                        IconButton(onClick = { scanName() }) {
                            Icon(Icons.Filled.PhotoCamera, contentDescription = "Scan item name", tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = price, onValueChange = { price = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Selling price (incl. tax) *") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    FilterChip(
                        selected = !taxable,
                        onClick = { taxable = false },
                        label = { Text("Without tax") }
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.padding(4.dp))
                    FilterChip(
                        selected = taxable,
                        onClick = { taxable = true },
                        label = { Text("With tax") }
                    )
                }
                if (taxable) {
                    OutlinedTextField(
                        value = taxPercent,
                        onValueChange = { taxPercent = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Tax %") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                // Category (optional): pick an existing one, or type / tap + for a new one.
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ExposedDropdownMenuBox(expanded = catMenu, onExpandedChange = { catMenu = !catMenu }, modifier = Modifier.weight(1f)) {
                        OutlinedTextField(
                            value = category, onValueChange = { category = it },
                            label = { Text("Category (optional)") }, singleLine = true,
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
                    value = barcode, onValueChange = { barcode = it },
                    label = { Text("Barcode (optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { barcode = System.currentTimeMillis().toString() }, modifier = Modifier.weight(1f)) { Text("Auto") }
                    OutlinedButton(
                        onClick = { scanLauncher.launch(ScanOptions().setPrompt("Scan barcode").setBeepEnabled(true).setOrientationLocked(false)) },
                        modifier = Modifier.weight(1f)
                    ) { Text("Scan") }
                }
                OutlinedTextField(
                    value = hsn, onValueChange = { hsn = it }, label = { Text("HSN / SAC (optional)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = openingStock, onValueChange = { openingStock = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Opening stock (optional)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth()
                )
                // Primary unit.
                ExposedDropdownMenuBox(expanded = unitMenu, onExpandedChange = { unitMenu = !unitMenu }) {
                    OutlinedTextField(
                        value = unit, onValueChange = { unit = it }, label = { Text("Primary unit") }, singleLine = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = unitMenu) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = unitMenu, onDismissRequest = { unitMenu = false }) {
                        com.billing.pos.ui.items.ITEM_UNITS.forEach { u ->
                            DropdownMenuItem(text = { Text(u) }, onClick = { unit = u; unitMenu = false })
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                        ExposedDropdownMenuBox(expanded = secUnitMenu, onExpandedChange = { secUnitMenu = !secUnitMenu }) {
                            OutlinedTextField(
                                value = secondaryUnit, onValueChange = { secondaryUnit = it },
                                label = { Text("Secondary unit") }, singleLine = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = secUnitMenu) },
                                modifier = Modifier.menuAnchor().fillMaxWidth()
                            )
                            ExposedDropdownMenu(expanded = secUnitMenu, onDismissRequest = { secUnitMenu = false }) {
                                com.billing.pos.ui.items.ITEM_UNITS.forEach { u ->
                                    DropdownMenuItem(text = { Text(u) }, onClick = { secondaryUnit = u; secUnitMenu = false })
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = factorText, onValueChange = { factorText = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Conv. factor") }, singleLine = true, enabled = unitsDiffer,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.width(120.dp)
                    )
                }
                OutlinedTextField(
                    value = storeLocation, onValueChange = { storeLocation = it }, label = { Text("Store location (optional)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val p = price.toDoubleOrNull() ?: 0.0
                val t = if (taxable) (taxPercent.toDoubleOrNull() ?: 0.0) else 0.0
                val os = openingStock.toDoubleOrNull() ?: 0.0
                val sec = if (unitsDiffer) secondaryUnit.trim() else unit.trim()
                val f = if (unitsDiffer) (factorText.toDoubleOrNull() ?: 1.0).coerceAtLeast(1.0) else 1.0
                onSave(NewItemForm(name, p, t, barcode, category, hsn, os, unit.trim().ifBlank { "PCS" }, sec, f, storeLocation))
            }) { Text("Save & add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Rapid "add by price" entry. Price is on top; each Add (button or keyboard
 * Done/Enter) appends a line and keeps the dialog + keyboard open, refocusing
 * the price box for the next amount. Close with Done when finished.
 */
@Composable
fun CustomLineDialog(
    onDismiss: () -> Unit,
    onAdd: (description: String, price: Double, taxPercent: Double, saveToMaster: Boolean, sellingPrice: Double) -> Unit
) {
    var price by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var taxable by remember { mutableStateOf(false) }
    var taxPercent by remember { mutableStateOf("18") }
    var saveToMaster by remember { mutableStateOf(false) }
    var sellingPrice by remember { mutableStateOf("") }
    var count by remember { mutableStateOf(0) }
    val priceFocus = remember { FocusRequester() }
    val startDescVoice = rememberVoiceInput { description = it }

    LaunchedEffect(Unit) { priceFocus.requestFocus() }

    fun addNow() {
        val p = price.toDoubleOrNull() ?: 0.0
        if (p <= 0) return
        val t = if (taxable) (taxPercent.toDoubleOrNull() ?: 0.0) else 0.0
        val sp = sellingPrice.toDoubleOrNull() ?: 0.0
        onAdd(description, p, t, saveToMaster, sp)
        count++
        price = ""
        description = ""
        sellingPrice = ""
        priceFocus.requestFocus()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add items by price") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = price, onValueChange = { price = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Price *") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { addNow() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(priceFocus)
                )
                OutlinedTextField(
                    value = description, onValueChange = { description = it },
                    label = { Text("Description (optional)") }, singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = startDescVoice) {
                            Icon(Icons.Filled.Mic, contentDescription = "Speak description", tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    FilterChip(selected = !taxable, onClick = { taxable = false }, label = { Text("Without tax") })
                    androidx.compose.foundation.layout.Spacer(Modifier.padding(4.dp))
                    FilterChip(selected = taxable, onClick = { taxable = true }, label = { Text("With tax") })
                }
                if (taxable) {
                    OutlinedTextField(
                        value = taxPercent,
                        onValueChange = { taxPercent = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Tax %") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Optionally save this description as a new item in the item master.
                FilterChip(
                    selected = saveToMaster,
                    onClick = { saveToMaster = !saveToMaster },
                    label = { Text(if (saveToMaster) "✓ Save to item master" else "Save to item master") }
                )
                if (saveToMaster) {
                    OutlinedTextField(
                        value = sellingPrice,
                        onValueChange = { sellingPrice = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Selling price for item (optional)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Uses the line price above if left blank. Needs a description.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                Button(
                    onClick = { addNow() },
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text("Add to bill", style = MaterialTheme.typography.titleMedium) }
                Text(
                    "$count item(s) added — keep entering, tap Done to finish",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
fun ItemPickerDialog(
    items: List<Item>,
    onDismiss: () -> Unit,
    onPick: (Item) -> Unit,
    onNewItem: () -> Unit,
    stockByItem: Map<Long, Double> = emptyMap(),
    photosByItem: Map<Long, List<String>> = emptyMap()
) {
    var query by remember { mutableStateOf("") }
    var viewImages by remember { mutableStateOf<List<String>?>(null) }
    val filtered = remember(query, items) {
        if (query.isBlank()) items
        else items.filter { it.name.contains(query, ignoreCase = true) || it.chemicalContent.contains(query, ignoreCase = true) }
    }
    val startVoice = rememberVoiceInput { query = it }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select item") },
        text = {
            Column {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    label = { Text("Search") }, singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = startVoice) {
                            Icon(Icons.Filled.Mic, contentDescription = "Voice search", tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                if (items.isEmpty()) {
                    Text(
                        "No items yet. Tap \"New item\" to create one.",
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(filtered, key = { it.id }) { item ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(item) }
                                    .padding(vertical = 10.dp)
                            ) {
                                // Item name — larger, bold — with a photo-viewer icon if it has images.
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Text(
                                        item.name,
                                        modifier = Modifier.weight(1f),
                                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
                                    )
                                    val imgs = photosByItem[item.id].orEmpty()
                                    if (imgs.isNotEmpty()) IconButton(onClick = { viewImages = imgs }) {
                                        Icon(Icons.Filled.Image, contentDescription = "View photos", tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                // Price / stock / location — each a distinct colour.
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(
                                        Format.rupee(item.price),
                                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                        color = androidx.compose.material3.MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        "Stock ${Format.qty(stockByItem[item.id] ?: item.openingStock)} ${item.unit}",
                                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                                        color = androidx.compose.material3.MaterialTheme.colorScheme.tertiary
                                    )
                                    if (item.storeLocation.isNotBlank()) {
                                        Text(
                                            item.storeLocation,
                                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                                            color = androidx.compose.material3.MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                }
                                if (item.chemicalContent.isNotBlank()) {
                                    Text(
                                        item.chemicalContent,
                                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                                        color = androidx.compose.material3.MaterialTheme.colorScheme.error
                                    )
                                }
                                Divider(Modifier.padding(top = 8.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onNewItem) { Text("New item") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
    // Full-screen image viewer for the tapped item's photos (share doesn't close it).
    viewImages?.let { com.billing.pos.ui.common.ImageViewerDialog(paths = it, onDismiss = { viewImages = null }) }
}
