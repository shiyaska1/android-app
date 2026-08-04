package com.billing.pos.ui.vat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.Bill
import com.billing.pos.data.CompanyInfo
import com.billing.pos.data.Customer
import com.billing.pos.data.GstTax
import com.billing.pos.data.Purchase
import com.billing.pos.data.PurchaseReturn
import com.billing.pos.data.Repository
import com.billing.pos.data.SalesReturn
import com.billing.pos.data.Supplier
import com.billing.pos.data.XlsxWriter
import com.billing.pos.pdf.TablePdf
import com.billing.pos.ui.common.endOfDay
import com.billing.pos.ui.common.startOfDay
import com.billing.pos.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

/** One document (invoice / purchase / return), GST-split, for the register report below. */
data class RegisterRow(
    val dateMillis: Long,
    val docNo: String,
    val party: String,
    val gstin: String,
    val taxable: Double,
    val cgst: Double,
    val sgst: Double,
    val igst: Double,
    val discount: Double,
    val otherCharge: Double,
    val grandTotal: Double,
    /** Tax rates present on this document's lines — used for the rate filter. */
    val rates: Set<Double>
) {
    val isB2B: Boolean get() = gstin.isNotBlank()
}

enum class RegisterKind(val label: String) {
    SALES("Sales"), PURCHASE("Purchase"), SALES_RETURN("Sales Return"), PURCHASE_RETURN("Purchase Return")
}

/** Three months back from now — the default window for the GST register report. */
fun threeMonthsAgoMillis(): Long = Calendar.getInstance().apply { add(Calendar.MONTH, -3) }.timeInMillis

suspend fun salesRegister(repo: Repository, company: CompanyInfo, bills: List<Bill>): List<RegisterRow> =
    bills.map { b ->
        val split = GstTax.split(b.taxTotal, company.gstin, b.customerState)
        val rates = repo.linesFor(b.id).map { it.taxPercent }.toSet()
        RegisterRow(
            b.dateMillis, b.billNo, b.customerName, b.customerGstin,
            b.subTotal, split.cgst, split.sgst, split.igst, b.discount, b.additionalCharge, b.grandTotal, rates
        )
    }.sortedByDescending { it.dateMillis }

suspend fun purchaseRegister(repo: Repository, company: CompanyInfo, purchases: List<Purchase>): List<RegisterRow> =
    purchases.map { p ->
        val split = GstTax.split(p.taxTotal, company.gstin, p.supplierState)
        val rates = repo.purchaseLinesFor(p.id).map { it.taxPercent }.toSet()
        RegisterRow(
            p.dateMillis, p.purchaseNo, p.supplierName, p.supplierGstin,
            p.subTotal, split.cgst, split.sgst, split.igst, p.discount, p.additionalCharge, p.grandTotal, rates
        )
    }.sortedByDescending { it.dateMillis }

/** Sales returns don't snapshot the customer's GSTIN/state, so they're looked up from the
 *  customer master (blank if the customer was since deleted). */
suspend fun salesReturnRegister(repo: Repository, company: CompanyInfo, returns: List<SalesReturn>, customers: List<Customer>): List<RegisterRow> =
    returns.map { r ->
        val cust = customers.firstOrNull { it.id == r.customerId }
        val split = GstTax.split(r.taxTotal, company.gstin, cust?.state ?: "")
        val rates = repo.salesReturnLines(r.id).map { it.taxPercent }.toSet()
        RegisterRow(
            r.dateMillis, r.returnNo, r.customerName, cust?.gstin ?: "",
            r.subTotal, split.cgst, split.sgst, split.igst, r.discount, r.additionalCharge, r.grandTotal, rates
        )
    }.sortedByDescending { it.dateMillis }

/** Purchase returns don't snapshot the supplier's GSTIN/state, so they're looked up from the
 *  supplier master (blank if the supplier was since deleted). */
suspend fun purchaseReturnRegister(repo: Repository, company: CompanyInfo, returns: List<PurchaseReturn>, suppliers: List<Supplier>): List<RegisterRow> =
    returns.map { r ->
        val sup = suppliers.firstOrNull { it.id == r.supplierId }
        val split = GstTax.split(r.taxTotal, company.gstin, sup?.state ?: "")
        val rates = repo.purchaseReturnLines(r.id).map { it.taxPercent }.toSet()
        RegisterRow(
            r.dateMillis, r.returnNo, r.supplierName, sup?.gstin ?: "",
            r.subTotal, split.cgst, split.sgst, split.igst, r.discount, r.additionalCharge, r.grandTotal, rates
        )
    }.sortedByDescending { it.dateMillis }

