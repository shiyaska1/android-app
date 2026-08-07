package com.billing.pos.ui.online

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.License
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.ui.common.rememberThumbnail
import com.billing.pos.util.Format

/**
 * Which of the shop's own items go into the online catalog, with an optional offer price.
 * "Upload" POSTs the selection to [com.billing.pos.data.AppPrefs.onlineCatalogUrl] — see
 * [com.billing.pos.customer.OnlineCatalogUpload]. The shop code and catalog URL themselves are
 * set once in Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineItemsScreen(onBack: () -> Unit, vm: OnlineItemsViewModel = viewModel()) {
    val rows by vm.rows.collectAsStateSafe()
    val uploading by vm.uploading.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()
    val showProLimitDialog by vm.showProLimitDialog.collectAsStateSafe()
    val snackbar = remember { SnackbarHostState() }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedCategory by rememberSaveable { mutableStateOf("All") }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.messageShown() }
    }

    if (showProLimitDialog) {
        com.billing.pos.ui.common.ProLimitDialog(
            title = "${License.ONLINE_CATALOG_FREE_LIMIT}-item free limit reached",
            message = "The free plan lists up to ${License.ONLINE_CATALOG_FREE_LIMIT} distinct items online at once. " +
                "Call or WhatsApp ${License.SUPPORT_PHONE} with your Device ID below to get a Pro key for unlimited items.",
            deviceId = vm.deviceId,
            onDismiss = { vm.dismissProLimitDialog() },
            onActivated = { vm.dismissProLimitDialog() }
        ) { key -> vm.activatePro(key) }
    }

    val onlineCount = rows.count { it.item.isOnline }
    val categories = remember(rows) {
        listOf("All") + rows.map { it.item.category }.filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase() }
    }
    val shown = remember(rows, selectedCategory, searchQuery) {
        rows
            .filter { selectedCategory == "All" || it.item.category == selectedCategory }
            .filter {
                searchQuery.isBlank() ||
                    it.item.name.contains(searchQuery, ignoreCase = true) ||
                    it.item.category.contains(searchQuery, ignoreCase = true)
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Online Items" + if (onlineCount > 0) " ($onlineCount)" else "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { vm.upload() },
                icon = {
                    if (uploading) CircularProgressIndicator(modifier = Modifier.padding(2.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Filled.CloudUpload, contentDescription = null)
                },
                text = { Text("Upload") }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        if (rows.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                Text("No items yet — add items first, from Masters > Items.", color = MaterialTheme.colorScheme.outline)
            }
        } else {
            Column(Modifier.fillMaxSize().padding(padding)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search items or category…") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                )
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
                if (shown.isEmpty()) {
                    Column(Modifier.fillMaxSize().padding(24.dp)) {
                        Text("No items match \"$searchQuery\"", color = MaterialTheme.colorScheme.outline)
                    }
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(shown, key = { it.item.id }) { row ->
                            OnlineItemCard(
                                row = row,
                                onToggle = { on -> vm.setOnline(row.item, on) },
                                onOfferPrice = { price -> vm.setOfferPrice(row.item, price) },
                                onDriveLink = { link -> vm.setDriveLink(row.item, link) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OnlineItemCard(
    row: OnlineItemRow,
    onToggle: (Boolean) -> Unit,
    onOfferPrice: (Double) -> Unit,
    onDriveLink: (String) -> Unit
) {
    val item = row.item
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = item.isOnline, onCheckedChange = onToggle)
                val thumb = row.photoPath?.let { rememberThumbnail(it, 200) }
                if (thumb != null) {
                    androidx.compose.foundation.Image(
                        thumb,
                        contentDescription = item.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(44.dp).padding(end = 8.dp)
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(item.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        "MRP ₹" + Format.money(item.price) + if (item.category.isNotBlank()) " · ${item.category}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        "Stock: ${Format.qty(row.stock)} ${item.unit}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                if (item.isOnline) {
                    var text by remember(item.id) {
                        androidx.compose.runtime.mutableStateOf(
                            if (item.onlineOfferPrice > 0.0) Format.money(item.onlineOfferPrice) else ""
                        )
                    }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { v ->
                            text = v
                            onOfferPrice(v.toDoubleOrNull() ?: 0.0)
                        },
                        label = { Text("Offer ₹") },
                        singleLine = true,
                        modifier = Modifier.width(110.dp)
                    )
                }
            }
            if (item.isOnline) {
                var link by remember(item.id) { androidx.compose.runtime.mutableStateOf(item.driveLink) }
                OutlinedTextField(
                    value = link,
                    onValueChange = { v -> link = v; onDriveLink(v) },
                    label = { Text("Product / catalog links (optional)") },
                    supportingText = { Text("One or more links, comma-separated — Google Drive, your own website, etc. A direct image link (.jpg/.png/...) shows the customer a photo they can open full-screen and zoom; anything else shows as a plain link they open in their browser") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, top = 4.dp)
                )
            }
        }
    }
}
