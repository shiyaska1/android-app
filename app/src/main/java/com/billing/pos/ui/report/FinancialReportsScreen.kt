package com.billing.pos.ui.report

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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.AccountNature
import com.billing.pos.data.Repository
import com.billing.pos.report.AccountingEngine
import com.billing.pos.report.Posting
import com.billing.pos.util.Format
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import java.util.Calendar

data class TBRow(val head: String, val group: String, val debit: Double, val credit: Double)

class FinancialReportViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)
    private fun <T> f(flow: kotlinx.coroutines.flow.Flow<T>, initial: T) =
        flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initial)

    private val heads = f(repo.accountHeads, emptyList())
    private val groups = f(repo.accountGroups, emptyList())
    private val bills = f(repo.allBills, emptyList())
    private val purchases = f(repo.allPurchases, emptyList())
    private val receipts = f(repo.allReceipts, emptyList())
    private val expenses = f(repo.allExpenses, emptyList())
    private val jEntries = f(repo.journalEntries, emptyList())
    private val jLines = f(repo.journalLines, emptyList())
    private val salesReturns = f(repo.salesReturns, emptyList())
    private val purchaseReturns = f(repo.purchaseReturns, emptyList())

    private fun postings(): List<Posting> = AccountingEngine.build(
        heads.value, groups.value, bills.value, purchases.value, receipts.value, expenses.value, jEntries.value, jLines.value,
        salesReturns.value, purchaseReturns.value
    )

    fun trialBalance(to: Long): List<TBRow> {
        val p = postings().filter { it.date <= to }
        return p.groupBy { it.head to it.group }.map { (k, list) ->
            val net = list.sumOf { it.debit } - list.sumOf { it.credit }
            TBRow(k.first, k.second, if (net >= 0) net else 0.0, if (net < 0) -net else 0.0)
        }.filter { it.debit != 0.0 || it.credit != 0.0 }.sortedWith(compareBy({ it.group }, { it.head }))
    }

    /** Returns income rows, expense rows, and net profit for the period. */
    fun profitLoss(from: Long, to: Long): Triple<List<Pair<String, Double>>, List<Pair<String, Double>>, Double> {
        val p = postings().filter { it.date in from..to }
        val inc = p.filter { it.nature == AccountNature.INCOME }.groupBy { it.head }
            .map { (h, l) -> h to (l.sumOf { it.credit } - l.sumOf { it.debit }) }.filter { it.second != 0.0 }.sortedBy { it.first }
        val exp = p.filter { it.nature == AccountNature.EXPENSE }.groupBy { it.head }
            .map { (h, l) -> h to (l.sumOf { it.debit } - l.sumOf { it.credit }) }.filter { it.second != 0.0 }.sortedBy { it.first }
        val net = inc.sumOf { it.second } - exp.sumOf { it.second }
        return Triple(inc, exp, net)
    }

    /** Returns asset rows, liability rows, net profit (to date), and any imbalance. */
    fun balanceSheet(to: Long): List<Any> {
        val p = postings().filter { it.date <= to }
        val assets = p.filter { it.nature == AccountNature.ASSET }.groupBy { it.head }
            .map { (h, l) -> h to (l.sumOf { it.debit } - l.sumOf { it.credit }) }.filter { it.second != 0.0 }.sortedBy { it.first }
        val liab = p.filter { it.nature == AccountNature.LIABILITY }.groupBy { it.head }
            .map { (h, l) -> h to (l.sumOf { it.credit } - l.sumOf { it.debit }) }.filter { it.second != 0.0 }.sortedBy { it.first }
        val inc = p.filter { it.nature == AccountNature.INCOME }.sumOf { it.credit - it.debit }
        val exp = p.filter { it.nature == AccountNature.EXPENSE }.sumOf { it.debit - it.credit }
        val netProfit = inc - exp
        val totalAssets = assets.sumOf { it.second }
        val totalLiab = liab.sumOf { it.second } + netProfit
        val diff = totalAssets - totalLiab
        return listOf(assets, liab, netProfit, diff)
    }
}

private fun monthAgo(): Long = Calendar.getInstance().apply { add(Calendar.MONTH, -1) }.timeInMillis
private fun today(): Long = Calendar.getInstance().timeInMillis

