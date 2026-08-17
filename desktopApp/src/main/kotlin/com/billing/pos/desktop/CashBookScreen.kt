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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val cashBookDateFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

private data class CashEntry(val dateMillis: Long, val voucherNo: String, val description: String, val amountIn: Double, val amountOut: Double)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CashBookScreen(onBack: () -> Unit) {
    val entries = remember {
        val receiptEntries = DesktopDatabase.allReceipts().map {
            CashEntry(it.dateMillis, it.receiptNo, "Received from ${it.customerName}", it.amount, 0.0)
        }
        val expenseEntries = DesktopDatabase.allExpenses().map {
            CashEntry(it.dateMillis, it.voucherNo, it.description + (if (it.payTo.isNotBlank()) " to ${it.payTo}" else ""), 0.0, it.amount)
        }
        (receiptEntries + expenseEntries).sortedBy { it.dateMillis }
    }

    var running = 0.0
    val rows = entries.map { e -> running += e.amountIn - e.amountOut; e to running }
    val totalIn = entries.sumOf { it.amountIn }
    val totalOut = entries.sumOf { it.amountOut }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cash Book") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Row(Modifier.fillMaxWidth().padding(16.dp)) {
                summaryBox("Cash In", totalIn, Modifier)
                summaryBox("Cash Out", totalOut, Modifier)
                summaryBox("Balance", totalIn - totalOut, Modifier)
            }
            HorizontalDivider()
            if (rows.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No cash transactions yet.", color = MaterialTheme.colorScheme.outline)
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Date / Voucher", Modifier.weight(2f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    Text("In", Modifier.weight(1f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    Text("Out", Modifier.weight(1f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    Text("Balance", Modifier.weight(1f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                }
                HorizontalDivider()
                LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    items(rows, key = { it.first.voucherNo }) { (e, balance) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Column(Modifier.weight(2f)) {
                                Text(e.voucherNo, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                Text(
                                    "${e.description}  •  ${cashBookDateFmt.format(Date(e.dateMillis))}",
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline
                                )
                            }
                            Text(if (e.amountIn > 0) "₹${"%.2f".format(e.amountIn)}" else "", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Text(if (e.amountOut > 0) "₹${"%.2f".format(e.amountOut)}" else "", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Text("₹${"%.2f".format(balance)}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun summaryBox(label: String, amount: Double, modifier: Modifier) {
    Column(modifier.padding(end = 16.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
        Text("₹${"%.2f".format(amount)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}
