package com.billing.pos.ui.vat

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.auth.Session
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.Bill
import com.billing.pos.data.DownloadSaver
import com.billing.pos.data.Purchase
import com.billing.pos.data.Repository
import com.billing.pos.data.XlsxWriter
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

data class VatRateRow(val rate: Double, val taxable: Double, val tax: Double)
data class VatSummary(
    val from: Long,
    val to: Long,
    val salesTaxable: Double,
    val salesTax: Double,
    val purchaseTaxable: Double,
    val purchaseTax: Double,
    val salesRates: List<VatRateRow>,
    val purchaseRates: List<VatRateRow>,
    val bills: List<Bill>,
    val purchases: List<Purchase>,
    val salesReturns: List<com.billing.pos.data.SalesReturn> = emptyList()
) {
    val netPayable: Double get() = salesTax - purchaseTax
}

private fun startOfDay(m: Long): Long {
    val c = Calendar.getInstance().apply {
        timeInMillis = m
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    return c.timeInMillis
}
private fun endOfDay(m: Long): Long = startOfDay(m) + 24L * 60 * 60 * 1000 - 1
private fun firstOfMonth(now: Long): Long {
    val c = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    return c.timeInMillis
}

class VatReportViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)

    var from by mutableStateOf(firstOfMonth(System.currentTimeMillis())); private set
    var to by mutableStateOf(System.currentTimeMillis()); private set
    var summary by mutableStateOf<VatSummary?>(null); private set
    var busy by mutableStateOf(false); private set
    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }

    fun updateFrom(m: Long) { from = m; load() }
    fun updateTo(m: Long) { to = m; load() }

    init { load() }

    fun load() {
        viewModelScope.launch {
            val lo = startOfDay(from); val hi = endOfDay(to)
            val bills = withContext(Dispatchers.IO) { repo.billsAll() }.filter { it.dateMillis in lo..hi }.sortedBy { it.dateMillis }
            val purchases = withContext(Dispatchers.IO) { repo.purchasesAll() }.filter { it.dateMillis in lo..hi }.sortedBy { it.dateMillis }
            val saleLines = withContext(Dispatchers.IO) { repo.saleTaxLines() }.filter { it.dateMillis in lo..hi }
            val purLines = withContext(Dispatchers.IO) { repo.purchaseTaxLines() }.filter { it.dateMillis in lo..hi }
            val salesReturns = withContext(Dispatchers.IO) { repo.salesReturnsAll() }.filter { it.dateMillis in lo..hi }.sortedBy { it.dateMillis }

            fun rates(list: List<com.billing.pos.data.TaxLineInfo>) =
                list.groupBy { it.rate }.map { (r, ls) -> VatRateRow(r, ls.sumOf { it.taxable }, ls.sumOf { it.tax }) }.sortedBy { it.rate }

            summary = VatSummary(
                from, to,
                saleLines.sumOf { it.taxable }, saleLines.sumOf { it.tax },
                purLines.sumOf { it.taxable }, purLines.sumOf { it.tax },
                rates(saleLines), rates(purLines), bills, purchases, salesReturns
            )
        }
    }

    fun exportExcel(context: Context, onSaved: (String) -> Unit) {
        val s = summary ?: return
        busy = true
        viewModelScope.launch {
            val company = AppPrefs(context).company
            val rows = buildExcelRows(company.name, company.gstin, s)
            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            val file = File(dir, "vat-report.xlsx")
            withContext(Dispatchers.IO) { XlsxWriter.write(file, "VAT Report", rows) }
            val ok = withContext(Dispatchers.IO) { DownloadSaver.save(context, file, "vat-report.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet") }
            busy = false
            onSaved(if (ok) "Excel saved to Downloads: vat-report.xlsx" else "Could not save Excel")
        }
    }

    fun exportJson(context: Context, onSaved: (String) -> Unit) {
        val s = summary ?: return
        busy = true
        viewModelScope.launch {
            val company = AppPrefs(context).company
            val json = buildJson(company.name, company.gstin, s)
            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            val file = File(dir, "vat-report.json")
            withContext(Dispatchers.IO) { file.writeText(json) }
            val ok = withContext(Dispatchers.IO) { DownloadSaver.save(context, file, "vat-report.json", "application/json") }
            busy = false
            onSaved(if (ok) "JSON saved to Downloads: vat-report.json" else "Could not save JSON")
        }
    }

    /**
     * GSTR-1 JSON in the section layout the GST portal's offline tool / API expects
     * (b2b, b2cs, cdnr, hsn). Built entirely from sales/sales-returns in the selected period —
     * GSTR-1 reports only outward supplies, purchases play no part in it. Each invoice/return
     * is classified IGST (interstate) or CGST+SGST (intra-state) from the customer's saved
     * state vs. the company's own GSTIN — an invoice with no customer state on file defaults
     * to intra-state.
     */
    fun exportGstr1Json(context: Context, onSaved: (String) -> Unit) {
        val s = summary ?: return
        val prefs = AppPrefs(context)
        if (prefs.compositionScheme) {
            onSaved("Composition dealers don't file GSTR-1 — file CMP-08 instead. Turn off Composition scheme in Settings if this isn't right.")
            return
        }
        busy = true
        viewModelScope.launch {
            val company = prefs.company
            val json = withContext(Dispatchers.IO) { buildGstr1Json(company, s) }
            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            val file = File(dir, "gstr1.json")
            withContext(Dispatchers.IO) { file.writeText(json) }
            val ok = withContext(Dispatchers.IO) { DownloadSaver.save(context, file, "gstr1.json", "application/json") }
            busy = false
            onSaved(if (ok) "GSTR-1 JSON saved to Downloads: gstr1.json" else "Could not save GSTR-1 JSON")
        }
    }

    /**
     * Tally-importable XML (Gateway of Tally > Import Data) with one Sales voucher per bill
     * and one Purchase voucher per purchase in the selected period. Ledger names are generic
     * ("Sales Account", "CGST", "Discount Allowed", the customer/supplier's own name as the
     * party ledger, ...) — create matching ledgers in Tally first, or use its "Alter Master"
     * prompt on import to map them.
     */
    fun exportTallyXml(context: Context, onSaved: (String) -> Unit) {
        val s = summary ?: return
        busy = true
        viewModelScope.launch {
            val prefs = AppPrefs(context)
            val company = prefs.company
            val xml = withContext(Dispatchers.IO) { buildTallyXml(prefs.gstEnabled, prefs.compositionScheme, company.gstin, s) }
            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            val file = File(dir, "tally-vouchers.xml")
            withContext(Dispatchers.IO) { file.writeText(xml) }
            val ok = withContext(Dispatchers.IO) { DownloadSaver.save(context, file, "tally-vouchers.xml", "text/xml") }
            busy = false
            onSaved(if (ok) "Tally XML saved to Downloads: tally-vouchers.xml" else "Could not save Tally XML")
        }
    }

    private fun buildExcelRows(companyName: String, gstin: String, s: VatSummary): List<List<XlsxWriter.Cell>> {
        val t = XlsxWriter::text; val n = XlsxWriter::num
        val rows = ArrayList<List<XlsxWriter.Cell>>()
        rows.add(XlsxWriter.row(t("VAT / GST Report")))
        rows.add(XlsxWriter.row(t(companyName), t("GSTIN: $gstin")))
        rows.add(XlsxWriter.row(t("Period"), t("${Format.date(s.from)} to ${Format.date(s.to)}")))
        rows.add(XlsxWriter.row(t("")))
        rows.add(XlsxWriter.row(t("OUTPUT VAT — SALES")))
        rows.add(XlsxWriter.row(t("Bill No"), t("Date"), t("Customer"), t("GSTIN"), t("Taxable"), t("Tax"), t("Total")))
        s.bills.forEach {
            rows.add(XlsxWriter.row(t(it.billNo), t(Format.date(it.dateMillis)), t(it.customerName), t(it.customerGstin), n(it.subTotal), n(it.taxTotal), n(it.grandTotal)))
        }
        rows.add(XlsxWriter.row(t("Total"), t(""), t(""), t(""), n(s.salesTaxable), n(s.salesTax)))
        rows.add(XlsxWriter.row(t("")))
        rows.add(XlsxWriter.row(t("Sales tax by rate")))
        rows.add(XlsxWriter.row(t("Rate %"), t("Taxable"), t("Tax")))
        s.salesRates.forEach { rows.add(XlsxWriter.row(n(it.rate), n(it.taxable), n(it.tax))) }
        rows.add(XlsxWriter.row(t("")))
        rows.add(XlsxWriter.row(t("INPUT VAT — PURCHASES")))
        rows.add(XlsxWriter.row(t("Purchase No"), t("Date"), t("Supplier"), t("GSTIN"), t("Taxable"), t("Tax"), t("Total")))
        s.purchases.forEach {
            rows.add(XlsxWriter.row(t(it.purchaseNo), t(Format.date(it.dateMillis)), t(it.supplierName), t(it.supplierGstin), n(it.subTotal), n(it.taxTotal), n(it.grandTotal)))
        }
        rows.add(XlsxWriter.row(t("Total"), t(""), t(""), t(""), n(s.purchaseTaxable), n(s.purchaseTax)))
        rows.add(XlsxWriter.row(t("")))
        rows.add(XlsxWriter.row(t("Purchase tax by rate")))
        rows.add(XlsxWriter.row(t("Rate %"), t("Taxable"), t("Tax")))
        s.purchaseRates.forEach { rows.add(XlsxWriter.row(n(it.rate), n(it.taxable), n(it.tax))) }
        rows.add(XlsxWriter.row(t("")))
        rows.add(XlsxWriter.row(t("SUMMARY")))
        rows.add(XlsxWriter.row(t("Output tax (sales)"), n(s.salesTax)))
        rows.add(XlsxWriter.row(t("Input tax (purchases)"), n(s.purchaseTax)))
        rows.add(XlsxWriter.row(t("Net VAT payable"), n(s.netPayable)))
        return rows
    }

    private fun buildJson(companyName: String, gstin: String, s: VatSummary): String {
        val root = JSONObject()
        root.put("gstin", gstin)
        root.put("companyName", companyName)
        root.put("period", JSONObject().put("from", Format.date(s.from)).put("to", Format.date(s.to)))
        root.put("sales", JSONArray().apply {
            s.bills.forEach {
                put(JSONObject().put("billNo", it.billNo).put("date", Format.date(it.dateMillis))
                    .put("customer", it.customerName).put("gstin", it.customerGstin)
                    .put("taxable", it.subTotal).put("tax", it.taxTotal).put("total", it.grandTotal))
            }
        })
        root.put("purchases", JSONArray().apply {
            s.purchases.forEach {
                put(JSONObject().put("purchaseNo", it.purchaseNo).put("date", Format.date(it.dateMillis))
                    .put("supplier", it.supplierName).put("gstin", it.supplierGstin)
                    .put("taxable", it.subTotal).put("tax", it.taxTotal).put("total", it.grandTotal))
            }
        })
        fun rateArr(rows: List<VatRateRow>) = JSONArray().apply {
            rows.forEach { put(JSONObject().put("rate", it.rate).put("taxable", it.taxable).put("tax", it.tax)) }
        }
        root.put("summary", JSONObject()
            .put("outputTax", s.salesTax).put("inputTax", s.purchaseTax).put("netPayable", s.netPayable)
            .put("salesByRate", rateArr(s.salesRates)).put("purchaseByRate", rateArr(s.purchaseRates)))
        return root.toString(2)
    }

    private class HsnAgg(val hsn: String, val rate: Double, var desc: String, var uqc: String) {
        var qty = 0.0; var txval = 0.0; var camt = 0.0; var samt = 0.0; var iamt = 0.0
    }

    private suspend fun buildGstr1Json(company: com.billing.pos.data.CompanyInfo, s: VatSummary): String {
        val itemsByName = repo.itemsAll().associateBy { it.name.trim().lowercase() }
        val customersById = repo.customersAll().associateBy { it.id }
        val ownStateCode = com.billing.pos.data.IndianStates.codeFromGstin(company.gstin)
        val hsnAgg = LinkedHashMap<String, HsnAgg>()   // key = "hsn|rate"

        fun posFor(customerState: String) =
            com.billing.pos.data.IndianStates.codeForState(customerState).ifBlank { ownStateCode }

        fun trackHsn(name: String, unit: String, rate: Double, qty: Double, txval: Double, interstate: Boolean) {
            val hsn = itemsByName[name.trim().lowercase()]?.hsn.orEmpty()
            val agg = hsnAgg.getOrPut("$hsn|$rate") { HsnAgg(hsn, rate, name, uqcFor(unit)) }
            agg.qty += qty; agg.txval += txval
            if (interstate) agg.iamt += txval * rate / 100.0 else { agg.camt += txval * rate / 200.0; agg.samt += txval * rate / 200.0 }
        }

        val b2b = JSONArray()
        s.bills.filter { it.customerGstin.isNotBlank() }.groupBy { it.customerGstin }.forEach { (ctin, bills) ->
            val invArr = JSONArray()
            bills.forEach { bill ->
                val interstate = com.billing.pos.data.GstTax.isInterstate(company.gstin, bill.customerState)
                val lines = repo.linesFor(bill.id)
                val itmsArr = JSONArray()
                var num = 1
                lines.groupBy { it.taxPercent }.forEach { (rate, ls) ->
                    val txval = round2(ls.sumOf { it.lineTotal })
                    val itmDet = JSONObject().put("txval", txval).put("rt", rate)
                    if (interstate) itmDet.put("iamt", round2(txval * rate / 100.0)).put("camt", 0.0).put("samt", 0.0)
                    else { val c = round2(txval * rate / 200.0); itmDet.put("iamt", 0.0).put("camt", c).put("samt", c) }
                    itmDet.put("csamt", 0.0)
                    itmsArr.put(JSONObject().put("num", num++).put("itm_det", itmDet))
                    ls.forEach { trackHsn(it.name, it.unit, rate, it.qty, it.lineTotal, interstate) }
                }
                invArr.put(
                    JSONObject().put("inum", bill.billNo).put("idt", Format.date(bill.dateMillis))
                        .put("val", round2(bill.grandTotal)).put("pos", posFor(bill.customerState)).put("rchrg", "N").put("inv_typ", "R")
                        .put("itms", itmsArr)
                )
            }
            b2b.put(JSONObject().put("ctin", ctin).put("inv", invArr))
        }

        data class RateAgg(var txval: Double = 0.0, var camt: Double = 0.0, var samt: Double = 0.0, var iamt: Double = 0.0)
        val b2csAgg = LinkedHashMap<String, RateAgg>()   // key = "rate|pos|interstate"
        s.bills.filter { it.customerGstin.isBlank() }.forEach { bill ->
            val interstate = com.billing.pos.data.GstTax.isInterstate(company.gstin, bill.customerState)
            val pos = posFor(bill.customerState)
            repo.linesFor(bill.id).groupBy { it.taxPercent }.forEach { (rate, ls) ->
                val txval = round2(ls.sumOf { it.lineTotal })
                val agg = b2csAgg.getOrPut("$rate|$pos|$interstate") { RateAgg() }
                agg.txval += txval
                if (interstate) agg.iamt += txval * rate / 100.0 else { val c = txval * rate / 200.0; agg.camt += c; agg.samt += c }
                ls.forEach { trackHsn(it.name, it.unit, rate, it.qty, it.lineTotal, interstate) }
            }
        }
        val b2cs = JSONArray().apply {
            b2csAgg.forEach { (key, agg) ->
                val (rateStr, pos, interstateStr) = key.split("|")
                put(
                    JSONObject().put("sply_ty", if (interstateStr.toBoolean()) "INTER" else "INTRA").put("pos", pos).put("typ", "OE")
                        .put("txval", round2(agg.txval)).put("rt", rateStr.toDouble())
                        .put("iamt", round2(agg.iamt)).put("camt", round2(agg.camt)).put("samt", round2(agg.samt)).put("csamt", 0.0)
                )
            }
        }

        // CDNR: credit notes against B2B invoices (sales returns for a customer with a GSTIN on file).
        val cdnr = JSONArray()
        s.salesReturns.mapNotNull { r -> customersById[r.customerId]?.takeIf { it.gstin.isNotBlank() }?.let { r to it } }
            .groupBy { (_, c) -> c.gstin }.forEach { (ctin, pairs) ->
                val notesArr = JSONArray()
                pairs.forEach { (r, cust) ->
                    val interstate = com.billing.pos.data.GstTax.isInterstate(company.gstin, cust.state)
                    val lines = repo.salesReturnLines(r.id)
                    val itmsArr = JSONArray()
                    var num = 1
                    lines.groupBy { it.taxPercent }.forEach { (rate, ls) ->
                        val txval = round2(ls.sumOf { it.lineTotal })
                        val itmDet = JSONObject().put("txval", txval).put("rt", rate)
                        if (interstate) itmDet.put("iamt", round2(txval * rate / 100.0)).put("camt", 0.0).put("samt", 0.0)
                        else { val c = round2(txval * rate / 200.0); itmDet.put("iamt", 0.0).put("camt", c).put("samt", c) }
                        itmDet.put("csamt", 0.0)
                        itmsArr.put(JSONObject().put("num", num++).put("itm_det", itmDet))
                    }
                    notesArr.put(
                        JSONObject().put("nt_num", r.returnNo).put("nt_dt", Format.date(r.dateMillis))
                            .put("ntty", "C").put("val", round2(r.grandTotal)).put("pos", posFor(cust.state))
                            .put("rchrg", "N").put("itms", itmsArr)
                    )
                }
                cdnr.put(JSONObject().put("ctin", ctin).put("nt", notesArr))
            }

        val hsnArr = JSONArray()
        var hnum = 1
        hsnAgg.values.forEach { a ->
            hsnArr.put(
                JSONObject().put("num", hnum++).put("hsn_sc", a.hsn).put("desc", a.desc).put("uqc", a.uqc)
                    .put("qty", round2(a.qty)).put("val", round2(a.txval + a.camt + a.samt + a.iamt)).put("txval", round2(a.txval))
                    .put("iamt", round2(a.iamt)).put("camt", round2(a.camt)).put("samt", round2(a.samt)).put("csamt", 0.0)
            )
        }

        val fp = java.text.SimpleDateFormat("MMyyyy", java.util.Locale.US).format(java.util.Date(s.from))
        return JSONObject()
            .put("gstin", company.gstin)
            .put("fp", fp)
            .put("version", "GST3.0.4")
            .put("b2b", b2b)
            .put("b2cs", b2cs)
            .put("cdnr", cdnr)
            .put("hsn", JSONObject().put("data", hsnArr))
            .toString(2)
    }

    private fun uqcFor(unit: String): String = when (unit.trim().uppercase()) {
        "PCS", "PC", "PIECE", "PIECES" -> "PCS"
        "BOX" -> "BOX"
        "KG", "KGS" -> "KGS"
        "GM", "GMS", "G" -> "GMS"
        "LTR", "LITRE", "LITRES", "L" -> "LTR"
        "MTR", "METER", "METRE", "M" -> "MTR"
        "DOZ", "DOZEN" -> "DOZ"
        "SET" -> "SET"
        "PAIR", "PRS" -> "PRS"
        "BTL", "BOTTLE" -> "BTL"
        "BAG" -> "BAG"
        "CTN", "CARTON" -> "CTN"
        "ROL", "ROLL" -> "ROL"
        "NOS", "NO", "NUMBER", "NUMBERS", "UNIT", "UNITS" -> "NOS"
        else -> "OTH"
    }

    private fun round2(v: Double): Double = kotlin.math.round(v * 100.0) / 100.0

    private fun xmlEscape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private fun buildTallyXml(gst: Boolean, composition: Boolean, companyGstin: String, s: VatSummary): String {
        val tallyDate = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
        val sb = StringBuilder()
        sb.append("<ENVELOPE>\n<HEADER><TALLYREQUEST>Import Data</TALLYREQUEST></HEADER>\n")
        sb.append("<BODY><IMPORTDATA><REQUESTDESC><REPORTNAME>Vouchers</REPORTNAME></REQUESTDESC><REQUESTDATA>\n")

        fun ledger(name: String, debit: Boolean, amount: Double) {
            if (amount == 0.0) return
            sb.append("<ALLLEDGERENTRIES.LIST>\n")
            sb.append("<LEDGERNAME>${xmlEscape(name)}</LEDGERNAME>\n")
            sb.append("<ISDEEMEDPOSITIVE>${if (debit) "Yes" else "No"}</ISDEEMEDPOSITIVE>\n")
            sb.append("<AMOUNT>${String.format(java.util.Locale.US, "%.2f", if (debit) amount else -amount)}</AMOUNT>\n")
            sb.append("</ALLLEDGERENTRIES.LIST>\n")
        }

        s.bills.forEach { b ->
            sb.append("<TALLYMESSAGE xmlns:UDF=\"TallyUDF\">\n<VOUCHER VCHTYPE=\"Sales\" ACTION=\"Create\">\n")
            sb.append("<DATE>${tallyDate.format(java.util.Date(b.dateMillis))}</DATE>\n")
            sb.append("<VOUCHERTYPENAME>Sales</VOUCHERTYPENAME>\n")
            sb.append("<VOUCHERNUMBER>${xmlEscape(b.billNo)}</VOUCHERNUMBER>\n")
            sb.append("<PARTYLEDGERNAME>${xmlEscape(b.customerName)}</PARTYLEDGERNAME>\n")
            ledger(b.customerName, debit = true, amount = b.grandTotal)
            ledger("Discount Allowed", debit = true, amount = b.discount)
            if (composition) {
                // A composition dealer can't show tax as a separate ledger — folded into Sales.
                ledger("Sales Account", debit = false, amount = b.subTotal + b.taxTotal)
            } else {
                ledger("Sales Account", debit = false, amount = b.subTotal)
                if (gst) {
                    val split = com.billing.pos.data.GstTax.split(b.taxTotal, companyGstin, b.customerState)
                    if (split.interstate) ledger("IGST", debit = false, amount = split.igst)
                    else { ledger("CGST", debit = false, amount = split.cgst); ledger("SGST", debit = false, amount = split.sgst) }
                } else {
                    ledger("Tax", debit = false, amount = b.taxTotal)
                }
            }
            ledger("Additional Charges", debit = false, amount = b.additionalCharge)
            sb.append("</VOUCHER>\n</TALLYMESSAGE>\n")
        }
        s.purchases.forEach { p ->
            sb.append("<TALLYMESSAGE xmlns:UDF=\"TallyUDF\">\n<VOUCHER VCHTYPE=\"Purchase\" ACTION=\"Create\">\n")
            sb.append("<DATE>${tallyDate.format(java.util.Date(p.dateMillis))}</DATE>\n")
            sb.append("<VOUCHERTYPENAME>Purchase</VOUCHERTYPENAME>\n")
            sb.append("<VOUCHERNUMBER>${xmlEscape(p.purchaseNo)}</VOUCHERNUMBER>\n")
            sb.append("<PARTYLEDGERNAME>${xmlEscape(p.supplierName)}</PARTYLEDGERNAME>\n")
            ledger("Purchase Account", debit = true, amount = p.subTotal)
            if (gst) {
                ledger("CGST Input", debit = true, amount = p.taxTotal / 2.0)
                ledger("SGST Input", debit = true, amount = p.taxTotal / 2.0)
            } else {
                ledger("Tax", debit = true, amount = p.taxTotal)
            }
            ledger("Additional Charges", debit = true, amount = p.additionalCharge)
            ledger("Discount Received", debit = false, amount = p.discount)
            ledger(p.supplierName, debit = false, amount = p.grandTotal)
            sb.append("</VOUCHER>\n</TALLYMESSAGE>\n")
        }
        sb.append("</REQUESTDATA></IMPORTDATA></BODY>\n</ENVELOPE>\n")
        return sb.toString()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VatReportScreen(
    onBack: () -> Unit,
    vm: VatReportViewModel = viewModel()
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val message by vm.message.collectAsStateSafe()
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    var pendingExport by remember { mutableStateOf<String?>(null) }   // "xlsx" | "json" | "gstr1" | "tally"
    fun runExport(kind: String) {
        when (kind) {
            "xlsx" -> vm.exportExcel(context) { vm.message.value = it }
            "gstr1" -> vm.exportGstr1Json(context) { vm.message.value = it }
            "tally" -> vm.exportTallyXml(context) { vm.message.value = it }
            else -> vm.exportJson(context) { vm.message.value = it }
        }
    }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val k = pendingExport; pendingExport = null
        if (granted && k != null) runExport(k) else vm.message.value = "Storage permission denied"
    }
    fun export(kind: String) {
        if (DownloadSaver.needsLegacyPermission() &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) { pendingExport = kind; storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
        else runExport(kind)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("VAT / GST Report") },
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
            Column(Modifier.fillMaxSize().padding(pad), verticalArrangement = Arrangement.Center, horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                Text("You don't have permission to view this report", color = MaterialTheme.colorScheme.outline)
            }
            return@Scaffold
        }
        val s = vm.summary
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState())) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickDate(context, vm.from) { vm.updateFrom(it) } }, modifier = Modifier.weight(1f)) {
                    Text("From: ${Format.date(vm.from)}")
                }
                OutlinedButton(onClick = { pickDate(context, vm.to) { vm.updateTo(it) } }, modifier = Modifier.weight(1f)) {
                    Text("To: ${Format.date(vm.to)}")
                }
            }

            Card(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    SumRow("Sales taxable value", s?.salesTaxable ?: 0.0)
                    SumRow("Output tax (sales)", s?.salesTax ?: 0.0)
                    Divider(Modifier.padding(vertical = 6.dp))
                    SumRow("Purchase taxable value", s?.purchaseTaxable ?: 0.0)
                    SumRow("Input tax (purchases)", s?.purchaseTax ?: 0.0)
                    Divider(Modifier.padding(vertical = 6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Net VAT payable", fontWeight = FontWeight.Bold)
                        Text(Format.rupee(s?.netPayable ?: 0.0), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }

            if (s != null && s.salesRates.isNotEmpty()) {
                Text("Sales tax by rate", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
                s.salesRates.forEach {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${Format.money(it.rate)}%  on  ${Format.rupee(it.taxable)}", style = MaterialTheme.typography.bodySmall)
                        Text(Format.rupee(it.tax), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Text(
                "${s?.bills?.size ?: 0} sales • ${s?.purchases?.size ?: 0} purchases in period",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 12.dp)
            )

            Button(onClick = { export("xlsx") }, enabled = !vm.busy && s != null, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Text("Download Excel (.xlsx)")
            }
            OutlinedButton(onClick = { export("json") }, enabled = !vm.busy && s != null, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("Download JSON")
            }

            Divider(Modifier.padding(vertical = 16.dp))
            Text("GST portal & Tally", fontWeight = FontWeight.SemiBold)
            Text(
                "GSTR-1 JSON has the b2b / b2cs / cdnr / HSN sections the GST portal's offline tool " +
                    "expects, built from sales and sales returns in this period only (purchases aren't part " +
                    "of GSTR-1). Each invoice is IGST or CGST+SGST based on the customer's state saved on " +
                    "their customer record — set that for interstate customers or they'll default to your own " +
                    "state. Composition scheme accounts don't get this export (file CMP-08 instead). Tally XML " +
                    "has one Sales/Purchase voucher per bill/purchase — import it from Gateway of Tally > " +
                    "Import Data, creating matching ledgers first if needed.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 4.dp)
            )
            OutlinedButton(onClick = { export("gstr1") }, enabled = !vm.busy && s != null, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("Download GSTR-1 JSON (GST portal)")
            }
            OutlinedButton(onClick = { export("tally") }, enabled = !vm.busy && s != null, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("Download Tally XML")
            }
        }
    }
}

@Composable
private fun SumRow(label: String, value: Double) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(Format.rupee(value), fontWeight = FontWeight.SemiBold)
    }
}

private fun pickDate(context: Context, current: Long, onPicked: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = current }
    android.app.DatePickerDialog(
        context,
        { _, y, m, d -> c.set(Calendar.YEAR, y); c.set(Calendar.MONTH, m); c.set(Calendar.DAY_OF_MONTH, d); onPicked(c.timeInMillis) },
        c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
    ).show()
}
