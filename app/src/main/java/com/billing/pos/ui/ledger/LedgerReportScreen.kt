package com.billing.pos.ui.ledger

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import com.billing.pos.data.Repository
import com.billing.pos.util.Format
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import java.util.Calendar

internal enum class Kind { CUSTOMER, SUPPLIER, CASHBANK, GENERAL }
internal data class AccountRef(val name: String, val kind: Kind, val headId: Long, val groupId: Long)
internal data class LedgerRow(val date: Long, val particulars: String, val vch: String, val debit: Double, val credit: Double, val balance: Double)
internal data class LedgerResult(val opening: Double, val rows: List<LedgerRow>, val closing: Double)

private fun drcr(v: Double): String = if (v >= 0) "${Format.money(v)} Dr" else "${Format.money(-v)} Cr"

class LedgerReportViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)
    private fun <T> f(flow: kotlinx.coroutines.flow.Flow<T>, initial: T) =
        flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initial)

    val groups = f(repo.accountGroups, emptyList())
    val heads = f(repo.accountHeads, emptyList())
    private val customers = f(repo.customers, emptyList())
    private val suppliers = f(repo.suppliers, emptyList())
    private val bills = f(repo.allBills, emptyList())
    private val receipts = f(repo.allReceipts, emptyList())
    private val expenses = f(repo.allExpenses, emptyList())
    private val purchases = f(repo.allPurchases, emptyList())
    private val jEntries = f(repo.journalEntries, emptyList())
    private val jLines = f(repo.journalLines, emptyList())

    private fun gid(name: String) = groups.value.firstOrNull { it.name == name }?.id ?: 0L
    private fun kindOf(groupId: Long): Kind = when (groupId) {
        gid("Sundry Debtors") -> Kind.CUSTOMER
        gid("Sundry Creditors") -> Kind.SUPPLIER
        gid("Cash-in-hand"), gid("Bank Accounts") -> Kind.CASHBANK
        else -> Kind.GENERAL
    }

    /** All selectable accounts: every head, plus customers/suppliers that don't yet have a head. */
    internal fun accountRefs(): List<AccountRef> {
        val hs = heads.value
        val refs = hs.map { AccountRef(it.name, kindOf(it.groupId), it.id, it.groupId) }.toMutableList()
        val headNames = hs.map { it.name.lowercase() }.toSet()
        val dg = gid("Sundry Debtors"); val cg = gid("Sundry Creditors")
        customers.value.filter { !it.isDefault && it.name.lowercase() !in headNames }
            .forEach { refs.add(AccountRef(it.name, Kind.CUSTOMER, 0, dg)) }
        suppliers.value.filter { !it.isDefault && it.name.lowercase() !in headNames }
            .forEach { refs.add(AccountRef(it.name, Kind.SUPPLIER, 0, cg)) }
        return refs.sortedBy { it.name.lowercase() }
    }

    private fun headLines(headId: Long): List<LedgerRow> {
        if (headId == 0L) return emptyList()
        val byId = jEntries.value.associateBy { it.id }
        return jLines.value.filter { it.headId == headId }.mapNotNull { l ->
            val e = byId[l.entryId] ?: return@mapNotNull null
            LedgerRow(e.dateMillis, e.narration.ifBlank { "Journal" }, e.voucherNo,
                if (l.isDebit) l.amount else 0.0, if (!l.isDebit) l.amount else 0.0, 0.0)
        }
    }

    private fun rawRows(ref: AccountRef): List<LedgerRow> {
        val list = ArrayList<LedgerRow>()
        when (ref.kind) {
            Kind.CUSTOMER -> {
                bills.value.filter { it.customerName.equals(ref.name, true) }
                    .forEach { list.add(LedgerRow(it.dateMillis, "Sales", it.billNo, it.grandTotal, 0.0, 0.0)) }
                receipts.value.filter { (it.payFrom.ifBlank { it.customerName }).equals(ref.name, true) }
                    .forEach { list.add(LedgerRow(it.dateMillis, "Receipt", it.receiptNo, 0.0, it.amount, 0.0)) }
                list.addAll(headLines(ref.headId))
            }
            Kind.SUPPLIER -> {
                purchases.value.filter { it.supplierName.equals(ref.name, true) }
                    .forEach { list.add(LedgerRow(it.dateMillis, "Purchase", it.purchaseNo, 0.0, it.grandTotal, 0.0)) }
                expenses.value.filter { it.payTo.equals(ref.name, true) }
                    .forEach { list.add(LedgerRow(it.dateMillis, "Payment", it.voucherNo, it.amount, 0.0, 0.0)) }
                list.addAll(headLines(ref.headId))
            }
            Kind.CASHBANK -> {
                receipts.value.filter { it.toAccountId == ref.headId && ref.headId != 0L }
                    .forEach { list.add(LedgerRow(it.dateMillis, "Receipt: " + it.payFrom.ifBlank { it.customerName }, it.receiptNo, it.amount, 0.0, 0.0)) }
                expenses.value.filter { it.fromAccountId == ref.headId && ref.headId != 0L }
                    .forEach { list.add(LedgerRow(it.dateMillis, "Payment: " + it.payTo, it.voucherNo, 0.0, it.amount, 0.0)) }
                list.addAll(headLines(ref.headId))
            }
            Kind.GENERAL -> list.addAll(headLines(ref.headId))
        }
        return list
    }

    internal fun build(ref: AccountRef, from: Long, to: Long): LedgerResult {
        val head = heads.value.firstOrNull { it.id == ref.headId }
        var opening = if (head != null) (if (head.openingIsDebit) head.openingBalance else -head.openingBalance) else 0.0
        val all = rawRows(ref)
        all.filter { it.date < from }.forEach { opening += it.debit - it.credit }
        var running = opening
        val rows = all.filter { it.date in from..to }.sortedBy { it.date }.map { r ->
            running += r.debit - r.credit
            r.copy(balance = running)
        }
        return LedgerResult(opening, rows, running)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerReportScreen(onBack: () -> Unit, vm: LedgerReportViewModel = viewModel()) {
    val context = LocalContext.current
    val groups by vm.groups.collectAsState()
    val heads by vm.heads.collectAsState()

    var groupId by remember { mutableStateOf(0L) }
    var groupMenu by remember { mutableStateOf(false) }
    var accMenu by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<AccountRef?>(null) }
    val cal = remember { Calendar.getInstance() }
    var toMillis by remember { mutableStateOf(cal.timeInMillis) }
    var fromMillis by remember { mutableStateOf(Calendar.getInstance().apply { add(Calendar.MONTH, -1) }.timeInMillis) }
    var result by remember { mutableStateOf<LedgerResult?>(null) }

    val refs = remember(heads, groups, groupId) {
        vm.accountRefs().let { list -> if (groupId == 0L) list else list.filter { it.groupId == groupId } }
    }

    fun pickDate(current: Long, onPicked: (Long) -> Unit) {
        val c = Calendar.getInstance().apply { timeInMillis = current }
        android.app.DatePickerDialog(context, { _, y, m, d ->
            onPicked(Calendar.getInstance().apply { set(y, m, d, 0, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis)
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ledger") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(12.dp)) {
            // Group (optional)
            Box {
                OutlinedTextField(
                    value = if (groupId == 0L) "All groups" else groups.firstOrNull { it.id == groupId }?.name ?: "All groups",
                    onValueChange = {}, readOnly = true, label = { Text("Account group (optional)") },
                    trailingIcon = { IconButton(onClick = { groupMenu = true }) { Icon(Icons.Filled.ArrowDropDown, null) } },
                    modifier = Modifier.fillMaxWidth()
                )
                DropdownMenu(expanded = groupMenu, onDismissRequest = { groupMenu = false }) {
                    DropdownMenuItem(text = { Text("All groups") }, onClick = { groupId = 0; selected = null; groupMenu = false })
                    groups.forEach { g -> DropdownMenuItem(text = { Text(g.name) }, onClick = { groupId = g.id; selected = null; groupMenu = false }) }
                }
            }
            // Account — type to search, shows up to 5 suggestions
            var accQuery by remember { mutableStateOf("") }
            OutlinedTextField(
                value = if (selected != null) selected!!.name else accQuery,
                onValueChange = { accQuery = it; selected = null; result = null },
                label = { Text("Account (type to search)") },
                trailingIcon = {
                    if (selected != null) IconButton(onClick = { selected = null; accQuery = "" }) { Icon(Icons.Filled.ArrowDropDown, "Clear") }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            if (selected == null && accQuery.isNotBlank()) {
                val matches = refs.filter { it.name.contains(accQuery, ignoreCase = true) }.take(5)
                matches.forEach { r ->
                    Text(
                        r.name,
                        modifier = Modifier.fillMaxWidth()
                            .clickable { selected = r; accQuery = "" }
                            .padding(vertical = 10.dp, horizontal = 8.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    HorizontalDivider()
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickDate(fromMillis) { fromMillis = it } }, modifier = Modifier.weight(1f)) { Text("From: ${Format.date(fromMillis)}") }
                OutlinedButton(onClick = { pickDate(toMillis) { toMillis = it } }, modifier = Modifier.weight(1f)) { Text("To: ${Format.date(toMillis)}") }
            }
            Button(
                onClick = { selected?.let { result = vm.build(it, com.billing.pos.ui.common.startOfDay(fromMillis), com.billing.pos.ui.common.endOfDay(toMillis)) } },
                enabled = selected != null, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text("View ledger") }

            result?.let { res ->
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Opening balance", fontWeight = FontWeight.Bold)
                    Text(drcr(res.opening), fontWeight = FontWeight.Bold)
                }
                // Header
                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text("Date", Modifier.weight(1.2f), style = MaterialTheme.typography.labelSmall)
                    Text("Particulars", Modifier.weight(2f), style = MaterialTheme.typography.labelSmall)
                    Text("Debit", Modifier.weight(1.2f), style = MaterialTheme.typography.labelSmall)
                    Text("Credit", Modifier.weight(1.2f), style = MaterialTheme.typography.labelSmall)
                    Text("Balance", Modifier.weight(1.4f), style = MaterialTheme.typography.labelSmall)
                }
                HorizontalDivider()
                LazyColumn(Modifier.weight(1f)) {
                    items(res.rows) { r ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(Format.date(r.date), Modifier.weight(1.2f), style = MaterialTheme.typography.bodySmall)
                            Column(Modifier.weight(2f)) {
                                Text(r.particulars, style = MaterialTheme.typography.bodySmall)
                                if (r.vch.isNotBlank()) Text(r.vch, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            }
                            Text(if (r.debit != 0.0) Format.money(r.debit) else "", Modifier.weight(1.2f), style = MaterialTheme.typography.bodySmall)
                            Text(if (r.credit != 0.0) Format.money(r.credit) else "", Modifier.weight(1.2f), style = MaterialTheme.typography.bodySmall)
                            Text(drcr(r.balance), Modifier.weight(1.4f), style = MaterialTheme.typography.bodySmall)
                        }
                        HorizontalDivider()
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Closing balance", fontWeight = FontWeight.Bold)
                    Text(drcr(res.closing), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
