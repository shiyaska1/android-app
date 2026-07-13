package com.billing.pos.ui.lab

import android.app.Application
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.LabBill
import com.billing.pos.data.LabResultValue
import com.billing.pos.data.Repository
import com.billing.pos.pdf.LabResultPdf
import com.billing.pos.ui.billing.collectAsStateSafe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** One editable result row on the result-entry screen. */
class ResultRow(
    val testId: Long, val testName: String,
    val evaluationId: Long, val evaluationName: String,
    val groupName: String, val unit: String, val normalValue: String,
    result: String, val isHeading: Boolean = false, val isPageBreak: Boolean = false
) {
    var result by mutableStateOf(result)
    val uid = next()
    companion object { private var c = 0L; fun next() = ++c }
}

class LabResultViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)
    var bill by mutableStateOf<LabBill?>(null); private set
    val rows: SnapshotStateList<ResultRow> = mutableStateListOf()
    var paymentMethod by mutableStateOf("Cash")
    var paidText by mutableStateOf("")
    private var loaded = false
    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }

    fun load(billId: Long) {
        if (loaded) return
        loaded = true
        viewModelScope.launch {
            val b = repo.labBillById(billId) ?: return@launch
            bill = b
            paymentMethod = b.paymentMethod.ifBlank { "Cash" }
            paidText = if (b.paidAmount != 0.0) com.billing.pos.util.Format.money(b.paidAmount) else com.billing.pos.util.Format.money(b.grandTotal)
            val saved = repo.labResultsFor(billId)
            rows.clear()
            repo.labBillTests(billId).forEach { bt ->
                val evals = repo.labEvaluationsFor(bt.testId)
                if (evals.isEmpty()) {
                    val prev = saved.firstOrNull { it.testId == bt.testId }
                    rows.add(ResultRow(bt.testId, bt.testName, 0, bt.testName, "", "", "", prev?.result ?: ""))
                } else evals.forEach { ev ->
                    val prev = saved.firstOrNull { it.testId == bt.testId && (it.evaluationId == ev.id || it.evaluationName.equals(ev.name, true)) }
                    rows.add(ResultRow(bt.testId, bt.testName, ev.id, ev.name, ev.groupName, ev.unit, ev.normalValue, prev?.result ?: "", ev.isHeading, ev.isPageBreak))
                }
            }
        }
    }

    private fun currentResults(billId: Long) = rows.mapIndexed { i, r ->
        LabResultValue(0, billId, r.testId, r.testName, r.evaluationId, r.evaluationName, r.groupName, r.unit, r.normalValue, r.result.trim(), i, r.isHeading, r.isPageBreak)
    }

    private fun LabBill.withResult() = copy(
        resultEntered = true, resultDateMillis = System.currentTimeMillis(),
        paymentMethod = this@LabResultViewModel.paymentMethod,
        paidAmount = this@LabResultViewModel.paidText.toDoubleOrNull() ?: grandTotal
    )

    fun save(onDone: () -> Unit) {
        val b = bill ?: return
        viewModelScope.launch {
            val updated = b.withResult()
            repo.saveLabResults(updated, currentResults(b.id))
            bill = updated
            message.value = "Results saved"
            onDone()
        }
    }

    /** Save first, then build and share the A4 report. */
    fun printReport(context: android.content.Context) {
        val b = bill ?: return
        viewModelScope.launch {
            val updated = b.withResult()
            repo.saveLabResults(updated, currentResults(b.id))
            bill = updated
            val company = AppPrefs(context).company
            val uri = LabResultPdf.make(context, company, updated, currentResults(b.id))
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching {
                context.startActivity(Intent.createChooser(intent, "Share lab report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { message.value = "Could not open share sheet" }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabResultScreen(billId: Long, onBack: () -> Unit, vm: LabResultViewModel = viewModel()) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val message by vm.message.collectAsStateSafe()
    LaunchedEffect(Unit) { vm.load(billId) }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Enter Result") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { vm.printReport(context) }) { Icon(Icons.Filled.PictureAsPdf, "Print A4") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            vm.bill?.let { b ->
                Card(Modifier.fillMaxWidth().padding(12.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${b.billNo}  •  ${b.patientName}", fontWeight = FontWeight.Bold)
                        Text(
                            listOf(b.age.ifBlank { "-" }, b.gender.ifBlank { "-" }, "Ref: ${b.referredBy.ifBlank { "-" }}").joinToString("  •  "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                        )
                        // Payment method + amount collected — recorded on the bill, NOT printed on the report.
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            listOf("Cash", "UPI", "Card", "Credit").forEach { m ->
                                androidx.compose.material3.FilterChip(selected = vm.paymentMethod == m, onClick = { vm.paymentMethod = m }, label = { Text(m) })
                            }
                        }
                        OutlinedTextField(
                            value = vm.paidText,
                            onValueChange = { vm.paidText = it.filter { ch -> ch.isDigit() || ch == '.' } },
                            label = { Text("Amount received (of ${com.billing.pos.util.Format.rupee(b.grandTotal)})") },
                            singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                        )
                    }
                }
            }
            if (vm.rows.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("This bill's tests have no evaluations", color = MaterialTheme.colorScheme.outline) }
            else LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
                itemsIndexed(vm.rows, key = { _, r -> r.uid }) { i, r ->
                    if (r.isPageBreak) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Divider(Modifier.weight(1f))
                            Text("  PAGE BREAK  ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Divider(Modifier.weight(1f))
                        }
                        return@itemsIndexed
                    }
                    val showTestHeader = i == 0 || vm.rows[i - 1].testName != r.testName
                    val showGroupHeader = r.groupName.isNotBlank() &&
                        (i == 0 || vm.rows[i - 1].testName != r.testName || vm.rows[i - 1].groupName != r.groupName)
                    if (showTestHeader) {
                        Text(r.testName, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
                        Divider()
                    }
                    if (showGroupHeader) Text(r.groupName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                    if (r.isHeading) {
                        Text(r.evaluationName, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 6.dp))
                    } else Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.evaluationName)
                            Text(
                                listOfNotNull(r.unit.ifBlank { null }, r.normalValue.ifBlank { null }?.let { "Normal $it" }).joinToString("  •  "),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline
                            )
                        }
                        OutlinedTextField(value = r.result, onValueChange = { r.result = it }, label = { Text("Result") }, singleLine = true, modifier = Modifier.width(130.dp))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.save { onBack() } }, modifier = Modifier.weight(1f)) { Text("Save") }
                Button(onClick = { vm.printReport(context) }, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.PictureAsPdf, null); Text("  Print A4") }
            }
        }
    }
}
