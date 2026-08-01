package com.billing.pos.ui.order

import android.app.Application
import android.content.Intent
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
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.CustOrder
import com.billing.pos.data.CustOrderItem
import com.billing.pos.data.Customer
import com.billing.pos.data.OrderStatus
import com.billing.pos.data.Repository
import com.billing.pos.data.SalesmanMap
import com.billing.pos.data.XlsxWriter
import com.billing.pos.pdf.TablePdf
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.util.Format
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.io.File
import java.util.Calendar

class OrderReportViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)
    val orders: StateFlow<List<CustOrder>> = repo.custOrders.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val lines: StateFlow<List<CustOrderItem>> = repo.custOrderLinesFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val customers: StateFlow<List<Customer>> = repo.customers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val salesmen: StateFlow<List<SalesmanMap>> = repo.salesmen.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

/** One item's total ordered qty across matching orders, its rolled-up status, plus the per-customer breakdown behind it. */
private data class ItemAgg(val name: String, val totalQty: Double, val status: OrderStatus, val byCustomer: List<Pair<String, Double>>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderReportScreen(onBack: () -> Unit, vm: OrderReportViewModel = viewModel()) {
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
    var detailFor by remember { mutableStateOf<ItemAgg?>(null) }

    val orderById = orders.associateBy { it.id }
    val statusEnumFilter = statusFilterToEnum(statusFilter)
    // Lines that pass the order-level filters (customer + date range + salesman), then item + status.
    val filteredLines = lines.filter { l ->
        val o = orderById[l.orderId] ?: return@filter false
        (custFilter == null || o.customerId == custFilter!!.id) &&
            o.dateMillis in fromMillis..toMillis &&
            (itemFilter == "All" || l.name.equals(itemFilter, true)) &&
            (salesmanFilter == "All" || resolveSalesman(o.deviceId, salesmen) == salesmanFilter) &&
            (statusEnumFilter == null || OrderStatus.from(l.status) == statusEnumFilter)
    }
    val aggregates = filteredLines.groupBy { it.name.lowercase() }.map { (_, ls) ->
        val name = ls.first().name
        val byCust = ls.groupBy { orderById[it.orderId]?.customerName ?: "—" }
            .map { (c, cl) -> c to cl.sumOf { it.qty } }.sortedByDescending { it.second }
        val status = OrderStatus.rollup(ls.map { OrderStatus.from(it.status) })
        ItemAgg(name, ls.sumOf { it.qty }, status, byCust)
    }.sortedByDescending { it.totalQty }

    val itemNames = listOf("All") + lines.map { it.name }.distinct().sorted()
    val salesmanNames = listOf("All") + orders.map { resolveSalesman(it.deviceId, salesmen) }.distinct().sorted()

    fun buildPdf(): File {
        val cols = listOf(TablePdf.Col("Item", 2.5f), TablePdf.Col("Total Qty", 1.2f, right = true), TablePdf.Col("Status", 1.3f))
        val data = aggregates.map { listOf(it.name, Format.qty(it.totalQty), it.status.label) }
        val crit = buildString {
            append(if (custFilter == null) "All customers" else custFilter!!.name)
            append(" · "); append(if (itemFilter == "All") "All items" else itemFilter)
            append(" · "); append(if (statusFilter == "All") "All status" else statusFilter)
            append(" · "); append(if (salesmanFilter == "All") "All salesmen" else salesmanFilter)
            append(" · "); append(Format.date(fromMillis)); append(" – "); append(Format.date(toMillis))
        }
        return TablePdf.generate(context, AppPrefs(context).company, "Consolidated Orders", crit, cols, data,
            listOf("Lines" to aggregates.size.toString()))
    }

    fun buildExcel(): File {
        val rows = ArrayList<List<XlsxWriter.Cell>>()
        rows.add(XlsxWriter.row(XlsxWriter.text("Item"), XlsxWriter.text("Customer"), XlsxWriter.text("Qty"), XlsxWriter.text("Status")))
        aggregates.forEach { a ->
            a.byCustomer.forEach { (c, q) -> rows.add(XlsxWriter.row(XlsxWriter.text(a.name), XlsxWriter.text(c), XlsxWriter.num(q), XlsxWriter.text(""))) }
            rows.add(XlsxWriter.row(XlsxWriter.text(a.name + " — TOTAL"), XlsxWriter.text(""), XlsxWriter.num(a.totalQty), XlsxWriter.text(a.status.label)))
        }
        val f = File(File(context.cacheDir, "shared").apply { mkdirs() }, "consolidated_orders.xlsx")
        XlsxWriter.write(f, "Orders", rows)
        return f
    }

    fun share(file: File, mime: String) {
        runCatching {
            val uri = FileProvider.getUriForFile(context, context.packageName + ".provider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mime; putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, "Consolidated orders")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
                val d = Intent(send).setPackage(pkg)
                if (d.resolveActivity(context.packageManager) != null) { runCatching { context.startActivity(d) }.onSuccess { return } }
            }
            context.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Consolidated Orders") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { share(buildPdf(), "application/pdf") }) { Icon(Icons.Filled.PictureAsPdf, "Share PDF") }
                    IconButton(onClick = { share(buildExcel(), "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet") }) { Icon(Icons.Filled.Share, "Share Excel") }
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
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                com.billing.pos.ui.common.CustomerPickField(
                    customers = customers,
                    selectedName = custFilter?.name ?: "All",
                    onPick = { custFilter = it },
                    extraOptions = listOf("All"),
                    onPickExtra = { custFilter = null },
                    modifier = Modifier.weight(1f)
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterDrop("Item", itemFilter, itemNames, { itemFilter = it }, Modifier.weight(1f))
                FilterDrop("Status", statusFilter, STATUS_FILTER_OPTIONS, { statusFilter = it }, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterDrop("Salesman", salesmanFilter, salesmanNames, { salesmanFilter = it }, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("From", style = MaterialTheme.typography.labelLarge)
                OutlinedButton(onClick = { pickDate(context, fromMillis) { fromMillis = startOfDay(it) } }, modifier = Modifier.weight(1f)) { Text(Format.date(fromMillis)) }
                Text("To", style = MaterialTheme.typography.labelLarge)
                OutlinedButton(onClick = { pickDate(context, toMillis) { toMillis = endOfDay(it) } }, modifier = Modifier.weight(1f)) { Text(Format.date(toMillis)) }
            }
            Divider(Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text("ITEM", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.outline)
                Text("QTY", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(end = 12.dp))
                Text("STATUS", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.outline)
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(aggregates, key = { it.name }) { a ->
                    Row(Modifier.fillMaxWidth().clickable { detailFor = a }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(a.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Text(Format.qty(a.totalQty), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 12.dp))
                        Text(a.status.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    Divider()
                }
                if (aggregates.isEmpty()) item { Text("Nothing ordered for this filter.", color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(16.dp)) }
            }
        }
    }

    detailFor?.let { a ->
        AlertDialog(
            onDismissRequest = { detailFor = null },
            title = { Text(a.name) },
            text = {
                Column {
                    a.byCustomer.forEach { (c, q) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(c, Modifier.weight(1f))
                            Text(Format.qty(q), fontWeight = FontWeight.Bold)
                        }
                    }
                    Divider(Modifier.padding(vertical = 4.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Text("Total", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        Text(Format.qty(a.totalQty), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { detailFor = null }) { Text("Close") } }
        )
    }
}

