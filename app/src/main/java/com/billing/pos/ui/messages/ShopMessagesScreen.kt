package com.billing.pos.ui.messages

import android.Manifest
import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.customer.BroadcastPromotion
import com.billing.pos.customer.OrderStatusPush
import com.billing.pos.customer.ShopMessagesFetch
import com.billing.pos.customer.ThumbnailCompressor
import com.billing.pos.data.AppDatabase
import com.billing.pos.data.Customer
import com.billing.pos.data.Repository
import com.billing.pos.data.ShopMessage
import com.billing.pos.ui.billing.collectAsStateSafe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Decodes a base64 data URI attachment to a small preview bitmap for the chat bubble — e.g. a
 *  customer's UPI payment-success screenshot attached as proof after paying via the QR-code
 *  fallback (see CustomerCatalogScreen's PaymentDialog). */
private fun decodeAttachmentBitmap(dataUri: String): androidx.compose.ui.graphics.ImageBitmap? {
    if (!dataUri.startsWith("data:image")) return null
    val comma = dataUri.indexOf(',')
    if (comma < 0) return null
    return runCatching {
        val bytes = android.util.Base64.decode(dataUri.substring(comma + 1), android.util.Base64.DEFAULT)
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }.getOrNull()
}

/** Opens an attachment in the device's default image viewer — same pattern as
 *  OnlineOrdersScreen's openAttachment, for a message bubble's photo. */
private fun openMessageAttachment(context: android.content.Context, dataUri: String) {
    if (!dataUri.startsWith("data:image")) return
    val comma = dataUri.indexOf(',')
    if (comma < 0) return
    runCatching {
        val bytes = android.util.Base64.decode(dataUri.substring(comma + 1), android.util.Base64.DEFAULT)
        val sharedDir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
        val file = java.io.File(sharedDir, "msg_attach_${System.nanoTime()}.jpg")
        file.writeBytes(bytes)
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "image/jpeg")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }
}

/** One row in the "Messages" list — every message with this customer collapsed to its latest,
 *  plus how many incoming ones are still unread. */
data class ChatThread(
    val customerPhone: String,
    val customerName: String,
    val lastText: String,
    val lastAt: Long,
    val unreadCount: Int
)

class ShopMessagesViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDatabase.get(app).shopMessageDao()
    private val repo = Repository(app)
    private val prefs = com.billing.pos.data.AppPrefs(app)
    val deviceId: String get() = com.billing.pos.data.License.deviceId(getApplication())

    /** Customers who registered through the online-ordering app (tagged "Online Customer" — see
     *  OrdersFetch/OnlineCustomersFetch) and have a phone on file — the pick list for "Message
     *  selected customers" (see SelectCustomersDialog). Scoped to online customers specifically
     *  since a message here is an app notification, which only reaches someone who's actually
     *  opened the app; a walk-in customer added the regular way has no app install to receive it. */
    val customers: StateFlow<List<Customer>> =
        repo.customers
            .map { list -> list.filter { it.phone.isNotBlank() && it.customerType == "Online Customer" } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _showProLimitDialog = MutableStateFlow(false)
    val showProLimitDialog: StateFlow<Boolean> = _showProLimitDialog
    fun dismissProLimitDialog() { _showProLimitDialog.value = false }

    /** The notification cap that currently applies — 50/day during the free trial, 100/day once
     *  activated, ignored entirely once Pro is unlocked (see [com.billing.pos.data.License.notificationDailyLimit]). */
    val notificationLimit: Int get() = com.billing.pos.data.License.notificationDailyLimit(getApplication())

    /** Validates [key] against this device's id and, if it matches, unlocks unlimited online
     *  items AND unlimited daily notifications for good (same key gates both). */
    fun activatePro(key: String): Boolean {
        val valid = com.billing.pos.data.License.isOnlineCatalogProValid(deviceId, key)
        if (valid) prefs.onlineCatalogPro = true
        return valid
    }

    val messages: StateFlow<List<ShopMessage>> =
        dao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending

    private val _broadcasting = MutableStateFlow(false)
    val broadcasting: StateFlow<Boolean> = _broadcasting

    private val _broadcastResult = MutableStateFlow<String?>(null)
    val broadcastResult: StateFlow<String?> = _broadcastResult

    fun broadcastResultShown() { _broadcastResult.value = null }

    /** Sends [text] to every customer who has ever registered a push token for this shop — a
     *  sales offer, a new-stock announcement, etc. See [BroadcastPromotion]. */
    fun sendPromotion(text: String) {
        if (text.isBlank() || _broadcasting.value) return
        if (!com.billing.pos.data.License.reserveNotificationSend(getApplication())) {
            _showProLimitDialog.value = true
            return
        }
        viewModelScope.launch {
            _broadcasting.value = true
            _broadcastResult.value = when (val result = BroadcastPromotion.send(getApplication(), text)) {
                is BroadcastPromotion.Result.Ok ->
                    if (result.count > 0) "Sent to ${result.count} customer(s)"
                    else "No customers registered for promotions yet — they need to have opened the app at least once"
                is BroadcastPromotion.Result.Failed -> "Failed to send: ${result.message}"
            }
            _broadcasting.value = false
        }
    }

    /** Collapses the flat message list into one row per customer, latest message on top. */
    fun threads(all: List<ShopMessage>): List<ChatThread> =
        all.groupBy { it.customerPhone }.map { (phone, msgs) ->
            val last = msgs.maxByOrNull { it.sentAt }!!
            ChatThread(
                customerPhone = phone,
                customerName = msgs.firstOrNull { it.customerName.isNotBlank() }?.customerName?.ifBlank { phone } ?: phone,
                lastText = last.text,
                lastAt = last.sentAt,
                unreadCount = msgs.count { it.direction == "IN" && !it.read }
            )
        }.sortedByDescending { it.lastAt }

    fun refresh() {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            val app: Application = getApplication()
            // Pop an actual system notification here too, not just on the background poll/push
            // path (ShopOrderPollReceiver) — otherwise opening this screen yourself never alerts
            // you the way a background fetch would.
            when (val result = ShopMessagesFetch.fetch(app)) {
                is ShopMessagesFetch.Result.Ok -> result.fresh.forEach {
                    com.billing.pos.customer.ShopNotifications.showNewMessage(app, it.customerPhone, it.customerName, it.text)
                }
                is ShopMessagesFetch.Result.Failed -> {}
            }
            _refreshing.value = false
        }
    }

    fun markThreadRead(phone: String) {
        viewModelScope.launch { dao.markReadForCustomer(phone) }
    }

    /** Clears one customer's whole chat thread — see [com.billing.pos.data.ShopMessageDao.deleteForCustomers]. */
    fun deleteThread(phone: String) {
        viewModelScope.launch { dao.deleteForCustomers(listOf(phone)) }
    }

    /** Clears several chats at once — the Messages list's "Select" / "Delete selected" bulk
     *  action, to clear out old conversations and free up space. */
    fun deleteThreads(phones: Set<String>) {
        if (phones.isEmpty()) return
        viewModelScope.launch { dao.deleteForCustomers(phones.toList()) }
    }

    /** Wipes the shop's entire local chat history, every customer — a full reset when the list
     *  has piled up. Doesn't touch anything server-side or the Customer master. */
    fun clearAllMessages() {
        viewModelScope.launch { dao.deleteAll() }
    }

    fun send(phone: String, name: String, text: String, amount: Double = 0.0, attachments: List<String> = emptyList()) {
        if ((text.isBlank() && amount <= 0.0 && attachments.isEmpty()) || _sending.value) return
        viewModelScope.launch {
            _sending.value = true
            if (com.billing.pos.data.License.reserveNotificationSend(getApplication())) {
                OrderStatusPush.push(
                    getApplication(), customerPhone = phone, orderId = "", message = text,
                    customerName = name, amount = amount, attachments = attachments
                )
            } else {
                _showProLimitDialog.value = true
            }
            _sending.value = false
        }
    }

    /** Sends the same [text] to each of [recipients] individually (each lands in that customer's
     *  own chat thread, same as a one-to-one reply, rather than the anonymous broadcast — see
     *  SelectCustomersDialog). Stops partway through and surfaces the Pro dialog if the daily
     *  notification cap is hit mid-send; whatever went out before that already sent. */
    fun sendToMany(recipients: List<Pair<String, String>>, text: String) {
        if (text.isBlank() || recipients.isEmpty() || _sending.value) return
        viewModelScope.launch {
            _sending.value = true
            var sent = 0
            for ((phone, name) in recipients) {
                if (!com.billing.pos.data.License.reserveNotificationSend(getApplication())) {
                    _showProLimitDialog.value = true
                    break
                }
                OrderStatusPush.push(getApplication(), customerPhone = phone, orderId = "", message = text, customerName = name)
                sent++
            }
            _broadcastResult.value = if (sent > 0) "Sent to $sent customer(s)" else null
            _sending.value = false
        }
    }
}

