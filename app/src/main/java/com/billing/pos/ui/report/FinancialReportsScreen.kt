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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.billing.pos.data.AccountGroup
import com.billing.pos.data.AccountNature
import com.billing.pos.data.Repository
import com.billing.pos.report.AccountingEngine
import com.billing.pos.report.Posting
import com.billing.pos.util.Format
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.util.Calendar

data class TBRow(val head: String, val group: String, val debit: Double, val credit: Double)

class FinancialReportViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)

    val accountGroups: StateFlow<List<AccountGroup>> =
        repo.accountGroups.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Unified account-transaction view — the ONE source for all statements. Collected by the UI. */
    val postings: StateFlow<List<Posting>> =
        combine(
            listOf<Flow<Any?>>(
                repo.accountHeads, repo.accountGroups, repo.allBills, repo.allPurchases, repo.allReceipts,
                repo.allExpenses, repo.journalEntries, repo.journalLines, repo.salesReturns, repo.purchaseReturns
            )
        ) { a ->
            @Suppress("UNCHECKED_CAST")
            AccountingEngine.build(
                a[0] as List<com.billing.pos.data.AccountHead>, a[1] as List<AccountGroup>,
                a[2] as List<com.billing.pos.data.Bill>, a[3] as List<com.billing.pos.data.Purchase>,
                a[4] as List<com.billing.pos.data.Receipt>, a[5] as List<com.billing.pos.data.Expense>,
                a[6] as List<com.billing.pos.data.JournalEntry>, a[7] as List<com.billing.pos.data.JournalLine>,
                a[8] as List<com.billing.pos.data.SalesReturn>, a[9] as List<com.billing.pos.data.PurchaseReturn>
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

fun trialBalanceOf(p: List<Posting>, to: Long): List<TBRow> =
    p.filter { it.date <= to }.groupBy { it.head to it.group }.map { (k, list) ->
        val net = list.sumOf { it.debit } - list.sumOf { it.credit }
        TBRow(k.first, k.second, if (net >= 0) net else 0.0, if (net < 0) -net else 0.0)
    }.filter { it.debit != 0.0 || it.credit != 0.0 }.sortedWith(compareBy({ it.group }, { it.head }))

fun profitLossOf(p: List<Posting>, from: Long, to: Long): Triple<List<Pair<String, Double>>, List<Pair<String, Double>>, Double> {
    val f = p.filter { it.date in from..to }
    val inc = f.filter { it.nature == AccountNature.INCOME }.groupBy { it.head }
        .map { (h, l) -> h to (l.sumOf { it.credit } - l.sumOf { it.debit }) }.filter { it.second != 0.0 }.sortedBy { it.first }
    val exp = f.filter { it.nature == AccountNature.EXPENSE }.groupBy { it.head }
        .map { (h, l) -> h to (l.sumOf { it.debit } - l.sumOf { it.credit }) }.filter { it.second != 0.0 }.sortedBy { it.first }
    return Triple(inc, exp, inc.sumOf { it.second } - exp.sumOf { it.second })
}

fun balanceSheetOf(p: List<Posting>, to: Long): List<Any> {
    val f = p.filter { it.date <= to }
    val assets = f.filter { it.nature == AccountNature.ASSET }.groupBy { it.head }
        .map { (h, l) -> h to (l.sumOf { it.debit } - l.sumOf { it.credit }) }.filter { it.second != 0.0 }.sortedBy { it.first }
    val liab = f.filter { it.nature == AccountNature.LIABILITY }.groupBy { it.head }
        .map { (h, l) -> h to (l.sumOf { it.credit } - l.sumOf { it.debit }) }.filter { it.second != 0.0 }.sortedBy { it.first }
    val inc = f.filter { it.nature == AccountNature.INCOME }.sumOf { it.credit - it.debit }
    val exp = f.filter { it.nature == AccountNature.EXPENSE }.sumOf { it.debit - it.credit }
    val netProfit = inc - exp
    val diff = assets.sumOf { it.second } - (liab.sumOf { it.second } + netProfit)
    return listOf(assets, liab, netProfit, diff)
}

data class GroupRow(val name: String, val debit: Double, val credit: Double, val level: Int)

/** Group-wise Trial Balance: each group's row already includes every descendant group's net
 * (via [AccountingEngine.rollUp]), rendered indented by nesting depth — so the TOTAL must sum
 * only the top-level (level 0) rows, not every row, or descendants would be double-counted. */
fun groupTrialBalanceOf(groups: List<AccountGroup>, postings: List<Posting>, to: Long): List<GroupRow> {
    val net = AccountingEngine.rollUp(groups, postings.filter { it.date <= to })
    val childrenOf = groups.groupBy { it.parentGroupId }
    val rows = mutableListOf<GroupRow>()
    fun walk(g: AccountGroup, level: Int) {
        val n = net[g.id] ?: 0.0
        if (n != 0.0) rows += GroupRow(g.name, if (n >= 0) n else 0.0, if (n < 0) -n else 0.0, level)
        (childrenOf[g.id] ?: emptyList()).sortedBy { it.name }.forEach { walk(it, level + 1) }
    }
    groups.filter { it.parentGroupId == null }.sortedBy { it.name }.forEach { walk(it, 0) }
    return rows
}

/** Group-wise Balance Sheet side (Assets or Liabilities): same rollup, restricted to top-level
 * groups of the given nature and their descendants, in each group's natural Dr/Cr sign — assets
 * show debit-credit, liabilities show credit-debit, matching [balanceSheetOf]'s per-head convention. */
fun groupBalanceSheetOf(groups: List<AccountGroup>, postings: List<Posting>, to: Long, nature: AccountNature): List<GroupRow> {
    val net = AccountingEngine.rollUp(groups, postings.filter { it.date <= to })
    val childrenOf = groups.groupBy { it.parentGroupId }
    val rows = mutableListOf<GroupRow>()
    fun walk(g: AccountGroup, level: Int) {
        val raw = net[g.id] ?: 0.0
        val amount = if (nature == AccountNature.LIABILITY) -raw else raw
        if (amount != 0.0) rows += GroupRow(g.name, amount, 0.0, level)
        (childrenOf[g.id] ?: emptyList()).sortedBy { it.name }.forEach { walk(it, level + 1) }
    }
    groups.filter { it.parentGroupId == null && it.nature == nature }.sortedBy { it.name }.forEach { walk(it, 0) }
    return rows
}

data class CashFlowResult(
    val operating: Double, val investing: Double, val financing: Double,
    val openingCash: Double, val closingCash: Double
) {
    val netChange: Double get() = operating + investing + financing
}

/**
 * Direct-method Cash Flow Statement. Rather than starting from Net Profit and reconciling
 * every non-cash adjustment (the indirect method), this walks each voucher's postings (grouped
 * by [Posting.vch]) and looks only at vouchers that actually move Cash-in-hand/Bank money —
 * non-cash entries like Depreciation or a credit sale simply have no cash leg and are skipped
 * automatically, no manual add-back needed. Each cash-moving voucher is classified by the
 * *other* leg(s) of the same voucher: Fixed Assets -> Investing, Capital Account -> Financing,
 * everything else (sales, purchases, expenses, debtors/creditors, tax...) -> Operating. A
 * voucher whose other legs span more than one category (rare for this app) falls back to
 * Operating rather than guessing which category dominates.
 */
fun cashFlowOf(groups: List<AccountGroup>, postings: List<Posting>, from: Long, to: Long): CashFlowResult {
    val byId = groups.associateBy { it.id }
    fun topGroupName(startName: String): String {
        var cur = groups.firstOrNull { it.name == startName } ?: return startName
        while (cur.parentGroupId != null) cur = byId[cur.parentGroupId] ?: break
        return cur.name
    }
    val topByName = HashMap<String, String>()
    fun top(name: String) = topByName.getOrPut(name) { topGroupName(name) }
    fun isCashBank(name: String) = top(name) == "Cash-in-hand" || top(name) == "Bank Accounts"
    fun isInvesting(name: String) = top(name) == "Fixed Assets"
    fun isFinancing(name: String) = top(name) == "Capital Account"

    var operating = 0.0; var investing = 0.0; var financing = 0.0
    postings.filter { it.date in from..to }.groupBy { it.vch }.forEach { (_, legs) ->
        val cashLegs = legs.filter { isCashBank(it.group) }
        if (cashLegs.isEmpty()) return@forEach
        val cashNet = cashLegs.sumOf { it.debit - it.credit }
        if (cashNet == 0.0) return@forEach
        val otherLegs = legs.filter { !isCashBank(it.group) }
        when {
            otherLegs.isNotEmpty() && otherLegs.all { isInvesting(it.group) } -> investing += cashNet
            otherLegs.isNotEmpty() && otherLegs.all { isFinancing(it.group) } -> financing += cashNet
            else -> operating += cashNet
        }
    }
    val openingCash = postings.filter { it.date < from && isCashBank(it.group) }.sumOf { it.debit - it.credit }
    val closingCash = postings.filter { it.date <= to && isCashBank(it.group) }.sumOf { it.debit - it.credit }
    return CashFlowResult(operating, investing, financing, openingCash, closingCash)
}

fun monthAgo(): Long = Calendar.getInstance().apply { add(Calendar.MONTH, -1) }.timeInMillis
fun today(): Long = Calendar.getInstance().timeInMillis

@Composable
fun DateBtn(label: String, millis: Long, onPick: (Long) -> Unit, modifier: Modifier = Modifier) {
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
    val postings by vm.postings.collectAsState()
    val groups by vm.accountGroups.collectAsState()
    var to by remember { mutableStateOf(today()) }
    var groupWise by remember { mutableStateOf(false) }
    var rows by remember { mutableStateOf<List<TBRow>?>(null) }
    var groupRows by remember { mutableStateOf<List<GroupRow>?>(null) }
    ReportScaffold("Trial Balance", onBack) { m ->
        Column(m) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DateBtn("As on", to, { to = it }, Modifier.weight(1f))
                Button(
                    onClick = {
                        val asOf = com.billing.pos.ui.common.endOfDay(to)
                        if (groupWise) groupRows = groupTrialBalanceOf(groups, postings, asOf)
                        else rows = trialBalanceOf(postings, asOf)
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("View") }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Group-wise", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = groupWise, onCheckedChange = { groupWise = it })
            }
            if (groupWise) {
                groupRows?.let { list ->
                    val tDr = list.filter { it.level == 0 }.sumOf { it.debit }
                    val tCr = list.filter { it.level == 0 }.sumOf { it.credit }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Text("Group", Modifier.weight(2f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                        Text("Debit", Modifier.weight(1f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                        Text("Credit", Modifier.weight(1f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    }
                    HorizontalDivider()
                    LazyColumn(Modifier.weight(1f)) {
                        items(list) { r ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                Text(
                                    r.name, Modifier.weight(2f).padding(start = (r.level * 16).dp),
                                    fontWeight = if (r.level == 0) FontWeight.Bold else FontWeight.Normal,
                                    style = MaterialTheme.typography.bodySmall
                                )
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
                    if (list.isEmpty()) Text("No transactions yet.", color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 8.dp))
                }
            } else {
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
                    if (list.isEmpty()) Text("No transactions yet.", color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

@Composable
fun ProfitLossScreen(onBack: () -> Unit, vm: FinancialReportViewModel = viewModel()) {
    val postings by vm.postings.collectAsState()
    var from by remember { mutableStateOf(monthAgo()) }
    var to by remember { mutableStateOf(today()) }
    var res by remember { mutableStateOf<Triple<List<Pair<String, Double>>, List<Pair<String, Double>>, Double>?>(null) }
    ReportScaffold("Profit & Loss", onBack) { m ->
        Column(m) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DateBtn("From", from, { from = it }, Modifier.weight(1f))
                DateBtn("To", to, { to = it }, Modifier.weight(1f))
            }
            Button(onClick = { res = profitLossOf(postings, com.billing.pos.ui.common.startOfDay(from), com.billing.pos.ui.common.endOfDay(to)) },
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
private fun groupRowLine(r: GroupRow) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            r.name, modifier = Modifier.padding(start = (r.level * 16).dp),
            fontWeight = if (r.level == 0) FontWeight.Bold else FontWeight.Normal,
            style = MaterialTheme.typography.bodyMedium
        )
        Text(Format.money(r.debit), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun BalanceSheetScreen(onBack: () -> Unit, vm: FinancialReportViewModel = viewModel()) {
    val postings by vm.postings.collectAsState()
    val groups by vm.accountGroups.collectAsState()
    var to by remember { mutableStateOf(today()) }
    var groupWise by remember { mutableStateOf(false) }
    var res by remember { mutableStateOf<List<Any>?>(null) }
    var groupAssets by remember { mutableStateOf<List<GroupRow>?>(null) }
    var groupLiab by remember { mutableStateOf<List<GroupRow>?>(null) }
    ReportScaffold("Balance Sheet", onBack) { m ->
        Column(m) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DateBtn("As on", to, { to = it }, Modifier.weight(1f))
                Button(
                    onClick = {
                        val asOf = com.billing.pos.ui.common.endOfDay(to)
                        res = balanceSheetOf(postings, asOf)
                        if (groupWise) {
                            groupAssets = groupBalanceSheetOf(groups, postings, asOf, AccountNature.ASSET)
                            groupLiab = groupBalanceSheetOf(groups, postings, asOf, AccountNature.LIABILITY)
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("View") }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Group-wise", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = groupWise, onCheckedChange = { groupWise = it })
            }
            res?.let { r ->
                @Suppress("UNCHECKED_CAST") val assets = r[0] as List<Pair<String, Double>>
                @Suppress("UNCHECKED_CAST") val liab = r[1] as List<Pair<String, Double>>
                val netProfit = r[2] as Double
                val diff = r[3] as Double
                if (groupWise) {
                    val gAssets = groupAssets ?: emptyList()
                    val gLiab = groupLiab ?: emptyList()
                    val totalAssets = gAssets.filter { it.level == 0 }.sumOf { it.debit }
                    val totalLiabGroups = gLiab.filter { it.level == 0 }.sumOf { it.debit }
                    LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
                        item { Text("Liabilities", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary); HorizontalDivider() }
                        items(gLiab) { groupRowLine(it) }
                        item {
                            rowLine(if (netProfit >= 0) "Net Profit" else "Net Loss", Format.money(kotlin.math.abs(netProfit)))
                            if (kotlin.math.abs(diff) > 0.01) rowLine("Difference in opening", Format.money(diff))
                            rowLine("Total Liabilities", Format.money(totalLiabGroups + netProfit + diff), bold = true)
                            HorizontalDivider(Modifier.padding(vertical = 6.dp))
                            Text("Assets", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary); HorizontalDivider()
                        }
                        items(gAssets) { groupRowLine(it) }
                        item { rowLine("Total Assets", Format.money(totalAssets), bold = true) }
                    }
                } else {
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
}

@Composable
fun CashFlowScreen(onBack: () -> Unit, vm: FinancialReportViewModel = viewModel()) {
    val postings by vm.postings.collectAsState()
    val groups by vm.accountGroups.collectAsState()
    var from by remember { mutableStateOf(monthAgo()) }
    var to by remember { mutableStateOf(today()) }
    var res by remember { mutableStateOf<CashFlowResult?>(null) }
    ReportScaffold("Cash Flow Statement", onBack) { m ->
        Column(m) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DateBtn("From", from, { from = it }, Modifier.weight(1f))
                DateBtn("To", to, { to = it }, Modifier.weight(1f))
            }
            Button(
                onClick = {
                    res = cashFlowOf(groups, postings, com.billing.pos.ui.common.startOfDay(from), com.billing.pos.ui.common.endOfDay(to))
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text("View") }
            res?.let { r ->
                LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
                    item { rowLine("Opening cash & bank balance", Format.money(r.openingCash), bold = true); HorizontalDivider(Modifier.padding(vertical = 6.dp)) }
                    item { Text("Operating Activities", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary); HorizontalDivider() }
                    item { rowLine("Net cash from operations", Format.money(r.operating), bold = true) }
                    item { HorizontalDivider(Modifier.padding(vertical = 6.dp)); Text("Investing Activities", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary); HorizontalDivider() }
                    item { rowLine("Net cash from investing", Format.money(r.investing), bold = true) }
                    item { HorizontalDivider(Modifier.padding(vertical = 6.dp)); Text("Financing Activities", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary); HorizontalDivider() }
                    item { rowLine("Net cash from financing", Format.money(r.financing), bold = true) }
                    item {
                        HorizontalDivider(Modifier.padding(vertical = 6.dp))
                        rowLine(if (r.netChange >= 0) "Net increase in cash" else "Net decrease in cash", Format.money(kotlin.math.abs(r.netChange)), bold = true)
                        rowLine("Closing cash & bank balance", Format.money(r.closingCash), bold = true)
                    }
                }
            }
        }
    }
}
