package com.billing.pos.ui.estimate

import android.app.Application
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.Estimate
import com.billing.pos.data.Repository
import com.billing.pos.data.XlsxWriter
import com.billing.pos.pdf.TablePdf
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.ui.common.rememberPdfDownloader
import com.billing.pos.ui.common.rememberXlsxDownloader
import com.billing.pos.util.Format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class EstimateListViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)
    val estimates: StateFlow<List<Estimate>> =
        repo.estimates.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }

    fun delete(e: Estimate) = viewModelScope.launch { repo.deleteEstimate(e) }
}

/** Saved estimates. Tapping one reopens it in the sales-entry screen in estimate mode. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EstimateListScreen(
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    onNew: () -> Unit,
    vm: EstimateListViewModel = viewModel()
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val estimates by vm.estimates.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }
    var deleteFor by remember { mutableStateOf<Estimate?>(null) }

    val downloadPdf = rememberPdfDownloader { msg -> vm.message.value = msg }
    val downloadXlsx = rememberXlsxDownloader { msg -> vm.message.value = msg }
    fun buildEstimatesPdf(): java.io.File {
        val cols = listOf(
            TablePdf.Col("Estimate No", 1.6f), TablePdf.Col("Date", 1.2f),
            TablePdf.Col("Customer", 2f), TablePdf.Col("Amount", 1.2f, right = true)
        )
        val data = estimates.map { listOf(it.estimateNo, Format.date(it.dateMillis), it.customerName, Format.money(it.grandTotal)) }
        return TablePdf.generate(context, AppPrefs(context).company, "Estimates", "Count: ${estimates.size}", cols, data)
    }
    fun buildEstimatesXlsx(): java.io.File {
        val rows = mutableListOf(XlsxWriter.row(XlsxWriter.text("Estimate No"), XlsxWriter.text("Date"), XlsxWriter.text("Customer"), XlsxWriter.text("Amount")))
        estimates.forEach { rows.add(XlsxWriter.row(XlsxWriter.text(it.estimateNo), XlsxWriter.text(Format.date(it.dateMillis)), XlsxWriter.text(it.customerName), XlsxWriter.num(it.grandTotal))) }
        val file = java.io.File(java.io.File(context.cacheDir, "shared").apply { mkdirs() }, "estimates.xlsx")
        XlsxWriter.write(file, "Estimates", rows)
        return file
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Estimates") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { downloadPdf { buildEstimatesPdf() } }) { Icon(Icons.Filled.PictureAsPdf, "Download PDF") }
                    IconButton(onClick = { downloadXlsx { buildEstimatesXlsx() } }) { Icon(Icons.Filled.GridOn, "Download Excel") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = onNew) { Icon(Icons.Filled.Add, "New estimate") } }
    ) { pad ->
        if (estimates.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text("No estimates yet", color = MaterialTheme.colorScheme.outline)
            }
        } else LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp)) {
            items(estimates, key = { it.id }) { e ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(e.id) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(e.estimateNo, fontWeight = FontWeight.Bold)
                        Text(
                            "${e.customerName} • ${Format.date(e.dateMillis)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    Text(Format.rupee(e.grandTotal), fontWeight = FontWeight.Bold)
                    IconButton(onClick = { deleteFor = e }) {
                        Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                    }
                }
                Divider()
            }
        }
    }

    deleteFor?.let { e ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Delete ${e.estimateNo}?") },
            text = { Text("An estimate never affected stock, so nothing is restored.") },
            confirmButton = { TextButton(onClick = { vm.delete(e); deleteFor = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Cancel") } }
        )
    }
}
