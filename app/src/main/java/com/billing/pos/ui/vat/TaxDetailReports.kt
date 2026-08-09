package com.billing.pos.ui.vat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.Bill
import com.billing.pos.data.CompanyInfo
import com.billing.pos.data.DownloadSaver
import com.billing.pos.data.GstTax
import com.billing.pos.data.Purchase
import com.billing.pos.data.Repository
import com.billing.pos.data.SalesReturn
import com.billing.pos.data.XlsxWriter
import com.billing.pos.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One transaction line, GST-split, for the detailed audit-style reports below. */
data class TaxDetailRow(
    val dateMillis: Long,
    val docNo: String,
    val party: String,
    val itemName: String,
    val rate: Double,
    val qty: Double,
    val taxable: Double,
    val cgst: Double,
    val sgst: Double,
    val igst: Double,
    val cess: Double,
    val total: Double
)

enum class TaxReportKind(val label: String) {
    SALES("Sales Tax"), PURCHASE("Purchase Tax"), CESS("Cess"), SALES_RETURN("Sales Return")
}

private fun round2(v: Double): Double = kotlin.math.round(v * 100.0) / 100.0

/** Same extraction as the GSTR-1 export: item prices are tax-inclusive, so the taxable value is
 *  extracted out of the line total rather than added on top of it. Returns (taxable, tax, cess). */
private fun extractTaxCess(inclusiveTotal: Double, taxPercent: Double, cessPercent: Double): Triple<Double, Double, Double> {
    val combinedRate = taxPercent + cessPercent
    val txval = if (combinedRate > 0.0) inclusiveTotal / (1.0 + combinedRate / 100.0) else inclusiveTotal
    return Triple(round2(txval), round2(txval * taxPercent / 100.0), round2(txval * cessPercent / 100.0))
}

private fun row(
    dateMillis: Long, docNo: String, party: String, itemName: String, rate: Double, qty: Double,
    lineTotal: Double, cessPercent: Double, interstate: Boolean
): TaxDetailRow {
    val (txval, tax, cess) = extractTaxCess(lineTotal, rate, cessPercent)
    val cgst = if (interstate) 0.0 else round2(tax / 2.0)
    val sgst = if (interstate) 0.0 else round2(tax / 2.0)
    val igst = if (interstate) tax else 0.0
    return TaxDetailRow(dateMillis, docNo, party, itemName, rate, qty, txval, cgst, sgst, igst, cess, round2(txval + tax + cess))
}

suspend fun salesTaxDetail(repo: Repository, company: CompanyInfo, bills: List<Bill>): List<TaxDetailRow> {
    val out = mutableListOf<TaxDetailRow>()
    bills.forEach { b ->
        val interstate = GstTax.isInterstate(company.gstin, b.customerState)
        repo.linesFor(b.id).forEach { l ->
            out += row(b.dateMillis, b.billNo, b.customerName, l.name, l.taxPercent, l.qty, l.lineTotal, l.cessPercent, interstate)
        }
    }
    return out.sortedBy { it.dateMillis }
}

suspend fun purchaseTaxDetail(repo: Repository, company: CompanyInfo, purchases: List<Purchase>): List<TaxDetailRow> {
    val out = mutableListOf<TaxDetailRow>()
    purchases.forEach { p ->
        val interstate = GstTax.isInterstate(company.gstin, p.supplierState)
        repo.purchaseLinesFor(p.id).forEach { l ->
            out += row(p.dateMillis, p.purchaseNo, p.supplierName, l.name, l.taxPercent, l.qty, l.lineTotal, l.cessPercent, interstate)
        }
    }
    return out.sortedBy { it.dateMillis }
}

/** Sales returns don't track a customer-state snapshot or a per-line cess rate, so every row
 *  here is intra-state (CGST+SGST) with cess = 0 — a known, smaller scope than sales/purchase. */
suspend fun salesReturnTaxDetail(repo: Repository, returns: List<SalesReturn>): List<TaxDetailRow> {
    val out = mutableListOf<TaxDetailRow>()
    returns.forEach { r ->
        repo.salesReturnLines(r.id).forEach { l ->
            out += row(r.dateMillis, r.returnNo, r.customerName, l.name, l.taxPercent, l.qty, l.lineTotal, 0.0, interstate = false)
        }
    }
    return out.sortedBy { it.dateMillis }
}

private fun buildDetailRows(companyName: String, gstin: String, kind: TaxReportKind, rows: List<TaxDetailRow>): List<List<XlsxWriter.Cell>> {
    val t = XlsxWriter::text; val n = XlsxWriter::num
    val out = ArrayList<List<XlsxWriter.Cell>>()
    out.add(XlsxWriter.row(t("${kind.label} Report")))
    out.add(XlsxWriter.row(t(companyName), t("GSTIN: $gstin")))
    out.add(XlsxWriter.row(t("")))
    out.add(XlsxWriter.row(t("Date"), t("Doc No"), t("Party"), t("Item"), t("Rate %"), t("Qty"), t("Taxable"), t("CGST"), t("SGST"), t("IGST"), t("Cess"), t("Total")))
    rows.forEach {
        out.add(
            XlsxWriter.row(
                t(Format.date(it.dateMillis)), t(it.docNo), t(it.party), t(it.itemName), n(it.rate), n(it.qty),
                n(it.taxable), n(it.cgst), n(it.sgst), n(it.igst), n(it.cess), n(it.total)
            )
        )
    }
    out.add(XlsxWriter.row(t("")))
    out.add(
        XlsxWriter.row(
            t("Total"), t(""), t(""), t(""), t(""), t(""),
            n(rows.sumOf { it.taxable }), n(rows.sumOf { it.cgst }), n(rows.sumOf { it.sgst }), n(rows.sumOf { it.igst }),
            n(rows.sumOf { it.cess }), n(rows.sumOf { it.total })
        )
    )
    return out
}

