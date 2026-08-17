package com.billing.pos.desktop

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.billing.pos.shared.DesktopDatabase
import com.billing.pos.shared.PurchaseLine
import com.billing.pos.shared.Supplier
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val purchaseDateFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PurchasesScreen(onBack: () -> Unit) {
    var purchases by remember { mutableStateOf(DesktopDatabase.allPurchases()) }
    var showNewPurchase by remember { mutableStateOf(false) }

    if (showNewPurchase) {
        NewPurchaseScreen(
            onBack = { showNewPurchase = false },
            onSaved = { showNewPurchase = false; purchases = DesktopDatabase.allPurchases() }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Purchases") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showNewPurchase = true }) { Icon(Icons.Filled.Add, "New purchase") }
        }
    ) { pad ->
        if (purchases.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text("No purchases yet — tap + to record one.", color = MaterialTheme.colorScheme.outline)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
                items(purchases, key = { it.id }) { p ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(p.purchaseNo, fontWeight = FontWeight.Bold)
                            Text(
                                "${p.supplierName}  •  ${purchaseDateFmt.format(Date(p.dateMillis))}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Text("₹${"%.2f".format(p.grandTotal)}", fontWeight = FontWeight.Bold)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewPurchaseScreen(onBack: () -> Unit, onSaved: () -> Unit) {
    val suppliers = remember { DesktopDatabase.allSuppliers() }
    val availableItems = remember { DesktopDatabase.allItems() }
    var selectedSupplier by remember { mutableStateOf<Supplier?>(suppliers.firstOrNull()) }
    var cart by remember { mutableStateOf(listOf<PurchaseLine>()) }
    var showSupplierPicker by remember { mutableStateOf(false) }
    var showItemPicker by remember { mutableStateOf(false) }

    val subTotal = cart.sumOf { it.qty * it.price }
    val taxTotal = cart.sumOf { it.qty * it.price * it.taxPercent / 100.0 }
    val grandTotal = subTotal + taxTotal

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New Purchase") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                HorizontalDivider(Modifier.padding(bottom = 8.dp))
                purchaseTotalRow("Subtotal", subTotal)
                purchaseTotalRow("Tax", taxTotal)
                purchaseTotalRow("Grand Total", grandTotal, bold = true)
                Button(
                    onClick = {
                        val s = selectedSupplier
                        if (s != null && cart.isNotEmpty()) {
                            DesktopDatabase.savePurchase(s.id, s.name, cart)
                            onSaved()
                        }
                    },
                    enabled = selectedSupplier != null && cart.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) { Text("Save Purchase") }
            }
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp)) {
            OutlinedButton(onClick = { showSupplierPicker = true }, modifier = Modifier.fillMaxWidth()) {
                Text(selectedSupplier?.name ?: "Select supplier")
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Items", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                TextButton(onClick = { showItemPicker = true }) { Text("+ Add item") }
            }
            if (cart.isEmpty()) {
                Text("No items added yet.", color = MaterialTheme.colorScheme.outline)
            } else {
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(cart, key = { it.name + cart.indexOf(it) }) { line ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(line.name)
                                Text(
                                    "${line.qty} x ₹${line.price}" + (if (line.taxPercent > 0) "  (+${line.taxPercent}% tax)" else ""),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                                )
                            }
                            OutlinedTextField(
                                value = if (line.qty == line.qty.toLong().toDouble()) line.qty.toLong().toString() else line.qty.toString(),
                                onValueChange = { v ->
                                    val q = v.toDoubleOrNull()
                                    if (q != null && q > 0) cart = cart.map { if (it === line) it.copy(qty = q) else it }
                                },
                                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.width(70.dp)
                            )
                            IconButton(onClick = { cart = cart.filter { it !== line } }) {
                                Icon(Icons.Filled.Delete, "Remove", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (showSupplierPicker) {
        AlertDialog(
            onDismissRequest = { showSupplierPicker = false },
            title = { Text("Select supplier") },
            text = {
                LazyColumn {
                    items(suppliers, key = { it.id }) { s ->
                        Text(
                            s.name,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { selectedSupplier = s; showSupplierPicker = false }
                                .padding(vertical = 10.dp)
                        )
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showSupplierPicker = false }) { Text("Cancel") } }
        )
    }

    if (showItemPicker) {
        AlertDialog(
            onDismissRequest = { showItemPicker = false },
            title = { Text("Pick item") },
            text = {
                LazyColumn {
                    items(availableItems, key = { it.id }) { i ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    val existing = cart.firstOrNull { it.name == i.name }
                                    cart = if (existing != null) {
                                        cart.map { if (it === existing) it.copy(qty = it.qty + 1) else it }
                                    } else {
                                        cart + PurchaseLine(purchaseId = 0, name = i.name, qty = 1.0, price = i.purchasePrice, taxPercent = i.taxPercent, lineTotal = 0.0)
                                    }
                                    showItemPicker = false
                                }
                                .padding(vertical = 10.dp)
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(i.name)
                                Text("Cost ₹${i.purchasePrice}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                            }
                        }
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showItemPicker = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun purchaseTotalRow(label: String, amount: Double, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
        Text("₹${"%.2f".format(amount)}", fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
    }
}