/** Shop owner's chat inbox: every customer who has exchanged a message, grouped with the latest
 *  one on top and an unread badge — tap a row to open the full thread and reply. [initialPhone]
 *  (set when opened from a "new message" notification tap, see [com.billing.pos.MainActivity])
 *  jumps straight into that customer's thread instead of showing the list first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShopMessagesScreen(
    onBack: () -> Unit,
    initialPhone: String? = null,
    vm: ShopMessagesViewModel = viewModel()
) {
    val messages by vm.messages.collectAsStateSafe()
    val refreshing by vm.refreshing.collectAsStateSafe()
    val broadcasting by vm.broadcasting.collectAsStateSafe()
    val sendingToMany by vm.sending.collectAsStateSafe()
    val broadcastResult by vm.broadcastResult.collectAsStateSafe()
    val showProLimitDialog by vm.showProLimitDialog.collectAsStateSafe()
    var selectedPhone by rememberSaveable { mutableStateOf(initialPhone) }
    var showPromotionDialog by rememberSaveable { mutableStateOf(false) }
    var showSelectCustomersDialog by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    // "Select" mode on the thread list, to delete more than one chat at once — mirrors the
    // Invoices list's own multi-select ("selectMode"/"selectedIds"). "Clear all" stays a
    // single-tap escape hatch for wiping everything without selecting each one.
    var selectMode by rememberSaveable { mutableStateOf(false) }
    val selectedPhones = remember { mutableStateListOf<String>() }
    var confirmBulkDelete by remember { mutableStateOf(false) }
    var confirmClearAll by remember { mutableStateOf(false) }

    if (showProLimitDialog) {
        com.billing.pos.ui.common.ProLimitDialog(
            title = "${vm.notificationLimit}-notification daily limit reached",
            message = "Up to ${vm.notificationLimit} customer notifications a day (order updates, chat, offers) can be " +
                "sent right now, to keep your server running smoothly. " +
                "Call or WhatsApp ${com.billing.pos.data.License.SUPPORT_PHONE} with your Device ID below for a Pro key — unlimited notifications, no daily reset.",
            deviceId = vm.deviceId,
            onDismiss = { vm.dismissProLimitDialog() },
            onActivated = { vm.dismissProLimitDialog() }
        ) { key -> vm.activatePro(key) }
    }

    LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(broadcastResult) {
        broadcastResult?.let { snackbar.showSnackbar(it); vm.broadcastResultShown() }
    }

    if (showPromotionDialog) {
        SendPromotionDialog(
            sending = broadcasting,
            onSend = { text -> vm.sendPromotion(text); showPromotionDialog = false },
            onDismiss = { showPromotionDialog = false }
        )
    }
    if (showSelectCustomersDialog) {
        val onlineCustomers by vm.customers.collectAsStateSafe()
        SelectCustomersDialog(
            customers = onlineCustomers,
            sending = sendingToMany,
            onSend = { recipients, text ->
                vm.sendToMany(recipients.map { it.phone to it.name }, text)
                showSelectCustomersDialog = false
            },
            onDismiss = { showSelectCustomersDialog = false }
        )
    }

    val phone = selectedPhone
    if (phone != null) {
        ThreadScreen(
            phone = phone,
            messages = remember(messages, phone) { messages.filter { it.customerPhone == phone }.sortedBy { it.sentAt } },
            vm = vm,
            onBack = { selectedPhone = null }
        )
    } else {
        val threads = remember(messages) { vm.threads(messages) }

        if (confirmBulkDelete) {
            AlertDialog(
                onDismissRequest = { confirmBulkDelete = false },
                title = { Text("Delete ${selectedPhones.size} chat(s)?") },
                text = { Text("Removes these conversations from this phone only. This can't be undone.") },
                confirmButton = {
                    TextButton(onClick = {
                        vm.deleteThreads(selectedPhones.toSet())
                        confirmBulkDelete = false; selectMode = false; selectedPhones.clear()
                    }) { Text("Delete") }
                },
                dismissButton = { TextButton(onClick = { confirmBulkDelete = false }) { Text("Cancel") } }
            )
        }
        if (confirmClearAll) {
            AlertDialog(
                onDismissRequest = { confirmClearAll = false },
                title = { Text("Clear all chats?") },
                text = { Text("Removes every conversation from this phone only. This can't be undone.") },
                confirmButton = {
                    TextButton(onClick = { vm.clearAllMessages(); confirmClearAll = false }) { Text("Clear all") }
                },
                dismissButton = { TextButton(onClick = { confirmClearAll = false }) { Text("Cancel") } }
            )
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        if (selectMode) Text("${selectedPhones.size} selected") else Text("Messages")
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (selectMode) { selectMode = false; selectedPhones.clear() } else onBack()
                        }) {
                            Icon(
                                if (selectMode) Icons.Filled.Close else Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = if (selectMode) "Cancel selection" else "Back"
                            )
                        }
                    },
                    actions = {
                        if (selectMode) {
                            val allSelected = threads.isNotEmpty() && selectedPhones.size == threads.size
                            IconButton(onClick = {
                                if (allSelected) selectedPhones.clear()
                                else { selectedPhones.clear(); selectedPhones.addAll(threads.map { it.customerPhone }) }
                            }) {
                                Icon(Icons.Filled.SelectAll, contentDescription = if (allSelected) "Deselect all" else "Select all")
                            }
                            IconButton(onClick = { if (selectedPhones.isNotEmpty()) confirmBulkDelete = true }, enabled = selectedPhones.isNotEmpty()) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete selected")
                            }
                        } else {
                            if (threads.isNotEmpty()) {
                                IconButton(onClick = { selectMode = true }) {
                                    Icon(Icons.Filled.Checklist, contentDescription = "Select chats")
                                }
                                IconButton(onClick = { confirmClearAll = true }) {
                                    Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear all chats")
                                }
                            }
                            IconButton(onClick = { showSelectCustomersDialog = true }) {
                                Icon(Icons.Filled.Contacts, contentDescription = "Message selected customers")
                            }
                            IconButton(onClick = { showPromotionDialog = true }) {
                                Icon(Icons.Filled.Campaign, contentDescription = "Send promotion to everyone")
                            }
                            IconButton(onClick = { vm.refresh() }) {
                                if (refreshing) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                                }
                            }
                        }
                    }
                )
            },
            snackbarHost = { SnackbarHost(snackbar) }
        ) { pad ->
            if (threads.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                    Text("No messages yet.", color = MaterialTheme.colorScheme.outline)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(pad)) {
                    items(threads, key = { it.customerPhone }) { thread ->
                        ListItem(
                            leadingContent = {
                                if (selectMode) {
                                    Checkbox(
                                        checked = thread.customerPhone in selectedPhones,
                                        onCheckedChange = { checked ->
                                            if (checked) selectedPhones.add(thread.customerPhone) else selectedPhones.remove(thread.customerPhone)
                                        }
                                    )
                                }
                            },
                            headlineContent = { Text(thread.customerName, fontWeight = FontWeight.SemiBold) },
                            supportingContent = { Text(thread.lastText, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingContent = {
                                if (thread.unreadCount > 0) Badge { Text("${thread.unreadCount}") }
                            },
                            modifier = Modifier.fillMaxWidth().clickable {
                                if (selectMode) {
                                    if (thread.customerPhone in selectedPhones) selectedPhones.remove(thread.customerPhone) else selectedPhones.add(thread.customerPhone)
                                } else {
                                    selectedPhone = thread.customerPhone
                                }
                            }
                        )
                        Divider()
                    }
                }
            }
        }
    }
}

/** Composes one message to send to every customer who has ever registered a push token for this
 *  shop (see [BroadcastPromotion]) — a sales offer, a new-stock announcement, etc. */