@Composable
private fun DateBtn(label: String, millis: Long, onPick: (Long) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    OutlinedButton(onClick = {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        android.app.DatePickerDialog(context, { _, y, m, d ->
            onPick(Calendar.getInstance().apply { set(y, m, d, 0, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis)
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }, modifier = modifier) { Text("$label: ${Format.date(millis)}") }
}

@Composable
private fun rowLine(a: String, b: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(a, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, style = MaterialTheme.typography.bodyMedium)
        Text(b, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReportScaffold(title: String, onBack: () -> Unit, content: @Composable (Modifier) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad -> content(Modifier.fillMaxSize().padding(pad).padding(12.dp)) }
}

@Composable
fun TrialBalanceScreen(onBack: () -> Unit, vm: FinancialReportViewModel = viewModel()) {
    var to by remember { mutableStateOf(today()) }
    var rows by remember { mutableStateOf<List<TBRow>?>(null) }
    ReportScaffold("Trial Balance", onBack) { m ->
        Column(m) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DateBtn("As on", to, { to = it }, Modifier.weight(1f))
                Button(onClick = { rows = vm.trialBalance(com.billing.pos.ui.common.endOfDay(to)) }, modifier = Modifier.weight(1f)) { Text("View") }
            }
            rows?.let { list ->
                val tDr = list.sumOf { it.debit }; val tCr = list.sumOf { it.credit }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text("Account", Modifier.weight(2f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    Text("Debit", Modifier.weight(1f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    Text("Credit", Modifier.weight(1f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                }
                HorizontalDivider()
                LazyColumn(Modifier.weight(1f)) {
                    items(list) { r ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Column(Modifier.weight(2f)) {
                                Text(r.head, style = MaterialTheme.typography.bodySmall)
                                Text(r.group, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            }
                            Text(if (r.debit != 0.0) Format.money(r.debit) else "", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Text(if (r.credit != 0.0) Format.money(r.credit) else "", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        }
                        HorizontalDivider()
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text("TOTAL", Modifier.weight(2f), fontWeight = FontWeight.Bold)
                    Text(Format.money(tDr), Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    Text(Format.money(tCr), Modifier.weight(1f), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun ProfitLossScreen(onBack: () -> Unit, vm: FinancialReportViewModel = viewModel()) {
    var from by remember { mutableStateOf(monthAgo()) }
    var to by remember { mutableStateOf(today()) }
    var res by remember { mutableStateOf<Triple<List<Pair<String, Double>>, List<Pair<String, Double>>, Double>?>(null) }
    ReportScaffold("Profit & Loss", onBack) { m ->
        Column(m) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DateBtn("From", from, { from = it }, Modifier.weight(1f))
                DateBtn("To", to, { to = it }, Modifier.weight(1f))
            }
            Button(onClick = { res = vm.profitLoss(com.billing.pos.ui.common.startOfDay(from), com.billing.pos.ui.common.endOfDay(to)) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("View") }
            res?.let { (inc, exp, net) ->
                LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
                    item { Text("Income", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary); HorizontalDivider() }
                    items(inc) { rowLine(it.first, Format.money(it.second)) }
                    item { rowLine("Total Income", Format.money(inc.sumOf { it.second }), bold = true); HorizontalDivider(Modifier.padding(vertical = 6.dp)) }
                    item { Text("Expenses", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary); HorizontalDivider() }
                    items(exp) { rowLine(it.first, Format.money(it.second)) }
                    item { rowLine("Total Expenses", Format.money(exp.sumOf { it.second }), bold = true) }
                    item {
                        HorizontalDivider(Modifier.padding(vertical = 6.dp))
                        rowLine(if (net >= 0) "Net Profit" else "Net Loss", Format.money(kotlin.math.abs(net)), bold = true)
                    }
                }
            }
        }
    }
}

@Composable
fun BalanceSheetScreen(onBack: () -> Unit, vm: FinancialReportViewModel = viewModel()) {
    var to by remember { mutableStateOf(today()) }
    var res by remember { mutableStateOf<List<Any>?>(null) }
    ReportScaffold("Balance Sheet", onBack) { m ->
        Column(m) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DateBtn("As on", to, { to = it }, Modifier.weight(1f))
                Button(onClick = { res = vm.balanceSheet(com.billing.pos.ui.common.endOfDay(to)) }, modifier = Modifier.weight(1f)) { Text("View") }
            }
            res?.let { r ->
                @Suppress("UNCHECKED_CAST") val assets = r[0] as List<Pair<String, Double>>
                @Suppress("UNCHECKED_CAST") val liab = r[1] as List<Pair<String, Double>>
                val netProfit = r[2] as Double
                val diff = r[3] as Double
                val totalAssets = assets.sumOf { it.second }
                LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
                    item { Text("Liabilities", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary); HorizontalDivider() }
                    items(liab) { rowLine(it.first, Format.money(it.second)) }
                    item {
                        rowLine(if (netProfit >= 0) "Net Profit" else "Net Loss", Format.money(kotlin.math.abs(netProfit)))
                        if (kotlin.math.abs(diff) > 0.01) rowLine("Difference in opening", Format.money(diff))
                        rowLine("Total Liabilities", Format.money(liab.sumOf { it.second } + netProfit + diff), bold = true)
                        HorizontalDivider(Modifier.padding(vertical = 6.dp))
                        Text("Assets", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary); HorizontalDivider()
                    }
                    items(assets) { rowLine(it.first, Format.money(it.second)) }
                    item { rowLine("Total Assets", Format.money(totalAssets), bold = true) }
                }
            }
        }
    }
}
