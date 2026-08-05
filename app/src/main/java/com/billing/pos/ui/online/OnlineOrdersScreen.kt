package com.billing.pos.ui.online

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.OnlineOrder
import com.billing.pos.data.OnlineOrderStatus
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.util.Format

/**
 * Shop owner's view of orders customers have placed and saved — see [OrdersFetch] for how they
 * get here (fetched from the server, deduped against Customer by phone, kept locally from then
 * on). Status is managed locally; there's no "Convert to Sale" yet — that's the next step once
 * this is confirmed useful as-is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineOrdersScreen(onBack: () -> Unit, vm: OnlineOrdersViewModel = viewModel()) {
    val context = LocalContext.current
    val orders by vm.orders.collectAsStateSafe()
    val fetching by vm.fetching.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { vm.fetch() }
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.messageShown() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Online Orders" + if (orders.isNotEmpty()) " (${orders.size})" else "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.fetch() }) {
                        if (fetching) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Refresh, contentDescription = "Fetch new orders")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        if (orders.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                Text(
                    if (fetching) "Checking for orders…" else "No orders yet — tap refresh to check again.",
                    color = MaterialTheme.colorScheme.outline
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(orders, key = { it.id }) { order ->
                    OrderCard(
                        order = order,
                        onStatusChange = { status -> vm.setStatus(order, status) },
                        onCall = {
                            val digits = order.customerPhone.filter { it.isDigit() }
                            if (digits.isNotBlank()) {
                                runCatching {
                                    context.startActivity(
                                        android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:$digits"))
                                    )
                                }
                            }
                        },
                        onWhatsApp = {
                            val digits = order.customerPhone.filter { it.isDigit() }
                            if (digits.isNotBlank()) {
                                runCatching {
                                    context.startActivity(
                                        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://wa.me/$digits"))
                                    )
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun OrderCard(
    order: OnlineOrder,
    onStatusChange: (String) -> Unit,
    onCall: () -> Unit,
    onWhatsApp: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(order.customerName, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                    if (order.customerPhone.isNotBlank()) {
                        Text(order.customerPhone, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
                Row {
                    if (order.customerPhone.isNotBlank()) {
                        IconButton(onClick = onCall) { Icon(Icons.Default.Call, contentDescription = "Call") }
                        IconButton(onClick = onWhatsApp) { Icon(Icons.Default.Chat, contentDescription = "WhatsApp") }
                    }
                }
            }

            Column(Modifier.padding(top = 6.dp, bottom = 6.dp)) {
                order.items.forEach { line ->
                    Text(
                        "${line.name} x${line.qty} = ₹${Format.money(line.price * line.qty)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Text("Total: ₹${Format.money(order.total)}", fontWeight = FontWeight.Bold)

            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OnlineOrderStatus.entries.forEach { s ->
                    FilterChip(
                        selected = order.status == s.name,
                        onClick = { onStatusChange(s.name) },
                        label = { Text(s.label) }
                    )
                }
            }
        }
    }
}
