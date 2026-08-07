package com.billing.pos.ui.online

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
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
    val showProLimitDialog by vm.showProLimitDialog.collectAsStateSafe()
    val snackbar = remember { SnackbarHostState() }
    var messageTarget by remember { mutableStateOf<OnlineOrder?>(null) }
    var selectedFilter by rememberSaveable { mutableStateOf("All") } // "All" or an OnlineOrderStatus name
    val shown = remember(orders, selectedFilter) {
        if (selectedFilter == "All") orders else orders.filter { it.status == selectedFilter }
    }

    if (showProLimitDialog) {
        com.billing.pos.ui.common.ProLimitDialog(
            title = "${com.billing.pos.data.License.NOTIFICATION_FREE_LIMIT_PER_DAY}-notification daily limit reached",
            message = "The free plan sends up to ${com.billing.pos.data.License.NOTIFICATION_FREE_LIMIT_PER_DAY} customer " +
                "notifications a day (order updates, chat, offers) to keep your server running smoothly. " +
                "Call or WhatsApp ${com.billing.pos.data.License.SUPPORT_PHONE} with your Device ID below for a Pro key — unlimited notifications, no daily reset.",
            deviceId = vm.deviceId,
            onDismiss = { vm.dismissProLimitDialog() },
            onActivated = { vm.dismissProLimitDialog() }
        ) { key -> vm.activatePro(key) }
    }

    // Without this, "new order"/"new message" alerts are silently dropped on Android 13+
    // (POST_NOTIFICATIONS defaults to denied until explicitly granted). Ask once, right when
    // this screen first opens.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

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
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (orders.isNotEmpty()) {
                val filters = listOf("All") + OnlineOrderStatus.entries.map { it.name }
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filters) { f ->
                        FilterChip(
                            selected = selectedFilter == f,
                            onClick = { selectedFilter = f },
                            label = { Text(if (f == "All") "All" else OnlineOrderStatus.valueOf(f).label) }
                        )
                    }
                }
            }
            if (orders.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(24.dp)) {
                    Text(
                        if (fetching) "Checking for orders…" else "No orders yet — tap refresh to check again.",
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            } else if (shown.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(24.dp)) {
                    Text("No orders in this status.", color = MaterialTheme.colorScheme.outline)
                }
            } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(shown, key = { it.id }) { order ->
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
                        },
                        onLocation = {
                            if (order.location.isNotBlank()) {
                                runCatching {
                                    context.startActivity(
                                        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(order.location))
                                    )
                                }
                            }
                        },
                        onMessage = { messageTarget = order },
                        onShareToSalesman = {
                            val text = buildString {
                                append(order.customerName)
                                if (order.customerPhone.isNotBlank()) append("\n").append(order.customerPhone)
                                if (order.customerAddress.isNotBlank()) append("\n").append(order.customerAddress)
                                if (order.location.isNotBlank()) append("\n").append(order.location)
                            }
                            runCatching {
                                context.startActivity(
                                    android.content.Intent.createChooser(
                                        android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(android.content.Intent.EXTRA_TEXT, text)
                                        },
                                        "Share delivery details"
                                    )
                                )
                            }
                        }
                    )
                }
            }
            }
        }
    }

    messageTarget?.let { order ->
        MessageDialog(
            onDismiss = { messageTarget = null },
            onSend = { text -> vm.sendMessage(order, text); messageTarget = null }
        )
    }
}

@Composable
private fun MessageDialog(onDismiss: () -> Unit, onSend: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Message customer") },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                label = { Text("Message") },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onSend(text.trim()) }, enabled = text.isNotBlank()) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Decodes a base64 data URI attachment to a cache file and opens it in whatever the device's
 *  default image viewer is — that app's own share/save sheet is how the shop owner downloads it,
 *  same as opening any other photo from a chat app. */
private fun openAttachment(context: android.content.Context, dataUri: String) {
    if (!dataUri.startsWith("data:image")) return
    val comma = dataUri.indexOf(',')
    if (comma < 0) return
    runCatching {
        val bytes = android.util.Base64.decode(dataUri.substring(comma + 1), android.util.Base64.DEFAULT)
        // Must be under cacheDir/shared/ — that's the only cache location file_paths.xml exposes
        // through the FileProvider; a file directly in cacheDir's root can't be shared this way.
        val sharedDir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
        val file = java.io.File(sharedDir, "order_attach_${System.nanoTime()}.jpg")
        file.writeBytes(bytes)
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "image/jpeg")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }
}

@Composable
private fun OrderCard(
    order: OnlineOrder,
    onStatusChange: (String) -> Unit,
    onCall: () -> Unit,
    onWhatsApp: () -> Unit,
    onLocation: () -> Unit,
    onMessage: () -> Unit,
    onShareToSalesman: () -> Unit
) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Column {
                Text(order.customerName, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                if (order.customerPhone.isNotBlank()) {
                    Text(order.customerPhone, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                if (order.customerAddress.isNotBlank()) {
                    Text(order.customerAddress, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
            // Its own full-width row, not squeezed next to the name — five icons plus a long
            // name overlapped badly on narrower phones.
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onMessage) { Icon(Icons.Default.Message, contentDescription = "Message customer") }
                IconButton(onClick = onShareToSalesman) { Icon(Icons.Default.Share, contentDescription = "Share to salesman") }
                if (order.location.isNotBlank()) {
                    IconButton(onClick = onLocation) { Icon(Icons.Default.Place, contentDescription = "Delivery location") }
                }
                if (order.customerPhone.isNotBlank()) {
                    IconButton(onClick = onCall) { Icon(Icons.Default.Call, contentDescription = "Call") }
                    IconButton(onClick = onWhatsApp) { Icon(Icons.Default.Chat, contentDescription = "WhatsApp") }
                }
            }

            if (order.note.isNotBlank()) {
                Text(
                    "Note: ${order.note}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            if (order.attachments.isNotEmpty()) {
                Column(Modifier.padding(top = 6.dp)) {
                    order.attachments.forEachIndexed { i, dataUri ->
                        Row(
                            Modifier.fillMaxWidth().clickable { openAttachment(context, dataUri) }.padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.AttachFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            Text(
                                "  Attachment ${i + 1} — tap to open or save",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodySmall,
                                textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                            )
                        }
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

            // A plain Row can't fit all six statuses — it used to squeeze the last chip's text
            // into a vertical letter-stack instead of overflowing sensibly. A horizontally
            // scrollable full-width row never does that, whatever the label length.
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(OnlineOrderStatus.entries.toList()) { s ->
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
