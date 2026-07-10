package com.billing.pos.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material.icons.filled.Print
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
import androidx.compose.ui.Alignment
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.billing.pos.data.AppPrefs
import kotlinx.coroutines.launch

val BUSINESS_TYPES = listOf(
    "General", "Textiles", "Mobile shop", "Electrical & plumbing",
    "Automobiles", "Grocery", "Medical store", "Restaurant"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenPrinter: () -> Unit = {}) {
    val context = LocalContext.current
    val prefs = remember { AppPrefs(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var name by remember { mutableStateOf(prefs.companyName) }
    var address by remember { mutableStateOf(prefs.companyAddress) }
    var phone by remember { mutableStateOf(prefs.companyPhone) }
    var gstin by remember { mutableStateOf(prefs.companyGstin) }
    var requireBatch by remember { mutableStateOf(prefs.requireItemBatch) }
    var businessType by remember { mutableStateOf(prefs.businessType) }
    var receiptWidth by remember { mutableStateOf(prefs.receiptWidth) }

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
                .verticalScroll(rememberScrollState())
        ) {
            // Registered install number (entered when the app was set up).
            Text(
                "Registered mobile number",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline
            )
            Text(
                prefs.mobileNumber.ifBlank { "Not registered" },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
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
            Button(
                onClick = {
                    prefs.companyName = name.trim().ifBlank { "My Shop" }
                    prefs.companyAddress = address.trim()
                    prefs.companyPhone = phone.trim()
                    prefs.companyGstin = gstin.trim()
                    scope.launch { snackbar.showSnackbar("Settings saved") }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
            ) { Text("Save") }

            Divider(Modifier.padding(vertical = 16.dp))
            Text("Printer", style = MaterialTheme.typography.titleSmall)
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
            // Business type: drives medical (chemical content) and restaurant (sizes).
            Text("Business type", style = MaterialTheme.typography.titleSmall)
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

            Divider(Modifier.padding(vertical = 16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Item batch tracking", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Track batch numbers + expiry dates per item; enable the expiry report.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                    )
                }
                Switch(checked = requireBatch, onCheckedChange = { requireBatch = it; prefs.requireItemBatch = it })
            }
        }
    }
}
