package com.billing.pos.ui.report

import android.content.Intent
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.XlsxWriter
import com.billing.pos.pdf.TablePdf
import com.billing.pos.report.Posting
import com.billing.pos.ui.common.rememberPdfDownloader
import com.billing.pos.ui.common.rememberXlsxDownloader
import com.billing.pos.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Every voucher (sales, purchases, receipts, payments, journals, contra) for a date range,
 * in chronological order — the general Day Book. Built straight off [AccountingEngine.build]'s
 * flat posting list, so it always matches the ledger/trial balance/balance sheet exactly. */
fun dayBookOf(p: List<Posting>, from: Long, to: Long): List<Posting> =
    p.filter { it.date in from..to }.sortedWith(compareBy({ it.date }, { it.vch }))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayBookScreen(onBack: () -> Unit, vm: FinancialReportViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val postings by vm.postings.collectAsState()

    var from by remember { mutableStateOf(today()) }
    var to by remember { mutableStateOf(today()) }
    var rows by remember { mutableStateOf<List<Posting>?>(null) }
    fun runView() { rows = dayBookOf(postings, com.billing.pos.ui.common.startOfDay(from), com.billing.pos.ui.common.endOfDay(to)) }

    val list = rows.orEmpty()
    val totalDr = list.sumOf { it.debit }
    val totalCr = list.sumOf { it.credit }
    val crit = "From: ${Format.date(from)}   To: ${Format.date(to)}"

    fun buildPdf(): File {
        val cols = listOf(
            TablePdf.Col("Date", 1f), TablePdf.Col("Vch No", 1.1f), TablePdf.Col("Account", 1.6f),
            TablePdf.Col("Particulars", 1.8f), TablePdf.Col("Debit", 1f, right = true), TablePdf.Col("Credit", 1f, right = true)
        )
        val data = list.map {
            listOf(
                Format.date(it.date), it.vch, it.head, it.particulars,
                if (it.debit != 0.0) Format.money(it.debit) else "",
                if (it.credit != 0.0) Format.money(it.credit) else ""
            )
        }
        val footer = listOf("Total Debit" to Format.money(totalDr), "Total Credit" to Format.money(totalCr))
        return TablePdf.generate(context, AppPrefs(context).company, "Day Book", crit, cols, data, footer)
    }
    val downloadPdf = rememberPdfDownloader { msg -> scope.launch { snackbar.showSnackbar(msg) } }
    val downloadXlsx = rememberXlsxDownloader { msg -> scope.launch { snackbar.showSnackbar(msg) } }
    fun buildXlsx(): File {
        val out = mutableListOf(
            XlsxWriter.row(
                XlsxWriter.text("Date"), XlsxWriter.text("Vch No"), XlsxWriter.text("Account"),
                XlsxWriter.text("Particulars"), XlsxWriter.text("Debit"), XlsxWriter.text("Credit")
            )
        )
        list.forEach {
            out.add(
                XlsxWriter.row(
                    XlsxWriter.text(Format.date(it.date)), XlsxWriter.text(it.vch), XlsxWriter.text(it.head),
                    XlsxWriter.text(it.particulars), XlsxWriter.num(it.debit), XlsxWriter.num(it.credit)
                )
            )
        }
        val file = File(File(context.cacheDir, "shared").apply { mkdirs() }, "daybook.xlsx")
        XlsxWriter.write(file, "Day Book", out)
        return file
    }
    fun shareDayBook() {
        scope.launch {
            val file = withContext(Dispatchers.IO) { buildPdf() }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching {
                context.startActivity(Intent.createChooser(intent, "Share Day Book").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { snackbar.showSnackbar("Could not share") }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Day Book") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                actions = {
                    IconButton(onClick = { downloadPdf { buildPdf() } }) { Icon(Icons.Filled.Download, contentDescription = "Download PDF") }
                    IconButton(onClick = { downloadXlsx { buildXlsx() } }) { Icon(Icons.Filled.GridOn, contentDescription = "Download Excel") }
                    IconButton(onClick = { shareDayBook() }) { Icon(Icons.Filled.Share, contentDescription = "Share") }
                }
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DateBtn("From", from, { from = it }, Modifier.weight(1f))
                DateBtn("To", to, { to = it }, Modifier.weight(1f))
            }
            Button(onClick = { runView() }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("View") }

            rows?.let {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text("Date", Modifier.weight(0.9f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    Text("Account / Particulars", Modifier.weight(2f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    Text("Debit", Modifier.weight(1f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    Text("Credit", Modifier.weight(1f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                }
                HorizontalDivider()
                if (list.isEmpty()) {
                    Text("No transactions in this period.", color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 12.dp))
                } else {
                    LazyColumn(Modifier.weight(1f)) {
                        items(list) { row ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Column(Modifier.weight(0.9f)) {
                                    Text(Format.date(row.date), style = MaterialTheme.typography.bodySmall)
                                    if (row.vch.isNotBlank()) Text(row.vch, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                }
                                Column(Modifier.weight(2f)) {
                                    Text(row.head, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                    if (row.particulars.isNotBlank()) Text(row.particulars, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                }
                                Text(if (row.debit != 0.0) Format.money(row.debit) else "", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                Text(if (row.credit != 0.0) Format.money(row.credit) else "", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            }
                            HorizontalDivider()
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Text("TOTAL", Modifier.weight(2.9f), fontWeight = FontWeight.Bold)
                        Text(Format.money(totalDr), Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        Text(Format.money(totalCr), Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
