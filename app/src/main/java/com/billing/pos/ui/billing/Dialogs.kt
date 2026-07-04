package com.billing.pos.ui.billing

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

@Composable
fun NewItemDialog(
    onDismiss: () -> Unit,
    onSave: (name: String, price: Double, taxPercent: Double, barcode: String, addToCart: Boolean) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var taxable by remember { mutableStateOf(false) }
    var taxPercent by remember { mutableStateOf("18") }
    var barcode by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Item") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Item name *") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = price, onValueChange = { price = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Price *") }, singleLine = true,
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
                OutlinedTextField(
                    value = barcode, onValueChange = { barcode = it },
                    label = { Text("Barcode (optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val p = price.toDoubleOrNull() ?: 0.0
                val t = if (taxable) (taxPercent.toDoubleOrNull() ?: 0.0) else 0.0
                onSave(name, p, t, barcode, true)
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
    onAdd: (description: String, price: Double, taxPercent: Double) -> Unit
) {
    var price by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var taxable by remember { mutableStateOf(false) }
    var taxPercent by remember { mutableStateOf("18") }
    var count by remember { mutableStateOf(0) }
    val priceFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { priceFocus.requestFocus() }

    fun addNow() {
        val p = price.toDoubleOrNull() ?: 0.0
        if (p <= 0) return
        val t = if (taxable) (taxPercent.toDoubleOrNull() ?: 0.0) else 0.0
        onAdd(description, p, t)
        count++
        price = ""
        description = ""
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
    onNewItem: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, items) {
        if (query.isBlank()) items else items.filter { it.name.contains(query, ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select item") },
        text = {
            Column {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    label = { Text("Search") }, singleLine = true,
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
                                Text(item.name)
                                Text(
                                    Format.rupee(item.price) +
                                        if (item.taxPercent > 0) "  •  ${Format.money(item.taxPercent)}% tax" else "  •  no tax",
                                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                                )
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
}