/**
 * Detailed, line-by-line GST reports for handing to an auditor — Sales Tax, Purchase Tax, Cess
 * and Sales Return, each filterable by rate (defaults to all rates). Only shown when GST mode is
 * on; the Cess tab additionally needs Cess enabled, since most accounts never use it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaxDetailReportsSection(vm: VatReportViewModel, gstEnabled: Boolean, cessEnabled: Boolean) {
    if (!gstEnabled) return
    val context = LocalContext.current
    val s = vm.summary ?: return
    val kinds = remember(cessEnabled) {
        if (cessEnabled) TaxReportKind.values().toList() else listOf(TaxReportKind.SALES, TaxReportKind.PURCHASE, TaxReportKind.SALES_RETURN)
    }
    var kind by remember { mutableStateOf(TaxReportKind.SALES) }
    var allRows by remember { mutableStateOf<List<TaxDetailRow>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(kind, s) {
        loading = true
        val repo = Repository(context)
        val company = AppPrefs(context).company
        allRows = withContext(Dispatchers.IO) {
            when (kind) {
                TaxReportKind.SALES -> salesTaxDetail(repo, company, s.bills)
                TaxReportKind.PURCHASE -> purchaseTaxDetail(repo, company, s.purchases)
                TaxReportKind.CESS -> (salesTaxDetail(repo, company, s.bills) + purchaseTaxDetail(repo, company, s.purchases)).filter { it.cess != 0.0 }
                TaxReportKind.SALES_RETURN -> salesReturnTaxDetail(repo, s.salesReturns)
            }
        }
        loading = false
    }

    var rateFilter by remember { mutableStateOf<Double?>(null) }   // null = All
    LaunchedEffect(kind) { rateFilter = null }   // reset the filter when switching report
    val rates = remember(allRows) { allRows.map { it.rate }.distinct().sortedBy { it } }
    val filtered = remember(allRows, rateFilter) { rateFilter?.let { r -> allRows.filter { it.rate == r } } ?: allRows }
    val scope = rememberCoroutineScope()

    Divider(Modifier.padding(vertical = 16.dp))
    Text("Detailed reports (for your auditor)", fontWeight = FontWeight.SemiBold)
    Text(
        "Line-by-line detail for the period above, split into CGST/SGST/IGST/Cess the same way the " +
            "invoices are. Filter by GST rate, or leave it on All.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(top = 4.dp)
    )

    Row(Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        kinds.forEach { k ->
            FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.label) })
        }
    }

    var rateMenu by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        OutlinedTextField(
            readOnly = true,
            value = rateFilter?.let { "${Format.money(it)}%" } ?: "All rates",
            onValueChange = {},
            label = { Text("GST rate") }, singleLine = true,
            trailingIcon = {
                IconButton(onClick = { rateMenu = true }) {
                    Icon(Icons.Filled.ArrowDropDown, "Pick rate")
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        DropdownMenu(expanded = rateMenu, onDismissRequest = { rateMenu = false }) {
            DropdownMenuItem(text = { Text("All rates") }, onClick = { rateFilter = null; rateMenu = false })
            rates.forEach { r ->
                DropdownMenuItem(text = { Text("${Format.money(r)}%") }, onClick = { rateFilter = r; rateMenu = false })
            }
        }
    }

    Text(
        if (loading) "Loading…" else "${filtered.size} line(s) — Taxable ${Format.rupee(filtered.sumOf { it.taxable })}, " +
            "Tax ${Format.rupee(filtered.sumOf { it.cgst + it.sgst + it.igst })}, Cess ${Format.rupee(filtered.sumOf { it.cess })}",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp)
    )

    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Date", Modifier.width(70.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        Text("Doc/Party", Modifier.width(110.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        Text("Item", Modifier.width(90.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        Text("Rate", Modifier.width(45.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        Text("Taxable", Modifier.width(70.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        Text("Tax", Modifier.width(70.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        Text("Cess", Modifier.width(60.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
    LazyColumn(Modifier.fillMaxWidth().height(220.dp)) {
        items(filtered) { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(Format.date(r.dateMillis), Modifier.width(70.dp), style = MaterialTheme.typography.labelSmall)
                Text(r.docNo.ifBlank { r.party }, Modifier.width(110.dp), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                Text(r.itemName, Modifier.width(90.dp), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                Text("${Format.money(r.rate)}%", Modifier.width(45.dp), style = MaterialTheme.typography.labelSmall)
                Text(Format.money(r.taxable), Modifier.width(70.dp), style = MaterialTheme.typography.labelSmall)
                Text(Format.money(r.cgst + r.sgst + r.igst), Modifier.width(70.dp), style = MaterialTheme.typography.labelSmall)
                Text(Format.money(r.cess), Modifier.width(60.dp), style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    OutlinedButton(
        onClick = {
            scope.launch {
                val company = AppPrefs(context).company
                val excelRows = buildDetailRows(company.name, company.gstin, kind, filtered)
                val dir = File(context.cacheDir, "shared").apply { mkdirs() }
                val fileName = "${kind.name.lowercase()}-detail.xlsx"
                val file = File(dir, fileName)
                withContext(Dispatchers.IO) { XlsxWriter.write(file, kind.label, excelRows) }
                val ok = withContext(Dispatchers.IO) {
                    DownloadSaver.save(context, file, fileName, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                }
                vm.message.value = if (ok) "Excel saved to Downloads: $fileName" else "Could not save Excel"
            }
        },
        enabled = filtered.isNotEmpty(),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
    ) { Text("Download ${kind.label} Excel") }
}
