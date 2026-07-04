package com.billing.pos.ui.outstanding

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.auth.Session
import com.billing.pos.data.Bill
import com.billing.pos.data.Purchase
import com.billing.pos.data.Repository
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.util.Format
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class OutstandingViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)
    val bills: StateFlow<List<Bill>> =
        repo.allBills.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val purchases: StateFlow<List<Purchase>> =
        repo.allPurchases.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

private data class DocDue(val no: String, val date: Long, val balance: Double)
private data class PartyDue(val name: String, val total: Double, val docs: List<DocDue>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutstandingScreen(
    onBack: () -> Unit,
    vm: OutstandingViewModel = viewModel()
) {
    val bills by vm.bills.collectAsStateSafe()
    val purchases by vm.purchases.collectAsStateSafe()
    var payable by remember { mutableStateOf(false) }   // false = receivable (customers)
    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }

    val receivables = remember(bills) {
        bills.filter { it.balance > 0.001 }.groupBy { it.customerName }
            .map { (name, list) -> PartyDue(name, list.sumOf { it.balance }, list.map { DocDue(it.billNo, it.dateMillis, it.balance) }) }
            .sortedByDescending { it.total }
    }
    val payables = remember(purchases) {
        purchases.filter { it.balance > 0.001 }.groupBy { it.supplierName }
            .map { (name, list) -> PartyDue(name, list.sumOf { it.balance }, list.map { DocDue(it.purchaseNo, it.dateMillis, it.balance) }) }
            .sortedByDescending { it.total }
    }

    val list = if (payable) payables else receivables
    val grandTotal = list.sumOf { it.total }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Outstanding") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        if (!Session.canViewInvoice) {
            Column(Modifier.fillMaxSize().padding(pad), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("You don't have permission to view this report", color = MaterialTheme.colorScheme.outline)
            }
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(pad).padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !payable, onClick = { payable = false }, label = { Text("Receivable (Customers)") })
                FilterChip(selected = payable, onClick = { payable = true }, label = { Text("Payable (Suppliers)") })
            }

            Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (payable) "Total payable" else "Total receivable", fontWeight = FontWeight.Bold)
                    Text(Format.rupee(grandTotal), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                }
            }

            if (list.isEmpty()) {
                Text("Nothing outstanding.", color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 16.dp))
            } else {
                LazyColumn(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    items(list, key = { it.name }) { party ->
                        val isOpen = party.name in expanded
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { expanded = if (isOpen) expanded - party.name else expanded + party.name }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(party.name.ifBlank { "(no name)" }, fontWeight = FontWeight.Bold)
                                Text("${party.docs.size} document(s)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            }
                            Text(Format.rupee(party.total), fontWeight = FontWeight.Bold)
                            Icon(if (isOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null)
                        }
                        if (isOpen) {
                            party.docs.sortedBy { it.date }.forEach { d ->
                                Row(Modifier.fillMaxWidth().padding(start = 12.dp, bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("${d.no}  •  ${Format.date(d.date)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                                    Text(Format.rupee(d.balance), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        Divider()
                    }
                }
            }
        }
    }
}