@Composable
private fun SendPromotionDialog(sending: Boolean, onSend: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Campaign, contentDescription = null) },
        title = { Text("Send promotion") },
        text = {
            Column {
                Text(
                    "Goes out to every customer who has opened your catalog before — great for a sale or a new-stock announcement.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text("Message") },
                    placeholder = { Text("e.g. 20% off on all items this weekend!") },
                    minLines = 3, maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSend(text.trim()) }, enabled = text.isNotBlank() && !sending) {
                if (sending) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Send")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !sending) { Text("Cancel") } }
    )
}

/** Pick one or more customers by checkbox (or "Select all"), then write one message and send it
 *  to each of them individually — each lands in that customer's own chat thread, same as a normal
 *  reply, unlike [SendPromotionDialog]'s anonymous broadcast to everyone who's ever opened the
 *  online-ordering app. [customers] is already scoped to "Online Customer"-tagged, phone-on-file
 *  customers (see ShopMessagesViewModel.customers) — a message here is an app notification, which
 *  only reaches someone who's actually installed and opened the online-ordering app. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectCustomersDialog(
    customers: List<Customer>,
    sending: Boolean,
    onSend: (recipients: List<Customer>, text: String) -> Unit,
    onDismiss: () -> Unit
) {
    val selected = remember { mutableStateListOf<Long>() }
    var text by rememberSaveable { mutableStateOf("") }
    // Same dialog-window edge-to-edge fix as the chat threads (see CustomerCatalogScreen's
    // ChatThreadScreen) — this dialog has its own bottom compose bar, so it needs the same
    // guaranteed-visible-above-the-nav-bar treatment.
    val extraBottomGap = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.12f).dp

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        val allSelected = customers.isNotEmpty() && selected.size == customers.size
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (selected.isEmpty()) "Message online customers" else "${selected.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close") }
                    },
                    actions = {
                        if (customers.isNotEmpty()) {
                            IconButton(onClick = {
                                if (allSelected) selected.clear()
                                else { selected.clear(); selected.addAll(customers.map { it.id }) }
                            }) {
                                Icon(Icons.Filled.SelectAll, contentDescription = if (allSelected) "Deselect all" else "Select all")
                            }
                        }
                    }
                )
            },
            bottomBar = {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(8.dp).padding(bottom = extraBottomGap)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = text, onValueChange = { text = it },
                            placeholder = { Text("Message") },
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { onSend(customers.filter { it.id in selected }, text.trim()) },
                            enabled = !sending && text.isNotBlank() && selected.isNotEmpty()
                        ) {
                            if (sending) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                        }
                    }
                }
            }
        ) { pad ->
            if (customers.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                    Text("No online customers yet — they need to have opened your online-ordering app at least once.", color = MaterialTheme.colorScheme.outline)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(pad)) {
                    items(customers, key = { it.id }) { c ->
                        ListItem(
                            leadingContent = {
                                Checkbox(
                                    checked = c.id in selected,
                                    onCheckedChange = { checked -> if (checked) selected.add(c.id) else selected.remove(c.id) }
                                )
                            },
                            headlineContent = { Text(c.name, fontWeight = FontWeight.SemiBold) },
                            supportingContent = { Text(c.phone) },
                            modifier = Modifier.fillMaxWidth().clickable {
                                if (c.id in selected) selected.remove(c.id) else selected.add(c.id)
                            }
                        )
                        Divider()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThreadScreen(
    phone: String,
    messages: List<ShopMessage>,
    vm: ShopMessagesViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sending by vm.sending.collectAsStateSafe()
    var text by rememberSaveable { mutableStateOf("") }
    var showRequestPayment by rememberSaveable { mutableStateOf(false) }
    val name = messages.firstOrNull { it.customerName.isNotBlank() }?.customerName?.ifBlank { phone } ?: phone
    val listState = rememberLazyListState()
    var confirmDeleteThread by remember { mutableStateOf(false) }
    // Same fix as the customer app's chat thread (see CustomerCatalogScreen.ChatThreadScreen):
    // a fixed dp gap on top of navigationBarsPadding() wasn't reliably enough clearance above a
    // 3-button nav bar on every OEM — 12% of screen height guarantees a visible gap regardless.
    val extraBottomGap = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.12f).dp

    // Attach-photo / record-voice on a reply — always available here (unlike the customer app's
    // own reply, where both are premium-only): premium is a paid customer-facing perk, not
    // something the shop owner needs to unlock to talk to their own customers.
    val pendingAttachments = remember(phone) { mutableStateListOf<String>() }
    var compressing by remember { mutableStateOf(false) }
    var isRecordingVoice by remember { mutableStateOf(false) }
    var voiceRecorder by remember { mutableStateOf<com.billing.pos.audio.VoiceRecorder?>(null) }
    var recordingFile by remember { mutableStateOf<File?>(null) }
    var playingVoiceNote by remember { mutableStateOf<String?>(null) }
    val voicePlayer = remember { android.media.MediaPlayer() }
    var playingVoiceFile by remember { mutableStateOf<File?>(null) }

    fun stopPlayback() {
        runCatching { voicePlayer.stop(); voicePlayer.reset() }
        playingVoiceFile?.delete()
        playingVoiceFile = null
        playingVoiceNote = null
    }
    fun togglePlayback(dataUri: String) {
        if (playingVoiceNote == dataUri) { stopPlayback(); return }
        val file = com.billing.pos.util.VoiceAttachment.decodeToTempFile(context, dataUri) ?: return
        runCatching {
            playingVoiceFile?.delete()
            playingVoiceFile = file
            voicePlayer.reset()
            voicePlayer.setDataSource(file.absolutePath)
            voicePlayer.setOnCompletionListener {
                playingVoiceNote = null
                file.delete()
                if (playingVoiceFile == file) playingVoiceFile = null
            }
            voicePlayer.prepare()
            voicePlayer.start()
            playingVoiceNote = dataUri
        }.onFailure { file.delete() }
    }
    DisposableEffect(Unit) {
        onDispose { runCatching { voicePlayer.release() }; playingVoiceFile?.delete() }
    }

    suspend fun compressPicked(uri: android.net.Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val tempFile = File.createTempFile("reply_", ".jpg", context.cacheDir)
            context.contentResolver.openInputStream(uri)?.use { input -> tempFile.outputStream().use { output -> input.copyTo(output) } }
            val bytes = ThumbnailCompressor.compress(tempFile.absolutePath, maxBytes = 300 * 1024, maxDim = 1024)
            tempFile.delete()
            bytes?.let { "data:image/jpeg;base64," + android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
        }.getOrNull()
    }
    val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        compressing = true
        scope.launch {
            val dataUri = compressPicked(uri)
            compressing = false
            if (dataUri != null) pendingAttachments.add(dataUri)
        }
    }
    fun startVoiceRecording() {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "voice_${System.nanoTime()}.m4a")
        runCatching {
            voiceRecorder = com.billing.pos.audio.VoiceRecorder(file.absolutePath).also { it.start() }
            recordingFile = file
            isRecordingVoice = true
        }.onFailure {
            voiceRecorder = null
            file.delete()
        }
    }
    fun stopVoiceRecording() {
        val rec = voiceRecorder ?: return
        val file = recordingFile
        isRecordingVoice = false
        voiceRecorder = null
        recordingFile = null
        scope.launch {
            withContext(Dispatchers.IO) { rec.stop() }
            if (file != null && file.exists() && file.length() > 0) {
                pendingAttachments.add(withContext(Dispatchers.IO) { com.billing.pos.util.VoiceAttachment.encode(file) })
                file.delete()
            } else {
                file?.delete()
            }
        }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startVoiceRecording()
    }
    fun requestMicAndRecord() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startVoiceRecording()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(phone) { vm.markThreadRead(phone) }
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }

    if (confirmDeleteThread) {
        AlertDialog(
            onDismissRequest = { confirmDeleteThread = false },
            title = { Text("Delete this chat?") },
            text = { Text("Removes the whole conversation with $name from this phone. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmDeleteThread = false; vm.deleteThread(phone); onBack() }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteThread = false }) { Text("Cancel") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { confirmDeleteThread = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete this chat")
                    }
                }
            )
        },
        bottomBar = {
            // navigationBarsPadding lifts this clear of the phone's gesture bar (it was sitting
            // right under it — see the report screenshot); the extra gap on top of that (12% of
            // screen height, see extraBottomGap above) keeps a visible gap so it doesn't hug the
            // edge even on 3-button-nav phones whose insets aren't fully trustworthy.
            Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(8.dp).padding(bottom = extraBottomGap)) {
                if (pendingAttachments.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                        items(pendingAttachments.toList()) { uri ->
                            Box(Modifier.size(56.dp)) {
                                if (com.billing.pos.util.VoiceAttachment.isAudio(uri)) {
                                    Box(
                                        Modifier.fillMaxSize()
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(MaterialTheme.colorScheme.secondaryContainer),
                                        contentAlignment = Alignment.Center
                                    ) { Icon(Icons.Filled.Mic, contentDescription = "Voice note") }
                                } else {
                                    val bmp = remember(uri) { decodeAttachmentBitmap(uri) }
                                    if (bmp != null) {
                                        Image(
                                            bitmap = bmp, contentDescription = "Attachment",
                                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp))
                                        )
                                    }
                                }
                                OutlinedIconButton(
                                    onClick = { pendingAttachments.remove(uri) },
                                    modifier = Modifier.size(18.dp).align(Alignment.TopEnd),
                                    colors = IconButtonDefaults.outlinedIconButtonColors(containerColor = MaterialTheme.colorScheme.surface)
                                ) { Icon(Icons.Filled.Close, contentDescription = "Remove", modifier = Modifier.size(10.dp)) }
                            }
                        }
                    }
                }
                if (isRecordingVoice) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                        Icon(Icons.Filled.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                        Text("  Recording… tap the mic again to stop", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = { showRequestPayment = true }) {
                        Icon(Icons.Filled.Payments, contentDescription = "Request payment")
                    }
                    OutlinedTextField(
                        value = text, onValueChange = { text = it },
                        placeholder = { Text("Message") },
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { galleryPicker.launch("image/*") }, enabled = !compressing && !isRecordingVoice) {
                        if (compressing) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Filled.AttachFile, contentDescription = "Attach a photo")
                    }
                    IconButton(
                        onClick = { if (isRecordingVoice) stopVoiceRecording() else requestMicAndRecord() },
                        enabled = !compressing
                    ) {
                        Icon(
                            if (isRecordingVoice) Icons.Filled.Stop else Icons.Filled.Mic,
                            contentDescription = if (isRecordingVoice) "Stop recording" else "Record a voice note",
                            tint = if (isRecordingVoice) MaterialTheme.colorScheme.error else LocalContentColor.current
                        )
                    }
                    IconButton(
                        onClick = {
                            val toSend = text.trim()
                            if (toSend.isNotBlank() || pendingAttachments.isNotEmpty()) {
                                vm.send(phone, name, toSend, attachments = pendingAttachments.toList())
                                text = ""; pendingAttachments.clear()
                            }
                        },
                        enabled = !sending && !compressing && !isRecordingVoice && (text.isNotBlank() || pendingAttachments.isNotEmpty())
                    ) {
                        if (sending) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        }
    ) { pad ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp)
        ) {
            items(messages, key = { it.id }) { m ->
                val fromShop = m.direction == "OUT"
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = if (fromShop) Arrangement.End else Arrangement.Start
                ) {
                    Surface(
                        color = if (fromShop) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Column {
                            if (m.text.isNotBlank()) {
                                Text(m.text, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
                            }
                            // A customer's UPI payment-success screenshot, attached as proof after
                            // paying via the QR-code fallback (no automatic success signal there,
                            // unlike the direct-pay button) — check it against your own bank/UPI
                            // app before trusting it; tap to open full-size. Voice notes play
                            // in place, same as a photo opens in place.
                            if (m.attachmentList.isNotEmpty()) {
                                Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                                    m.attachmentList.forEach { uri ->
                                        if (com.billing.pos.util.VoiceAttachment.isAudio(uri)) {
                                            Box(
                                                Modifier.size(56.dp).padding(end = 6.dp)
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(MaterialTheme.colorScheme.secondaryContainer)
                                                    .clickable { togglePlayback(uri) },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    if (playingVoiceNote == uri) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                                                    contentDescription = if (playingVoiceNote == uri) "Stop voice note" else "Play voice note"
                                                )
                                            }
                                        } else {
                                            val bmp = remember(uri) { decodeAttachmentBitmap(uri) }
                                            if (bmp != null) {
                                                Image(
                                                    bitmap = bmp,
                                                    contentDescription = "Attachment",
                                                    modifier = Modifier
                                                        .size(120.dp)
                                                        .padding(end = 6.dp)
                                                        .clickable { openMessageAttachment(context, uri) }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            Text(
                                SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(m.sentAt)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showRequestPayment) {
        RequestPaymentDialog(
            sending = sending,
            onSend = { note, amount -> vm.send(phone, name, note, amount); showRequestPayment = false },
            onDismiss = { showRequestPayment = false }
        )
    }
}

/** A chat-only way to ask a customer to pay, for anything that isn't a specific placed order (a
 *  custom service, an advance, a general due) — the same "amount" mechanism [OrderStatusPush]
 *  already sends for a per-order bill (see [com.billing.pos.ui.online.OnlineOrdersScreen]'s
 *  message dialog), just without an orderId attached. The customer's Notifications bell shows a
 *  "Pay via UPI now" button for it exactly the same way, including the QR fallback and payment
 *  screenshot proof; the only difference is there's no specific order to mark paid afterwards —
 *  [CustomerCatalogViewModel.markNotificationPaid] already handles a blank orderId by simply not
 *  touching order history, so this needs nothing extra there. */
@Composable
private fun RequestPaymentDialog(sending: Boolean, onSend: (note: String, amount: Double) -> Unit, onDismiss: () -> Unit) {
    var amountText by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    val amount = amountText.toDoubleOrNull() ?: 0.0
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Payments, contentDescription = null) },
        title = { Text("Request payment") },
        text = {
            Column {
                Text(
                    "Sends a message with a \"Pay via UPI\" button for this amount — not tied to any particular order.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                )
                OutlinedTextField(
                    value = amountText, onValueChange = { amountText = it },
                    label = { Text("Amount") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                )
                OutlinedTextField(
                    value = note, onValueChange = { note = it },
                    label = { Text("Note (optional)") },
                    placeholder = { Text("e.g. Home delivery charge") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSend(note.trim(), amount) }, enabled = amount > 0.0 && !sending) {
                if (sending) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Send")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !sending) { Text("Cancel") } }
    )
}
