package com.billing.pos.desktop

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
import androidx.compose.material.icons.filled.Print
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
import com.billing.pos.shared.BillLine
import com.billing.pos.shared.Customer
import com.billing.pos.shared.DesktopDatabase
import com.billing.pos.shared.InvoicePdf
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SalesScreen(onBack: () -> Unit) {
    var bills by remember { mutableStateOf(DesktopDatabase.allBills()) }
    var showNewSale by remember { mutableStateOf(false) }

    if (showNewSale) {
        NewSaleScreen(
            onBack = { showNewSale = false },
            onSaved = { showNewSale = false; bills = DesktopDatabase.allBills() }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sales") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showNewSale = true }) { Icon(Icons.Filled.Add, "New sale") }
        }
    ) { pad ->
        if (bills.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text("No sales yet — tap + to bill a customer.", color = MaterialTheme.colorScheme.outline)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
                items(bills, key = { it.id }) { b ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(b.billNo, fontWeight = FontWeight.Bold)
                            Text(
                                "${b.customerName}  •  ${dateFmt.format(Date(b.dateMillis))}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Text("₹${"%.2f".format(b.grandTotal)}", fontWeight = FontWeight.Bold)
                        IconButton(onClick = { savePdfFor(b) }) {
                            Icon(Icons.Filled.Print, "Print / Save PDF")
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/** Generates the invoice PDF and prompts a native save-file dialog — Swing's JFileChooser works
 * fine standalone on desktop JVM even inside a Compose app, no extra windowing setup needed. */
private fun savePdfFor(bill: com.billing.pos.shared.Bill) {
    val lines = DesktopDatabase.linesFor(bill.id)
    val chooser = javax.swing.JFileChooser().apply {
        selectedFile = File("${bill.billNo}.pdf")
        fileFilter = javax.swing.filechooser.FileNameExtensionFilter("PDF files", "pdf")
    }
    if (chooser.showSaveDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
        var file = chooser.selectedFile
        if (!file.name.endsWith(".pdf", ignoreCase = true)) file = File(file.parentFile, file.name + ".pdf")
        InvoicePdf.generate(bill, lines, file)
        javax.swing.JOptionPane.showMessageDialog(null, "Saved to ${file.absolutePath}")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewSaleScreen(onBack: () -> Unit, onSaved: () -> Unit) {
    var customers by remember { mutableStateOf(DesktopDatabase.allCustomers()) }
    var availableItems by remember { mutableStateOf(DesktopDatabase.allItems()) }
    var selectedCustomer by remember { mutableStateOf<Customer?>(customers.firstOrNull()) }
    var cart by remember { mutableStateOf(listOf<BillLine>()) }
    var showCustomerPicker by remember { mutableStateOf(false) }
    var showItemPicker by remember { mutableStateOf(false) }
    var showAddCustomer by remember { mutableStateOf(false) }
    var showAddItem by remember { mutableStateOf(false) }

    val subTotal = cart.sumOf { it.qty * it.price }
    val taxTotal = cart.sumOf { it.qty * it.price * it.taxPercent / 100.0 }
    val grandTotal = subTotal + taxTotal

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New Sale") },
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
                totalRow("Subtotal", subTotal)
                totalRow("Tax", taxTotal)
                totalRow("Grand Total", grandTotal, bold = true)
                Button(
                    onClick = {
                        val c = selectedCustomer
                        if (c != null && cart.isNotEmpty()) {
                            DesktopDatabase.saveBill(c.id, c.name, cart)
                            onSaved()
                        }
                    },
                    enabled = selectedCustomer != null && cart.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) { Text("Save Bill") }
            }
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp)) {
            OutlinedButton(onClick = { showCustomerPicker = true }, modifier = Modifier.fillMaxWidth()) {
                Text(selectedCustomer?.name ?: "Select customer")
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

    if (showCustomerPicker) {
        SearchablePickerDialog(
            title = "Select customer",
            items = customers,
            labelOf = { it.name },
            subtitleOf = { it.phone },
            onDismiss = { showCustomerPicker = false },
            onPick = { c -> selectedCustomer = c; showCustomerPicker = false },
            onAddNew = { showCustomerPicker = false; showAddCustomer = true }
        )
    }

    if (showItemPicker) {
        SearchablePickerDialog(
            title = "Pick item",
            items = availableItems,
            labelOf = { it.name },
            subtitleOf = { "₹${it.price}" },
            onDismiss = { showItemPicker = false },
            onPick = { i ->
                val existing = cart.firstOrNull { it.name == i.name }
                cart = if (existing != null) {
                    cart.map { if (it === existing) it.copy(qty = it.qty + 1) else it }
                } else {
                    cart + BillLine(billId = 0, name = i.name, qty = 1.0, price = i.price, taxPercent = i.taxPercent, lineTotal = 0.0)
                }
                showItemPicker = false
            },
            onAddNew = { showItemPicker = false; showAddItem = true }
        )
    }

    if (showAddCustomer) {
        CustomerFormDialog(
            onDismiss = { showAddCustomer = false },
            onSave = { c ->
                DesktopDatabase.addCustomer(c.name, c.phone, c.address)
                customers = DesktopDatabase.allCustomers()
                selectedCustomer = customers.firstOrNull { it.name == c.name } ?: selectedCustomer
                showAddCustomer = false
            }
        )
    }

    if (showAddItem) {
        ItemFormDialog(
            onDismiss = { showAddItem = false },
            onSave = { i ->
                DesktopDatabase.addItem(i)
                availableItems = DesktopDatabase.allItems()
                val saved = availableItems.firstOrNull { it.name == i.name }
                if (saved != null) {
                    cart = cart + BillLine(billId = 0, name = saved.name, qty = 1.0, price = saved.price, taxPercent = saved.taxPercent, lineTotal = 0.0)
                }
                showAddItem = false
            }
        )
    }
}

@Composable
private fun totalRow(label: String, amount: Double, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
        Text("₹${"%.2f".format(amount)}", fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
    }
}
