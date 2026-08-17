package com.billing.pos.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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

/**
 * Simplified P&L: Income = total Sales, Expenses = total Payments recorded. Purchases aren't
 * counted as an expense line here — without inventory/COGS valuation (not modeled at this
 * schema depth yet), booking the full purchase cost would double-count against stock still on
 * hand. A real Purchase-as-COGS treatment lands once stock valuation is ported.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfitLossScreen(onBack: () -> Unit) {
    val income = remember { DesktopDatabase.allBills().sumOf { it.grandTotal } }
    val expenseTotal = remember { DesktopDatabase.allExpenses().sumOf { it.amount } }
    val netProfit = income - expenseTotal

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profit & Loss") },
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
                "Income = total sales. Expenses = total payments recorded. A full P&L with " +
                    "cost-of-goods-sold lands once stock valuation is ported.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
            )
            Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Text("Income", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                HorizontalDivider()
                plRow("Sales", income)
                plRow("Total Income", income, bold = true)

                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text("Expenses", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                HorizontalDivider()
                plRow("Payments", expenseTotal)
                plRow("Total Expenses", expenseTotal, bold = true)

                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                plRow(if (netProfit >= 0) "Net Profit" else "Net Loss", abs(netProfit), bold = true)
            }
        }
    }
}

@Composable
private fun plRow(label: String, amount: Double, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
        Text("₹${"%.2f".format(amount)}", fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
    }
}
