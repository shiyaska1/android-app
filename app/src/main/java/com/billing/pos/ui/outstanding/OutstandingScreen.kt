package com.billing.pos.ui.outstanding

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.auth.Session
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.Bill
import com.billing.pos.data.DownloadSaver
import com.billing.pos.data.Purchase
import com.billing.pos.data.Repository
import com.billing.pos.pdf.StatementPdf
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class OutstandingViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)
    val bills: StateFlow<List<Bill>> =
        repo.allBills.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val purchases: StateFlow<List<Purchase>> =
        repo.allPurchases.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val customers: StateFlow<List<com.billing.pos.data.Customer>> =
        repo.customers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val suppliers: StateFlow<List<com.billing.pos.data.Supplier>> =
        repo.suppliers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

private data class DocDue(val no: String, val date: Long, val balance: Double)
private data class PartyDue(val name: String, val total: Double, val docs: List<DocDue>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutstandingScreen(
    onBack: () -> Unit,
    vm: OutstandingViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val bills by vm.bills.collectAsStateSafe()
    val purchases by vm.purchases.collectAsStateSafe()
    val customers by vm.customers.collectAsStateSafe()
    val suppliers by vm.suppliers.collectAsStateSafe()
    var payable by remember { mutableStateOf(false) }   // false = receivable (customers)
    var nameQuery by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }

    val receivables = remember(bills) {
        bills.filter { it.balance > 0.001 }.groupBy { it.customerName }
            .map { (name, list) -> PartyDue(name, list.sumOf { it.balance }, list.map { DocDue(it.billNo, it.dateMillis, it.balance) }) }
            .sortedByDescending { it.total }
    }
    val payables = remember(purchases) {
        purchases.filter { it.balance > 0.001 }.groupBy { it.supplierName }
            .map { (name, list) -> PartyDue(name, list.sumOf { it.balance }, list.map { DocDue(it.purchaseNo, it.dateMillis, it.balance) }) }
            .sortedByDescending { it.total }
    }

    val list = (if (payable) payables else receivables)
        .filter { nameQuery.isBlank() || it.name.contains(nameQuery, ignoreCase = true) }
    val grandTotal = list.sumOf { it.total }

    fun phoneFor(name: String): String =
        (if (payable) suppliers.firstOrNull { it.name.equals(name, true) }?.phone
        else customers.firstOrNull { it.name.equals(name, true) }?.phone) ?: ""

    fun buildStatement(party: PartyDue): File {
        val heading = if (payable) "Payable Statement" else "Outstanding Statement"
        val lines = party.docs.map { StatementPdf.Line(it.no, it.date, it.balance) }
        return StatementPdf.generate(
            context, AppPrefs(context).company, party.name.ifBlank { "(no name)" },
            heading, phoneFor(party.name), lines, party.total
        )
    }

    fun doDownload(party: PartyDue) {
        scope.launch {
            val file = withContext(Dispatchers.IO) { buildStatement(party) }
            val ok = withContext(Dispatchers.IO) { DownloadSaver.save(context, file, file.name, "application/pdf") }
            snackbar.showSnackbar(if (ok) "Saved to Downloads: ${file.name}" else "Could not save")
        }
    }

    var pendingDownload by remember { mutableStateOf<PartyDue?>(null) }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val party = pendingDownload; pendingDownload = null
        if (granted && party != null) doDownload(party) else scope.launch { snackbar.showSnackbar("Storage permission denied") }
    }
    fun requestDownload(party: PartyDue) {
        if (DownloadSaver.needsLegacyPermission() &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) { pendingDownload = party; storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
        else doDownload(party)
    }

    fun sendWhatsApp(party: PartyDue) {
        scope.launch {
            val file = withContext(Dispatchers.IO) { buildStatement(party) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            val phone = phoneFor(party.name).filter { it.isDigit() }
            val caption = (if (payable) "Payable" else "Outstanding") + ": ${party.name} — Total ${Format.rupee(party.total)}"
            fun tryPkg(pkg: String?): Boolean {
                val i = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TEXT, caption)
                    if (phone.isNotEmpty()) putExtra("jid", "$phone@s.whatsapp.net")
                    if (pkg != null) setPackage(pkg)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                return runCatching { context.startActivity(i) }.isSuccess
            }
            if (tryPkg("com.whatsapp") || tryPkg("com.whatsapp.w4b")) return@launch
            val chooser = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri); putExtra(Intent.EXTRA_TEXT, caption)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(Intent.createChooser(chooser, "Send statement").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Outstanding") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        if (!Session.canViewInvoice) {
            Column(Modifier.fillMaxSize().padding(pad), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("You don't have permission to view this report", color = MaterialTheme.colorScheme.outline)
            }
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(pad).padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !payable, onClick = { payable = false }, label = { Text("Receivable (Customers)") })
                FilterChip(selected = payable, onClick = { payable = true }, label = { Text("Payable (Suppliers)") })
            }
            OutlinedTextField(
                value = nameQuery, onValueChange = { nameQuery = it },
                label = { Text(if (payable) "Search supplier" else "Search customer") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )

            Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (payable) "Total payable" else "Total receivable", fontWeight = FontWeight.Bold)
                    Text(Format.rupee(grandTotal), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                }
            }

            if (list.isEmpty()) {
                Text("Nothing outstanding.", color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 16.dp))
            } else {
                LazyColumn(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    items(list, key = { it.name }) { party ->
                        val isOpen = party.name in expanded
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { expanded = if (isOpen) expanded - party.name else expanded + party.name }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(party.name.ifBlank { "(no name)" }, fontWeight = FontWeight.Bold)
                                Text("${party.docs.size} document(s)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            }
                            Text(Format.rupee(party.total), fontWeight = FontWeight.Bold)
                            IconButton(onClick = { requestDownload(party) }) {
                                Icon(Icons.Filled.Download, contentDescription = "Download PDF")
                            }
                            IconButton(onClick = { sendWhatsApp(party) }) {
                                Icon(Icons.Filled.Share, contentDescription = "Send to WhatsApp", tint = MaterialTheme.colorScheme.primary)
                            }
                            Icon(if (isOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null)
                        }
                        if (isOpen) {
                            party.docs.sortedBy { it.date }.forEach { d ->
                                Row(Modifier.fillMaxWidth().padding(start = 12.dp, bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("${d.no}  •  ${Format.date(d.date)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                                    Text(Format.rupee(d.balance), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        Divider()
                    }
                }
            }
        }
    }
}
