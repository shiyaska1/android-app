package com.billing.pos.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.billing.pos.data.AppPrefs
import com.billing.pos.util.Format
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Copies a picked file (image or PDF) into app storage, keeping a sensible extension. */
private fun mmText(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

private val SEARCH_HIGHLIGHT = Color(0xFFFFEB3B)

/** A settings label that records its own on-screen position (for the search box to scroll to)
 *  and paints itself yellow while it's the current search match. */
@Composable
private fun HighlightText(
    text: String,
    style: TextStyle,
    anchors: MutableMap<String, Float>,
    highlightedKey: String?,
    modifier: Modifier = Modifier
) {
    Text(
        text, style = style,
        modifier = modifier
            .onGloballyPositioned { anchors[text] = it.positionInRoot().y }
            .then(if (highlightedKey == text) Modifier.background(SEARCH_HIGHLIGHT) else Modifier)
    )
}

private fun copyToAppFiles(context: android.content.Context, uri: android.net.Uri, baseName: String): String? {
    val type = context.contentResolver.getType(uri) ?: ""
    val ext = when { type.contains("pdf") -> "pdf"; type.contains("png") -> "png"; else -> "jpg" }
    val dest = java.io.File(context.filesDir, "$baseName.$ext")
    // Remove any older copy of this asset with a different extension.
    listOf("pdf", "png", "jpg").forEach { e -> if (e != ext) java.io.File(context.filesDir, "$baseName.$e").delete() }
    return runCatching {
        context.contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } }
        dest.absolutePath
    }.getOrNull()
}

