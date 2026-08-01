package com.billing.pos.ui.order

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import com.billing.pos.data.CustOrder
import com.billing.pos.data.CustOrderItem
import com.billing.pos.data.Customer
import com.billing.pos.data.OrderStatus
import com.billing.pos.data.Repository
import com.billing.pos.data.SalesmanMap
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.util.Format
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class OrderStatusReportViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)
    val orders: StateFlow<List<CustOrder>> = repo.custOrders.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val lines: StateFlow<List<CustOrderItem>> = repo.custOrderLinesFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val customers: StateFlow<List<Customer>> = repo.customers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val salesmen: StateFlow<List<SalesmanMap>> = repo.salesmen.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setStatus(lineId: Long, status: OrderStatus) { viewModelScope.launch { repo.updateOrderItemStatus(lineId, status) } }
    fun setStatusBulk(lineIds: List<Long>, status: OrderStatus) { viewModelScope.launch { repo.updateOrderItemStatusBulk(lineIds, status) } }
}

/** One order-item line, flattened with its parent order's customer/date/salesman for the report table. */
private data class LineRow(
    val lineId: Long, val orderId: Long, val orderNo: String, val customerName: String,
    val dateMillis: Long, val itemName: String, val qty: Double, val unit: String,
    val status: OrderStatus, val salesman: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderStatusReportScreen(onBack: () -> Unit, vm: OrderStatusReportViewModel = viewModel()) {
    val context = LocalContext.current
    val orders by vm.orders.collectAsStateSafe()
    val lines by vm.lines.collectAsStateSafe()
    val customers by vm.customers.collectAsStateSafe()
    val salesmen by vm.salesmen.collectAsStateSafe()

    var itemFilter by remember { mutableStateOf("All") }
    var custFilter by remember { mutableStateOf<Customer?>(null) }
    var statusFilter by remember { mutableStateOf("All") }
    var salesmanFilter by remember { mutableStateOf("All") }
    var fromMillis by remember { mutableStateOf(defaultRangeFrom()) }
    var toMillis by remember { mutableStateOf(defaultRangeTo()) }
    var statusMenuFor by remember { mutableStateOf<Long?>(null) }
    var bulkMenuOpen by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<Long>() }

    val orderById = orders.associateBy { it.id }
    val statusEnumFilter = statusFilterToEnum(statusFilter)
    val rows = lines.mapNotNull { l ->
        val o = orderById[l.orderId] ?: return@mapNotNull null
        LineRow(l.id, o.id, o.orderNo, o.customerName, o.dateMillis, l.name, l.qty, l.unit, OrderStatus.from(l.status), resolveSalesman(o.deviceId, salesmen))
    }.filter { r ->
        (custFilter == null || r.customerName.equals(custFilter!!.name, true)) &&
            r.dateMillis in fromMillis..toMillis &&
            (itemFilter == "All" || r.itemName.equals(itemFilter, true)) &&
            (salesmanFilter == "All" || r.salesman == salesmanFilter) &&
            (statusEnumFilter == null || r.status == statusEnumFilter)
    }.sortedByDescending { it.dateMillis }

    val itemNames = listOf("All") + lines.map { it.name }.distinct().sorted()
    val salesmanNames = listOf("All") + orders.map { resolveSalesman(it.deviceId, salesmen) }.distinct().sorted()
    val allSelected = rows.isNotEmpty() && rows.all { it.lineId in selected }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selected.isEmpty()) "Order Status Report" else "${selected.size} selected") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (selected.isNotEmpty()) {
                        Box {
                            TextButton(onClick = { bulkMenuOpen = true }) { Text("Set status", color = MaterialTheme.colorScheme.onPrimary) }
                            DropdownMenu(expanded = bulkMenuOpen, onDismissRequest = { bulkMenuOpen = false }) {
                                OrderStatus.values().forEach { s ->
                                    DropdownMenuItem(text = { Text(s.label) }, onClick = {
                                        vm.setStatusBulk(selected.toList(), s); selected.clear(); bulkMenuOpen = false
                                    })
                                }
                            }
                        }
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
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                com.billing.pos.ui.common.CustomerPickField(
                    customers = customers,
                    selectedName = custFilter?.name ?: "All",
                    onPick = { custFilter = it },
                    extraOptions = listOf("All"),
                    onPickExtra = { custFilter = null },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterDrop("Item", itemFilter, itemNames, { itemFilter = it }, Modifier.weight(1f))
                FilterDrop("Status", statusFilter, STATUS_FILTER_OPTIONS, { statusFilter = it }, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                FilterDrop("Salesman", salesmanFilter, salesmanNames, { salesmanFilter = it }, Modifier.fillMaxWidth())
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("From", style = MaterialTheme.typography.labelLarge)
                OutlinedButton(onClick = { pickDate(context, fromMillis) { fromMillis = startOfDay(it) } }, modifier = Modifier.weight(1f)) { Text(Format.date(fromMillis)) }
                Text("To", style = MaterialTheme.typography.labelLarge)
                OutlinedButton(onClick = { pickDate(context, toMillis) { toMillis = endOfDay(it) } }, modifier = Modifier.weight(1f)) { Text(Format.date(toMillis)) }
            }
            Divider(Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = allSelected, onCheckedChange = { checked ->
                    if (checked) { selected.clear(); selected.addAll(rows.map { it.lineId }) } else selected.clear()
                })
                Text("Select all", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(rows, key = { it.lineId }) { r ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            if (r.lineId in selected) selected.remove(r.lineId) else selected.add(r.lineId)
                        }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = r.lineId in selected, onCheckedChange = {
                            if (r.lineId in selected) selected.remove(r.lineId) else selected.add(r.lineId)
                        })
                        Column(Modifier.weight(1f)) {
                            Text(r.itemName + "  •  " + Format.qty(r.qty) + " " + r.unit, fontWeight = FontWeight.SemiBold)
                            Text(
                                r.customerName + "  •  " + Format.date(r.dateMillis) + "  •  " + r.salesman,
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Box {
                            TextButton(onClick = { statusMenuFor = r.lineId }) { Text(r.status.label) }
                            DropdownMenu(expanded = statusMenuFor == r.lineId, onDismissRequest = { statusMenuFor = null }) {
                                OrderStatus.values().forEach { s ->
                                    DropdownMenuItem(text = { Text(s.label) }, onClick = { vm.setStatus(r.lineId, s); statusMenuFor = null })
                                }
                            }
                        }
                    }
                    Divider()
                }
                if (rows.isEmpty()) item { Text("Nothing ordered for this filter.", color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(16.dp)) }
            }
        }
    }
}
