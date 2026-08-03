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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.AccountHead
import com.billing.pos.data.Repository
import com.billing.pos.report.AccountingEngine
import com.billing.pos.report.Posting
import com.billing.pos.util.Format
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private fun reconKey(headId: Long, vch: String, dateMillis: Long, amount: Double) = "$headId|$vch|$dateMillis|$amount"

@OptIn(ExperimentalCoroutinesApi::class)
class BankReconciliationViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)

    val cashBankHeads: StateFlow<List<AccountHead>> =
        repo.cashBankHeads.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val selectedHeadId = MutableStateFlow(0L)
    fun selectHead(id: Long) { selectedHeadId.value = id }

    /** Same unified posting feed every other accounting report reads. */
    val postings: StateFlow<List<Posting>> =
        combine(
            listOf<Flow<Any?>>(
                repo.accountHeads, repo.accountGroups, repo.allBills, repo.allPurchases, repo.allReceipts,
                repo.allExpenses, repo.journalEntries, repo.journalLines, repo.salesReturns, repo.purchaseReturns
            )
        ) { a ->
            @Suppress("UNCHECKED_CAST")
            AccountingEngine.build(
                a[0] as List<AccountHead>, a[1] as List<com.billing.pos.data.AccountGroup>,
                a[2] as List<com.billing.pos.data.Bill>, a[3] as List<com.billing.pos.data.Purchase>,
                a[4] as List<com.billing.pos.data.Receipt>, a[5] as List<com.billing.pos.data.Expense>,
                a[6] as List<com.billing.pos.data.JournalEntry>, a[7] as List<com.billing.pos.data.JournalLine>,
                a[8] as List<com.billing.pos.data.SalesReturn>, a[9] as List<com.billing.pos.data.PurchaseReturn>
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val reconciledKeys: StateFlow<Set<String>> = selectedHeadId.flatMapLatest { id ->
        if (id == 0L) flowOf(emptyList()) else repo.reconciledFor(id)
    }.map { list -> list.map { reconKey(it.headId, it.vch, it.dateMillis, it.amount) }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    fun toggle(headId: Long, vch: String, dateMillis: Long, amount: Double, currentlyReconciled: Boolean) {
        viewModelScope.launch {
            if (currentlyReconciled) repo.unmarkReconciled(headId, vch, dateMillis, amount)
            else repo.markReconciled(headId, vch, dateMillis, amount)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BankReconciliationScreen(onBack: () -> Unit, vm: BankReconciliationViewModel = viewModel()) {
    val heads by vm.cashBankHeads.collectAsState()
    val postings by vm.postings.collectAsState()
    val reconciledKeys by vm.reconciledKeys.collectAsState()
    val headId by vm.selectedHeadId.collectAsState()

    var headMenu by remember { mutableStateOf(false) }
    val selectedHead = heads.firstOrNull { it.id == headId }
    var from by remember { mutableStateOf(monthAgo()) }
    var to by remember { mutableStateOf(today()) }
    var statementBalanceText by remember { mutableStateOf("") }

    val headName = selectedHead?.name ?: ""
    val allForHead = postings.filter { it.head == headName }
    val toEnd = com.billing.pos.ui.common.endOfDay(to)
    val fromStart = com.billing.pos.ui.common.startOfDay(from)
    val opening = allForHead.filter { it.date < fromStart }.sumOf { it.debit - it.credit }
    val inRange = allForHead.filter { it.date in fromStart..toEnd }.sortedBy { it.date }
    val bookBalance = opening + inRange.sumOf { it.debit - it.credit }
    val reconciledInRange = inRange.filter { reconKey(headId, it.vch, it.date, it.debit - it.credit) in reconciledKeys }
    val reconciledBalance = opening + reconciledInRange.sumOf { it.debit - it.credit }
    val statementBalance = statementBalanceText.toDoubleOrNull()
    val difference = statementBalance?.let { it - reconciledBalance }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bank Reconciliation") },
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
            ExposedDropdownMenuBox(expanded = headMenu, onExpandedChange = { headMenu = !headMenu }) {
                OutlinedTextField(
                    readOnly = true, value = selectedHead?.name ?: "Select Cash/Bank account",
                    onValueChange = {}, label = { Text("Account") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(headMenu) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = headMenu, onDismissRequest = { headMenu = false }) {
                    heads.forEach { h ->
                        DropdownMenuItem(text = { Text(h.name) }, onClick = { vm.selectHead(h.id); headMenu = false })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DateBtn("From", from, { from = it }, Modifier.weight(1f))
                DateBtn("To", to, { to = it }, Modifier.weight(1f))
            }
            OutlinedTextField(
                value = statementBalanceText, onValueChange = { statementBalanceText = it.filter { c -> c.isDigit() || c == '.' || c == '-' } },
                label = { Text("Bank statement closing balance") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )

            if (headId != 0L) {
                Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        SummaryRow("Book balance (ledger)", Format.money(bookBalance))
                        SummaryRow("Reconciled balance", Format.money(reconciledBalance))
                        statementBalance?.let { SummaryRow("Statement balance", Format.money(it)) }
                        difference?.let {
                            HorizontalDivider(Modifier.padding(vertical = 4.dp))
                            SummaryRow(
                                if (kotlin.math.abs(it) < 0.01) "Matched ✓" else "Difference", Format.money(it),
                                bold = true,
                                color = if (kotlin.math.abs(it) < 0.01) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text("Reconciled", Modifier.weight(0.4f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    Text("Date / Vch", Modifier.weight(1f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    Text("Amount", Modifier.weight(1f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                }
                HorizontalDivider()
                if (inRange.isEmpty()) {
                    Text("No transactions in this period.", color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 12.dp))
                } else {
                    LazyColumn(Modifier.weight(1f)) {
                        items(inRange, key = { "${it.vch}_${it.date}_${it.debit}_${it.credit}" }) { p ->
                            val net = p.debit - p.credit
                            val key = reconKey(headId, p.vch, p.date, net)
                            val checked = key in reconciledKeys
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { vm.toggle(headId, p.vch, p.date, net, checked) },
                                    modifier = Modifier.weight(0.4f)
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(Format.date(p.date), style = MaterialTheme.typography.bodySmall)
                                    Text(p.vch, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                    if (p.particulars.isNotBlank()) Text(p.particulars, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                }
                                Text(
                                    (if (net >= 0) "+ " else "- ") + Format.money(kotlin.math.abs(net)),
                                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            } else {
                Text("Pick a Cash/Bank account to begin.", color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 24.dp))
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String, bold: Boolean = false, color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, style = MaterialTheme.typography.bodyMedium)
        Text(value, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}