val BUSINESS_TYPES = listOf(
    "Personal",
    "General", "Textiles", "Mobile shop", "Electrical & plumbing",
    "Automobiles", "Grocery", "Medical store", "Restaurant", "Rental", "Medical lab", "Bulk SMS", "Gym", "Coaching Center", "Service Center"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenPrinter: () -> Unit = {}) {
    val context = LocalContext.current
    val prefs = remember { AppPrefs(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // Search box: jumps to and highlights a matching setting's label. Each labelled section
    // records its own screen position via HighlightText below as the page composes/scrolls.
    val scrollState = rememberScrollState()
    val settingAnchors = remember { mutableMapOf<String, Float>() }
    var settingsTop by remember { mutableStateOf(0f) }
    var settingsQuery by remember { mutableStateOf("") }
    var highlightedSetting by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(settingsQuery) {
        val q = settingsQuery.trim()
        if (q.length < 2) { highlightedSetting = null; return@LaunchedEffect }
        delay(300)
        val match = settingAnchors.entries.firstOrNull { it.key.contains(q, ignoreCase = true) }
        highlightedSetting = match?.key
        if (match != null) {
            val target = (scrollState.value + match.value - settingsTop - 24f).toInt().coerceAtLeast(0)
            scrollState.animateScrollTo(target)
        }
    }

    var name by remember { mutableStateOf(prefs.companyName) }
    var address by remember { mutableStateOf(prefs.companyAddress) }
    var phone by remember { mutableStateOf(prefs.companyPhone) }
    var gstin by remember { mutableStateOf(prefs.companyGstin) }
    var gstEnabled by remember { mutableStateOf(prefs.gstEnabled) }
    var compositionScheme by remember { mutableStateOf(prefs.compositionScheme) }
    var cessEnabled by remember { mutableStateOf(prefs.cessEnabled) }
    var noTaxInvoiceEnabled by remember { mutableStateOf(prefs.noTaxInvoiceEnabled) }
    var noTaxInvoicePrefix by remember { mutableStateOf(prefs.noTaxInvoicePrefix) }
    var zatcaEnabled by remember { mutableStateOf(prefs.zatcaEnabled) }
    var zatcaVatNumber by remember { mutableStateOf(prefs.zatcaVatNumber) }
    var uaeVatEnabled by remember { mutableStateOf(prefs.uaeVatEnabled) }
    var uaeTrn by remember { mutableStateOf(prefs.uaeTrn) }
    var priceIncludesTax by remember { mutableStateOf(prefs.priceIncludesTax) }
    var upiId by remember { mutableStateOf(prefs.upiId) }
    var upiName by remember { mutableStateOf(prefs.upiName) }
    var upiQrOnPrint by remember { mutableStateOf(prefs.showUpiQrOnPrint) }
    var requireBatch by remember { mutableStateOf(prefs.requireItemBatch) }
    var fifoAutoPick by remember { mutableStateOf(prefs.fifoAutoPickBatch) }
    var weighScaleEnabled by remember { mutableStateOf(prefs.weighScaleEnabled) }
    var weighScalePrefix by remember { mutableStateOf(prefs.weighScalePrefix) }
    var weighScaleItemLen by remember { mutableStateOf(prefs.weighScaleItemCodeLen.toString()) }
    var weighScaleValueLen by remember { mutableStateOf(prefs.weighScaleValueLen.toString()) }
    var weighScaleValueIsPrice by remember { mutableStateOf(prefs.weighScaleValueIsPrice) }
    var barcodeLabelW by remember { mutableStateOf(mmText(prefs.barcodeLabelWidthMm)) }
    var barcodeLabelH by remember { mutableStateOf(mmText(prefs.barcodeLabelHeightMm)) }
    var barcodeShowPrice by remember { mutableStateOf(prefs.barcodeShowPrice) }
    var barcodeShowCompanyName by remember { mutableStateOf(prefs.barcodeShowCompanyName) }
    var barcodeShowSize by remember { mutableStateOf(prefs.barcodeShowSize) }
    var businessType by remember { mutableStateOf(prefs.businessType) }
    var receiptWidth by remember { mutableStateOf(prefs.receiptWidth) }
    var ocrLanguage by remember { mutableStateOf(prefs.ocrLanguage) }
    var logoPath by remember { mutableStateOf(prefs.logoPath) }
    var logoFull by remember { mutableStateOf(prefs.logoFullWidth) }
    val logoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val dest = java.io.File(context.filesDir, "company_logo.jpg")
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.billing.pos.diary.AttachmentStore.compressImageTo(context, uri, dest, 900, 88)
            }
            if (ok) { prefs.logoPath = dest.absolutePath; logoPath = dest.absolutePath; snackbar.showSnackbar("Logo saved") }
            else snackbar.showSnackbar("Could not read image")
        }
    }

    // ---- medical lab print assets ----
    var sealPath by remember { mutableStateOf(prefs.labSealPath) }
    var signPath by remember { mutableStateOf(prefs.labSignaturePath) }
    var letterheadPath by remember { mutableStateOf(prefs.labLetterheadPath) }
    var topSkip by remember { mutableStateOf(prefs.labTopSkipLines.toString()) }
    var bottomSkip by remember { mutableStateOf(prefs.labBottomSkipLines.toString()) }
    var stickyNote by remember { mutableStateOf(prefs.stickyNoteOnLaunch) }
    var autoVoiceDiary by remember { mutableStateOf(prefs.autoVoiceDiary) }
    // ---- LAN sync (two-counter over WiFi) ----
    var deviceTag by remember { mutableStateOf(prefs.deviceTag) }
    var hostMode by remember { mutableStateOf(com.billing.pos.sync.SyncManager.isHosting()) }
    var hostIp by remember { mutableStateOf(prefs.syncHostIp) }
    var autoSync by remember { mutableStateOf(prefs.syncAuto) }
    val syncStatus by com.billing.pos.sync.SyncManager.status.collectAsState()
    val myWifiIp = remember { com.billing.pos.sync.SyncManager.wifiIp(context) }
    val micPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) { autoVoiceDiary = true; com.billing.pos.audio.AutoDiaryService.start(context) }
    }
    var appLock by remember { mutableStateOf(prefs.appLock) }
    var expiryAlert by remember { mutableStateOf(prefs.expiryAlert) }
    var expiryDays by remember { mutableStateOf(prefs.expiryAlertDays.toString()) }
    // Warn if the lock is switched on but the phone itself has no lock to check against.
    val deviceSecure = remember {
        context.getSystemService(android.app.KeyguardManager::class.java)?.isDeviceSecure == true
    }
    val sealPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val path = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { copyToAppFiles(context, uri, "lab_seal") }
            if (path != null) { prefs.labSealPath = path; sealPath = path; snackbar.showSnackbar("Seal saved") } else snackbar.showSnackbar("Could not read image")
        }
    }
    val signPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val path = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { copyToAppFiles(context, uri, "lab_sign") }
            if (path != null) { prefs.labSignaturePath = path; signPath = path; snackbar.showSnackbar("Signature saved") } else snackbar.showSnackbar("Could not read image")
        }
    }
    val letterheadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val path = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { copyToAppFiles(context, uri, "lab_letterhead") }
            if (path != null) { prefs.labLetterheadPath = path; letterheadPath = path; snackbar.showSnackbar("Letterhead saved") } else snackbar.showSnackbar("Could not read file")
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Invoice / Company Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(16.dp)
                .onGloballyPositioned { settingsTop = it.positionInRoot().y }
                .verticalScroll(scrollState)
        ) {
            OutlinedTextField(
                value = settingsQuery, onValueChange = { settingsQuery = it },
                placeholder = { Text("Search settings — GST, UPI, printer, barcode…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (settingsQuery.isNotBlank()) {
                        IconButton(onClick = { settingsQuery = ""; highlightedSetting = null }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
            )

            // Device ID (used for licensing / activation).
            Text(
                "Device ID",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline
            )
            Text(
                com.billing.pos.data.License.deviceId(context),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            com.billing.pos.ui.license.SupportContactBlock(
                deviceId = com.billing.pos.data.License.deviceId(context),
                compact = true
            )
            Divider(Modifier.padding(vertical = 12.dp))

            Text(
                "Shown on printed bills and PDF invoices.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Company name") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            )
            OutlinedTextField(
                value = address, onValueChange = { address = it },
                label = { Text("Address") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            OutlinedTextField(
                value = phone, onValueChange = { phone = it },
                label = { Text("Phone") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            OutlinedTextField(
                value = gstin, onValueChange = { gstin = it },
                label = { Text("GSTIN / TIN") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    HighlightText("GST mode (India)", MaterialTheme.typography.bodyMedium, settingAnchors, highlightedSetting)
                    Text(
                        "Item entry shows \"GST %\" instead of \"Tax %\". Invoices always print as " +
                            "\"TAX INVOICE\", split the tax into CGST + SGST (assumes sales within your " +
                            "own state), and print the customer's GSTIN with a B2B/B2C tag when one is set.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                    )
                }
                Switch(checked = gstEnabled, onCheckedChange = { gstEnabled = it; prefs.gstEnabled = it })
            }
            if (gstEnabled) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Column(Modifier.weight(1f)) {
                        HighlightText("Composition scheme dealer", MaterialTheme.typography.bodyMedium, settingAnchors, highlightedSetting)
                        Text(
                            "A composition dealer can't show tax as a separate line by law. Invoices " +
                                "title as \"BILL OF SUPPLY\" and hide the CGST/SGST/IGST breakup — only the " +
                                "total prints.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                        )
                    }
                    Switch(checked = compositionScheme, onCheckedChange = { compositionScheme = it; prefs.compositionScheme = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Column(Modifier.weight(1f)) {
                        HighlightText("GST Compensation Cess", MaterialTheme.typography.bodyMedium, settingAnchors, highlightedSetting)
                        Text(
                            "Only a short list of goods carry Cess on top of GST (tobacco, aerated drinks, " +
                                "coal, luxury vehicles, ...). Off by default — turn on to show a Cess % field " +
                                "on item entry and a Cess line on invoices.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                        )
                    }
                    Switch(checked = cessEnabled, onCheckedChange = { cessEnabled = it; prefs.cessEnabled = it })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    HighlightText("Price includes tax", MaterialTheme.typography.bodyMedium, settingAnchors, highlightedSetting)
                    Text(
                        "On (default): item prices already include tax — the tax amount is worked out " +
                            "of the price, not added on top, so Sub Total + Tax always equals the price " +
                            "you entered. Off: prices exclude tax — tax is added on top of the price, " +
                            "raising the total the customer pays. Applies to Billing and Purchase only.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                    )
                }
                Switch(checked = priceIncludesTax, onCheckedChange = { priceIncludesTax = it; prefs.priceIncludesTax = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    HighlightText("No Tax Invoice", MaterialTheme.typography.bodyMedium, settingAnchors, highlightedSetting)
                    Text(
                        "Off by default. Turns on a per-sale \"No Tax Invoice\" switch on the Billing " +
                            "screen: that invoice prints with no company header, no title, and no tax shown " +
                            "(only date + bill no, then the same items and totals). It gets its own numbering " +
                            "series below, and is excluded from every GST/VAT report and the main Invoice " +
                            "list — it only shows in the separate No Tax Invoices list.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                    )
                }
                Switch(checked = noTaxInvoiceEnabled, onCheckedChange = { noTaxInvoiceEnabled = it; prefs.noTaxInvoiceEnabled = it })
            }
            if (noTaxInvoiceEnabled) {
                OutlinedTextField(
                    value = noTaxInvoicePrefix,
                    onValueChange = { noTaxInvoicePrefix = it },
                    label = { Text("No Tax Invoice number prefix") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
            HighlightText(
                "UPI payment QR", MaterialTheme.typography.bodyMedium, settingAnchors, highlightedSetting,
                modifier = Modifier.padding(top = 8.dp)
            )
            OutlinedTextField(
                value = upiId, onValueChange = { upiId = it },
                label = { Text("UPI ID for payment QR (e.g. name@okaxis)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            OutlinedTextField(
                value = upiName, onValueChange = { upiName = it },
                label = { Text("Payee name on QR") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Checkbox(checked = upiQrOnPrint, onCheckedChange = { upiQrOnPrint = it; prefs.showUpiQrOnPrint = it })
                Text("Print UPI QR at the bottom of invoices")
            }
            Button(
                onClick = {
                    prefs.companyName = name.trim().ifBlank { "My Shop" }
                    prefs.companyAddress = address.trim()
                    prefs.companyPhone = phone.trim()
                    prefs.companyGstin = gstin.trim()
                    prefs.noTaxInvoicePrefix = noTaxInvoicePrefix.trim().ifBlank { "NT-" }
                    prefs.upiId = upiId.trim()
                    prefs.upiName = upiName.trim()
                    prefs.showUpiQrOnPrint = upiQrOnPrint
                    scope.launch { snackbar.showSnackbar("Settings saved") }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
            ) { Text("Save") }

            Divider(Modifier.padding(vertical = 16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    HighlightText("Saudi ZATCA invoice", MaterialTheme.typography.titleSmall, settingAnchors, highlightedSetting)
                    Text(
                        "Off by default. When on, PDF invoices print as a ZATCA \"Simplified Tax Invoice\" " +
                            "(seller + VAT Reg. No., SAR totals, ZATCA QR code) instead of the GST/plain " +
                            "layout — for KSA-registered sellers only.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                    )
                }
                Switch(checked = zatcaEnabled, onCheckedChange = { zatcaEnabled = it; prefs.zatcaEnabled = it; if (it) { uaeVatEnabled = false; prefs.uaeVatEnabled = false } })
            }
            if (zatcaEnabled) {
                OutlinedTextField(
                    value = zatcaVatNumber,
                    onValueChange = { zatcaVatNumber = it; prefs.zatcaVatNumber = it },
                    label = { Text("Seller VAT registration number (15 digits)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }

            Divider(Modifier.padding(vertical = 16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    HighlightText("UAE VAT invoice", MaterialTheme.typography.titleSmall, settingAnchors, highlightedSetting)
                    Text(
                        "Off by default. When on, PDF invoices print as a UAE FTA-style tax invoice — " +
                            "seller TRN and a single VAT line (no state split like GST), totals in AED. " +
                            "UAE's standard VAT rate is 5% — set that on each item's Tax % field.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                    )
                }
                Switch(checked = uaeVatEnabled, onCheckedChange = { uaeVatEnabled = it; prefs.uaeVatEnabled = it; if (it) { zatcaEnabled = false; prefs.zatcaEnabled = false } })
            }
            if (uaeVatEnabled) {
                OutlinedTextField(
                    value = uaeTrn,
                    onValueChange = { uaeTrn = it; prefs.uaeTrn = it },
                    label = { Text("Seller Tax Registration Number (TRN)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }

            Divider(Modifier.padding(vertical = 16.dp))
            HighlightText("Company logo (A4 invoice)", MaterialTheme.typography.titleSmall, settingAnchors, highlightedSetting)
            if (logoPath.isNotBlank()) {
                val bmp = com.billing.pos.ui.common.rememberThumbnail(logoPath, 400)
                if (bmp != null) {
                    Box(Modifier.fillMaxWidth().height(90.dp).padding(top = 6.dp)) {
                        Image(bmp, contentDescription = "Logo", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().height(84.dp))
                    }
                }
            }
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { logoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.weight(1f)) {
                    Text(if (logoPath.isBlank()) "Upload logo" else "Change logo")
                }
                if (logoPath.isNotBlank()) {
                    Spacer(Modifier.size(8.dp))
                    OutlinedButton(onClick = { prefs.logoPath = ""; logoPath = "" }) { Text("Remove") }
                }
            }
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    HighlightText("Logo is the full header", MaterialTheme.typography.bodyMedium, settingAnchors, highlightedSetting)
                    Text("Turn on if your logo image already includes name, address & phone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                Checkbox(checked = logoFull, onCheckedChange = { logoFull = it; prefs.logoFullWidth = it })
            }

            Divider(Modifier.padding(vertical = 16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    HighlightText("Sticky note on launch", MaterialTheme.typography.titleSmall, settingAnchors, highlightedSetting)
                    Text("Open a full-screen handwriting canvas each time the app starts; Save stores each page as a picture in My Diary.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                Checkbox(checked = stickyNote, onCheckedChange = { stickyNote = it; prefs.stickyNoteOnLaunch = it })
            }

            if (com.billing.pos.BuildConfig.DEBUG) {
                Divider(Modifier.padding(vertical = 16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        HighlightText("Auto voice diary (hourly)", MaterialTheme.typography.titleSmall, settingAnchors, highlightedSetting)
                        Text(
                            "Records voice in the background and, once an hour, saves a diary entry (titled with the date-time, type \"voice\") with the recording attached. Only the parts where a voice is heard are kept. A notification stays on while it records.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                        )
                    }
                    Checkbox(checked = autoVoiceDiary, onCheckedChange = { on ->
                        if (!on) {
                            autoVoiceDiary = false
                            com.billing.pos.audio.AutoDiaryService.stop(context)
                        } else if (androidx.core.content.ContextCompat.checkSelfPermission(
                                context, android.Manifest.permission.RECORD_AUDIO
                            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) {
                            autoVoiceDiary = true
                            com.billing.pos.audio.AutoDiaryService.start(context)
                        } else {
                            micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                        }
                    })
                }
            }

            Divider(Modifier.padding(vertical = 16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    HighlightText("App lock", MaterialTheme.typography.titleSmall, settingAnchors, highlightedSetting)
                    Text(
                        "Ask for this phone's own fingerprint / PIN / pattern each time the app is opened. No separate password is stored.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                    )
                }
                Checkbox(checked = appLock, onCheckedChange = { appLock = it; prefs.appLock = it })
            }
            if (appLock && !deviceSecure) {
                Text(
                    "This phone has no screen lock set. Set a PIN/pattern/fingerprint in Android Settings, or the app will open without asking.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error
                )
            }

            Divider(Modifier.padding(vertical = 16.dp))
            HighlightText("Share over WiFi (two counters)", MaterialTheme.typography.titleSmall, settingAnchors, highlightedSetting)
            Text(
                "Sync items, customers, sales, receipts and expenses between two phones on the same WiFi — fully offline. " +
                    "One phone is the host (keep its app open); the other connects to it. Give each phone a short letter so " +
                    "bill numbers don't clash (e.g. A makes A-INV-0001).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
            )
            OutlinedTextField(
                value = deviceTag,
                onValueChange = { deviceTag = it.take(6); prefs.deviceTag = deviceTag },
                label = { Text("This phone's letter/name (e.g. A)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    HighlightText("Act as host (server)", MaterialTheme.typography.bodyMedium, settingAnchors, highlightedSetting)
                    Text(
                        if (hostMode) "Others connect to ${myWifiIp ?: "this phone"}:${prefs.syncPort}"
                        else "Turn on for the main phone. Keep this app open while the other syncs.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                    )
                }
                Switch(checked = hostMode, onCheckedChange = { on ->
                    hostMode = on
                    if (on) com.billing.pos.sync.SyncManager.startHost(context)
                    else com.billing.pos.sync.SyncManager.stopHost(context)
                })
            }
            OutlinedTextField(
                value = hostIp,
                onValueChange = { hostIp = it.trim() },
                label = { Text("Host phone IP (from the host's screen above)") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = {
                    prefs.syncHostIp = hostIp
                    com.billing.pos.sync.SyncManager.syncWithHost(context, hostIp, prefs.syncPort)
                }, enabled = hostIp.isNotBlank()) { Text("Sync now") }
                Spacer(Modifier.width(12.dp))
                Column {
                    HighlightText("Auto-sync", MaterialTheme.typography.bodyMedium, settingAnchors, highlightedSetting)
                    Text("every 20s while open", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                Spacer(Modifier.weight(1f))
                Switch(checked = autoSync, onCheckedChange = { on ->
                    autoSync = on; prefs.syncAuto = on
                    if (on && hostIp.isNotBlank()) { prefs.syncHostIp = hostIp; com.billing.pos.sync.SyncManager.startAuto(context, hostIp, prefs.syncPort) }
                    else com.billing.pos.sync.SyncManager.stopAuto()
                })
            }
            Text(syncStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))

            Divider(Modifier.padding(vertical = 16.dp))
            // Which script the camera/gallery OCR reads. Applies to every scan in the app.
            HighlightText("Text language (camera & handwriting)", MaterialTheme.typography.titleSmall, settingAnchors, highlightedSetting)
            Text(
                "Used when reading a photo and when reading what you draw. English is fast and " +
                    "accurate. Malayalam and Arabic photo reading work offline but are slower and " +
                    "less accurate, so check the text before saving. Auto tries English first, then " +
                    "Malayalam. On the drawing pad you can switch language at any time.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
            )
            var ocrMenu by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = ocrMenu, onExpandedChange = { ocrMenu = !ocrMenu }) {
                OutlinedTextField(
                    readOnly = true, value = ocrLanguage, onValueChange = {},
                    label = { Text("OCR language") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(ocrMenu) },
                    modifier = Modifier.menuAnchor().fillMaxWidth().padding(top = 4.dp)
                )
                ExposedDropdownMenu(expanded = ocrMenu, onDismissRequest = { ocrMenu = false }) {
                    AppPrefs.OCR_LANGUAGES.forEach { l ->
                        DropdownMenuItem(
                            text = { Text(l) },
                            onClick = { ocrLanguage = l; prefs.ocrLanguage = l; ocrMenu = false }
                        )
                    }
                }
            }
            if (ocrLanguage != AppPrefs.OCR_ENGLISH) {
                // Checks the offline reader can actually load, so a problem shows up here
                // rather than as a silently empty item name after taking a photo. Auto's
                // offline fallback is Malayalam, same as today.
                val testLang = if (ocrLanguage == AppPrefs.OCR_ARABIC) com.billing.pos.ocr.TesseractOcr.ARABIC else com.billing.pos.ocr.TesseractOcr.MALAYALAM
                val testLabel = if (ocrLanguage == AppPrefs.OCR_ARABIC) "Arabic" else "Malayalam"
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            snackbar.showSnackbar("Checking $testLabel reader…")
                            val err = com.billing.pos.ocr.TesseractOcr.selfTest(context, testLang)
                            snackbar.showSnackbar(
                                if (err == null) "$testLabel reader is ready" else "$testLabel reader failed: $err"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) { Text("Check $testLabel reader") }
            }

            Divider(Modifier.padding(vertical = 16.dp))
            HighlightText("Printer", MaterialTheme.typography.titleSmall, settingAnchors, highlightedSetting)
            Text(
                "Receipt widths (58mm / 80mm) print on thermal receipt printers; A4 makes a full-page PDF for a normal printer.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
            )
            var widthMenu by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = widthMenu, onExpandedChange = { widthMenu = !widthMenu }) {
                OutlinedTextField(
                    readOnly = true, value = receiptWidth, onValueChange = {},
                    label = { Text("Receipt / print width") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(widthMenu) },
                    modifier = Modifier.menuAnchor().fillMaxWidth().padding(top = 4.dp)
                )
                ExposedDropdownMenu(expanded = widthMenu, onDismissRequest = { widthMenu = false }) {
                    AppPrefs.RECEIPT_WIDTHS.forEach { w ->
                        DropdownMenuItem(text = { Text(w) }, onClick = { receiptWidth = w; prefs.receiptWidth = w; widthMenu = false })
                    }
                }
            }
            OutlinedButton(onClick = onOpenPrinter, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Icon(Icons.Filled.Print, contentDescription = null)
                Text("  Thermal printer setup & test")
            }

            Divider(Modifier.padding(vertical = 16.dp))
            HighlightText("Barcode label printing", MaterialTheme.typography.titleSmall, settingAnchors, highlightedSetting)
            Text(
                "Set this to match your barcode-label printer's label size. Items → Print barcode " +
                    "then generates one PDF page per label at this exact size.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedTextField(
                    value = barcodeLabelW,
                    onValueChange = { v ->
                        barcodeLabelW = v.filter { it.isDigit() || it == '.' }
                        barcodeLabelW.toDoubleOrNull()?.let { if (it > 0) prefs.barcodeLabelWidthMm = it }
                    },
                    label = { Text("Width (mm)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = barcodeLabelH,
                    onValueChange = { v ->
                        barcodeLabelH = v.filter { it.isDigit() || it == '.' }
                        barcodeLabelH.toDoubleOrNull()?.let { if (it > 0) prefs.barcodeLabelHeightMm = it }
                    },
                    label = { Text("Height (mm)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                "Fields on the label", style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Checkbox(checked = barcodeShowPrice, onCheckedChange = { barcodeShowPrice = it; prefs.barcodeShowPrice = it })
                Text("Selling price (default)")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = barcodeShowCompanyName, onCheckedChange = { barcodeShowCompanyName = it; prefs.barcodeShowCompanyName = it })
                Text("Company name")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = barcodeShowSize, onCheckedChange = { barcodeShowSize = it; prefs.barcodeShowSize = it })
                Text("Size / unit")
            }

            Divider(Modifier.padding(vertical = 16.dp))
            // Business type: drives medical (chemical content) and restaurant (sizes).
            HighlightText("Business type", MaterialTheme.typography.titleSmall, settingAnchors, highlightedSetting)
            var typeMenu by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = typeMenu, onExpandedChange = { typeMenu = !typeMenu }) {
                OutlinedTextField(
                    readOnly = true, value = businessType.ifBlank { "General" }, onValueChange = {},
                    label = { Text("Type") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeMenu) },
                    modifier = Modifier.menuAnchor().fillMaxWidth().padding(top = 4.dp)
                )
                ExposedDropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                    BUSINESS_TYPES.forEach { t ->
                        DropdownMenuItem(text = { Text(t) }, onClick = {
                            businessType = t; prefs.businessType = t; typeMenu = false
                            if (t == "Medical store") { requireBatch = true; prefs.requireItemBatch = true }
                            scope.launch {
                                val added = com.billing.pos.data.Repository(context).seedSampleItems(t)
                                if (added > 0) snackbar.showSnackbar("Loaded $added sample $t item(s) for demo")
                            }
                        })
                    }
                }
            }
            if (com.billing.pos.data.SampleData.itemsFor(businessType).isNotEmpty()) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val repo = com.billing.pos.data.Repository(context)
                            val samples = com.billing.pos.data.SampleData.itemsFor(businessType)
                            var added = 0
                            samples.forEach { s ->
                                if (repo.itemByName(s.name) == null) {
                                    repo.addItem(s.name, s.price, 0.0, "", "", s.category, 0.0, s.unit, "", s.chemical)
                                    added++
                                }
                            }
                            snackbar.showSnackbar("Loaded $added sample $businessType item(s)")
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                ) { Text("Load sample items for $businessType") }
            }

            // Medical store: warn about medicines nearing expiry.
            if (businessType == "Medical store") {
                Divider(Modifier.padding(vertical = 16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        HighlightText("Expiry alert", MaterialTheme.typography.titleSmall, settingAnchors, highlightedSetting)
                        Text(
                            "Notify once a day about medicines nearing expiry, and show the list when the app opens. Repeats daily until the batch is sold, removed or purchase-returned.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                        )
                    }
                    Checkbox(
                        checked = expiryAlert,
                        onCheckedChange = {
                            expiryAlert = it
                            prefs.expiryAlert = it
                            com.billing.pos.medical.ExpiryAlarm.sync(context)
                        }
                    )
                }
                if (expiryAlert) {
                    OutlinedTextField(
                        value = expiryDays,
                        onValueChange = { v ->
                            expiryDays = v.filter { it.isDigit() }.take(4)
                            expiryDays.toIntOrNull()?.let { d ->
                                if (d > 0) { prefs.expiryAlertDays = d; com.billing.pos.medical.ExpiryAlarm.sync(context) }
                            }
                        },
                        label = { Text("Warn how many days before expiry") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
            }

            if (businessType == "Medical lab") {
                Divider(Modifier.padding(vertical = 16.dp))
                HighlightText("Lab result print", MaterialTheme.typography.titleSmall, settingAnchors, highlightedSetting)
                Text(
                    "Seal + signature print above the technician line. A letterhead (JPG/PNG/PDF) prints as the page background and hides the app header — set how many blank lines to skip below the printed header and above the printed footer.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                )
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { sealPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.weight(1f)) {
                        Text(if (sealPath.isBlank()) "Upload seal" else "Change seal")
                    }
                    if (sealPath.isNotBlank()) OutlinedButton(onClick = { prefs.labSealPath = ""; sealPath = "" }) { Text("Remove") }
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { signPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.weight(1f)) {
                        Text(if (signPath.isBlank()) "Upload signature" else "Change signature")
                    }
                    if (signPath.isNotBlank()) OutlinedButton(onClick = { prefs.labSignaturePath = ""; signPath = "" }) { Text("Remove") }
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { letterheadPicker.launch(arrayOf("application/pdf", "image/*")) }, modifier = Modifier.weight(1f)) {
                        Text(if (letterheadPath.isBlank()) "Upload letter pad" else "Change letter pad")
                    }
                    if (letterheadPath.isNotBlank()) OutlinedButton(onClick = { prefs.labLetterheadPath = ""; letterheadPath = "" }) { Text("Remove") }
                }
                if (letterheadPath.isNotBlank()) {
                    Text("Letter pad set: ${java.io.File(letterheadPath).name}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = topSkip, onValueChange = { topSkip = it.filter { c -> c.isDigit() }; prefs.labTopSkipLines = topSkip.toIntOrNull() ?: 0 },
                            label = { Text("Top lines to skip") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = bottomSkip, onValueChange = { bottomSkip = it.filter { c -> c.isDigit() }; prefs.labBottomSkipLines = bottomSkip.toIntOrNull() ?: 0 },
                            label = { Text("Bottom lines to skip") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Divider(Modifier.padding(vertical = 16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    HighlightText("Item batch tracking", MaterialTheme.typography.titleSmall, settingAnchors, highlightedSetting)
                    Text(
                        "Track batch numbers + expiry dates per item; enable the expiry report.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                    )
                }
                Switch(checked = requireBatch, onCheckedChange = { requireBatch = it; prefs.requireItemBatch = it })
            }
            if (requireBatch) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Column(Modifier.weight(1f)) {
                        HighlightText("Auto-pick batch (FIFO)", MaterialTheme.typography.titleSmall, settingAnchors, highlightedSetting)
                        Text(
                            "Sales use the oldest-expiry batch with stock automatically, without asking.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                        )
                    }
                    Switch(checked = fifoAutoPick, onCheckedChange = { fifoAutoPick = it; prefs.fifoAutoPickBatch = it })
                }
            }

            Divider(Modifier.padding(vertical = 16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    HighlightText("Weighing-scale barcodes", MaterialTheme.typography.titleSmall, settingAnchors, highlightedSetting)
                    Text(
                        "Off by default. Turns on reading \"in-store\" scale labels during a sale: " +
                            "scanning one looks the item up by the PLU code printed on it and fills the " +
                            "cart line's quantity straight from the label — no typing needed. Match the " +
                            "item's Barcode field (in Items) to the scale's PLU code for this to work.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                    )
                }
                Switch(checked = weighScaleEnabled, onCheckedChange = { weighScaleEnabled = it; prefs.weighScaleEnabled = it })
            }
            if (weighScaleEnabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedTextField(
                        value = weighScalePrefix,
                        onValueChange = { weighScalePrefix = it.filter { c -> c.isDigit() }.take(2); prefs.weighScalePrefix = weighScalePrefix },
                        label = { Text("Prefix digit") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = weighScaleItemLen,
                        onValueChange = { v ->
                            weighScaleItemLen = v.filter { it.isDigit() }.take(1)
                            weighScaleItemLen.toIntOrNull()?.let { prefs.weighScaleItemCodeLen = it }
                        },
                        label = { Text("Item code digits") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = weighScaleValueLen,
                        onValueChange = { v ->
                            weighScaleValueLen = v.filter { it.isDigit() }.take(1)
                            weighScaleValueLen.toIntOrNull()?.let { prefs.weighScaleValueLen = it }
                        },
                        label = { Text("Value digits") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (weighScaleValueIsPrice) "Value is the price (paise)" else "Value is the weight (grams)",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "Weight (default): the app prices it at the item's own rate. Price: the scale " +
                                "already computed the total; the app uses it as-is.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                        )
                    }
                    Switch(checked = weighScaleValueIsPrice, onCheckedChange = { weighScaleValueIsPrice = it; prefs.weighScaleValueIsPrice = it })
                }
                Text(
                    "Defaults (prefix 2, 5-digit item code, 6-digit value) match the common Indian " +
                        "\"in-store\" barcode convention — adjust only if a scan from your scale doesn't match an item.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Divider(Modifier.padding(vertical = 16.dp))
            HighlightText("Cloud backup sync", MaterialTheme.typography.titleSmall, settingAnchors, highlightedSetting)
            Text(
                "Push uploads the backup zip to your server; Pull downloads and restores it — " +
                    "used from the Backup & Restore screen. Each push overwrites the same file, keyed " +
                    "by Org ID + Device ID, so nothing piles up on the server.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
            )
            var backupPushUrl by remember { mutableStateOf(prefs.backupPushUrl) }
            var backupPullUrl by remember { mutableStateOf(prefs.backupPullUrl) }
            var backupOrgId by remember { mutableStateOf(prefs.backupOrgId) }
            var backupDeviceId by remember {
                mutableStateOf(prefs.backupDeviceId.ifBlank { com.billing.pos.data.License.deviceId(context) })
            }
            OutlinedTextField(
                value = backupPushUrl,
                onValueChange = { backupPushUrl = it; prefs.backupPushUrl = it },
                label = { Text("Push URL") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            OutlinedTextField(
                value = backupOrgId,
                onValueChange = { backupOrgId = it; prefs.backupOrgId = it },
                label = { Text("Org ID") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            OutlinedTextField(
                value = backupDeviceId,
                onValueChange = { backupDeviceId = it; prefs.backupDeviceId = it },
                label = { Text("Device ID") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            OutlinedTextField(
                value = backupPullUrl,
                onValueChange = { backupPullUrl = it; prefs.backupPullUrl = it },
                label = { Text("Pull URL") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )

            var cloudAutoSync by remember { mutableStateOf(prefs.cloudAutoSync) }
            var cloudAutoSyncIntervalMinText by remember { mutableStateOf((prefs.cloudAutoSyncIntervalSec / 60).coerceAtLeast(1).toString()) }
            val cloudSyncStatus by com.billing.pos.sync.CloudSyncManager.status.collectAsState()
            fun restartCloudAutoSyncIfOn() {
                if (cloudAutoSync) {
                    val mins = cloudAutoSyncIntervalMinText.toIntOrNull()?.coerceAtLeast(1) ?: 5
                    com.billing.pos.sync.CloudSyncManager.startAuto(context, mins * 60_000L)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                Column(Modifier.weight(1f)) {
                    HighlightText("Auto pull + merge + push", MaterialTheme.typography.bodyMedium, settingAnchors, highlightedSetting)
                    Text(
                        "Keeps syncing with the server on its own while the app is open — data and " +
                            "settings only, never photos/attachments. Sales, purchases, receipts, payments, " +
                            "quotations, estimates and orders are safe to sync repeatedly; diary, lab, hire, " +
                            "service, gym, coaching, material movements, returns, LPOs, production and bundles " +
                            "are not deduplicated yet and may duplicate with repeated syncing.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                    )
                }
                Switch(checked = cloudAutoSync, onCheckedChange = { on ->
                    cloudAutoSync = on; prefs.cloudAutoSync = on
                    if (on) restartCloudAutoSyncIfOn() else com.billing.pos.sync.CloudSyncManager.stopAuto()
                })
            }
            OutlinedTextField(
                value = cloudAutoSyncIntervalMinText,
                onValueChange = { txt ->
                    cloudAutoSyncIntervalMinText = txt.filter { it.isDigit() }
                    cloudAutoSyncIntervalMinText.toIntOrNull()?.let { prefs.cloudAutoSyncIntervalSec = it * 60 }
                    restartCloudAutoSyncIfOn()
                },
                label = { Text("Interval (minutes, min 1)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            if (cloudAutoSync) {
                Text(cloudSyncStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
            }

            var pushWindowDaysText by remember { mutableStateOf(prefs.cloudPushWindowDays.toString()) }
            OutlinedTextField(
                value = pushWindowDaysText,
                onValueChange = { txt ->
                    pushWindowDaysText = txt.filter { it.isDigit() }
                    pushWindowDaysText.toIntOrNull()?.let { prefs.cloudPushWindowDays = it }
                },
                label = { Text("Push window (days, 0 = all)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            )
            Text(
                "Only records created or edited in this window are pushed, to keep uploads small. " +
                    "A device syncing for the first time (or after a long gap) will only receive this " +
                    "recent window on Pull — use \"Full resync now\" below after setting one up, or any " +
                    "time you suspect a device is missing older data.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 4.dp)
            )
            var resyncing by remember { mutableStateOf(false) }
            var lastSyncAt by remember { mutableStateOf(prefs.lastCloudSyncAt) }
            var lastSyncOk by remember { mutableStateOf(prefs.lastCloudSyncOk) }
            var lastSyncMessage by remember { mutableStateOf(prefs.lastCloudSyncMessage) }
            OutlinedButton(
                onClick = {
                    if (!resyncing) {
                        resyncing = true
                        scope.launch {
                            com.billing.pos.sync.CloudSyncManager.runFullResync(context)
                            resyncing = false
                            lastSyncAt = prefs.lastCloudSyncAt
                            lastSyncOk = prefs.lastCloudSyncOk
                            lastSyncMessage = prefs.lastCloudSyncMessage
                        }
                    }
                },
                enabled = !resyncing,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text(if (resyncing) "Resyncing…" else "Full resync now (ignores the window, once)") }
            if (lastSyncAt > 0) {
                Text(
                    "Last sync: ${Format.dateTime(lastSyncAt)} — " + if (lastSyncOk) "Success" else "Failed: $lastSyncMessage",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (lastSyncOk) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            Divider(Modifier.padding(vertical = 16.dp))
            HighlightText("Salesman mapping", MaterialTheme.typography.titleSmall, settingAnchors, highlightedSetting)
            Text(
                "Order reports show a Salesman column, resolved from the phone that took the " +
                    "order. Map each phone's device id to a name here — add one row per phone.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
            )
            val orderRepo = remember { com.billing.pos.data.Repository(context) }
            val salesmen by orderRepo.salesmen.collectAsState(initial = emptyList())
            var newSalesmanDeviceId by remember { mutableStateOf("") }
            var newSalesmanName by remember { mutableStateOf("") }
            salesmen.forEach { s ->
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, style = MaterialTheme.typography.bodyMedium)
                        Text(s.deviceId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                    IconButton(onClick = { scope.launch { orderRepo.deleteSalesman(s.deviceId) } }) {
                        Icon(Icons.Filled.Delete, "Remove", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newSalesmanDeviceId, onValueChange = { newSalesmanDeviceId = it },
                    label = { Text("Device ID") }, singleLine = true, modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { newSalesmanDeviceId = com.billing.pos.data.License.deviceId(context) }) { Text("This device") }
            }
            OutlinedTextField(
                value = newSalesmanName, onValueChange = { newSalesmanName = it },
                label = { Text("Salesman name") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            Button(
                onClick = {
                    if (newSalesmanDeviceId.isNotBlank() && newSalesmanName.isNotBlank()) {
                        scope.launch {
                            orderRepo.upsertSalesman(newSalesmanDeviceId.trim(), newSalesmanName.trim())
                            newSalesmanDeviceId = ""; newSalesmanName = ""
                        }
                    }
                },
                enabled = newSalesmanDeviceId.isNotBlank() && newSalesmanName.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text("Add / update salesman") }

            Divider(Modifier.padding(vertical = 16.dp))
            val appVersion = remember {
                runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "unknown"
            }
            Text(
                "App version $appVersion",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            )
        }
    }
}
