package com.billing.pos.ui.order

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.CustOrderAttachment
import com.billing.pos.data.Customer
import com.billing.pos.data.Item
import com.billing.pos.data.OrderAttachmentStore
import com.billing.pos.data.hasTwoUnits
import com.billing.pos.data.primaryChoice
import com.billing.pos.ui.billing.CartLine
import com.billing.pos.ui.billing.UnitPickDialog
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.util.Format
import kotlinx.coroutines.launch

/**
 * Fast, tap-to-add order entry — the Quick Bill grid/cart UX applied to Orders instead of Sales.
 * Saves through the same [OrderViewModel] as the detailed "New Order" screen, so every option
 * that screen has (new customer, remark, photo/file attachments, GPS location) is available here
 * too, just folded into the cart dialog instead of spread across the full-page form.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickOrderScreen(onBack: () -> Unit, vm: OrderViewModel = viewModel()) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val items by vm.items.collectAsStateSafe()
    val customers by vm.customers.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    var query by remember { mutableStateOf("") }
    var selectedCat by remember { mutableStateOf<String?>(null) }   // null = All
    var showCart by remember { mutableStateOf(false) }
    val cart = remember { mutableStateListOf<CartLine>() }
    var selectedCustomer by remember { mutableStateOf<Customer?>(null) }
    var remark by remember { mutableStateOf("") }
    var lat by remember { mutableStateOf(0.0) }
    var lng by remember { mutableStateOf(0.0) }
    val attachments = remember { mutableStateListOf<CustOrderAttachment>() }
    var unitPickFor by remember { mutableStateOf<Item?>(null) }
    var newCust by remember { mutableStateOf(false) }

    fun clearAll() {
        cart.clear(); selectedCustomer = null; remark = ""; lat = 0.0; lng = 0.0; attachments.clear()
    }
    fun addItem(item: Item) {
        if (item.hasTwoUnits) unitPickFor = item
        else { val ch = item.primaryChoice(); cart.add(CartLine(item.id, item.name, ch.price, 0.0, 1.0, unit = ch.unit)) }
    }

    val cats = remember(items) {
        items.map { it.category.trim() }.filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase() }
    }
    val filtered = items.filter {
        (selectedCat == null || it.category.equals(selectedCat, true)) &&
            (query.isBlank() || it.name.contains(query, true) || it.barcode.contains(query, true))
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        uris.forEach { u -> OrderAttachmentStore.copyIn(context, u)?.let(attachments::add) }
    }
    val filePick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { u -> OrderAttachmentStore.copyIn(context, u)?.let(attachments::add) }
    }
    val locationPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) captureLocation(context) { la, lo -> lat = la; lng = lo; vm.message.value = "Location attached" }
        else vm.message.value = "Location permission denied"
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Quick Order") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { clearAll() }) { Icon(Icons.Filled.NoteAdd, "New order (clear)") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    label = { Text("Search item") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    modifier = Modifier.weight(1f)
                )
                Box {
                    IconButton(onClick = { showCart = true }) {
                        Icon(Icons.Filled.ShoppingCart, contentDescription = "Cart", modifier = Modifier.size(30.dp))
                    }
                    if (cart.isNotEmpty()) {
                        Box(
                            Modifier.align(Alignment.TopEnd).size(18.dp).clip(RoundedCornerShape(9.dp))
                                .background(MaterialTheme.colorScheme.error),
                            contentAlignment = Alignment.Center
                        ) { Text("${cart.size}", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }

            Card(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp).clickable { showCart = true },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("TOTAL", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(Format.rupee(cart.sumOf { it.total }), fontWeight = FontWeight.Bold, fontSize = 26.sp)
                }
            }

            Row(Modifier.fillMaxWidth().weight(1f)) {
                Column(Modifier.width(96.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(4.dp)) {
                    QoCatButton("All", selectedCat == null) { selectedCat = null }
                    cats.forEach { c -> QoCatButton(c, selectedCat == c) { selectedCat = c } }
                }
                Box(Modifier.fillMaxHeight().width(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                if (filtered.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        Text("No items", color = MaterialTheme.colorScheme.outline)
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(112.dp),
                        modifier = Modifier.weight(1f).fillMaxHeight().padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(filtered, key = { it.id }) { item -> QoItemTile(item) { addItem(item) } }
                    }
                }
            }
        }
    }

    unitPickFor?.let { item ->
        UnitPickDialog(item = item, onPick = { choice -> cart.add(CartLine(item.id, item.name, choice.price, 0.0, 1.0, unit = choice.unit)); unitPickFor = null }, onDismiss = { unitPickFor = null })
    }

    if (showCart) {
        QuickOrderCartDialog(
            cart = cart, customers = customers, selectedCustomer = selectedCustomer,
            onPickCustomer = { selectedCustomer = it }, onNewCustomer = { newCust = true },
            remark = remark, onRemarkChange = { remark = it },
            attachments = attachments,
            lat = lat, lng = lng,
            onPickGallery = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
            onPickFile = { filePick.launch(arrayOf("*/*")) },
            onPickLocation = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                    captureLocation(context) { la, lo -> lat = la; lng = lo; vm.message.value = "Location attached" }
                else locationPerm.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            },
            onDismiss = { showCart = false },
            onSave = {
                vm.save(null, selectedCustomer, cart.toList(), remark, lat, lng, attachments.toList()) { clearAll(); showCart = false }
            }
        )
    }

    if (newCust) {
        var nm by remember { mutableStateOf("") }
        var ph by remember { mutableStateOf("") }
        var ctype by remember { mutableStateOf("General") }
        var drawNm by remember { mutableStateOf(false) }
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        if (drawNm) {
            com.billing.pos.ui.common.HandwriteTextDialog(
                onResult = { if (it.isNotBlank()) nm = it; drawNm = false },
                onDismiss = { drawNm = false }
            )
        }
        AlertDialog(
            onDismissRequest = { newCust = false },
            title = { Text("New customer") },
            text = {
                Column {
                    OutlinedTextField(
                        value = nm, onValueChange = { nm = it }, label = { Text("Name") }, singleLine = true,
                        trailingIcon = { IconButton(onClick = { drawNm = true }) { Icon(Icons.Filled.Gesture, "Write name") } }
                    )
                    OutlinedTextField(value = ph, onValueChange = { ph = it.filter { c -> c.isDigit() } }, label = { Text("Phone") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.padding(top = 8.dp))
                    com.billing.pos.ui.common.CustomerTypeField(value = ctype, onValue = { ctype = it }, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = { if (nm.isNotBlank()) scope.launch { selectedCustomer = vm.addCustomer(nm.trim(), ph.trim(), ctype); newCust = false } }) { Text("Add") } },
            dismissButton = { TextButton(onClick = { newCust = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun QoCatButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Text(
            label,
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            textAlign = TextAlign.Center,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            maxLines = 2
        )
    }
}

@Composable
private fun QoItemTile(item: Item, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { onClick() }) {
        Column {
            Box(
                Modifier.fillMaxWidth().size(72.dp).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text(item.name.take(1).uppercase(), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.outline)
            }
            Text(
                item.name,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
                maxLines = 2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
            )
            Text(
                Format.rupee(item.price),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp).padding(bottom = 6.dp),
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp
            )
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun QuickOrderCartDialog(
    cart: androidx.compose.runtime.snapshots.SnapshotStateList<CartLine>,
    customers: List<Customer>,
    selectedCustomer: Customer?,
    onPickCustomer: (Customer) -> Unit,
    onNewCustomer: () -> Unit,
    remark: String,
    onRemarkChange: (String) -> Unit,
    attachments: androidx.compose.runtime.snapshots.SnapshotStateList<CustOrderAttachment>,
    lat: Double,
    lng: Double,
    onPickGallery: () -> Unit,
    onPickFile: () -> Unit,
    onPickLocation: () -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Order cart") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    com.billing.pos.ui.common.CustomerPickField(
                        customers = customers,
                        selectedName = selectedCustomer?.name ?: "",
                        onPick = onPickCustomer,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onNewCustomer) { Icon(Icons.Filled.PersonAdd, "New customer", tint = MaterialTheme.colorScheme.primary) }
                }
                OutlinedTextField(
                    value = remark, onValueChange = onRemarkChange, label = { Text("Remark") },
                    singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = onPickGallery, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.PhotoLibrary, "Photo", Modifier.size(16.dp)); Text(" Photo", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(onClick = onPickFile, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.AttachFile, "File", Modifier.size(16.dp)); Text(" File", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(onClick = onPickLocation, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Place, "Location", Modifier.size(16.dp)); Text(" GPS", style = MaterialTheme.typography.labelSmall)
                    }
                }
                val extras = buildList {
                    if (attachments.isNotEmpty()) add("${attachments.size} file(s)")
                    if (lat != 0.0 || lng != 0.0) add("Location " + String.format("%.5f, %.5f", lat, lng))
                }
                if (extras.isNotEmpty()) Text(extras.joinToString("  •  "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
                if (attachments.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        attachments.forEach { att ->
                            AssistChip(
                                onClick = {},
                                label = { Text(att.name, maxLines = 1) },
                                trailingIcon = {
                                    Icon(Icons.Filled.Remove, contentDescription = "Remove",
                                        modifier = Modifier.size(16.dp).clickable { attachments.remove(att) })
                                }
                            )
                        }
                    }
                }
                Divider(Modifier.padding(vertical = 8.dp))

                if (cart.isEmpty()) {
                    Text("Cart is empty. Tap items to add.")
                } else {
                    cart.forEachIndexed { index, line ->
                        var priceText by remember(line.uid) { mutableStateOf(Format.money(line.price)) }
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(line.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1)
                                IconButton(onClick = { cart.removeAt(index) }) { Icon(Icons.Filled.Delete, "Remove", tint = MaterialTheme.colorScheme.error) }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = priceText,
                                    onValueChange = { v -> val f = v.filter { it.isDigit() || it == '.' }; priceText = f; cart[index] = cart[index].copy(price = f.toDoubleOrNull() ?: 0.0) },
                                    label = { Text("Rate") }, singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    modifier = Modifier.width(110.dp)
                                )
                                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                                IconButton(onClick = { val q = cart[index].qty - 1; if (q <= 0) cart.removeAt(index) else cart[index] = cart[index].copy(qty = q) }) { Icon(Icons.Filled.Remove, "Less") }
                                Text(Format.qty(cart[index].qty), fontWeight = FontWeight.Bold)
                                IconButton(onClick = { cart[index] = cart[index].copy(qty = cart[index].qty + 1) }) { Icon(Icons.Filled.Add, "More") }
                                androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
                                Text(Format.rupee(line.total), fontWeight = FontWeight.Bold)
                            }
                            Divider()
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("TOTAL", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(Format.rupee(cart.sumOf { it.total }), fontWeight = FontWeight.Bold, fontSize = 22.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onSave, enabled = selectedCustomer != null && cart.isNotEmpty()) { Text("Save order") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