private fun buildRegisterXlsx(companyName: String, gstin: String, kind: RegisterKind, rows: List<RegisterRow>): List<List<XlsxWriter.Cell>> {
    val t = XlsxWriter::text; val n = XlsxWriter::num
    val out = ArrayList<List<XlsxWriter.Cell>>()
    out.add(XlsxWriter.row(t("${kind.label} Register")))
    out.add(XlsxWriter.row(t(companyName), t("GSTIN: $gstin")))
    out.add(XlsxWriter.row(t("")))
    out.add(
        XlsxWriter.row(
            t("Date"), t("Doc No"), t("Party"), t("GSTIN"), t("Type"), t("Taxable"),
            t("CGST"), t("SGST"), t("IGST"), t("Discount"), t("Other charge"), t("Grand Total")
        )
    )
    rows.forEach {
        out.add(
            XlsxWriter.row(
                t(Format.date(it.dateMillis)), t(it.docNo), t(it.party), t(it.gstin), t(if (it.isB2B) "B2B" else "B2C"),
                n(it.taxable), n(it.cgst), n(it.sgst), n(it.igst), n(it.discount), n(it.otherCharge), n(it.grandTotal)
            )
        )
    }
    out.add(XlsxWriter.row(t("")))
    out.add(
        XlsxWriter.row(
            t("Total"), t(""), t(""), t(""), t(""),
            n(rows.sumOf { it.taxable }), n(rows.sumOf { it.cgst }), n(rows.sumOf { it.sgst }), n(rows.sumOf { it.igst }),
            n(rows.sumOf { it.discount }), n(rows.sumOf { it.otherCharge }), n(rows.sumOf { it.grandTotal })
        )
    )
    return out
}

