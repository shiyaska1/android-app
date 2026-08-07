package com.billing.pos.ui.online

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.OnlineOrder
import com.billing.pos.data.OnlineOrderStatus
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.util.Format
import java.util.Calendar
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val shopPrefs = remember { com.billing.pos.data.AppPrefs(context) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // No shop location saved in Settings? Fall back to this phone's own current position — handy
    // when the owner is out delivering and wants to see how far each order is from wherever they
    // are right now, not from a fixed shop address.
    var liveShopLatLng by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    val shopLocationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true || grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) scope.launch { liveShopLatLng = com.billing.pos.customer.LocationHelper.currentLatLng(context) }
    }
    LaunchedEffect(Unit) {
        if (!shopPrefs.shopLocationCaptured) {
            if (com.billing.pos.customer.LocationHelper.hasPermission(context)) {
                liveShopLatLng = com.billing.pos.customer.LocationHelper.currentLatLng(context)
            } else {
                shopLocationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
        }
    }
    val shopLatLng = if (shopPrefs.shopLocationCaptured) shopPrefs.shopLatitude to shopPrefs.shopLongitude else liveShopLatLng
    val usingLiveShopLocation = !shopPrefs.shopLocationCaptured && liveShopLatLng != null
    var messageTarget by remember { mutableStateOf<OnlineOrder?>(null) }
    var deleteTarget by remember { mutableStateOf<OnlineOrder?>(null) }
    var disputeTarget by remember { mutableStateOf<OnlineOrder?>(null) }
    // One shared player for every order card's voice-note attachments (see OrderCard) — a
    // customer's premium-only voice note travels the same base64-data-URI attachment channel as
    // a photo, so it needs decoding to a temp file before it can play; the temp file is cleaned
    // up as soon as playback stops/finishes, same lifetime as the image-viewer temp file already
    // used for photo attachments (see openAttachment below).
    var playingAttachment by remember { mutableStateOf<String?>(null) }
    val voicePlayer = remember { android.media.MediaPlayer() }
    var playingVoiceFile by remember { mutableStateOf<java.io.File?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { voicePlayer.stop() }
            runCatching { voicePlayer.release() }
            playingVoiceFile?.delete()
        }
    }
    fun toggleAttachmentPlayback(dataUri: String) {
        if (playingAttachment == dataUri) {
            runCatching { voicePlayer.stop(); voicePlayer.reset() }
            playingVoiceFile?.delete()
            playingVoiceFile = null
            playingAttachment = null
            return
        }
        runCatching {
            val comma = dataUri.indexOf(',')
            if (comma < 0) return
            val bytes = android.util.Base64.decode(dataUri.substring(comma + 1), android.util.Base64.DEFAULT)
            val sharedDir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
            val file = java.io.File(sharedDir, "order_voice_play_${System.nanoTime()}.m4a")
            file.writeBytes(bytes)
            playingVoiceFile?.delete()
            playingVoiceFile = file
            voicePlayer.reset()
            voicePlayer.setDataSource(file.absolutePath)
            voicePlayer.setOnCompletionListener {
                playingAttachment = null
                file.delete()
                if (playingVoiceFile == file) playingVoiceFile = null
            }
            voicePlayer.prepare()
            voicePlayer.start()
            playingAttachment = dataUri
        }.onFailure { scope.launch { snackbar.showSnackbar("Could not play voice note") } }
    }
    var selectedFilter by rememberSaveable { mutableStateOf("All") } // "All" or an OnlineOrderStatus name
    // Defaults to the last 7 days — without a cap this list only ever grows, since every fetched
    // order stays local forever (see OrdersFetch). The owner can widen or narrow it from here.
    var dateFrom by rememberSaveable { mutableStateOf(startOfDay(System.currentTimeMillis() - 6L * 24 * 60 * 60 * 1000)) }
    var dateTo by rememberSaveable { mutableStateOf(endOfDay(System.currentTimeMillis())) }
    val shown = remember(orders, selectedFilter, dateFrom, dateTo) {
        orders
            .filter { selectedFilter == "All" || it.status == selectedFilter }
            .filter { orderMillis(it) in dateFrom..dateTo }
    }

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
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = { pickDate(context, dateFrom) { dateFrom = startOfDay(it) } }, modifier = Modifier.weight(1f)) {
                        Text("From: ${Format.date(dateFrom)}")
                    }
                    OutlinedButton(onClick = { pickDate(context, dateTo) { dateTo = endOfDay(it) } }, modifier = Modifier.weight(1f)) {
                        Text("To: ${Format.date(dateTo)}")
                    }
                }
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
                    Text("No orders in this status/date range.", color = MaterialTheme.colorScheme.outline)
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
                        shopLatLng = shopLatLng,
                        usingLiveShopLocation = usingLiveShopLocation,
                        playingAttachment = playingAttachment,
                        onTogglePlayAttachment = { uri -> toggleAttachmentPlayback(uri) },
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
                        onDelete = { deleteTarget = order },
                        onDisputePayment = { disputeTarget = order },
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
            playingAttachment = playingAttachment,
            onTogglePlayAttachment = { uri -> toggleAttachmentPlayback(uri) },
            onDismiss = { messageTarget = null },
            onSend = { text, amount, attachments -> vm.sendMessage(order, text, amount, attachments); messageTarget = null }
        )
    }

    deleteTarget?.let { order ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete this order?") },
            text = { Text("${order.customerName} — ₹${Format.money(order.total)}. This only removes it from this list, on this phone.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteOrder(order); deleteTarget = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
        )
    }

    // The customer's "Paid via UPI" is self-reported — there's no bank/gateway confirming it (see
    // PaymentDialog). If checking your own UPI app shows the money never actually arrived, or a
    // screenshot proof looks wrong, this flips it back to unpaid instead of leaving a false claim
    // standing forever.
    disputeTarget?.let { order ->
        AlertDialog(
            onDismissRequest = { disputeTarget = null },
            title = { Text("Payment not received?") },
            text = {
                Text(
                    "${order.customerName} — ₹${Format.money(order.total)}. This marks the order back as unpaid " +
                        "(Cash on delivery) and lets the customer know their payment couldn't be confirmed. " +
                        "Only do this after checking your own UPI app/bank statement."
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.disputePayment(order); disputeTarget = null }) { Text("Mark as not received") }
            },
            dismissButton = { TextButton(onClick = { disputeTarget = null }) { Text("Cancel") } }
        )
    }
}

private fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun endOfDay(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
}.timeInMillis

/** The order's real timestamp — [OnlineOrder.receivedAt] is an ISO-8601 string from the server
 *  (`date('c')`); falls back to [OnlineOrder.fetchedAt] (when this device pulled it) if that
 *  ever fails to parse, so a malformed/blank value never drops an order out of every date range. */
private fun orderMillis(order: OnlineOrder): Long =
    runCatching { java.time.OffsetDateTime.parse(order.receivedAt).toInstant().toEpochMilli() }
        .getOrDefault(order.fetchedAt)

private fun pickDate(context: android.content.Context, current: Long, onPicked: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = current }
    android.app.DatePickerDialog(
        context,
        { _, y, m, d -> c.set(Calendar.YEAR, y); c.set(Calendar.MONTH, m); c.set(Calendar.DAY_OF_MONTH, d); onPicked(c.timeInMillis) },
        c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
    ).show()
}

/** How far an order is from [shopLatLng] — null coordinates on either side, or a location that
 *  couldn't be parsed/resolved, all show as "unknown". See [com.billing.pos.customer.GeoLink] for
 *  what location link shapes are understood. Lets the owner spot a delivery worth an extra
 *  charge at a glance. */
private fun distanceLabel(shopLatLng: Pair<Double, Double>?, orderLatLng: Pair<Double, Double>?, resolving: Boolean): String {
    if (shopLatLng == null) return "Distance: unknown (allow location access, or set your shop location in Settings)"
    if (orderLatLng == null) return if (resolving) "Distance: locating…" else "Distance: unknown"
    val km = com.billing.pos.customer.NearbyShops.haversineKm(shopLatLng.first, shopLatLng.second, orderLatLng.first, orderLatLng.second)
    return if (km < 1.0) "Distance: ${(km * 1000).roundToInt()} m" else "Distance: ${Format.money(km)} km"
}

/** [onSend]'s amount is 0.0 when left blank — e.g. after a note/prescription order with no fixed
 *  price at order time, set it to bill the customer; they get a "Pay via UPI now" button on the
 *  notification. A photo (e.g. the bill itself) and/or one voice note can go along with it too —
 *  same attach mechanism as a customer's own order form. Any one of message/amount/attachment
 *  alone is enough to send. [playingAttachment]/[onTogglePlayAttachment] share the screen-level
 *  player (see OnlineOrdersScreen) so previewing here and listening to an order's own voice note
 *  never talk over each other. */
