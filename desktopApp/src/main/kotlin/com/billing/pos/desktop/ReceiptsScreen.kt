package com.billing.pos.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.billing.pos.shared.Customer
import com.billing.pos.shared.DesktopDatabase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val receiptDateFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptsScreen(onBack: () -> Unit) {
    var receipts by remember { mutableStateOf(DesktopDatabase.allReceipts()) }
    val customers = remember { DesktopDatabase.allCustomers() }
    var showDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Receipts") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showDialog = true }) { Icon(Icons.Filled.Add, "New receipt") }
        }
    ) { pad ->
        if (receipts.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text("No receipts yet — tap + to record one.", color = MaterialTheme.colorScheme.outline)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
                items(receipts, key = { it.id }) { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(r.receiptNo, fontWeight = FontWeight.Bold)
                            Text(
                                "${r.customerName}  •  ${receiptDateFmt.format(Date(r.dateMillis))}  •  ${r.paymentMode}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Text("₹${"%.2f".format(r.amount)}", fontWeight = FontWeight.Bold)
                        IconButton(onClick = {
                            DesktopDatabase.deleteReceipt(r.id)
                            receipts = DesktopDatabase.allReceipts()
                        }) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    if (showDialog) {
        var selectedCustomer by remember { mutableStateOf<Customer?>(customers.firstOrNull()) }
        var amount by remember { mutableStateOf("") }
        var mode by remember { mutableStateOf("Cash") }
        var showCustomerPicker by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("New receipt") },
            text = {
                Column {
                    OutlinedButton(onClick = { showCustomerPicker = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(selectedCustomer?.name ?: "Select customer")
                    }
                    OutlinedTextField(
                        value = amount, onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Amount *") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    OutlinedTextField(
                        value = mode, onValueChange = { mode = it }, label = { Text("Payment mode (Cash/UPI/Card)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val a = amount.toDoubleOrNull()
                    val c = selectedCustomer
                    if (a != null && a > 0 && c != null) {
                        DesktopDatabase.addReceipt(c.name, a, mode.ifBlank { "Cash" })
                        receipts = DesktopDatabase.allReceipts()
                        showDialog = false
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text("Cancel") } }
        )

        if (showCustomerPicker) {
            AlertDialog(
                onDismissRequest = { showCustomerPicker = false },
                title = { Text("Select customer") },
                text = {
                    LazyColumn {
                        items(customers, key = { it.id }) { c ->
                            Text(
                                c.name,
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { selectedCustomer = c; showCustomerPicker = false }
                                    .padding(vertical = 10.dp)
                            )
                            HorizontalDivider()
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { showCustomerPicker = false }) { Text("Cancel") } }
            )
        }
    }
}
