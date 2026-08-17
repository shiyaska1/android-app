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
import java.util.concurrent.TimeUnit

private val dayBookDateFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

private data class DayBookEntry(val dateMillis: Long, val voucherNo: String, val type: String, val party: String, val amount: Double)

/** All transaction types combined into one chronological view, last 30 days — the simplified
 * desktop equivalent of Android's Day Book (which reads the full double-entry Posting feed;
 * this reads the same Phase 1 tables every other desktop report already uses). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayBookScreen(onBack: () -> Unit) {
    val entries = remember {
        val since = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
        val sales = DesktopDatabase.allBills().filter { it.dateMillis >= since }
            .map { DayBookEntry(it.dateMillis, it.billNo, "Sale", it.customerName, it.grandTotal) }
        val purchases = DesktopDatabase.allPurchases().filter { it.dateMillis >= since }
            .map { DayBookEntry(it.dateMillis, it.purchaseNo, "Purchase", it.supplierName, it.grandTotal) }
        val receipts = DesktopDatabase.allReceipts().filter { it.dateMillis >= since }
            .map { DayBookEntry(it.dateMillis, it.receiptNo, "Receipt", it.customerName, it.amount) }
        val payments = DesktopDatabase.allExpenses().filter { it.dateMillis >= since }
            .map { DayBookEntry(it.dateMillis, it.voucherNo, "Payment", it.payTo.ifBlank { it.description }, it.amount) }
        (sales + purchases + receipts + payments).sortedByDescending { it.dateMillis }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Day Book") },
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
            Text(
                "Last 30 days — every Sale, Purchase, Receipt, and Payment in one chronological list.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(16.dp)
            )
            if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No transactions in the last 30 days.", color = MaterialTheme.colorScheme.outline)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    items(entries, key = { it.type + it.voucherNo }) { e ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("${e.type}  •  ${e.voucherNo}", fontWeight = FontWeight.Bold)
                                Text(
                                    "${e.party}  •  ${dayBookDateFmt.format(Date(e.dateMillis))}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                                )
                            }
                            Text("₹${"%.2f".format(e.amount)}", fontWeight = FontWeight.Bold)
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
