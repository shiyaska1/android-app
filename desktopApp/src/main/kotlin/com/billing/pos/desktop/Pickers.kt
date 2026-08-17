package com.billing.pos.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.billing.pos.shared.Customer
import com.billing.pos.shared.Item
import com.billing.pos.shared.Supplier

/** Shared searchable picker used by every transaction form's customer/supplier/item picker —
 * clears to a live-filtered search box instead of a plain scroll-and-tap list, matching the
 * Android app's standing "every picker must be searchable" rule. [onAddNew], when given, shows
 * a "+ Add new" row above the results so a customer/item can be created without leaving the
 * transaction screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SearchablePickerDialog(
    title: String,
    items: List<T>,
    labelOf: (T) -> String,
    subtitleOf: ((T) -> String?)? = null,
    onDismiss: () -> Unit,
    onPick: (T) -> Unit,
    onAddNew: (() -> Unit)? = null
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, items) {
        if (query.isBlank()) items else items.filter { labelOf(it).contains(query, ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, label = { Text("Search") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                if (onAddNew != null) {
                    TextButton(onClick = onAddNew, modifier = Modifier.fillMaxWidth()) { Text("+ Add new") }
                }
                if (filtered.isEmpty()) {
                    Text(
                        "No matches.", color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                        items(filtered) { item ->
                            Column(
                                Modifier.fillMaxWidth().clickable { onPick(item) }.padding(vertical = 10.dp)
                            ) {
                                Text(labelOf(item), fontWeight = FontWeight.Bold)
                                subtitleOf?.invoke(item)?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Full-parity Item form — every field the Android app's Item entity has, none dropped. Shared
 * by the Items master screen's "+ Add item" and every transaction form's inline "+ Add new" for
 * items, so both stay in sync automatically. */
@Composable
fun ItemFormDialog(onDismiss: () -> Unit, onSave: (Item) -> Unit) {
    var name by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var purchasePrice by remember { mutableStateOf("") }
    var mrp by remember { mutableStateOf("") }
    var tax by remember { mutableStateOf("") }
    var barcode by remember { mutableStateOf("") }
    var hsn by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var openingStock by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("PCS") }
    var secondaryUnit by remember { mutableStateOf("PCS") }
    var conversionFactor by remember { mutableStateOf("1") }
    var storeLocation by remember { mutableStateOf("") }
    var chemicalContent by remember { mutableStateOf("") }

    @Composable
    fun numField(value: String, onChange: (String) -> Unit, label: String) {
        OutlinedTextField(
            value = value, onValueChange = { onChange(it.filter { c -> c.isDigit() || c == '.' }) },
            label = { Text(label) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New item") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                numField(price, { price = it }, "Sale price *")
                numField(purchasePrice, { purchasePrice = it }, "Purchase (cost) price")
                numField(mrp, { mrp = it }, "MRP")
                numField(tax, { tax = it }, "Tax %")
                OutlinedTextField(value = barcode, onValueChange = { barcode = it }, label = { Text("Barcode") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedTextField(value = hsn, onValueChange = { hsn = it }, label = { Text("HSN") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedTextField(value = category, onValueChange = { category = it }, label = { Text("Category") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                numField(openingStock, { openingStock = it }, "Opening stock")
                OutlinedTextField(value = unit, onValueChange = { unit = it }, label = { Text("Unit (e.g. PCS, BOX, KG)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedTextField(value = secondaryUnit, onValueChange = { secondaryUnit = it }, label = { Text("Secondary unit") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                numField(conversionFactor, { conversionFactor = it }, "Conversion factor (secondary per primary)")
                OutlinedTextField(value = storeLocation, onValueChange = { storeLocation = it }, label = { Text("Store location") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedTextField(value = chemicalContent, onValueChange = { chemicalContent = it }, label = { Text("Composition / content") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val p = price.toDoubleOrNull()
                if (name.isNotBlank() && p != null) {
                    onSave(
                        Item(
                            name = name.trim(), price = p,
                            purchasePrice = purchasePrice.toDoubleOrNull() ?: 0.0,
                            mrp = mrp.toDoubleOrNull() ?: 0.0,
                            taxPercent = tax.toDoubleOrNull() ?: 0.0,
                            barcode = barcode.trim(), hsn = hsn.trim(), category = category.trim(),
                            openingStock = openingStock.toDoubleOrNull() ?: 0.0,
                            unit = unit.trim().ifBlank { "PCS" }, secondaryUnit = secondaryUnit.trim().ifBlank { "PCS" },
                            conversionFactor = conversionFactor.toDoubleOrNull() ?: 1.0,
                            storeLocation = storeLocation.trim(), chemicalContent = chemicalContent.trim()
                        )
                    )
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Shared Customer quick-add form — used by the Customers master screen and every transaction
 * form's inline "+ Add new" for customers. */
@Composable
fun CustomerFormDialog(onDismiss: () -> Unit, onSave: (Customer) -> Unit) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New customer") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("Phone") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("Address") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank()) onSave(Customer(name = name.trim(), phone = phone.trim(), address = address.trim()))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Shared Supplier quick-add form — used by the Suppliers master screen and every transaction
 * form's inline "+ Add new" for suppliers. */
@Composable
fun SupplierFormDialog(onDismiss: () -> Unit, onSave: (Supplier) -> Unit) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New supplier") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("Phone") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("Address") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank()) onSave(Supplier(name = name.trim(), phone = phone.trim(), address = address.trim()))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
