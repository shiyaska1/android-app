package com.billing.pos.ui.reports

import android.app.Application
import android.content.Intent
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.Bill
import com.billing.pos.data.Repository
import com.billing.pos.pdf.InvoicePdf
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

enum class DateRange(val label: String) { TODAY("Today"), MONTH("This Month"), ALL("All") }

private fun rangeBounds(range: DateRange): Pair<Long, Long> {
    val now = System.currentTimeMillis()
    val cal = Calendar.getInstance()
    return when (range) {
        DateRange.TODAY -> {
            cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            cal.timeInMillis to now
        }
        DateRange.MONTH -> {
            cal.set(Calendar.DAY_OF_MONTH, 1)
            cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            cal.timeInMillis to now
        }
        DateRange.ALL -> 0L to Long.MAX_VALUE
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ReportsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)
    val range = MutableStateFlow(DateRange.TODAY)

    val bills: StateFlow<List<Bill>> = range
        .flatMapLatest { r -> val (f, t) = rangeBounds(r); repo.billsBetween(f, t) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setRange(r: DateRange) { range.value = r }

    fun sharePdf(context: android.content.Context, bill: Bill, shopName: String) {
        viewModelScope.launch {
            val lines = withContext(Dispatchers.IO) { repo.linesFor(bill.id) }
            val uri = InvoicePdf.generate(context, shopName, bill, lines)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(intent, "Share invoice")
                    .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(
    onBack: () -> Unit,
    vm: ReportsViewModel = viewModel()
) {
    val context = LocalContext.current
    val bills by vm.bills.collectAsStateSafe()
    val range by vm.range.collectAsStateSafe()

    val total = bills.sumOf { it.grandTotal }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sales Report") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(12.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DateRange.values().forEach { r ->
                    FilterChip(
                        selected = range == r,
                        onClick = { vm.setRange(r) },
                        label = { Text(r.label) }
                    )
                }
            }

            Card(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Total Sales", style = MaterialTheme.typography.labelMedium)
                        Text(Format.rupee(total), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    }
                    Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                        Text("Bills", style = MaterialTheme.typography.labelMedium)
                        Text("${bills.size}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (bills.isEmpty()) {
                Text("No bills in this period.", color = MaterialTheme.colorScheme.outline)
            } else {
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(bills, key = { it.id }) { bill ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { vm.sharePdf(context, bill, "My Shop") }
                                .padding(vertical = 10.dp)
                        ) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(bill.billNo, fontWeight = FontWeight.Bold)
                                Text(Format.rupee(bill.grandTotal), fontWeight = FontWeight.Bold)
                            }
                            Text(
                                "${bill.customerName} • ${bill.paymentMethod} • ${Format.dateTime(bill.dateMillis)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                            Text("Tap to share PDF", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Divider(Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }
        }
    }
}
