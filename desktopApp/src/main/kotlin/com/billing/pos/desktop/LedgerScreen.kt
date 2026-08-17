package com.billing.pos.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.billing.pos.shared.DesktopDatabase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ledgerDateFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

private data class LedgerEntry(val dateMillis: Long, val voucherNo: String, val description: String, val debit: Double, val credit: Double)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerScreen(onBack: () -> Unit) {
    val customers = remember { DesktopDatabase.allCustomers() }
    val suppliers = remember { DesktopDatabase.allSuppliers() }
    val bills = remember { DesktopDatabase.allBills() }
    val receipts = remember { DesktopDatabase.allReceipts() }
    val purchases = remember { DesktopDatabase.allPurchases() }
    val expenses = remember { DesktopDatabase.allExpenses() }

    var isCustomerMode by remember { mutableStateOf(true) }
    var selectedName by remember { mutableStateOf<String?>(null) }
    var showPicker by remember { mutableStateOf(false) }

    val entries: List<LedgerEntry> = remember(selectedName, isCustomerMode) {
        val name = selectedName ?: return@remember emptyList()
        if (isCustomerMode) {
            val billEntries = bills.filter { it.customerName == name }
                .map { LedgerEntry(it.dateMillis, it.billNo, "Sale", it.grandTotal, 0.0) }
            val receiptEntries = receipts.filter { it.customerName == name }
                .map { LedgerEntry(it.dateMillis, it.receiptNo, "Payment received", 0.0, it.amount) }
            (billEntries + receiptEntries).sortedBy { it.dateMillis }
        } else {
            val purchaseEntries = purchases.filter { it.supplierName == name }
                .map { LedgerEntry(it.dateMillis, it.purchaseNo, "Purchase", 0.0, it.grandTotal) }
            val paymentEntries = expenses.filter { it.payTo == name }
                .map { LedgerEntry(it.dateMillis, it.voucherNo, "Payment made", it.amount, 0.0) }
            (purchaseEntries + paymentEntries).sortedBy { it.dateMillis }
        }
    }

    var running = 0.0
    val rows = entries.map { e -> running += e.debit - e.credit; e to running }

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
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = isCustomerMode,
                    onClick = { isCustomerMode = true; selectedName = null },
                    label = { Text("Customers") }
                )
                FilterChip(
                    selected = !isCustomerMode,
                    onClick = { isCustomerMode = false; selectedName = null },
                    label = { Text("Suppliers") }
                )
            }
            OutlinedButton(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Text(selectedName ?: if (isCustomerMode) "Select customer" else "Select supplier")
            }

            if (selectedName == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Pick a party to see their ledger.", color = MaterialTheme.colorScheme.outline)
                }
            } else if (rows.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No transactions for $selectedName yet.", color = MaterialTheme.colorScheme.outline)
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp)) {
                    Text("Voucher", Modifier.weight(2f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    Text("Debit", Modifier.weight(1f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    Text("Credit", Modifier.weight(1f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    Text("Balance", Modifier.weight(1f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                }
                HorizontalDivider()
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(rows, key = { it.first.voucherNo }) { (e, balance) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Column(Modifier.weight(2f)) {
                                Text(e.voucherNo, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                Text(
                                    "${e.description}  •  ${ledgerDateFmt.format(Date(e.dateMillis))}",
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline
                                )
                            }
                            Text(if (e.debit > 0) "₹${"%.2f".format(e.debit)}" else "", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Text(if (e.credit > 0) "₹${"%.2f".format(e.credit)}" else "", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Text("₹${"%.2f".format(balance)}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (showPicker) {
        val names = if (isCustomerMode) customers.map { it.name } else suppliers.map { it.name }
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text(if (isCustomerMode) "Select customer" else "Select supplier") },
            text = {
                LazyColumn {
                    items(names) { n ->
                        Text(
                            n,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { selectedName = n; showPicker = false }
                                .padding(vertical = 10.dp)
                        )
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("Cancel") } }
        )
    }
}
