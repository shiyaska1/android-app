package com.billing.pos.ui.customer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.ShopCatalogItem
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.util.Format
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The entire app, for a customer install: the shop's item catalog, grouped by category, with a
 * manual refresh. Cached offline (see [CustomerCatalogViewModel]) so it still opens with data
 * after the first successful fetch. Picking a quantity on any item reveals two ways to act on
 * it: Share (WhatsApp text, no server) or Save (POSTs to the shop's server for a real order —
 * asks for name/phone once, then remembers them).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerCatalogScreen(onExitTestMode: () -> Unit = {}, vm: CustomerCatalogViewModel = viewModel()) {
    val context = LocalContext.current
    val prefs = remember { AppPrefs(context) }
    val items by vm.items.collectAsStateSafe()
    val refreshing by vm.refreshing.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()
    val qty by vm.qty.collectAsStateSafe()
    val saving by vm.saving.collectAsStateSafe()
    val snackbar = remember { SnackbarHostState() }
    var selectedCategory by rememberSaveable { mutableStateOf("All") }
    var showSaveDialog by rememberSaveable { mutableStateOf(false) }
    // Re-read after every fetch (a plain remember would freeze these at first composition).
    val shopName = prefs.shopDisplayName
    val shopPhone = prefs.shopContactPhone
    val catalogLabel = remember {
        when (prefs.customerBusinessType) {
            "Medical store" -> "Medicines"
            "Medical lab" -> "Home Collection"
            "Restaurant" -> "Order"
            else -> "Order"
        }
    }
    val shareLabel = remember {
        when (prefs.customerBusinessType) {
            "Medical store" -> "Send order"
            "Medical lab" -> "Book collection"
            else -> "Share order"
        }
    }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.messageShown() }
    }

    var searchQuery by rememberSaveable { mutableStateOf("") }

    val categories = remember(items) {
        listOf("All") + items.map { it.category }.filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase() }
    }
    val shown = remember(items, selectedCategory, searchQuery) {
        items
            .filter { selectedCategory == "All" || it.category == selectedCategory }
            .filter { searchQuery.isBlank() || it.name.contains(searchQuery, ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(shopName.ifBlank { catalogLabel }) },
                actions = {
                    if (shopPhone.isNotBlank()) {
                        IconButton(onClick = {
                            val digits = shopPhone.filter { it.isDigit() }
                            val msg = android.net.Uri.encode("Hi, I'd like to place an order.")
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://wa.me/$digits?text=$msg")
                            )
                            runCatching { context.startActivity(intent) }
                        }) {
                            Icon(Icons.Default.Chat, contentDescription = "Message shop on WhatsApp")
                        }
                    }
                    IconButton(onClick = { vm.refresh() }) {
                        if (refreshing) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh items")
                        }
                    }
                    if (com.billing.pos.BuildConfig.DEBUG) {
                        IconButton(onClick = {
                            prefs.customerMode = false
                            prefs.onboarded = false
                            prefs.referrerChecked = false
                            onExitTestMode()
                        }) {
                            Icon(Icons.Default.BugReport, contentDescription = "Exit test mode")
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (qty.isNotEmpty()) {
                val total = items.filter { qty.containsKey(it.id) }.sumOf { it.price * (qty[it.id] ?: 0) }
                BottomAppBar {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("${qty.values.sum()} item(s)", style = MaterialTheme.typography.labelMedium)
                            Text("₹" + Format.money(total), fontWeight = FontWeight.Bold)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                val text = vm.orderMessage()
                                val digits = shopPhone.filter { it.isDigit() }
                                val target = if (digits.isNotBlank()) "https://wa.me/$digits" else "https://wa.me/"
                                val intent = android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse("$target?text=${android.net.Uri.encode(text)}")
                                )
                                runCatching { context.startActivity(intent) }
                            }) {
                                Icon(Icons.Default.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text("  $shareLabel")
                            }
                            Button(onClick = { showSaveDialog = true }, enabled = !saving) {
                                if (saving) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Text("  Save")
                                }
                            }
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (vm.lastFetchedAt > 0L) {
                Text(
                    "Updated " + SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(vm.lastFetchedAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }

            if (items.isNotEmpty()) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search items…") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }

            if (categories.size > 1) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(categories) { cat ->
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = cat },
                            label = { Text(cat) }
                        )
                    }
                }
            }

            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (refreshing) {
                        CircularProgressIndicator()
                    } else {
                        Text(
                            "No items yet — tap refresh",
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            } else if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No items match \"$searchQuery\"", color = MaterialTheme.colorScheme.outline)
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(shown, key = { it.id }) { item ->
                        CatalogItemRow(
                            item = item,
                            qty = qty[item.id] ?: 0,
                            onQtyChange = { n -> vm.setQty(item.id, n) }
                        )
                    }
                }
            }
        }
    }

    if (showSaveDialog) {
        SaveOrderDialog(
            initialName = vm.savedCustomerName,
            initialPhone = vm.savedCustomerPhone,
            onDismiss = { showSaveDialog = false },
            onConfirm = { name, phone ->
                showSaveDialog = false
                vm.saveOrder(name, phone)
            }
        )
    }
}

@Composable
private fun SaveOrderDialog(
    initialName: String,
    initialPhone: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, phone: String) -> Unit
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var phone by rememberSaveable { mutableStateOf(initialPhone) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your details") },
        text = {
            Column {
                Text(
                    "So the shop knows who ordered and can reach you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Your name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
                OutlinedTextField(
                    value = phone, onValueChange = { phone = it },
                    label = { Text("Mobile number") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), phone.trim()) },
                enabled = name.isNotBlank() && phone.isNotBlank()
            ) { Text("Save order") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Item photos travel as a base64 data URI in the catalog fetch itself (see ThumbnailCompressor /
 *  OnlineCatalogUpload) — no separate image download, so this just decodes what's already there. */
private fun decodeDataUriBitmap(dataUri: String): androidx.compose.ui.graphics.ImageBitmap? {
    if (!dataUri.startsWith("data:image")) return null
    val comma = dataUri.indexOf(',')
    if (comma < 0) return null
    return runCatching {
        val bytes = android.util.Base64.decode(dataUri.substring(comma + 1), android.util.Base64.DEFAULT)
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?.asImageBitmap()
    }.getOrNull()
}

@Composable
private fun CatalogItemRow(item: ShopCatalogItem, qty: Int, onQtyChange: (Int) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val thumb = remember(item.imageUrl) { decodeDataUriBitmap(item.imageUrl) }
            if (thumb != null) {
                androidx.compose.foundation.Image(
                    thumb,
                    contentDescription = item.name,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.size(52.dp).padding(end = 12.dp)
                )
            }
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (item.description.isNotBlank()) {
                    Text(
                        item.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                Text("₹" + Format.money(item.price) + if (item.unit.isNotBlank()) " / ${item.unit}" else "", fontWeight = FontWeight.Bold)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedIconButton(onClick = { if (qty > 0) onQtyChange(qty - 1) }, enabled = qty > 0) {
                    Icon(Icons.Default.Remove, contentDescription = "Remove one")
                }
                Text(
                    "$qty",
                    modifier = Modifier.padding(horizontal = 10.dp),
                    style = MaterialTheme.typography.titleMedium
                )
                OutlinedIconButton(onClick = { onQtyChange(qty + 1) }) {
                    Icon(Icons.Default.Add, contentDescription = "Add one")
                }
            }
        }
    }
}
