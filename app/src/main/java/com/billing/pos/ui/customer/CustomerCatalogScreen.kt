package com.billing.pos.ui.customer

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
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
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.customer.NearbyShops
import com.billing.pos.customer.ReferralLink
import com.billing.pos.customer.RemoteImageCache
import com.billing.pos.customer.ShopSwitch
import com.billing.pos.customer.TechnicalSupport
import com.billing.pos.customer.ThumbnailCompressor
import com.billing.pos.ui.common.rememberThumbnail
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The entire app, for a customer install: the shop's item catalog, grouped by category, with a
 * manual refresh. Cached offline (see [CustomerCatalogViewModel]) so it still opens with data
 * after the first successful fetch. Picking a quantity on any item reveals the Order button —
 * register (name/phone, asked once) if not already, then "Are you at the delivery location right
 * now?" — Yes captures the phone's current GPS fix; No asks for a Google Maps link instead
 * (compulsory either way, the shop needs to know where to deliver) — then POST to the server.
 */
private data class PendingOrderSubmit(
    val name: String, val phone: String, val address: String,
    val note: String, val attachments: List<String>
)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerCatalogScreen(
    onExitTestMode: () -> Unit = {},
    // True when a shop owner opened this screen from their own Dashboard (see
    // MainActivity's "customerPreview" route) to show a customer how ordering works, on the
    // same catalog/settings they've already configured — not an actual customer's device.
    // Shows a back arrow instead of touching customerMode/onboarded, so returning to the shop
    // app is a plain nav pop, with nothing to undo.
    isOwnerPreview: Boolean = false,
    onBackToShop: () -> Unit = {},
    vm: CustomerCatalogViewModel = viewModel()
) {
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
    var showDeliveryPointDialog by rememberSaveable { mutableStateOf(false) }
    var showManualLocationDialog by rememberSaveable { mutableStateOf(false) }
    var pendingOrderSubmit by remember { mutableStateOf<PendingOrderSubmit?>(null) }
    var showSwitchShop by rememberSaveable { mutableStateOf(false) }
    var showDirectory by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var showNotifications by rememberSaveable { mutableStateOf(false) }
    val history by vm.history.collectAsStateSafe()
    val notifications by vm.notifications.collectAsStateSafe()
    val unreadNotifications by vm.unreadNotifications.collectAsStateSafe()
    val replying by vm.replying.collectAsStateSafe()
    val scope = rememberCoroutineScope()

    // Note + attachments live here, on the main screen, instead of inside the "Your details"
    // dialog — so a shop with no browsable catalog (e.g. a medical store that only takes a
    // prescription photo) still has somewhere to write/attach without picking any items first.
    var orderNote by rememberSaveable { mutableStateOf("") }
    val orderAttachments = remember { mutableStateListOf<String>() }
    var compressingAttachment by remember { mutableStateOf(false) }
    var viewingAttachment by remember { mutableStateOf<String?>(null) }
    var pendingCameraFile by remember { mutableStateOf<java.io.File?>(null) }

    // 1024px so an attachment stays legible (e.g. a prescription) — not the 240px item-thumbnail
    // size — but still compressed client-side before it ever reaches the server.
    suspend fun compressToDataUri(sourcePath: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = ThumbnailCompressor.compress(sourcePath, maxBytes = 300 * 1024, maxDim = 1024) ?: return@runCatching null
            "data:image/jpeg;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        }.getOrNull()
    }

    val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        compressingAttachment = true
        scope.launch {
            for (uri in uris) {
                val dataUri = withContext(Dispatchers.IO) {
                    val tempFile = java.io.File.createTempFile("attach_", ".jpg", context.cacheDir)
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { input -> tempFile.outputStream().use { output -> input.copyTo(output) } }
                    }
                    val result = compressToDataUri(tempFile.absolutePath)
                    tempFile.delete()
                    result
                }
                if (dataUri != null) orderAttachments.add(dataUri)
            }
            compressingAttachment = false
        }
    }
    val cameraCapture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = pendingCameraFile
        pendingCameraFile = null
        if (ok && f != null) {
            compressingAttachment = true
            scope.launch {
                val dataUri = compressToDataUri(f.absolutePath)
                f.delete()
                if (dataUri != null) orderAttachments.add(dataUri)
                compressingAttachment = false
            }
        } else {
            f?.delete()
        }
    }
    fun launchCamera() {
        // Must be under cacheDir/shared/ — that's the only cache location file_paths.xml exposes
        // through the FileProvider; a file directly in cacheDir's root can't be shared this way.
        val sharedDir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
        val file = java.io.File.createTempFile("attach_cam_", ".jpg", sharedDir)
        pendingCameraFile = file
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        runCatching { cameraCapture.launch(uri) }
            .onFailure { pendingCameraFile?.delete(); pendingCameraFile = null; scope.launch { snackbar.showSnackbar("No camera app found") } }
    }
    // Declaring android.permission.CAMERA in the manifest (needed elsewhere, e.g. QR scanning)
    // means the system camera intent below silently fails without this being granted first —
    // it used to just show "No camera app found", which was misleading; the camera was there,
    // permission just hadn't been asked for.
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else scope.launch { snackbar.showSnackbar("Camera permission is needed to attach a photo") }
    }
    fun requestCameraAndLaunch() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            launchCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    // Without this, every order-status/chat/promotion notification is silently dropped on
    // Android 13+ (POST_NOTIFICATIONS defaults to denied until explicitly granted) — the
    // notification still lands in the in-app bell (that's just a DB insert), it just never pops
    // as a system alert. Ask once, right when the catalog first opens, same as the diary's
    // reminder-permission pattern elsewhere in the app.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    // A real customer's device already has a cached catalog from its last fetch/install; a shop
    // owner previewing on their own phone has never fetched their own shop as a "customer" before,
    // so the list would otherwise open empty until they find the refresh icon themselves.
    LaunchedEffect(isOwnerPreview) { if (isOwnerPreview) vm.refresh() }

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true || grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        val pending = pendingOrderSubmit
        pendingOrderSubmit = null
        if (granted && pending != null) {
            vm.saveOrder(pending.name, pending.phone, pending.address, pending.note, pending.attachments)
        } else if (!granted) {
            scope.launch { snackbar.showSnackbar("Location permission is required to place an order") }
        }
    }
    // "Yes, I'm at the delivery point" path — captures the phone's current GPS fix. Only clears
    // pendingOrderSubmit on the immediate-submit branch; the permission-request branch hands that
    // job to the locationPermission callback above, once the async permission result comes back.
    fun submitOrderAtCurrentLocation(pending: PendingOrderSubmit) {
        val hasLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (hasLocation) {
            vm.saveOrder(pending.name, pending.phone, pending.address, pending.note, pending.attachments)
            pendingOrderSubmit = null
        } else {
            pendingOrderSubmit = pending
            locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
    // "No, I'm not there" path — a pasted Google Maps link stands in for GPS; no location
    // permission needed at all since the phone's own position is never read.
    fun submitOrderWithManualLocation(pending: PendingOrderSubmit, mapsLink: String) {
        vm.saveOrder(pending.name, pending.phone, pending.address, pending.note, pending.attachments, manualLocationLink = mapsLink)
    }

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
                title = { Text(shopName.ifBlank { catalogLabel } + if (isOwnerPreview) " · Preview" else "") },
                navigationIcon = {
                    if (isOwnerPreview) {
                        IconButton(onClick = onBackToShop) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to shop app")
                        }
                    }
                },
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
            // Visible whenever there's something to submit — items picked, a note typed, or a
            // photo attached — not just when items are picked, since some shops (e.g. a medical
            // store) have no browsable catalog at all and only take a note/photo.
            if (qty.isNotEmpty() || orderNote.isNotBlank() || orderAttachments.isNotEmpty()) {
                val total = items.filter { qty.containsKey(it.id) }.sumOf { it.price * (qty[it.id] ?: 0) }
                BottomAppBar {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            if (qty.isNotEmpty()) {
                                Text("${qty.values.sum()} item(s)", style = MaterialTheme.typography.labelMedium)
                                Text("₹" + Format.money(total), fontWeight = FontWeight.Bold)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { showSaveDialog = true }, enabled = !saving) {
                                if (saving) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Text("  Order")
                                }
                            }
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
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
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Updated " + SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(vm.lastFetchedAt)) +
                                " — new offer? tap refresh",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        IconButton(onClick = { vm.refresh() }, modifier = Modifier.size(28.dp)) {
                            if (refreshing) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh to see new offers", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }

            item {
                OrderNoteCard(
                    note = orderNote,
                    onNoteChange = { orderNote = it },
                    attachments = orderAttachments,
                    premium = vm.isPremiumShop,
                    compressing = compressingAttachment,
                    onAddFromCamera = { requestCameraAndLaunch() },
                    onAddFromGallery = { galleryPicker.launch("image/*") },
                    onRemoveAttachment = { orderAttachments.remove(it) },
                    onViewAttachment = { viewingAttachment = it }
                )
            }

            if (items.isNotEmpty()) {
                item {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("Search items…") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }

            if (categories.size > 1) {
                item {
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
            }

            if (items.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        if (refreshing) {
                            CircularProgressIndicator()
                        } else {
                            Text(
                                "No items yet — tap refresh",
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            } else if (shown.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("No items match \"$searchQuery\"", color = MaterialTheme.colorScheme.outline)
                    }
                }
            } else {
                items(shown, key = { it.id }) { item ->
                    CatalogItemRow(
                        item = item,
                        qty = qty[item.id] ?: 0,
                        onQtyChange = { n -> vm.setQty(item.id, n) },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
            }
        }
    }

    if (showSaveDialog) {
        SaveOrderDialog(
            initialName = vm.savedCustomerName,
            initialPhone = vm.savedCustomerPhone,
            initialAddress = vm.savedCustomerAddress,
            onDismiss = { showSaveDialog = false },
            onConfirm = { name, phone, address ->
                showSaveDialog = false
                pendingOrderSubmit = PendingOrderSubmit(name, phone, address, orderNote.trim(), orderAttachments.toList())
                showDeliveryPointDialog = true
                orderNote = ""; orderAttachments.clear()
            }
        )
    }
    if (showDeliveryPointDialog) {
        DeliveryPointDialog(
            onDismiss = { showDeliveryPointDialog = false; pendingOrderSubmit = null },
            onAtLocation = {
                showDeliveryPointDialog = false
                pendingOrderSubmit?.let { submitOrderAtCurrentLocation(it) }
            },
            onNotAtLocation = {
                showDeliveryPointDialog = false
                showManualLocationDialog = true
            }
        )
    }
    if (showManualLocationDialog) {
        ManualLocationDialog(
            onDismiss = { showManualLocationDialog = false; pendingOrderSubmit = null },
            onConfirm = { link ->
                showManualLocationDialog = false
                pendingOrderSubmit?.let { submitOrderWithManualLocation(it, link) }
                pendingOrderSubmit = null
            }
        )
    }
    viewingAttachment?.let { uri ->
        AttachmentPreviewDialog(dataUri = uri, onDismiss = { viewingAttachment = null })
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
            isShopMuted = { shop -> vm.isShopMuted(shop) },
            onDismiss = { showNotifications = false },
            onDelete = { n -> vm.deleteNotification(n) },
            onClearAll = { vm.clearAllNotifications(); showNotifications = false },
            onReply = { n, text -> vm.replyToNotification(n, text) },
            onMuteShop = { shop, muted -> vm.setShopMuted(shop, muted) }
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
    if (saving) {
        // Blocking, full-screen — the server can be slow to respond (especially with photo
        // attachments), and without this the customer has no sign anything is happening and may
        // think the app froze and tap Save again.
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false)
        ) {
            Box(
                Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = androidx.compose.ui.graphics.Color.White)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Sending your order…",
                        color = androidx.compose.ui.graphics.Color.White,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "This can take a moment — please wait",
                        color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
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
    isShopMuted: (String) -> Boolean,
    onDismiss: () -> Unit,
    onDelete: (CustomerNotification) -> Unit,
    onClearAll: () -> Unit,
    onReply: (CustomerNotification, String) -> Unit,
    onMuteShop: (shop: String, muted: Boolean) -> Unit
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
                                    if (n.shopName.isNotBlank()) {
                                        Text(n.shopName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                    }
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
                                if (n.shop.isNotBlank()) {
                                    val muted = isShopMuted(n.shop)
                                    IconButton(onClick = { onMuteShop(n.shop, !muted) }, modifier = Modifier.size(32.dp)) {
                                        Icon(
                                            if (muted) Icons.Default.NotificationsOff else Icons.Default.Notifications,
                                            contentDescription = if (muted) "Unmute ${n.shopName.ifBlank { "this shop" }}" else "Mute ${n.shopName.ifBlank { "this shop" }}",
                                            modifier = Modifier.size(18.dp),
                                            tint = if (muted) MaterialTheme.colorScheme.error else LocalContentColor.current
                                        )
                                    }
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
    onDismiss: () -> Unit,
    onConfirm: (name: String, phone: String, address: String) -> Unit
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var phone by rememberSaveable { mutableStateOf(initialPhone) }
    var address by rememberSaveable { mutableStateOf(initialAddress) }

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
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), phone.trim(), address.trim()) },
                enabled = name.isNotBlank() && phone.isNotBlank()
            ) { Text("Save order") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Shown right after "Your details" — decides whether the order location comes from GPS (the
 *  customer is standing at the delivery point right now) or a pasted Google Maps link (they're
 *  ordering ahead, from somewhere else, e.g. for their home while still at work). */
@Composable
private fun DeliveryPointDialog(onDismiss: () -> Unit, onAtLocation: () -> Unit, onNotAtLocation: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Are you at the delivery point?") },
        text = {
            Text(
                "If you're at the delivery address right now, we'll use this phone's current location. " +
                    "Otherwise, you'll paste a Google Maps location link for where to deliver.",
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = { TextButton(onClick = onAtLocation) { Text("Yes, I'm here") } },
        dismissButton = { TextButton(onClick = onNotAtLocation) { Text("No") } }
    )
}

/** Delivery location is compulsory — the shop needs to know where to deliver — so [onConfirm] is
 *  disabled until something's pasted in. No format validation beyond non-blank: a shop owner
 *  reading a garbled link back is still better than blocking the order outright over it. */
@Composable
private fun ManualLocationDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var link by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delivery location") },
        text = {
            Column {
                Text(
                    "Open Google Maps, drop a pin on the delivery address, and share/paste the link here. This is required.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = link, onValueChange = { link = it },
                    label = { Text("Google Maps link") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(link.trim()) }, enabled = link.isNotBlank()) { Text("Place order") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Note + attachments, always visible on the main catalog screen (not tucked inside the "Your
 *  details" dialog) — so a shop with no browsable catalog at all (e.g. a medical store that only
 *  takes a prescription photo) still has somewhere to write/attach. Several photos can be
 *  attached, from camera or gallery, each removable and viewable before sending. */
@Composable
private fun OrderNoteCard(
    note: String,
    onNoteChange: (String) -> Unit,
    attachments: List<String>,
    premium: Boolean,
    compressing: Boolean,
    onAddFromCamera: () -> Unit,
    onAddFromGallery: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onViewAttachment: (String) -> Unit
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(
                if (premium) "Don't want to pick items? Write what you want here instead, or attach a photo using the icons."
                else "Don't want to pick items? Write what you want here instead.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            OutlinedTextField(
                value = note, onValueChange = onNoteChange,
                label = { Text("Note / your order") },
                minLines = 2, maxLines = 2,
                trailingIcon = if (!premium) null else {
                    {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onAddFromCamera, enabled = !compressing) {
                                Icon(Icons.Default.CameraAlt, contentDescription = "Attach a photo from camera")
                            }
                            IconButton(onClick = onAddFromGallery, enabled = !compressing) {
                                Icon(Icons.Default.AddAPhoto, contentDescription = "Attach a photo from gallery")
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            if (premium) {
                if (compressing) {
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("  Adding attachment…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(start = 6.dp))
                    }
                }
                if (attachments.isNotEmpty()) {
                    LazyRow(
                        contentPadding = PaddingValues(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(attachments) { uri ->
                            Box(Modifier.size(72.dp)) {
                                val bmp = remember(uri) { decodeDataUriBitmap(uri) }
                                if (bmp != null) {
                                    androidx.compose.foundation.Image(
                                        bmp, contentDescription = "Attachment",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                                            .clickable { onViewAttachment(uri) }
                                    )
                                }
                                OutlinedIconButton(
                                    onClick = { onRemoveAttachment(uri) },
                                    modifier = Modifier.size(22.dp).align(Alignment.TopEnd),
                                    colors = IconButtonDefaults.outlinedIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.surface
                                    )
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Remove attachment", modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Full-screen preview of one not-yet-sent attachment, so the customer can verify it before
 *  saving/sharing the order — a simpler one-off version of [com.billing.pos.ui.common.ImageViewerDialog],
 *  which works from file paths rather than an in-memory data URI. */
@Composable
private fun AttachmentPreviewDialog(dataUri: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
            IconButton(onClick = onDismiss, modifier = Modifier.padding(4.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = androidx.compose.ui.graphics.Color.White)
            }
            val bmp = remember(dataUri) { decodeDataUriBitmap(dataUri) }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (bmp != null) {
                    androidx.compose.foundation.Image(
                        bmp, contentDescription = "Attachment",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text("Could not open image", color = androidx.compose.ui.graphics.Color.White)
                }
            }
        }
    }
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
    var mutedShops by remember { mutableStateOf(ShopSwitch.mutedShops(context)) }
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
                                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
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
                                        val isMuted = shop.shop in mutedShops
                                        IconButton(
                                            onClick = {
                                                val now = !isMuted
                                                ShopSwitch.setMuted(context, shop.shop, now)
                                                mutedShops = if (now) mutedShops + shop.shop else mutedShops - shop.shop
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                if (isMuted) Icons.Default.NotificationsOff else Icons.Default.Notifications,
                                                contentDescription = if (isMuted) "Unmute ${shop.name.ifBlank { shop.shop }}" else "Mute ${shop.name.ifBlank { shop.shop }}",
                                                modifier = Modifier.size(18.dp),
                                                tint = if (isMuted) MaterialTheme.colorScheme.error else LocalContentColor.current
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

/** True for a URL that points directly at an image file (by extension) — as opposed to a page
 *  the customer just needs to open in a browser (a product page, a Drive folder listing, etc.). */
private fun isDirectImageUrl(url: String): Boolean {
    val clean = url.substringBefore('?').substringBefore('#').trim().lowercase()
    return listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp").any { clean.endsWith(it) }
}

@Composable
private fun CatalogItemRow(item: ShopCatalogItem, qty: Int, onQtyChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current

    // item.driveLink is one or more links, comma-separated — image links are downloaded/cached
    // and shown as a thumbnail + full-screen swipeable gallery; anything else (a product page, a
    // Drive folder) stays a plain clickable link the customer opens in their browser.
    val links = remember(item.driveLink) {
        item.driveLink.split(",").map { it.trim() }.filter { it.isNotBlank() }
    }
    val imageLinks = remember(links) { links.filter { isDirectImageUrl(it) } }
    val textLinks = remember(links) { links.filterNot { isDirectImageUrl(it) } }

    var galleryPaths by remember(item.driveLink) { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(item.driveLink) {
        if (imageLinks.isNotEmpty()) {
            galleryPaths = coroutineScope {
                imageLinks.map { url -> async { RemoteImageCache.fetch(context, url)?.absolutePath } }.awaitAll()
            }.filterNotNull()
        }
    }
    val galleryThumb = galleryPaths.firstOrNull()?.let { rememberThumbnail(it, 200) }
    var viewingGallery by remember { mutableStateOf(false) }

    Card(modifier.fillMaxWidth()) {
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
                    modifier = Modifier.size(55.dp).padding(end = 12.dp)
                )
            }
            if (galleryThumb != null) {
                Box(Modifier.size(55.dp).padding(end = 12.dp)) {
                    androidx.compose.foundation.Image(
                        galleryThumb,
                        contentDescription = item.name,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                            .clickable { viewingGallery = true }
                    )
                    if (galleryPaths.size > 1) {
                        Text(
                            "+${galleryPaths.size - 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = androidx.compose.ui.graphics.Color.White,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .background(
                                    androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f),
                                    androidx.compose.foundation.shape.RoundedCornerShape(topStart = 4.dp)
                                )
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
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
                textLinks.forEachIndexed { idx, link ->
                    Text(
                        if (textLinks.size > 1) "View catalog ${idx + 1}" else "View photos / catalog",
                        style = MaterialTheme.typography.bodySmall.copy(textDecoration = TextDecoration.Underline),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { runCatching { uriHandler.openUri(link) } }
                    )
                }
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
    if (viewingGallery && galleryPaths.isNotEmpty()) {
        com.billing.pos.ui.common.ImageViewerDialog(paths = galleryPaths, onDismiss = { viewingGallery = false })
    }
}
