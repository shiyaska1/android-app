package com.billing.pos.ui.reports

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.Repository
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.ui.common.PartyFilterField
import com.billing.pos.util.Format
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** One expiring batch, costed and matched to the supplier it was last bought from. */
data class ExpiryReportRow(
    val batchId: Long,
    val itemId: Long,
    val itemName: String,
    val batchNo: String,
    val unit: String,
    val expiryMillis: Long,
    val daysLeft: Long,          // negative once expired
    val qty: Double,
    val rate: Double,
    val taxPercent: Double,
    val supplierId: Long,
    val supplierName: String,
    val supplierPhone: String
) {
    val stockValue: Double get() = rate * qty
}

class ExpiryReportViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)
    private val prefs = AppPrefs(app)

    /** Seeds the "within N days" filter — same default as the popup alert, not written back to it. */
    val defaultDays: Int get() = prefs.expiryAlertDays

    val rows: StateFlow<List<ExpiryReportRow>> =
        combine(repo.items, repo.itemBatches, repo.batchCosts, repo.suppliers) { items, batches, costs, suppliers ->
            val itemById = items.associateBy { it.id }
            val supplierById = suppliers.associateBy { it.id }
            val supplierByName = suppliers.associateBy { it.name.lowercase() }
            val costByKey = costs
                .groupBy { it.name.lowercase() to it.batchNo.lowercase() }
                // If a batch was bought more than once, use the latest voucher.
                .mapValues { (_, l) -> l.maxByOrNull { it.dateMillis }!! }
            val now = System.currentTimeMillis()
            batches
                .filter { it.expiryMillis > 0 && it.quantity > 0 }
                .map { b ->
                    val item = itemById[b.itemId]
                    val name = item?.name ?: "?"
                    val cost = costByKey[name.lowercase() to b.batchNo.lowercase()]
                    val supplier = cost?.let { supplierById[it.supplierId] ?: supplierByName[it.supplierName.lowercase()] }
                    ExpiryReportRow(
                        batchId = b.id,
                        itemId = b.itemId,
                        itemName = name,
                        batchNo = b.batchNo,
                        unit = item?.unit.orEmpty(),
                        expiryMillis = b.expiryMillis,
                        daysLeft = Math.floorDiv(b.expiryMillis - now, 86_400_000L),
                        qty = b.quantity,
                        rate = cost?.price ?: 0.0,
                        taxPercent = cost?.taxPercent ?: 0.0,
                        supplierId = supplier?.id ?: cost?.supplierId ?: 0L,
                        supplierName = supplier?.name ?: cost?.supplierName.orEmpty(),
                        supplierPhone = supplier?.phone.orEmpty()
                    )
                }
                .sortedBy { it.expiryMillis }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Groups the ticked rows by supplier, for the one-return-per-supplier hand-off. */
    fun buildReturnGroups(selected: List<ExpiryReportRow>): List<ReturnGroup> =
        selected.groupBy { it.supplierId to it.supplierName }
            .map { (key, group) ->
                ReturnGroup(
                    supplierId = key.first,
                    supplierName = key.second,
                    lines = group.map {
                        ReturnPrefillLine(
                            itemId = it.itemId, name = it.itemName, batchNo = it.batchNo,
                            qty = it.qty, price = it.rate, taxPercent = it.taxPercent
                        )
                    }
                )
            }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpiryReportScreen(
    onBack: () -> Unit,
    onCreatePurchaseReturn: () -> Unit,
    vm: ExpiryReportViewModel = viewModel()
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val allRows by vm.rows.collectAsStateSafe()

    var withinDaysText by remember { mutableStateOf(vm.defaultDays.toString()) }
    var supplierFilter by remember { mutableStateOf("") }
    var itemFilter by remember { mutableStateOf("") }
    var selecting by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<Long>() }

    val withinDays = withinDaysText.toIntOrNull()?.coerceIn(1, 3650) ?: 30
    val cutoff = remember(withinDays) { System.currentTimeMillis() + withinDays.toLong() * 86_400_000L }

    val shown = allRows.filter {
        it.expiryMillis <= cutoff &&
            (supplierFilter.isBlank() || it.supplierName.equals(supplierFilter, ignoreCase = true)) &&
            (itemFilter.isBlank() || it.itemName.equals(itemFilter, ignoreCase = true))
    }
    val supplierNames = remember(allRows) { allRows.map { it.supplierName }.filter { it.isNotBlank() }.distinct().sorted() }
    val itemNames = remember(allRows) { allRows.map { it.itemName }.distinct().sorted() }

    LaunchedEffect(shown) { selected.retainAll(shown.map { it.batchId }.toSet()) }

    val totalValue = shown.sumOf { it.stockValue }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (selecting) "${selected.size} selected" else "Expiry Report") },
                navigationIcon = {
                    IconButton(onClick = { if (selecting) { selecting = false; selected.clear() } else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (selecting) {
                        IconButton(onClick = { selecting = false; selected.clear() }) { Icon(Icons.Filled.Close, "Cancel selection") }
                    } else {
                        IconButton(onClick = { selecting = true }) { Icon(Icons.Filled.Checklist, "Select items for return") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = withinDaysText,
                    onValueChange = { withinDaysText = it.filter { c -> c.isDigit() } },
                    label = { Text("Within days") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(110.dp)
                )
                PartyFilterField(
                    names = supplierNames, selected = supplierFilter,
                    onSelect = { supplierFilter = it }, label = "Supplier", allLabel = "All suppliers",
                    modifier = Modifier.weight(1f)
                )
            }
            PartyFilterField(
                names = itemNames, selected = itemFilter,
                onSelect = { itemFilter = it }, label = "Item", allLabel = "All items",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            )
            Divider(Modifier.padding(top = 6.dp))

            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No expiring batches match", color = MaterialTheme.colorScheme.outline)
                }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(shown, key = { it.batchId }) { r ->
                        val expired = r.daysLeft < 0
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable(enabled = selecting) {
                                    if (r.batchId in selected) selected.remove(r.batchId) else selected.add(r.batchId)
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (selecting) {
                                Checkbox(
                                    checked = r.batchId in selected,
                                    onCheckedChange = { if (r.batchId in selected) selected.remove(r.batchId) else selected.add(r.batchId) }
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(r.itemName, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 2)
                                    Text(
                                        if (expired) "EXPIRED" else "${r.daysLeft} days",
                                        color = if (expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                                Text(
                                    "Batch ${r.batchNo}  •  Exp ${Format.date(r.expiryMillis)}  •  Qty ${Format.qty(r.qty)} ${r.unit}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.outline
                                )
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    if (r.supplierName.isNotBlank()) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = if (r.supplierPhone.isNotBlank() && !selecting) Modifier.clickable {
                                                context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + r.supplierPhone)))
                                            } else Modifier
                                        ) {
                                            Text(
                                                r.supplierName, style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.secondary
                                            )
                                            if (r.supplierPhone.isNotBlank()) {
                                                Icon(
                                                    Icons.Filled.Call, "Call " + r.supplierName,
                                                    tint = MaterialTheme.colorScheme.secondary,
                                                    modifier = Modifier.padding(start = 4.dp).size(14.dp)
                                                )
                                            }
                                        }
                                    } else {
                                        Text("No supplier on record", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                                    }
                                    Text("Value " + Format.rupee(r.stockValue), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                        Divider()
                    }
                }
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Total stock value", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(Format.rupee(totalValue), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                }
            }

            if (selecting) {
                Divider(thickness = 2.dp)
                Button(
                    onClick = {
                        val picked = shown.filter { it.batchId in selected }
                        val groups = vm.buildReturnGroups(picked)
                        ExpiryReportToReturnLink.set(groups)
                        selecting = false
                        selected.clear()
                        onCreatePurchaseReturn()
                    },
                    enabled = selected.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(14.dp)
                ) { Text("Create purchase return (${selected.size})") }
            }
        }
    }
}
