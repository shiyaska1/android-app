package com.billing.pos.desktop

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.billing.pos.shared.DesktopDatabase

private data class TBRow(val name: String, val debit: Double, val credit: Double)

/**
 * Simplified trial balance for the current desktop schema — there's no chart-of-accounts or
 * double-entry postings yet (that's the shared Room-multiplatform migration, a later batch),
 * so this shows net balances per customer/supplier plus a Cash row derived directly from the
 * Phase 1 tables. It won't foot to zero the way a real double-entry trial balance does — a
 * caption says so — but it's the same "who owes what" information the Android app's Ledger
 * ultimately rolls up into.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrialBalanceScreen(onBack: () -> Unit) {
    val rows = remember {
        val bills = DesktopDatabase.allBills()
        val receipts = DesktopDatabase.allReceipts()
        val purchases = DesktopDatabase.allPurchases()
        val expenses = DesktopDatabase.allExpenses()

        val customerRows = DesktopDatabase.allCustomers().mapNotNull { c ->
            val owed = bills.filter { it.customerName == c.name }.sumOf { it.grandTotal } -
                receipts.filter { it.customerName == c.name }.sumOf { it.amount }
            if (owed == 0.0) null
            else if (owed > 0) TBRow(c.name, owed, 0.0) else TBRow(c.name, 0.0, -owed)
        }
        val supplierRows = DesktopDatabase.allSuppliers().mapNotNull { s ->
            val owed = purchases.filter { it.supplierName == s.name }.sumOf { it.grandTotal } -
                expenses.filter { it.payTo == s.name }.sumOf { it.amount }
            if (owed == 0.0) null
            else if (owed > 0) TBRow(s.name, 0.0, owed) else TBRow(s.name, -owed, 0.0)
        }
        val cash = receipts.sumOf { it.amount } - expenses.sumOf { it.amount }
        val cashRow = if (cash == 0.0) emptyList() else listOf(
            if (cash > 0) TBRow("Cash / Bank", cash, 0.0) else TBRow("Cash / Bank", 0.0, -cash)
        )
        (cashRow + customerRows.sortedBy { it.name } + supplierRows.sortedBy { it.name })
    }
    val totalDr = rows.sumOf { it.debit }
    val totalCr = rows.sumOf { it.credit }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trial Balance") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp)) {
            Text(
                "Net balance per party (and cash on hand) from Phase 1 data — a full chart-of-accounts trial balance lands with the shared database migration.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
            )
            if (rows.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No balances yet.", color = MaterialTheme.colorScheme.outline)
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp)) {
                    Text("Account", Modifier.weight(2f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    Text("Debit", Modifier.weight(1f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    Text("Credit", Modifier.weight(1f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                }
                HorizontalDivider()
                LazyColumn(Modifier.weight(1f)) {
                    items(rows, key = { it.name }) { r ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text(r.name, Modifier.weight(2f), style = MaterialTheme.typography.bodySmall)
                            Text(if (r.debit != 0.0) "₹${"%.2f".format(r.debit)}" else "", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Text(if (r.credit != 0.0) "₹${"%.2f".format(r.credit)}" else "", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        }
                        HorizontalDivider()
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text("TOTAL", Modifier.weight(2f), fontWeight = FontWeight.Bold)
                    Text("₹${"%.2f".format(totalDr)}", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    Text("₹${"%.2f".format(totalCr)}", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
