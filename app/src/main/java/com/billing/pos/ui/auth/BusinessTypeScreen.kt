package com.billing.pos.ui.auth

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.billing.pos.customer.ShopSwitch
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.Repository
import com.billing.pos.ui.customer.DebugSimulateLinkDialog
import com.billing.pos.ui.settings.BUSINESS_TYPES
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One-time question on first launch: what is this app for?
 *
 * The choice drives which features the dashboard shows. "Personal" strips it back to the
 * everyday tools; the shop types show the full billing app.
 */
@Composable
fun BusinessTypeScreen(onChosen: () -> Unit, onCustomerLinkSimulated: () -> Unit = {}) {
    val context = LocalContext.current
    val prefs = androidx.compose.runtime.remember { AppPrefs(context) }
    val scope = rememberCoroutineScope()
    var showSimulateLink by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf(false) }

    // Real-world safety net, shown in every build (not just debug): the Play Store install
    // referrer that normally routes a customer straight into customerCatalog can fail for
    // reasons outside anyone's control — a sideloaded APK shared over WhatsApp instead of a real
    // Play install, the referrer service being briefly unavailable on some device, etc. If that
    // happens, this business-type screen is what a customer sees instead of their shop's catalog.
    // Scanning the exact same QR/link the shop already gave them applies the same prefs the
    // referrer would have, no reinstall or developer help needed.
    fun applyCustomerShop(shop: ShopSwitch.Shop) {
        prefs.customerMode = true
        prefs.shopCode = shop.shop
        prefs.onlineCatalogUrl = shop.url
        if (shop.type.isNotBlank()) prefs.customerBusinessType = shop.type
        prefs.customerPremiumShop = shop.premium
        prefs.onboarded = true
        onCustomerLinkSimulated()
    }
    var decoding by remember { mutableStateOf(false) }
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@rememberLauncherForActivityResult
        val shop = ShopSwitch.parse(contents)
        if (shop != null) applyCustomerShop(shop) else scanError = true
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanLauncher.launch(ScanOptions().setPrompt("Scan your shop's code").setBeepEnabled(true))
    }
    fun startCustomerScan() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            scanLauncher.launch(ScanOptions().setPrompt("Scan your shop's code").setBeepEnabled(true))
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }
    // Not every customer is standing at the shop with a physical code to point the camera at —
    // the shop often just sends the QR as a picture over WhatsApp instead. Decoding it from a
    // saved photo (same ZXing-on-a-still-image path the in-app "switch shop" dialog already
    // uses) covers that without needing anything printed/displayed in person.
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
            if (shop != null) applyCustomerShop(shop) else scanError = true
        }
    }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text(
            "Welcome",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 24.dp)
        )
        // The primary, unmissable entry point for a customer — not a small link at the bottom.
        // The Play Store install link is meant to route a customer straight past this screen
        // entirely, but that depends on Play's install referrer actually arriving in time (see
        // InstallReferrer.kt), which real-world testing has shown can't always be counted on.
        // Scanning the shop's own QR/code here applies the exact same prefs the referrer would
        // have, with no dependence on Play at all.
        Button(
            onClick = { startCustomerScan() },
            enabled = !decoding,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
        ) { Text("Open order/customer app — scan QR") }
        OutlinedButton(
            onClick = { galleryPicker.launch("image/*") },
            enabled = !decoding,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) {
            if (decoding) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text("Or pick the shop's QR code from your gallery")
        }
        Text(
            "Got a shop's QR code or link to order from them — printed, on screen, or sent as a photo on WhatsApp? Use either button above.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
        )
        Divider()
        Text(
            "Or, how will you use the app as a shop? This sets up your home screen and can be changed later in Settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 16.dp, bottom = 16.dp)
        )
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(BUSINESS_TYPES) { type ->
                Card(
                    Modifier.fillMaxWidth().clickable {
                        prefs.businessType = type
                        if (type == "Medical store") prefs.requireItemBatch = true
                        prefs.onboarded = true
                        scope.launch { Repository(context).seedSampleItems(type) }
                        onChosen()
                    }
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(type, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (type == "Personal") {
                            Text(
                                "Diary, sticky notes, calculator, payments, receipts, cash book — no shop billing.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        }
        if (com.billing.pos.BuildConfig.DEBUG) {
            TextButton(onClick = { showSimulateLink = true }, modifier = Modifier.padding(top = 4.dp)) {
                Text("Debug: simulate customer link")
            }
        }
    }

    if (scanError) {
        AlertDialog(
            onDismissRequest = { scanError = false },
            title = { Text("Not a shop code") },
            text = { Text("That didn't look like a shop's customer code/link. Ask the shop to resend it, or scan again.") },
            confirmButton = { TextButton(onClick = { scanError = false }) { Text("OK") } }
        )
    }

    if (showSimulateLink) {
        DebugSimulateLinkDialog(
            onDismiss = { showSimulateLink = false },
            onApply = { shop, url, type ->
                prefs.customerMode = true
                prefs.shopCode = shop
                prefs.onlineCatalogUrl = url
                if (type.isNotBlank()) prefs.customerBusinessType = type
                prefs.onboarded = true
                showSimulateLink = false
                onCustomerLinkSimulated()
            }
        )
    }
}
