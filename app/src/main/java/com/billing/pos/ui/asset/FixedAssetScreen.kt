package com.billing.pos.ui.asset

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.DepreciationMethod
import com.billing.pos.data.FixedAsset
import com.billing.pos.data.Purchase
import com.billing.pos.data.Repository
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.ui.common.PurchasePickerField
import com.billing.pos.util.Format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

class FixedAssetViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)
    val assets: StateFlow<List<FixedAsset>> =
        repo.fixedAssets.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val purchases: StateFlow<List<Purchase>> =
        repo.allPurchases.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }

    fun save(
        existing: FixedAsset?, name: String, category: String, purchaseDate: Long, purchaseCost: Double,
        method: String, ratePercent: Double, linkedPurchaseId: Long, remarks: String, onDone: () -> Unit
    ) {
        if (name.isBlank()) { message.value = "Enter a name"; return }
        if (purchaseCost <= 0.0) { message.value = "Enter a valid cost"; return }
        viewModelScope.launch {
            val asset = (existing ?: FixedAsset(name = "", purchaseDate = purchaseDate, purchaseCost = 0.0)).copy(
                name = name.trim(), category = category.trim(), purchaseDate = purchaseDate, purchaseCost = purchaseCost,
                depreciationMethod = method, ratePercent = ratePercent, linkedPurchaseId = linkedPurchaseId, remarks = remarks.trim()
            )
            if (existing == null) repo.saveFixedAsset(asset) else repo.updateFixedAsset(asset)
            message.value = "Saved"; onDone()
        }
    }

    fun delete(asset: FixedAsset) {
        viewModelScope.launch { repo.deleteFixedAsset(asset); message.value = "Asset deleted" }
    }

    fun runDepreciation(asOf: Long) {
        viewModelScope.launch {
            val result = repo.runDepreciation(asOf)
            message.value = if (result.assetsProcessed > 0)
                "Depreciation posted for ${result.assetsProcessed} asset(s): ${Format.rupee(result.totalAmount)}"
            else "No depreciation due — nothing to post"
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FixedAssetScreen(onBack: () -> Unit, vm: FixedAssetViewModel = viewModel()) {
    val snackbar = remember { SnackbarHostState() }
    val assets by vm.assets.collectAsStateSafe()
    val purchases by vm.purchases.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    var showDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<FixedAsset?>(null) }
    var deleteFor by remember { mutableStateOf<FixedAsset?>(null) }
    var showDepreciationDialog by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Fixed Assets") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { showDepreciationDialog = true }) {
                        Icon(Icons.Filled.TrendingDown, "Run depreciation")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = null; showDialog = true }) { Icon(Icons.Filled.Add, "Add asset") }
        }
    ) { pad ->
        if (assets.isEmpty()) {
            Text(
                "No fixed assets yet", color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.fillMaxSize().padding(pad).padding(24.dp)
            )
        } else LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp)) {
            items(assets, key = { it.id }) { a ->
                Row(
                    Modifier.fillMaxWidth().clickable { editing = a; showDialog = true }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(a.name, fontWeight = FontWeight.Bold)
                        Text(
                            (if (a.category.isNotBlank()) "${a.category}  •  " else "") +
                                "Cost ${Format.rupee(a.purchaseCost)}  •  Dep ${Format.rupee(a.accumulatedDepreciation)}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                        )
                    }
                    Text(Format.rupee(a.bookValue), fontWeight = FontWeight.Bold)
                    IconButton(onClick = { deleteFor = a }) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                }
                Divider()
            }
        }
    }

    if (showDialog) {
        FixedAssetDialog(
            existing = editing, purchases = purchases,
            onDismiss = { showDialog = false },
            onSave = { n, cat, date, cost, method, rate, linkedId, remarks ->
                vm.save(editing, n, cat, date, cost, method, rate, linkedId, remarks) { showDialog = false }
            }
        )
    }
    deleteFor?.let { a ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Delete ${a.name}?") },
            confirmButton = { TextButton(onClick = { vm.delete(a); deleteFor = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Cancel") } }
        )
    }
    if (showDepreciationDialog) {
        var asOf by remember { mutableStateOf(System.currentTimeMillis()) }
        AlertDialog(
            onDismissRequest = { showDepreciationDialog = false },
            title = { Text("Run depreciation") },
            text = {
                Column {
                    Text(
                        "Posts Depreciation Expense Dr / Accumulated Depreciation Cr for every asset with a " +
                            "rate set, covering the period since it was last depreciated (or since purchase).",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                    )
                    OutlinedButton(
                        onClick = { pickAssetDate(context, asOf) { asOf = it } },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                    ) { Text("As of: ${Format.date(asOf)}") }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.runDepreciation(asOf); showDepreciationDialog = false }) { Text("Run") }
            },
            dismissButton = { TextButton(onClick = { showDepreciationDialog = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun FixedAssetDialog(
    existing: FixedAsset?,
    purchases: List<Purchase>,
    onDismiss: () -> Unit,
    onSave: (String, String, Long, Double, String, Double, Long, String) -> Unit
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var category by remember { mutableStateOf(existing?.category ?: "") }
    var purchaseDate by remember { mutableStateOf(existing?.purchaseDate ?: System.currentTimeMillis()) }
    var cost by remember { mutableStateOf(if ((existing?.purchaseCost ?: 0.0) != 0.0) Format.money(existing!!.purchaseCost) else "") }
    var method by remember { mutableStateOf(existing?.depreciationMethod ?: DepreciationMethod.STRAIGHT_LINE) }
    var rate by remember { mutableStateOf(if ((existing?.ratePercent ?: 0.0) != 0.0) Format.money(existing!!.ratePercent) else "") }
    var linkedPurchaseId by remember { mutableStateOf(existing?.linkedPurchaseId ?: 0L) }
    var remarks by remember { mutableStateOf(existing?.remarks ?: "") }
    var showPurchasePicker by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    val linkedPurchase = remember(linkedPurchaseId, purchases) { purchases.firstOrNull { it.id == linkedPurchaseId } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New fixed asset" else "Edit fixed asset") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Asset name *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = category, onValueChange = { category = it }, label = { Text("Category") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))

                if (!showPurchasePicker) {
                    OutlinedButton(onClick = { showPurchasePicker = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Text(linkedPurchase?.let { "From purchase: ${it.purchaseNo}" } ?: "Pick from an existing purchase (optional)")
                    }
                } else {
                    PurchasePickerField(
                        purchases = purchases,
                        selectedNo = linkedPurchase?.purchaseNo ?: "",
                        onPick = { p -> linkedPurchaseId = p.id; purchaseDate = p.dateMillis; cost = Format.money(p.grandTotal) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                OutlinedButton(
                    onClick = { pickAssetDate(context, purchaseDate) { purchaseDate = it } },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) { Text("Purchase date: ${Format.date(purchaseDate)}") }
                OutlinedTextField(
                    value = cost, onValueChange = { cost = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Purchase cost *") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = method == DepreciationMethod.STRAIGHT_LINE, onClick = { method = DepreciationMethod.STRAIGHT_LINE }, label = { Text("Straight Line") })
                    FilterChip(selected = method == DepreciationMethod.WDV, onClick = { method = DepreciationMethod.WDV }, label = { Text("WDV") })
                }
                OutlinedTextField(
                    value = rate, onValueChange = { rate = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Depreciation rate % / year") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                OutlinedTextField(value = remarks, onValueChange = { remarks = it }, label = { Text("Remarks") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(name, category, purchaseDate, cost.toDoubleOrNull() ?: 0.0, method, rate.toDoubleOrNull() ?: 0.0, linkedPurchaseId, remarks)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun pickAssetDate(context: android.content.Context, current: Long, onPicked: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = current }
    android.app.DatePickerDialog(
        context,
        { _, y, m, d -> c.set(Calendar.YEAR, y); c.set(Calendar.MONTH, m); c.set(Calendar.DAY_OF_MONTH, d); onPicked(c.timeInMillis) },
        c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
    ).show()
}