/**
 * Document-wise GST register — one row per invoice/purchase/return, for Sales, Purchase, Sales
 * Return and Purchase Return, filterable by date range (defaults to the last 3 months), B2B/B2C,
 * GST rate, and a free-text search across doc no / party name. Only shown when GST mode is on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GstRegisterSection(vm: VatReportViewModel, gstEnabled: Boolean) {
    if (!gstEnabled) return
    val context = LocalContext.current
    val downloadXlsx = com.billing.pos.ui.common.rememberXlsxDownloader { vm.message.value = it }
    val downloadPdf = com.billing.pos.ui.common.rememberPdfDownloader { vm.message.value = it }

    var kind by remember { mutableStateOf(RegisterKind.SALES) }
    var from by remember { mutableStateOf(threeMonthsAgoMillis()) }
    var to by remember { mutableStateOf(System.currentTimeMillis()) }
    var allRows by remember { mutableStateOf<List<RegisterRow>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(kind, from, to) {
        loading = true
        val repo = Repository(context)
        val company = AppPrefs(context).company
        val lo = startOfDay(from); val hi = endOfDay(to)
        allRows = withContext(Dispatchers.IO) {
            when (kind) {
                RegisterKind.SALES ->
                    salesRegister(repo, company, repo.billsAll().filter { it.dateMillis in lo..hi })
                RegisterKind.PURCHASE ->
                    purchaseRegister(repo, company, repo.purchasesAll().filter { it.dateMillis in lo..hi })
                RegisterKind.SALES_RETURN ->
                    salesReturnRegister(repo, company, repo.salesReturnsAll().filter { it.dateMillis in lo..hi }, repo.customersAll())
                RegisterKind.PURCHASE_RETURN ->
                    purchaseReturnRegister(repo, company, repo.purchaseReturnsAll().filter { it.dateMillis in lo..hi }, repo.suppliersAll())
            }
        }
        loading = false
    }

    var b2bFilter by remember { mutableStateOf("All") }   // "All" | "B2B" | "B2C"
    var rateFilter by remember { mutableStateOf<Double?>(null) }   // null = All
    var search by remember { mutableStateOf("") }
    LaunchedEffect(kind) { b2bFilter = "All"; rateFilter = null; search = "" }

    val rates = remember(allRows) { allRows.flatMap { it.rates }.distinct().sortedBy { it } }
    val filtered = remember(allRows, b2bFilter, rateFilter, search) {
        allRows.filter {
            (b2bFilter == "All" || (b2bFilter == "B2B") == it.isB2B) &&
                (rateFilter == null || it.rates.contains(rateFilter)) &&
                (search.isBlank() || it.party.contains(search, ignoreCase = true) || it.docNo.contains(search, ignoreCase = true))
        }
    }

    Divider(Modifier.padding(vertical = 16.dp))
    Text("GST register (for your auditor)", fontWeight = FontWeight.SemiBold)
    Text(
        "One row per document, taxable value split into CGST/SGST/IGST the same way the " +
            "invoices are. Filter by date range, B2B/B2C, GST rate, or search by doc no / party.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(top = 4.dp)
    )

    Row(Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RegisterKind.values().forEach { k ->
            FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.label) })
        }
    }

    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { pickRegisterDate(context, from) { from = it } }, modifier = Modifier.weight(1f)) {
            Text("From: ${Format.date(from)}")
        }
        OutlinedButton(onClick = { pickRegisterDate(context, to) { to = it } }, modifier = Modifier.weight(1f)) {
            Text("To: ${Format.date(to)}")
        }
    }

    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        var b2bMenu by remember { mutableStateOf(false) }
        Box(Modifier.weight(1f)) {
            OutlinedTextField(
                readOnly = true, value = b2bFilter, onValueChange = {},
                label = { Text("Type") }, singleLine = true,
                trailingIcon = { IconButton(onClick = { b2bMenu = true }) { Icon(Icons.Filled.ArrowDropDown, "Pick type") } },
                modifier = Modifier.fillMaxWidth()
            )
            DropdownMenu(expanded = b2bMenu, onDismissRequest = { b2bMenu = false }) {
                listOf("All", "B2B", "B2C").forEach { opt ->
                    DropdownMenuItem(text = { Text(opt) }, onClick = { b2bFilter = opt; b2bMenu = false })
                }
            }
        }
        var rateMenu by remember { mutableStateOf(false) }
        Box(Modifier.weight(1f)) {
            OutlinedTextField(
                readOnly = true, value = rateFilter?.let { "${Format.money(it)}%" } ?: "All rates", onValueChange = {},
                label = { Text("GST rate") }, singleLine = true,
                trailingIcon = { IconButton(onClick = { rateMenu = true }) { Icon(Icons.Filled.ArrowDropDown, "Pick rate") } },
                modifier = Modifier.fillMaxWidth()
            )
            DropdownMenu(expanded = rateMenu, onDismissRequest = { rateMenu = false }) {
                DropdownMenuItem(text = { Text("All rates") }, onClick = { rateFilter = null; rateMenu = false })
                rates.forEach { r -> DropdownMenuItem(text = { Text("${Format.money(r)}%") }, onClick = { rateFilter = r; rateMenu = false }) }
            }
        }
    }

    OutlinedTextField(
        value = search, onValueChange = { search = it },
        label = { Text("Search doc no / party") }, singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
    )

    Text(
        if (loading) "Loading…" else "${filtered.size} document(s) — Taxable ${Format.rupee(filtered.sumOf { it.taxable })}, " +
            "CGST ${Format.rupee(filtered.sumOf { it.cgst })}, SGST ${Format.rupee(filtered.sumOf { it.sgst })}, " +
            "Grand Total ${Format.rupee(filtered.sumOf { it.grandTotal })}",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp)
    )

    Column(Modifier.fillMaxWidth().padding(top = 4.dp).horizontalScroll(rememberScrollState())) {
        Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RegisterHeaderCell("Date", 70.dp)
            RegisterHeaderCell("Doc/Party", 120.dp)
            RegisterHeaderCell("Taxable", 75.dp)
            RegisterHeaderCell("CGST", 65.dp)
            RegisterHeaderCell("SGST", 65.dp)
            RegisterHeaderCell("IGST", 65.dp)
            RegisterHeaderCell("Disc.", 65.dp)
            RegisterHeaderCell("Other", 65.dp)
            RegisterHeaderCell("Grand Total", 90.dp)
        }
        LazyColumn(Modifier.height(220.dp)) {
            items(filtered) { r ->
                Row(Modifier.padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RegisterCell(Format.date(r.dateMillis), 70.dp)
                    RegisterCell("${r.docNo}\n${r.party}", 120.dp, maxLines = 2)
                    RegisterCell(Format.money(r.taxable), 75.dp)
                    RegisterCell(Format.money(r.cgst), 65.dp)
                    RegisterCell(Format.money(r.sgst), 65.dp)
                    RegisterCell(Format.money(r.igst), 65.dp)
                    RegisterCell(Format.money(r.discount), 65.dp)
                    RegisterCell(Format.money(r.otherCharge), 65.dp)
                    RegisterCell(Format.money(r.grandTotal), 90.dp)
                }
            }
        }
        Divider(Modifier.padding(vertical = 4.dp))
        Row(Modifier.padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RegisterHeaderCell("Total", 190.dp)
            RegisterHeaderCell(Format.money(filtered.sumOf { it.taxable }), 75.dp)
            RegisterHeaderCell(Format.money(filtered.sumOf { it.cgst }), 65.dp)
            RegisterHeaderCell(Format.money(filtered.sumOf { it.sgst }), 65.dp)
            RegisterHeaderCell(Format.money(filtered.sumOf { it.igst }), 65.dp)
            RegisterHeaderCell(Format.money(filtered.sumOf { it.discount }), 65.dp)
            RegisterHeaderCell(Format.money(filtered.sumOf { it.otherCharge }), 65.dp)
            RegisterHeaderCell(Format.money(filtered.sumOf { it.grandTotal }), 90.dp)
        }
    }

    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = {
                downloadXlsx {
                    val company = AppPrefs(context).company
                    val f = java.io.File(java.io.File(context.cacheDir, "shared").apply { mkdirs() }, "${kind.name.lowercase()}-register.xlsx")
                    XlsxWriter.write(f, "${kind.label} Register", buildRegisterXlsx(company.name, company.gstin, kind, filtered))
                    f
                }
            },
            enabled = filtered.isNotEmpty(),
            modifier = Modifier.weight(1f)
        ) { Text("Excel") }

        OutlinedButton(
            onClick = {
                downloadPdf {
                    val company = AppPrefs(context).company
                    TablePdf.generate(
                        context, company, "${kind.label} Register",
                        "${Format.date(from)} to ${Format.date(to)}  •  ${filtered.size} document(s)",
                        listOf(
                            TablePdf.Col("Date", 1.1f), TablePdf.Col("Doc No", 1.3f), TablePdf.Col("Party", 1.6f),
                            TablePdf.Col("Type", 0.7f), TablePdf.Col("Taxable", 1f, right = true),
                            TablePdf.Col("CGST", 0.9f, right = true), TablePdf.Col("SGST", 0.9f, right = true),
                            TablePdf.Col("IGST", 0.9f, right = true), TablePdf.Col("Disc.", 0.9f, right = true),
                            TablePdf.Col("Other", 0.9f, right = true), TablePdf.Col("Grand Total", 1.1f, right = true)
                        ),
                        filtered.map {
                            listOf(
                                Format.date(it.dateMillis), it.docNo, it.party, if (it.isB2B) "B2B" else "B2C",
                                Format.money(it.taxable), Format.money(it.cgst), Format.money(it.sgst),
                                Format.money(it.igst), Format.money(it.discount), Format.money(it.otherCharge), Format.money(it.grandTotal)
                            )
                        },
                        listOf(
                            "Total Taxable" to Format.rupee(filtered.sumOf { it.taxable }),
                            "Total CGST" to Format.rupee(filtered.sumOf { it.cgst }),
                            "Total SGST" to Format.rupee(filtered.sumOf { it.sgst }),
                            "Total IGST" to Format.rupee(filtered.sumOf { it.igst }),
                            "Total Grand Total" to Format.rupee(filtered.sumOf { it.grandTotal })
                        )
                    )
                }
            },
            enabled = filtered.isNotEmpty(),
            modifier = Modifier.weight(1f)
        ) { Text("PDF") }
    }
}

@Composable
private fun RegisterHeaderCell(text: String, width: androidx.compose.ui.unit.Dp) {
    Text(text, Modifier.width(width), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 1)
}

@Composable
private fun RegisterCell(text: String, width: androidx.compose.ui.unit.Dp, maxLines: Int = 1) {
    Text(text, Modifier.width(width), style = MaterialTheme.typography.labelSmall, maxLines = maxLines)
}

private fun pickRegisterDate(context: android.content.Context, current: Long, onPicked: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = current }
    android.app.DatePickerDialog(
        context,
        { _, y, m, d -> c.set(Calendar.YEAR, y); c.set(Calendar.MONTH, m); c.set(Calendar.DAY_OF_MONTH, d); onPicked(c.timeInMillis) },
        c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
    ).show()
}
