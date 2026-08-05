package com.billing.pos.ui.online

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.Item
import com.billing.pos.ui.billing.collectAsStateSafe
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
    val items by vm.items.collectAsStateSafe()
    val uploading by vm.uploading.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.messageShown() }
    }

    val onlineCount = items.count { it.isOnline }

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
        if (items.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                Text("No items yet — add items first, from Masters > Items.", color = MaterialTheme.colorScheme.outline)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items, key = { it.id }) { item ->
                    OnlineItemRow(
                        item = item,
                        onToggle = { on -> vm.setOnline(item, on) },
                        onOfferPrice = { price -> vm.setOfferPrice(item, price) }
                    )
                }
            }
        }
    }
}

@Composable
private fun OnlineItemRow(item: Item, onToggle: (Boolean) -> Unit, onOfferPrice: (Double) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = item.isOnline, onCheckedChange = onToggle)
            Column(Modifier.weight(1f)) {
                Text(item.name, fontWeight = FontWeight.SemiBold)
                Text(
                    "MRP ₹" + Format.money(item.price) + if (item.category.isNotBlank()) " · ${item.category}" else "",
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
    }
}
