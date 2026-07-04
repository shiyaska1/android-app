package com.billing.pos.ui.purchase

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.auth.Session
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.PaymentMethod
import com.billing.pos.pdf.PurchasePdf
import com.billing.pos.print.ThermalPrinter
import com.billing.pos.ui.billing.CustomLineDialog
import com.billing.pos.ui.billing.ItemPickerDialog
import com.billing.pos.ui.billing.NewItemDialog
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.util.Format
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PurchaseScreen(
    editPurchaseId: Long? = null,
    onBack: () -> Unit,
    vm: PurchaseViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(editPurchaseId) { if (editPurchaseId != null && editPurchaseId > 0) vm.startEditing(editPurchaseId) }

    val suppliers by vm.suppliers.collectAsStateSafe()
    val items by vm.items.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()

    var showNewSupplier by remember { mutableStateOf(false) }
    var showNewItem by remember { mutableStateOf(false) }
    var showItemPicker by remember { mutableStateOf(false) }
    var showCustomLine by remember { mutableStateOf(false) }

    val readOnly = vm.editingId != null && !Session.canEdit

    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    val printPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) scope.launch { doPrint(context, vm, snackbar) } }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { vm.onBarcodeScanned(it) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (vm.editingId != null) "Edit Purchase" else "New Purchase") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                actions = {
                    IconButton(onClick = { vm.newPurchase() }) {
                        Icon(Icons.Filled.NoteAdd, contentDescription = "New purchase")
                    }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(12.dp)
        ) {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Purchase No", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        Text(vm.purchaseNo, fontWeight = FontWeight.Bold)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Date", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        Text(Format.date(vm.dateMillis), fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(Modifier.padding(6.dp))

            // Supplier (searchable) + New + Payment, all one line
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                var expanded by remember { mutableStateOf(false) }
                var query by remember { mutableStateOf("") }
                val focusManager = LocalFocusManager.current
                LaunchedEffect(vm.selectedSupplier?.id) { query = vm.selectedSupplier?.name ?: "" }
                val filtered = remember(query, suppliers) {
                    if (query.isBlank()) suppliers
                    else suppliers.filter { it.name.contains(query, true) || it.phone.contains(query) }
                }
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = Modifier.weight(1.5f)) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it; expanded = true },
                        label = { Text("Supplier") }, placeholder = { Text("Search") }, singleLine = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth().onFocusChanged { fs ->
                            if (fs.isFocused) { query = ""; expanded = true } else query = vm.selectedSupplier?.name ?: ""
                        }
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        filtered.forEach { s ->
                            DropdownMenuItem(
                                text = { Text(s.name + if (s.isDefault) "  (default)" else "") },
                                onClick = { vm.selectSupplier(s); query = s.name; expanded = false; focusManager.clearFocus() }
                            )
                        }
                        if (filtered.isEmpty()) DropdownMenuItem(text = { Text("No match") }, onClick = { expanded = false })
                    }
                }
                IconButton(onClick = { showNewSupplier = true }) { Icon(Icons.Filled.PersonAdd, "New supplier") }
                var payExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = payExpanded, onExpandedChange = { payExpanded = it }, modifier = Modifier.weight(1f)) {
                    OutlinedTextField(
                        readOnly = true, value = vm.payment.label, onValueChange = {},
                        label = { Text("Pay") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(payExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = payExpanded, onDismissRequest = { payExpanded = false }) {
                        PaymentMethod.values().forEach { m ->
                            DropdownMenuItem(text = { Text(m.label) }, onClick = { vm.selectPayment(m); payExpanded = false })
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                ToolAction(Icons.Filled.Add, "Item") { showItemPicker = true }
                ToolAction(Icons.Filled.Dialpad, "Price") { showCustomLine = true }
                ToolAction(Icons.Filled.NoteAdd, "New") { showNewItem = true }
                ToolAction(Icons.Filled.QrCodeScanner, "Scan") {
                    scanLauncher.launch(ScanOptions().setPrompt("Scan item barcode").setBeepEnabled(true).setOrientationLocked(false))
                }
            }

            Spacer(Modifier.padding(2.dp))

            Card(Modifier.weight(1f).fillMaxWidth()) {
                if (vm.cart.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No items added", color = MaterialTheme.colorScheme.outline)
                    }
                } else {
                    LazyColumn(Modifier.padding(horizontal = 8.dp)) {
                        itemsIndexed(vm.cart, key = { _, line -> line.uid }) { index, line ->
                            var priceText by remember(line.uid) { mutableStateOf(Format.money(line.price)) }
                            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(line.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    if (line.taxPercent > 0) Text("+${Format.money(line.taxPercent)}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                    IconButton(onClick = { vm.removeLine(index) }) { Icon(Icons.Filled.Delete, "Remove", tint = MaterialTheme.colorScheme.error) }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = priceText,
                                        onValueChange = { v -> val f = v.filter { it.isDigit() || it == '.' }; priceText = f; vm.setLinePrice(index, f.toDoubleOrNull() ?: 0.0) },
                                        label = { Text("Rate") }, singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                        modifier = Modifier.width(116.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    IconButton(onClick = { vm.changeQty(index, -1.0) }) { Icon(Icons.Filled.Remove, "Less") }
                                    Text(Format.qty(line.qty), fontWeight = FontWeight.Bold)
                                    IconButton(onClick = { vm.changeQty(index, 1.0) }) { Icon(Icons.Filled.Add, "More") }
                                    Spacer(Modifier.weight(1f))
                                    Text(Format.rupee(line.total), fontWeight = FontWeight.Bold)
                                }
                                Divider()
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.padding(2.dp))

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("Sub Total", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            Text(Format.rupee(vm.subTotal + vm.taxTotal), fontWeight = FontWeight.SemiBold)
                        }
                        OutlinedTextField(
                            value = vm.additionalChargeText,
                            onValueChange = { vm.setAdditionalCharge(it.filter { c -> c.isDigit() || c == '.' }) },
                            singleLine = true, label = { Text("Add.") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.width(96.dp)
                        )
                        OutlinedTextField(
                            value = vm.discountText,
                            onValueChange = { vm.setDiscount(it.filter { c -> c.isDigit() || c == '.' }) },
                            singleLine = true, label = { Text("Disc.") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.width(96.dp)
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

            if (readOnly) {
                Text("View only — you don't have permission to edit purchases.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.padding(2.dp))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { scope.launch { vm.saveCurrent() } }, enabled = !readOnly, modifier = Modifier.weight(1f)) { Text("Save") }
                OutlinedButton(
                    onClick = { scope.launch { val saved = vm.saveCurrent() ?: return@launch; sharePdf(context, saved) } },
                    modifier = Modifier.weight(1f)
                ) { Icon(Icons.Filled.Share, null); Text("PDF") }
                OutlinedButton(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !ThermalPrinter.hasConnectPermission(context)) {
                            printPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                        } else scope.launch { doPrint(context, vm, snackbar) }
                    },
                    modifier = Modifier.weight(1f)
                ) { Icon(Icons.Filled.Print, null); Text("Print") }
            }
        }
    }

    if (showNewSupplier) {
        NewSupplierDialog(
            onDismiss = { showNewSupplier = false },
            onSave = { n, p, a -> vm.addSupplier(n, p, a) { showNewSupplier = false } }
        )
    }
    if (showNewItem) {
        NewItemDialog(onDismiss = { showNewItem = false }, onSave = { n, price, tax, barcode, add -> vm.addItem(n, price, tax, barcode, add) { showNewItem = false } })
    }
    if (showCustomLine) {
        CustomLineDialog(onDismiss = { showCustomLine = false }, onAdd = { desc, price, tax -> vm.addCustomLine(desc, price, tax) })
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

private suspend fun doPrint(context: android.content.Context, vm: PurchaseViewModel, snackbar: SnackbarHostState) {
    val saved = vm.saveCurrent() ?: return
    val company = AppPrefs(context).company
    val result = withContext(Dispatchers.IO) { runCatching { ThermalPrinter.printPurchase(context, company, saved.purchase, saved.lines) } }
    result.onSuccess { snackbar.showSnackbar("Sent to printer") }.onFailure { snackbar.showSnackbar(it.message ?: "Print failed") }
}

private fun sharePdf(context: android.content.Context, saved: PurchaseWithItems) {
    val company = AppPrefs(context).company
    val uri = PurchasePdf.generate(context, company, saved.purchase, saved.lines)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share purchase").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
private fun ToolAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick) { Icon(icon, contentDescription = label) }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun NewSupplierDialog(onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Supplier") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("Phone") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("Address") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, phone, address) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
