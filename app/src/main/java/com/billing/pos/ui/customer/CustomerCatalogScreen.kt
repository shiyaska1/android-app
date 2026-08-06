package com.billing.pos.ui.customer

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.customer.NearbyShops
import com.billing.pos.customer.ReferralLink
import com.billing.pos.customer.ShopSwitch
import com.billing.pos.customer.TechnicalSupport
import com.billing.pos.customer.ThumbnailCompressor
import com.billing.pos.ocr.rememberListScanner
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.CustomerNotification
import com.billing.pos.data.CustomerOrderHistory
import com.billing.pos.data.OnlineOrderStatus
import com.billing.pos.data.ShopCatalogItem
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.util.Format
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The entire app, for a customer install: the shop's item catalog, grouped by category, with a
 * manual refresh. Cached offline (see [CustomerCatalogViewModel]) so it still opens with data
 * after the first successful fetch. Picking a quantity on any item reveals two ways to place an
 * order — Save, or Share (Save, then also open WhatsApp with the order text) — both go through
 * the same pipeline: register (name/phone, asked once) if not already, then a location fix
 * (compulsory — the shop needs to know where to deliver), then POST to the server.
 */
private data class PendingOrderSubmit(
    val name: String, val phone: String, val address: String,
    val note: String, val attachment: String?, val alsoShare: Boolean
)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerCatalogScreen(onExitTestMode: () -> Unit = {}, vm: CustomerCatalogViewModel = viewModel()) {
    val context = LocalContext.current
    val prefs = remember { AppPrefs(context) }
    val items by vm.items.collectAsStateSafe()
    val refreshing by vm.refreshing.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()
    val technicalError by vm.technicalError.collectAsStateSafe()
    val qty by vm.qty.collectAsStateSafe()
    val saving by vm.saving.collectAsStateSafe()
    val snackbar = remember { SnackbarHostState() }
    var selectedCategory by rememberSaveable { mutableStateOf("All") }
    var showSaveDialog by rememberSaveable { mutableStateOf(false) }
    var saveDialogAction by rememberSaveable { mutableStateOf("save") } // "save" or "share"
    var pendingOrderSubmit by remember { mutableStateOf<PendingOrderSubmit?>(null) }
    var showSwitchShop by rememberSaveable { mutableStateOf(false) }
    var showDirectory by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var showNotifications by rememberSaveable { mutableStateOf(false) }
    val history by vm.history.collectAsStateSafe()
    val notifications by vm.notifications.collectAsStateSafe()
    val unreadNotifications by vm.unreadNotifications.collectAsStateSafe()
    val replying by vm.replying.collectAsStateSafe()
    val shareText by vm.shareText.collectAsStateSafe()
    val scope = rememberCoroutineScope()

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true || grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        val pending = pendingOrderSubmit
        pendingOrderSubmit = null
        if (granted && pending != null) {
            vm.saveOrder(pending.name, pending.phone, pending.address, pending.note, pending.attachment, pending.alsoShare)
        } else if (!granted) {
            scope.launch { snackbar.showSnackbar("Location permission is required to place an order") }
        }
    }
    fun submitOrder(pending: PendingOrderSubmit) {
        val hasLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (hasLocation) {
            vm.saveOrder(pending.name, pending.phone, pending.address, pending.note, pending.attachment, pending.alsoShare)
        } else {
            pendingOrderSubmit = pending
            locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    // Re-read after every fetch (a plain remember would freeze these at first composition).
    val shopName = prefs.shopDisplayName
    val shopPhone = prefs.shopContactPhone

    LaunchedEffect(shareText) {
        shareText?.let { text ->
            val digits = shopPhone.filter { it.isDigit() }
            val target = if (digits.isNotBlank()) "https://wa.me/$digits" else "https://wa.me/"
            val intent = android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("$target?text=${android.net.Uri.encode(text)}")
            )
            runCatching { context.startActivity(intent) }
            vm.shareTextConsumed()
        }
    }
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

    // Order-update notification tap → open the notification list straight away.
    val pendingNotificationsOpen = com.billing.pos.auth.PendingCustomerNotificationsOpen.pending
    LaunchedEffect(pendingNotificationsOpen) {
        if (com.billing.pos.auth.PendingCustomerNotificationsOpen.consume()) showNotifications = true
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
                    IconButton(onClick = { saveDialogAction = "save"; showSaveDialog = true }) {
                        Icon(Icons.Default.EditNote, contentDescription = "Write your order (no items needed)")
                    }
                    IconButton(onClick = { showNotifications = true }) {
                        BadgedBox(badge = { if (unreadNotifications > 0) Badge { Text("$unreadNotifications") } }) {
                            Icon(
                                if (unreadNotifications > 0) Icons.Default.NotificationsActive else Icons.Default.Notifications,
                                contentDescription = "Notifications"
                            )
                        }
                    }
                    IconButton(onClick = { showHistory = true }) {
                        Icon(Icons.Default.History, contentDescription = "Order history")
                    }
                    IconButton(onClick = { showDirectory = true }) {
                        Icon(Icons.Default.Storefront, contentDescription = "Browse shops")
                    }
                    IconButton(onClick = { showSwitchShop = true }) {
                        Icon(Icons.Default.SwapHoriz, contentDescription = "Switch shop")
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
                            OutlinedButton(
                                onClick = {
                                    saveDialogAction = "share"
                                    if (vm.isRegistered) {
                                        submitOrder(
                                            PendingOrderSubmit(
                                                vm.savedCustomerName, vm.savedCustomerPhone, vm.savedCustomerAddress,
                                                note = "", attachment = null, alsoShare = true
                                            )
                                        )
                                    } else {
                                        showSaveDialog = true
                                    }
                                },
                                enabled = !saving
                            ) {
                                Icon(Icons.Default.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text("  $shareLabel")
                            }
                            Button(onClick = { saveDialogAction = "save"; showSaveDialog = true }, enabled = !saving) {
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
            val bannerImage = prefs.shopBannerImage
            if (bannerImage.isNotBlank()) {
                val banner = remember(bannerImage) { decodeDataUriBitmap(bannerImage) }
                if (banner != null) {
                    androidx.compose.foundation.Image(
                        banner,
                        contentDescription = "$shopName banner",
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(120.dp)
                    )
                }
            }
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
            initialAddress = vm.savedCustomerAddress,
            premium = vm.isPremiumShop,
            onDismiss = { showSaveDialog = false },
            onConfirm = { name, phone, address, note, attachment ->
                showSaveDialog = false
                submitOrder(PendingOrderSubmit(name, phone, address, note, attachment, saveDialogAction == "share"))
            }
        )
    }
    if (showSwitchShop) {
        SwitchShopDialog(
            recent = vm.recentShops(),
            onDismiss = { showSwitchShop = false },
            onSwitch = { shop, fromScan -> vm.switchShop(shop, forceFetch = fromScan) { showSwitchShop = false } }
        )
    }
    if (showDirectory) {
        ShopDirectoryDialog(
            shops = vm.knownShops(),
            currentShop = prefs.shopCode,
            currentShopName = shopName,
            onDismiss = { showDirectory = false },
            onPick = { shop, forceFetch -> vm.switchShop(shop, forceFetch = forceFetch) { showDirectory = false } }
        )
    }
    if (showHistory) {
        OrderHistoryDialog(
            history = history,
            onDismiss = { showHistory = false },
            onReorder = { order -> vm.reorder(order); showHistory = false }
        )
    }
    if (showNotifications) {
        LaunchedEffect(Unit) { vm.notificationsOpened() }
        NotificationsDialog(
            notifications = notifications,
            replying = replying,
            onDismiss = { showNotifications = false },
            onDelete = { n -> vm.deleteNotification(n) },
            onClearAll = { vm.clearAllNotifications(); showNotifications = false },
            onReply = { n, text -> vm.replyToNotification(n, text) }
        )
    }
    technicalError?.let { detail ->
        TechnicalErrorDialog(
            detail = detail,
            shopName = shopName,
            shopPhone = shopPhone,
            onDismiss = { vm.technicalErrorShown() }
        )
    }
}

/** Shown when an order save or catalog fetch fails for a reason the customer can't fix
 *  themselves (server down, shop moved servers, etc.) — a snackbar would just vanish and leave
 *  them unsure whether the order went through, so this stays up until dismissed and gives two
 *  numbers to call: the shop directly, and — only here, nowhere else in the app — the developer's
 *  own technical support line for when the shop itself needs to chase it. */
@Composable
private fun TechnicalErrorDialog(
    detail: String,
    shopName: String,
    shopPhone: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    fun call(number: String) {
        runCatching {
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:$number")))
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("Technical error") },
        text = {
            Column {
                Text("Something went wrong reaching the shop's server. Please try again in a moment, or contact the shop directly below.")
                if (detail.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                Divider(Modifier.padding(vertical = 12.dp))
                if (shopPhone.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { call(shopPhone) }.padding(vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Call, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(shopName.ifBlank { "Shop" }, style = MaterialTheme.typography.bodyMedium)
                            Text(shopPhone, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { call(TechnicalSupport.PHONE) }.padding(vertical = 6.dp)
                ) {
                    Icon(Icons.Default.SupportAgent, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("App technical support", style = MaterialTheme.typography.bodyMedium)
                        Text(TechnicalSupport.PHONE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

/** The customer's notification list — order status changes / messages the shop sent. Each one
 *  can be deleted, and replied to right there (the customer side of a live chat with the shop —
 *  the reply is picked up by the shop owner's Messages screen, see [CustomerMessageSend]). */
@Composable
private fun NotificationsDialog(
    notifications: List<CustomerNotification>,
    replying: Boolean,
    onDismiss: () -> Unit,
    onDelete: (CustomerNotification) -> Unit,
    onClearAll: () -> Unit,
    onReply: (CustomerNotification, String) -> Unit
) {
    var replyTargetId by remember { mutableStateOf<Long?>(null) }
    var replyText by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Notifications")
                if (notifications.isNotEmpty()) {
                    TextButton(onClick = onClearAll) { Text("Clear all") }
                }
            }
        },
        text = {
            if (notifications.isEmpty()) {
                Text("No notifications yet.", color = MaterialTheme.colorScheme.outline)
            } else {
                Column {
                    notifications.forEach { n ->
                        val statusLabel = OnlineOrderStatus.entries.find { it.name == n.status }?.label
                        Column(Modifier.padding(vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.Top) {
                                Column(Modifier.weight(1f)) {
                                    if (statusLabel != null) {
                                        Text(statusLabel, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                                    }
                                    if (n.message.isNotBlank()) {
                                        Text(n.message, style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text(
                                        SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(n.receivedAt)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                                IconButton(onClick = { onDelete(n) }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(18.dp))
                                }
                            }
                            if (replyTargetId == n.id) {
                                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = replyText, onValueChange = { replyText = it },
                                        placeholder = { Text("Reply to the shop") },
                                        singleLine = true, modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = {
                                            val text = replyText.trim()
                                            if (text.isNotBlank()) { onReply(n, text); replyText = ""; replyTargetId = null }
                                        },
                                        enabled = !replying && replyText.isNotBlank()
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send reply")
                                    }
                                }
                            } else {
                                TextButton(onClick = { replyTargetId = n.id; replyText = "" }, modifier = Modifier.padding(top = 2.dp)) {
                                    Text("Reply")
                                }
                            }
                            Divider(Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun SaveOrderDialog(
    initialName: String,
    initialPhone: String,
    initialAddress: String,
    premium: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (name: String, phone: String, address: String, note: String, attachmentImage: String?) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf(initialName) }
    var phone by rememberSaveable { mutableStateOf(initialPhone) }
    var address by rememberSaveable { mutableStateOf(initialAddress) }
    var note by rememberSaveable { mutableStateOf("") }
    var attachmentDataUri by rememberSaveable { mutableStateOf<String?>(null) }
    var compressing by remember { mutableStateOf(false) }
    val scanOrderText = rememberListScanner { lines ->
        val scanned = lines.joinToString("\n").trim()
        if (scanned.isNotBlank()) note = if (note.isBlank()) scanned else "$note\n$scanned"
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        compressing = true
        scope.launch {
            attachmentDataUri = withContext(Dispatchers.IO) {
                val tempFile = java.io.File.createTempFile("attach_", ".jpg", context.cacheDir)
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tempFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    val bytes = ThumbnailCompressor.compress(tempFile.absolutePath) ?: return@withContext null
                    "data:image/jpeg;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                }.getOrNull().also { tempFile.delete() }
            }
            compressing = false
        }
    }

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
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                OutlinedTextField(
                    value = address, onValueChange = { address = it },
                    label = { Text("Address (optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Text(
                    "Don't want to pick items? Just write what you want here — type it, or scan a list/photo.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 10.dp)
                )
                OutlinedTextField(
                    value = note, onValueChange = { note = it },
                    label = { Text("Note / your order") },
                    trailingIcon = {
                        IconButton(onClick = { scanOrderText() }) {
                            Icon(Icons.Default.DocumentScanner, contentDescription = "Scan a list into the note")
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
                if (premium) {
                    OutlinedButton(
                        onClick = { photoPicker.launch("image/*") },
                        enabled = !compressing,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) {
                        if (compressing) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(if (attachmentDataUri != null) "  Photo attached — tap to change" else "  Attach a photo (e.g. prescription)")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), phone.trim(), address.trim(), note.trim(), attachmentDataUri) },
                enabled = name.isNotBlank() && phone.isNotBlank() && !compressing
            ) { Text("Save order") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Scan the same QR a shop hands out for install to point this app at a different shop —
 *  no reinstall needed — or tap a recently-used shop to switch straight back. A scan (camera or
 *  a QR photo from the gallery, e.g. one a friend forwarded over WhatsApp) always fetches that
 *  shop's current items, even if it's a shop this device already knows — see [onSwitch]'s
 *  `fromScan` flag. Tapping a "switch back to" entry, by contrast, is just picking an
 *  already-known shop and shows its cache instantly (see [CustomerCatalogViewModel.switchShop]). */
@Composable
private fun SwitchShopDialog(
    recent: List<ShopSwitch.Shop>,
    onDismiss: () -> Unit,
    onSwitch: (shop: ShopSwitch.Shop, fromScan: Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var scanError by remember { mutableStateOf(false) }
    var decoding by remember { mutableStateOf(false) }
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@rememberLauncherForActivityResult
        val shop = ShopSwitch.parse(contents)
        if (shop != null) onSwitch(shop, true) else scanError = true
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanLauncher.launch(ScanOptions().setPrompt("Scan the new shop's QR code").setBeepEnabled(true))
    }
    fun startScan() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            scanLauncher.launch(ScanOptions().setPrompt("Scan the new shop's QR code").setBeepEnabled(true))
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }
    val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        decoding = true
        scope.launch {
            val shop = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = input.readBytes()
                        val opt = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opt)
                        var sample = 1
                        while (opt.outWidth / sample > 2000 || opt.outHeight / sample > 2000) sample *= 2
                        val bmp = android.graphics.BitmapFactory.decodeByteArray(
                            bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                        )
                        bmp?.let { ShopSwitch.decodeFromBitmap(it) }?.let { ShopSwitch.parse(it) }
                    }
                }.getOrNull()
            }
            decoding = false
            if (shop != null) onSwitch(shop, true) else scanError = true
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Switch shop") },
        text = {
            Column {
                Text(
                    "Scan a different shop's QR code to switch — no need to reinstall the app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = { startScan() }, enabled = !decoding, modifier = Modifier.weight(1f)) {
                        Text("Scan QR")
                    }
                    OutlinedButton(
                        onClick = { galleryPicker.launch("image/*") },
                        enabled = !decoding, modifier = Modifier.weight(1f)
                    ) {
                        if (decoding) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text("From gallery")
                        }
                    }
                }
                if (scanError) {
                    Text(
                        "That QR code isn't a shop link.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                if (recent.isNotEmpty()) {
                    Divider(Modifier.padding(vertical = 12.dp))
                    Text("Switch back to:", style = MaterialTheme.typography.labelMedium)
                    recent.forEach { shop ->
                        TextButton(
                            onClick = { onSwitch(shop, false) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(shop.name.ifBlank { shop.shop }, modifier = Modifier.fillMaxWidth()) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

/** Every shop this device has connected to, grouped by the shop's own category (Restaurant,
 *  Medical store, ...) — e.g. scan 3 restaurants' QR codes, and this shows all 3 under
 *  "Restaurant" so the customer can pick which one to order from — plus a "Nearby" tab that asks
 *  the server hosting the CURRENT shop (do=directory) for every other shop sharing that same
 *  deployment and sorts them by distance, so a customer can discover a shop they've never scanned
 *  a QR for. Picking a known shop shows its cache instantly (see
 *  [CustomerCatalogViewModel.switchShop]'s `forceFetch`); picking a nearby one is a discovery —
 *  same as a fresh QR scan — so it always fetches. "Invite a friend" at the top shares the
 *  current shop's own install link (see [ReferralLink]). */
@Composable
private fun ShopDirectoryDialog(
    shops: List<ShopSwitch.Shop>,
    currentShop: String,
    currentShopName: String,
    onDismiss: () -> Unit,
    onPick: (shop: ShopSwitch.Shop, forceFetch: Boolean) -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { AppPrefs(context) }
    val scope = rememberCoroutineScope()
    val grouped = remember(shops) {
        shops.groupBy { it.category.ifBlank { "Other" } }.toSortedMap(compareBy { it.lowercase() })
    }
    var showNearby by remember { mutableStateOf(false) }
    var nearbyLoading by remember { mutableStateOf(false) }
    var nearbyError by remember { mutableStateOf<String?>(null) }
    var nearbyResults by remember { mutableStateOf<List<NearbyShops.Entry>?>(null) }

    fun runNearbySearch() {
        nearbyLoading = true
        nearbyError = null
        scope.launch {
            when (val result = NearbyShops.fetch(context)) {
                is NearbyShops.Result.Ok -> nearbyResults = result.shops
                is NearbyShops.Result.Failed -> nearbyError = result.message
            }
            nearbyLoading = false
        }
    }
    val nearbyLocationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true || grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) runNearbySearch() else nearbyError = "Location permission is required to find nearby shops"
    }
    fun startNearbySearch() {
        val hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (hasPermission) runNearbySearch()
        else nearbyLocationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Browse shops") },
        text = {
            Column {
                OutlinedButton(
                    onClick = {
                        val link = ReferralLink.build(prefs) ?: return@OutlinedButton
                        val text = "Order from ${currentShopName.ifBlank { "this shop" }} on the POS Billing app — install here: $link"
                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, text)
                        }
                        runCatching { context.startActivity(android.content.Intent.createChooser(intent, "Invite a friend")) }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  Invite a friend to " + currentShopName.ifBlank { "this shop" })
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(selected = !showNearby, onClick = { showNearby = false }, label = { Text("My shops") })
                    FilterChip(
                        selected = showNearby,
                        onClick = {
                            showNearby = true
                            if (nearbyResults == null && !nearbyLoading) startNearbySearch()
                        },
                        label = { Text("Nearby") }
                    )
                }
                Divider(Modifier.padding(vertical = 12.dp))

                if (!showNearby) {
                    if (shops.isEmpty()) {
                        Text("No shops yet — switch shop to scan one.", color = MaterialTheme.colorScheme.outline)
                    } else {
                        grouped.forEach { (category, group) ->
                            Text(category, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp))
                            group.forEach { shop ->
                                val isCurrent = shop.shop == currentShop
                                Column(
                                    Modifier.fillMaxWidth()
                                        .let { if (!isCurrent) it.clickable { onPick(shop, false) } else it }
                                        .padding(vertical = 8.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            shop.name.ifBlank { shop.shop },
                                            fontWeight = FontWeight.Medium,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        if (isCurrent) {
                                            Text(
                                                "  (current)",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                    if (shop.address.isNotBlank()) {
                                        Text(shop.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                                    }
                                }
                                Divider()
                            }
                        }
                    }
                } else {
                    when {
                        nearbyLoading -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("  Searching nearby...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                        nearbyError != null -> Column {
                            Text(nearbyError!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { startNearbySearch() }, modifier = Modifier.padding(top = 4.dp)) { Text("Try again") }
                        }
                        nearbyResults?.isEmpty() == true -> Text(
                            "No other shops with a location set were found on this shop's server.",
                            color = MaterialTheme.colorScheme.outline
                        )
                        else -> nearbyResults?.forEach { entry ->
                            Column(
                                Modifier.fillMaxWidth()
                                    .clickable {
                                        onPick(
                                            ShopSwitch.Shop(
                                                shop = entry.shop, url = prefs.onlineCatalogUrl,
                                                type = entry.category, premium = false,
                                                name = entry.name, category = entry.category, address = entry.address
                                            ),
                                            true
                                        )
                                    }
                                    .padding(vertical = 8.dp)
                            ) {
                                Text(entry.name.ifBlank { entry.shop }, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "%.1f km".format(entry.distanceKm) + (if (entry.address.isNotBlank()) " · ${entry.address}" else ""),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                                )
                            }
                            Divider()
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun OrderHistoryDialog(
    history: List<CustomerOrderHistory>,
    onDismiss: () -> Unit,
    onReorder: (CustomerOrderHistory) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Order history") },
        text = {
            if (history.isEmpty()) {
                Text("No past orders yet.", color = MaterialTheme.colorScheme.outline)
            } else {
                Column {
                    history.forEach { order ->
                        Column(Modifier.padding(vertical = 8.dp)) {
                            Text(
                                SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(order.placedAt)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                            order.items.forEach { line ->
                                Text("${line.name} x${line.qty}", style = MaterialTheme.typography.bodySmall)
                            }
                            if (order.note.isNotBlank()) {
                                Text(order.note, style = MaterialTheme.typography.bodySmall)
                            }
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (order.items.isNotEmpty()) {
                                    Text("₹" + Format.money(order.total), fontWeight = FontWeight.Bold)
                                    TextButton(onClick = { onReorder(order) }) { Text("Re-order") }
                                } else {
                                    Text("Written order", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                }
                            }
                            Divider(Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
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
