package com.billing.pos.ui.billing

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.BillWithItems
import com.billing.pos.data.PaymentMethod
import com.billing.pos.pdf.InvoicePdf
import com.billing.pos.print.ThermalPrinter
import com.billing.pos.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillingScreen(
    onOpenReports: () -> Unit,
    vm: BillingViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val customers by vm.customers.collectAsStateSafe()
    val items by vm.items.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()

    var showNewCustomer by remember { mutableStateOf(false) }
    var showNewItem by remember { mutableStateOf(false) }
    var showItemPicker by remember { mutableStateOf(false) }

    // Show one-off messages.
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }

    // Bluetooth permission → print when granted.
    val printPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) scope.launch { doPrint(context, vm, snackbar) }
        else scope.launch { snackbar.showSnackbar("Bluetooth permission denied") }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("New Bill") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                actions = {
                    IconButton(onClick = onOpenReports) {
                        Icon(Icons.Filled.Assessment, contentDescription = "Sales report")
                    }
                    IconButton(onClick = { vm.newBill() }) {
                        Icon(Icons.Filled.NoteAdd, contentDescription = "New bill")
                    }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(12.dp)
        ) {
            // --- Header: bill no + date ---
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Label("Bill No")
                        Text(vm.billNo, fontWeight = FontWeight.Bold)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Label("Date")
                        Text(Format.date(vm.dateMillis), fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(Modifier.padding(6.dp))

            // --- Customer selector + New ---
            Row(verticalAlignment = Alignment.CenterVertically) {
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                    modifier = Modifier.weight(1f)
                ) {
                    OutlinedTextField(
                        readOnly = true,
                        value = vm.selectedCustomer?.name ?: "Select customer",
                        onValueChange = {},
                        label = { Text("Customer") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        customers.forEach { c ->
                            DropdownMenuItem(
                                text = { Text(c.name + if (c.isDefault) "  (default)" else "") },
                                onClick = { vm.selectCustomer(c); expanded = false }
                            )
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { showNewCustomer = true }) {
                    Icon(Icons.Filled.Add, null); Text("New")
                }
            }

            Spacer(Modifier.padding(4.dp))

            // --- Payment method ---
            Label("Payment method")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PaymentMethod.values().forEach { m ->
                    FilterChip(
                        selected = vm.payment == m,
                        onClick = { vm.setPayment(m) },
                        label = { Text(m.label) }
                    )
                }
            }

            Spacer(Modifier.padding(4.dp))

            // --- Add item buttons ---
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showItemPicker = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Add, null); Text("Add item")
                }
                OutlinedButton(onClick = { showNewItem = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.NoteAdd, null); Text("New item")
                }
            }

            Spacer(Modifier.padding(4.dp))

            // --- Cart grid ---
            Card(Modifier.weight(1f).fillMaxWidth()) {
                if (vm.cart.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No items added", color = MaterialTheme.colorScheme.outline)
                    }
                } else {
                    LazyColumn(Modifier.padding(8.dp)) {
                        itemsIndexed(vm.cart) { index, line ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(line.name, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        Format.rupee(line.price) +
                                            if (line.taxPercent > 0) "  +${Format.money(line.taxPercent)}%" else "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                                IconButton(onClick = { vm.changeQty(index, -1.0) }) {
                                    Icon(Icons.Filled.Remove, "Decrease")
                                }
                                Text(Format.qty(line.qty), fontWeight = FontWeight.Bold)
                                IconButton(onClick = { vm.changeQty(index, 1.0) }) {
                                    Icon(Icons.Filled.Add, "Increase")
                                }
                                Text(
                                    Format.rupee(line.total),
                                    modifier = Modifier.width(84.dp),
                                    fontWeight = FontWeight.Bold,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.End
                                )
                                IconButton(onClick = { vm.removeLine(index) }) {
                                    Icon(Icons.Filled.Delete, "Remove", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                            Divider()
                        }
                    }
                }
            }

            Spacer(Modifier.padding(4.dp))

            // --- Totals + charges ---
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    TotalRow("Sub Total", Format.rupee(vm.subTotal))
                    TotalRow("Tax", Format.rupee(vm.taxTotal))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Additional charge", Modifier.weight(1f))
                        OutlinedTextField(
                            value = vm.additionalChargeText,
                            onValueChange = { vm.setAdditionalCharge(it.filter { c -> c.isDigit() || c == '.' }) },
                            singleLine = true,
                            placeholder = { Text("0") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.width(120.dp)
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Discount", Modifier.weight(1f))
                        OutlinedTextField(
                            value = vm.discountText,
                            onValueChange = { vm.setDiscount(it.filter { c -> c.isDigit() || c == '.' }) },
                            singleLine = true,
                            placeholder = { Text("0") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.width(120.dp)
                        )
                    }
                    Divider(Modifier.padding(vertical = 6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("GRAND TOTAL", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text(Format.rupee(vm.grandTotal), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }

            Spacer(Modifier.padding(4.dp))

            // --- Actions: Save / PDF+Share / Print ---
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { scope.launch { vm.saveCurrent() } },
                    modifier = Modifier.weight(1f)
                ) { Text("Save") }

                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val saved = vm.saveCurrent() ?: return@launch
                            sharePdf(context, vm.shopName, saved)
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Icon(Icons.Filled.Share, null); Text("PDF") }

                OutlinedButton(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                            !ThermalPrinter.hasConnectPermission(context)
                        ) {
                            printPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                        } else {
                            scope.launch { doPrint(context, vm, snackbar) }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Icon(Icons.Filled.Print, null); Text("Print") }
            }
        }
    }

    if (showNewCustomer) {
        NewCustomerDialog(
            onDismiss = { showNewCustomer = false },
            onSave = { n, p, a -> vm.addCustomer(n, p, a) { showNewCustomer = false } }
        )
    }
    if (showNewItem) {
        NewItemDialog(
            onDismiss = { showNewItem = false },
            onSave = { n, price, tax, add -> vm.addItem(n, price, tax, add) { showNewItem = false } }
        )
    }
    if (showItemPicker) {
        ItemPickerDialog(
            items = items,
            onDismiss = { showItemPicker = false },
            onPick = { vm.addItemToCart(it); showItemPicker = false },
            onNewItem = { showItemPicker = false; showNewItem = true }
        )
    }
}

private suspend fun doPrint(
    context: android.content.Context,
    vm: BillingViewModel,
    snackbar: SnackbarHostState
) {
    val saved = vm.saveCurrent() ?: return
    val result = withContext(Dispatchers.IO) {
        runCatching { ThermalPrinter.printBill(context, vm.shopName, saved.bill, saved.lines) }
    }
    result.onSuccess { snackbar.showSnackbar("Sent to printer") }
        .onFailure { snackbar.showSnackbar(it.message ?: "Print failed") }
}

private fun sharePdf(context: android.content.Context, shopName: String, saved: BillWithItems) {
    val uri = InvoicePdf.generate(context, shopName, saved.bill, saved.lines)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share invoice").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    })
}

@Composable
private fun Label(text: String) =
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)

@Composable
private fun TotalRow(label: String, value: String) =
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(value)
    }
