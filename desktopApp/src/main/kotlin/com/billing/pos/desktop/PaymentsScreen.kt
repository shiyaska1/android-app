package com.billing.pos.desktop

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
import com.billing.pos.shared.DesktopDatabase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val paymentDateFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentsScreen(onBack: () -> Unit) {
    var expenses by remember { mutableStateOf(DesktopDatabase.allExpenses()) }
    var showDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Payments") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showDialog = true }) { Icon(Icons.Filled.Add, "New payment") }
        }
    ) { pad ->
        if (expenses.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text("No payments yet — tap + to record one.", color = MaterialTheme.colorScheme.outline)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
                items(expenses, key = { it.id }) { e ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(e.voucherNo, fontWeight = FontWeight.Bold)
                            Text(
                                e.description + (if (e.payTo.isNotBlank()) "  •  ${e.payTo}" else "") +
                                    "  •  ${paymentDateFmt.format(Date(e.dateMillis))}  •  ${e.paymentMode}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Text("₹${"%.2f".format(e.amount)}", fontWeight = FontWeight.Bold)
                        IconButton(onClick = {
                            DesktopDatabase.deleteExpense(e.id)
                            expenses = DesktopDatabase.allExpenses()
                        }) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    if (showDialog) {
        var description by remember { mutableStateOf("") }
        var payTo by remember { mutableStateOf("") }
        var amount by remember { mutableStateOf("") }
        var mode by remember { mutableStateOf("Cash") }

        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("New payment") },
            text = {
                Column {
                    OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Description *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = payTo, onValueChange = { payTo = it }, label = { Text("Pay to") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
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
                    if (description.isNotBlank() && a != null && a > 0) {
                        DesktopDatabase.addExpense(description.trim(), a, mode.ifBlank { "Cash" }, payTo.trim())
                        expenses = DesktopDatabase.allExpenses()
                        showDialog = false
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text("Cancel") } }
        )
    }
}
