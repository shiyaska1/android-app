package com.billing.pos.ui.purchase

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.billing.pos.data.Item
import com.billing.pos.data.Supplier
import com.billing.pos.data.costRate
import com.billing.pos.ocr.SupplierBillLine
import com.billing.pos.util.Format

private class BillRow(line: SupplierBillLine) {
    var name by mutableStateOf(line.name)
    var price by mutableStateOf(if (line.price > 0.0) trimNum(line.price) else "")
    var qty by mutableStateOf(trimNum(line.qty.takeIf { it > 0 } ?: 1.0))
    var unit by mutableStateOf(line.unit)
    var expiryMillis by mutableStateOf(line.expiryMillis)
    var include by mutableStateOf(true)
}

/**
 * The single review step after a supplier bill's pages are OCR'd: the header fields the user
 * just marked (supplier name / bill no / date / total) already filled in, plus one row per item
 * — matched against the item master by name, or left as-is to create a new item. Everything here
 * is editable before anything is saved, including which existing supplier this maps to.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupplierBillMapDialog(
    supplierText: String,
    billNoText: String,
    dateMillis: Long,
    total: Double,
    lines: List<SupplierBillLine>,
    suppliers: List<Supplier>,
    matchedSupplierId: Long?,
    masterItems: List<Item>,
    onDismiss: () -> Unit,
    onConfirm: (supplierId: Long?, billNo: String, dateMillis: Long, lines: List<SupplierBillLine>) -> Unit
) {
    val context = LocalContext.current
    var billNo by remember { mutableStateOf(billNoText) }
    var pickedDate by remember { mutableStateOf(if (dateMillis > 0) dateMillis else System.currentTimeMillis()) }
    var supplierQuery by remember { mutableStateOf(suppliers.firstOrNull { it.id == matchedSupplierId }?.name ?: supplierText) }
    var supplierId by remember { mutableStateOf(matchedSupplierId) }
    var supplierExpanded by remember { mutableStateOf(false) }
    val rows = remember { mutableStateListOf<BillRow>().apply { lines.forEach { add(BillRow(it)) } } }
    val selected = rows.count { it.include && it.name.isNotBlank() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)
                .safeDrawingPadding().imePadding().padding(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Check the bill", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("$selected selected", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }

            Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(Modifier.padding(10.dp)) {
                    val supplierSuggestions = remember(supplierQuery, suppliers) {
                        val q = supplierQuery.trim()
                        if (q.isBlank()) suppliers else suppliers.filter { it.name.contains(q, ignoreCase = true) }
                    }
                    ExposedDropdownMenuBox(
                        expanded = supplierExpanded && supplierSuggestions.isNotEmpty(),
                        onExpandedChange = { supplierExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = supplierQuery,
                            onValueChange = { q ->
                                supplierQuery = q; supplierExpanded = true
                                supplierId = suppliers.firstOrNull { it.name.equals(q.trim(), ignoreCase = true) }?.id
                            },
                            label = { Text("Supplier") }, singleLine = true,
                            supportingText = { if (supplierId == null) Text("No match — pick one, or set it in the form below") },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = supplierExpanded && supplierSuggestions.isNotEmpty(),
                            onDismissRequest = { supplierExpanded = false }
                        ) {
                            supplierSuggestions.forEach { s ->
                                DropdownMenuItem(
                                    text = { Text(s.name) },
                                    onClick = { supplierQuery = s.name; supplierId = s.id; supplierExpanded = false }
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        OutlinedTextField(
                            value = billNo, onValueChange = { billNo = it },
                            label = { Text("Supplier bill no") }, singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(onClick = { pickPurchaseDate(context, pickedDate) { pickedDate = it } }) {
                            Text(Format.date(pickedDate))
                        }
                    }
                    if (total > 0.0) {
                        Text(
                            "Bill total (as read): ${Format.rupee(total)} — check this against the item total below.",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { rows.add(BillRow(SupplierBillLine("", 0.0))) }) { Icon(Icons.Filled.Add, "Add row"); Text(" Row") }
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(
                    onClick = {
                        val out = rows.filter { it.include && it.name.isNotBlank() }.map {
                            SupplierBillLine(
                                name = it.name.trim(), price = it.price.toDoubleOrNull() ?: 0.0,
                                qty = it.qty.toDoubleOrNull()?.takeIf { q -> q > 0 } ?: 1.0,
                                unit = it.unit, expiryMillis = it.expiryMillis
                            )
                        }
                        onConfirm(supplierId, billNo.trim(), pickedDate, out)
                    },
                    enabled = selected > 0,
                    modifier = Modifier.weight(1.2f)
                ) { Text("Add ($selected)") }
            }
            Text(
                "Fix any wrong name, price, qty, unit or expiry, untick what you don't want, then Add.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 6.dp)
            )
            Divider(Modifier.padding(vertical = 6.dp))

            if (rows.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text("Nothing readable in the photos.", color = MaterialTheme.colorScheme.outline)
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(rows) { index, row ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = row.include, onCheckedChange = { row.include = it })
                                var expanded by remember { mutableStateOf(false) }
                                val suggestions = remember(row.name, masterItems) {
                                    val q = row.name.trim()
                                    if (q.isBlank()) emptyList()
                                    else masterItems.filter { it.name.contains(q, ignoreCase = true) }
                                        .sortedBy { it.name.lowercase() }.take(6)
                                }
                                ExposedDropdownMenuBox(
                                    expanded = expanded && suggestions.isNotEmpty(),
                                    onExpandedChange = { expanded = it },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    OutlinedTextField(
                                        value = row.name,
                                        onValueChange = { row.name = it; expanded = true },
                                        label = { Text("Item name") }, singleLine = true,
                                        modifier = Modifier.menuAnchor().fillMaxWidth()
                                    )
                                    ExposedDropdownMenu(expanded = expanded && suggestions.isNotEmpty(), onDismissRequest = { expanded = false }) {
                                        suggestions.forEach { item ->
                                            DropdownMenuItem(
                                                text = { Text("${item.name}   ₹${trimNum(item.costRate)}") },
                                                onClick = {
                                                    row.name = item.name
                                                    row.price = trimNum(item.costRate)
                                                    expanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                                IconButton(onClick = { rows.removeAt(index) }) { Icon(Icons.Filled.Delete, "Remove", tint = MaterialTheme.colorScheme.error) }
                            }
                            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedTextField(
                                    value = row.qty, onValueChange = { row.qty = it.filter { c -> c.isDigit() || c == '.' } },
                                    label = { Text("Qty") }, singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    modifier = Modifier.width(84.dp)
                                )
                                OutlinedTextField(
                                    value = row.price, onValueChange = { row.price = it.filter { c -> c.isDigit() || c == '.' } },
                                    label = { Text("Rate") }, singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    modifier = Modifier.width(92.dp)
                                )
                                OutlinedTextField(
                                    value = row.unit, onValueChange = { row.unit = it },
                                    label = { Text("Unit") }, singleLine = true,
                                    modifier = Modifier.width(84.dp)
                                )
                                OutlinedButton(onClick = {
                                    pickPurchaseDate(context, if (row.expiryMillis > 0) row.expiryMillis else System.currentTimeMillis()) { row.expiryMillis = it }
                                }) {
                                    Text(if (row.expiryMillis > 0) Format.date(row.expiryMillis) else "Expiry")
                                }
                            }
                        }
                        Divider()
                    }
                }
            }
        }
    }
}

private fun trimNum(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