@Composable
private fun MessageDialog(
    playingAttachment: String?,
    onTogglePlayAttachment: (String) -> Unit,
    onDismiss: () -> Unit,
    onSend: (String, Double, List<String>) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    var amountText by rememberSaveable { mutableStateOf("") }
    val amount = amountText.toDoubleOrNull() ?: 0.0
    val attachments = remember { mutableStateListOf<String>() }
    var compressing by remember { mutableStateOf(false) }

    suspend fun compressToDataUri(sourcePath: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = com.billing.pos.customer.ThumbnailCompressor.compress(sourcePath, maxBytes = 300 * 1024, maxDim = 1024) ?: return@runCatching null
            "data:image/jpeg;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        }.getOrNull()
    }
    val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        compressing = true
        scope.launch {
            val dataUri = withContext(Dispatchers.IO) {
                val tempFile = java.io.File.createTempFile("reply_", ".jpg", context.cacheDir)
                runCatching { context.contentResolver.openInputStream(uri)?.use { input -> tempFile.outputStream().use { output -> input.copyTo(output) } } }
                val result = compressToDataUri(tempFile.absolutePath)
                tempFile.delete()
                result
            }
            if (dataUri != null) attachments.add(dataUri)
            compressing = false
        }
    }
    var pendingCameraFile by remember { mutableStateOf<java.io.File?>(null) }
    val cameraCapture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = pendingCameraFile
        pendingCameraFile = null
        if (ok && f != null) {
            compressing = true
            scope.launch {
                val dataUri = compressToDataUri(f.absolutePath)
                f.delete()
                if (dataUri != null) attachments.add(dataUri)
                compressing = false
            }
        } else {
            f?.delete()
        }
    }
    fun launchCamera() {
        val sharedDir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
        val file = java.io.File.createTempFile("reply_cam_", ".jpg", sharedDir)
        pendingCameraFile = file
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        runCatching { cameraCapture.launch(uri) }
            .onFailure { pendingCameraFile?.delete(); pendingCameraFile = null }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) launchCamera() }
    fun requestCameraAndLaunch() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) launchCamera()
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    var isRecording by remember { mutableStateOf(false) }
    var voiceRecorder by remember { mutableStateOf<com.billing.pos.audio.VoiceRecorder?>(null) }
    var recordingFile by remember { mutableStateOf<java.io.File?>(null) }
    fun startRecording() {
        val sharedDir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
        val file = java.io.File(sharedDir, "reply_voice_${System.nanoTime()}.m4a")
        runCatching {
            voiceRecorder = com.billing.pos.audio.VoiceRecorder(file.absolutePath).also { it.start() }
            recordingFile = file
            isRecording = true
        }.onFailure { voiceRecorder = null; file.delete() }
    }
    fun stopRecording() {
        val rec = voiceRecorder ?: return
        val file = recordingFile
        isRecording = false
        voiceRecorder = null
        recordingFile = null
        scope.launch {
            withContext(Dispatchers.IO) { rec.stop() }
            if (file != null && file.exists() && file.length() > 0) {
                attachments.add(withContext(Dispatchers.IO) { com.billing.pos.util.VoiceAttachment.encode(file) })
                file.delete()
            } else {
                file?.delete()
            }
        }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) startRecording() }
    fun requestMicAndRecord() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) startRecording()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Message customer") },
        text = {
            Column {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text("Message") },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { requestCameraAndLaunch() }, enabled = !compressing && !isRecording) {
                                Icon(Icons.Default.CameraAlt, contentDescription = "Attach a photo from camera")
                            }
                            IconButton(onClick = { galleryPicker.launch("image/*") }, enabled = !compressing && !isRecording) {
                                Icon(Icons.Default.AddAPhoto, contentDescription = "Attach a photo from gallery")
                            }
                            IconButton(onClick = if (isRecording) { { stopRecording() } } else { { requestMicAndRecord() } }, enabled = !compressing) {
                                Icon(
                                    if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                                    contentDescription = if (isRecording) "Stop recording" else "Record a voice note",
                                    tint = if (isRecording) MaterialTheme.colorScheme.error else androidx.compose.material3.LocalContentColor.current
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                if (compressing) {
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("  Adding attachment…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(start = 6.dp))
                    }
                }
                if (isRecording) {
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                        Text("  Recording… tap the mic again to stop", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                if (attachments.isNotEmpty()) {
                    LazyRow(contentPadding = PaddingValues(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(attachments) { uri ->
                            Box(Modifier.size(64.dp)) {
                                if (com.billing.pos.util.VoiceAttachment.isAudio(uri)) {
                                    Box(
                                        Modifier.fillMaxSize()
                                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                                            .background(MaterialTheme.colorScheme.secondaryContainer)
                                            .clickable { onTogglePlayAttachment(uri) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(if (playingAttachment == uri) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = "Voice note")
                                    }
                                } else {
                                    val comma = uri.indexOf(',')
                                    val bmp = remember(uri) {
                                        if (comma < 0) null else runCatching {
                                            val bytes = android.util.Base64.decode(uri.substring(comma + 1), android.util.Base64.DEFAULT)
                                            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                                        }.getOrNull()
                                    }
                                    if (bmp != null) {
                                        androidx.compose.foundation.Image(
                                            bmp, contentDescription = "Attachment",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize().clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                                        )
                                    }
                                }
                                OutlinedIconButton(
                                    onClick = { attachments.remove(uri) },
                                    modifier = Modifier.size(20.dp).align(Alignment.TopEnd),
                                    colors = IconButtonDefaults.outlinedIconButtonColors(containerColor = MaterialTheme.colorScheme.surface)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Remove attachment", modifier = Modifier.size(12.dp))
                                }
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = amountText, onValueChange = { amountText = it },
                    label = { Text("Bill amount (optional)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Text(
                    "Set this for a note/prescription order that had no fixed price — the " +
                        "customer gets a \"Pay via UPI now\" button for this amount.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSend(text.trim(), amount, attachments.toList()) },
                enabled = (text.isNotBlank() || amount > 0.0 || attachments.isNotEmpty()) && !compressing && !isRecording
            ) { Text("Send") }
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
    shopLatLng: Pair<Double, Double>?,
    usingLiveShopLocation: Boolean,
    playingAttachment: String?,
    onStatusChange: (String) -> Unit,
    onCall: () -> Unit,
    onWhatsApp: () -> Unit,
    onLocation: () -> Unit,
    onMessage: () -> Unit,
    onDelete: () -> Unit,
    onDisputePayment: () -> Unit,
    onShareToSalesman: () -> Unit,
    onTogglePlayAttachment: (String) -> Unit
) {
    val context = LocalContext.current
    // Parses instantly for a plain link (this app's own GPS capture, or most pasted Maps links);
    // a maps.app.goo.gl/goo.gl short link has no coordinates in the text at all, so that case
    // needs a network round-trip to follow its redirect (see GeoLink.resolve) — hence "resolving".
    var orderLatLng by remember(order.id) { mutableStateOf<Pair<Double, Double>?>(null) }
    var resolving by remember(order.id) { mutableStateOf(false) }
    LaunchedEffect(order.location) {
        orderLatLng = com.billing.pos.customer.GeoLink.parseLatLng(order.location)
        if (orderLatLng == null && com.billing.pos.customer.GeoLink.isShortLink(order.location)) {
            resolving = true
            orderLatLng = com.billing.pos.customer.GeoLink.resolve(order.location)
            resolving = false
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Column {
                Text(order.customerName, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                Text(Format.date(orderMillis(order)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                if (order.customerPhone.isNotBlank()) {
                    Text(order.customerPhone, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                if (order.customerAddress.isNotBlank()) {
                    Text(order.customerAddress, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                Text(
                    distanceLabel(shopLatLng, orderLatLng, resolving) + if (usingLiveShopLocation && shopLatLng != null) " (from your current location)" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary
                )
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
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete order", tint = MaterialTheme.colorScheme.error)
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
                        val isAudio = com.billing.pos.util.VoiceAttachment.isAudio(dataUri)
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { if (isAudio) onTogglePlayAttachment(dataUri) else openAttachment(context, dataUri) }
                                .padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (isAudio) (if (playingAttachment == dataUri) Icons.Default.Stop else Icons.Default.PlayArrow) else Icons.Default.AttachFile,
                                contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp)
                            )
                            Text(
                                "  " + if (isAudio) "Voice note ${i + 1} — tap to listen" else "Attachment ${i + 1} — tap to open or save",
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Total: ₹${Format.money(order.total)}", fontWeight = FontWeight.Bold)
                if (order.paymentStatus == "UPI") {
                    Text(
                        "✓ Paid via UPI — tap if not received",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable(onClick = onDisputePayment)
                    )
                } else {
                    Text(
                        "Cash on delivery",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

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
