package com.billing.pos.ui.production

import android.app.Application
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
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.ProductionRun
import com.billing.pos.data.Repository
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.ui.order.FilterDrop
import com.billing.pos.ui.order.defaultRangeFrom
import com.billing.pos.ui.order.defaultRangeTo
import com.billing.pos.ui.order.endOfDay
import com.billing.pos.ui.order.pickDate
import com.billing.pos.ui.order.startOfDay
import com.billing.pos.util.Format
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class ProductionReportViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)
    val runs: StateFlow<List<ProductionRun>> = repo.productionRuns.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductionReportScreen(onBack: () -> Unit, vm: ProductionReportViewModel = viewModel()) {
    val context = LocalContext.current
    val runs by vm.runs.collectAsStateSafe()

    var procedureFilter by remember { mutableStateOf("All") }
    var fromMillis by remember { mutableStateOf(defaultRangeFrom()) }
    var toMillis by remember { mutableStateOf(defaultRangeTo()) }

    val procedureNames = listOf("All") + runs.map { it.procedureName }.distinct().sorted()
    val shown = runs.filter { r ->
        (procedureFilter == "All" || r.procedureName == procedureFilter) && r.dateMillis in fromMillis..toMillis
    }.sortedByDescending { it.dateMillis }
    val totalMaterial = shown.sumOf { it.materialCost }
    val totalLabour = shown.sumOf { it.labourCost }
    val totalCost = shown.sumOf { it.totalCost }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Production Cost Report") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                FilterDrop("Procedure", procedureFilter, procedureNames, { procedureFilter = it }, Modifier.fillMaxWidth())
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("From", style = MaterialTheme.typography.labelLarge)
                OutlinedButton(onClick = { pickDate(context, fromMillis) { fromMillis = startOfDay(it) } }, modifier = Modifier.weight(1f)) { Text(Format.date(fromMillis)) }
                Text("To", style = MaterialTheme.typography.labelLarge)
                OutlinedButton(onClick = { pickDate(context, toMillis) { toMillis = endOfDay(it) } }, modifier = Modifier.weight(1f)) { Text(Format.date(toMillis)) }
            }
            Divider(Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text("RUN / ITEM", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.outline)
                Text("TOTAL COST", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.outline)
            }
            LazyColumn(Modifier.weight(1f)) {
                items(shown, key = { it.id }) { r ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.runNo + "  •  " + r.producedItemName, fontWeight = FontWeight.SemiBold)
                                Text(
                                    Format.date(r.dateMillis) + "  •  " + r.procedureName + "  •  Qty " + Format.qty(r.qtyProduced) + " " + r.unit,
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                                )
                            }
                            Text(Format.rupee(r.totalCost), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Material " + Format.rupee(r.materialCost), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text("Labour " + Format.rupee(r.labourCost), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text("Unit " + Format.rupee(r.unitCost), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Divider()
                }
                if (shown.isEmpty()) item { Text("No production runs for this filter.", color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(16.dp)) }
            }
            Divider(thickness = 2.dp)
            Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text("Material cost", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    Text(Format.rupee(totalMaterial), fontWeight = FontWeight.Bold)
                }
                Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                    Text("Labour cost", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    Text(Format.rupee(totalLabour), fontWeight = FontWeight.Bold)
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text("TOTAL PRODUCTION COST", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(Format.rupee(totalCost), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
