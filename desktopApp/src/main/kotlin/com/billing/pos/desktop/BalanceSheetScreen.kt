package com.billing.pos.desktop

import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.billing.pos.shared.DesktopDatabase
import kotlin.math.abs

private data class BSRow(val label: String, val amount: Double)

/**
 * Simplified balance sheet — Assets = cash on hand + what customers owe us; Liabilities = what
 * we owe suppliers; the gap between Assets and Liabilities is shown as accumulated Net Profit
 * (same figure Profit & Loss reports), same simplification/disclaimer as Trial Balance and P&L.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BalanceSheetScreen(onBack: () -> Unit) {
    val (assets, liabilities, netProfit) = remember {
        val bills = DesktopDatabase.allBills()
        val receipts = DesktopDatabase.allReceipts()
        val purchases = DesktopDatabase.allPurchases()
        val expenses = DesktopDatabase.allExpenses()

        val cash = receipts.sumOf { it.amount } - expenses.sumOf { it.amount }
        val receivable = DesktopDatabase.allCustomers().sumOf { c ->
            (bills.filter { it.customerName == c.name }.sumOf { it.grandTotal } -
                receipts.filter { it.customerName == c.name }.sumOf { it.amount }).coerceAtLeast(0.0)
        }
        val payable = DesktopDatabase.allSuppliers().sumOf { s ->
            (purchases.filter { it.supplierName == s.name }.sumOf { it.grandTotal } -
                expenses.filter { it.payTo == s.name }.sumOf { it.amount }).coerceAtLeast(0.0)
        }
        val assetRows = listOfNotNull(
            if (cash != 0.0) BSRow("Cash / Bank", cash) else null,
            if (receivable != 0.0) BSRow("Receivable from customers", receivable) else null
        )
        val liabRows = listOfNotNull(
            if (payable != 0.0) BSRow("Payable to suppliers", payable) else null
        )
        val income = bills.sumOf { it.grandTotal }
        val expenseTotal = expenses.sumOf { it.amount }
        Triple(assetRows, liabRows, income - expenseTotal)
    }
    val totalAssets = assets.sumOf { it.amount }
    val totalLiabilities = liabilities.sumOf { it.amount } + netProfit

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Balance Sheet") },
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
                "Simplified — Assets (cash + receivables) vs. Liabilities (payables + accumulated " +
                    "profit). A full balance sheet with capital/equity lands with the shared database migration.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
            )
            LazyColumn(Modifier.fillMaxSize().padding(top = 16.dp)) {
                item { Text("Liabilities", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary); HorizontalDivider() }
                items(liabilities) { bsRow(it.label, it.amount) }
                item {
                    bsRow(if (netProfit >= 0) "Accumulated Net Profit" else "Accumulated Net Loss", abs(netProfit))
                    bsRow("Total Liabilities", totalLiabilities, bold = true)
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    Text("Assets", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary); HorizontalDivider()
                }
                items(assets) { bsRow(it.label, it.amount) }
                item { bsRow("Total Assets", totalAssets, bold = true) }
            }
        }
    }
}

@Composable
private fun bsRow(label: String, amount: Double, bold: Boolean = false) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
    ) {
        Text(label, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
        Text("₹${"%.2f".format(amount)}", fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
    }
}
