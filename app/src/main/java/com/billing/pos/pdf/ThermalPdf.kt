package com.billing.pos.pdf

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import com.billing.pos.data.Bill
import com.billing.pos.data.BillItem
import com.billing.pos.data.CompanyInfo
import com.billing.pos.data.Receipt
import com.billing.pos.util.Format
import java.io.File

/**
 * Renders a bill / receipt as a narrow 58mm thermal-receipt-style PDF (32 columns,
 * monospace), so a shared PDF looks like the paper receipt from the thermal printer.
 */
object ThermalPdf {

    private const val COLS = 32
    private const val PAGE_W = 165f   // ~58mm in points
    private const val MARGIN = 6f

    fun invoice(context: Context, company: CompanyInfo, bill: Bill, lines: List<BillItem>): Uri {
        val sb = ArrayList<Line>()
        sb.header(company)
        sb.center("TAX INVOICE", bold = true)
        sb.rule()
        sb += "Bill: ${bill.billNo}"
        sb += "Date: ${Format.dateTime(bill.dateMillis)}"
        sb += "Cust: ${clip(bill.customerName)}"
        sb += "Pay : ${bill.paymentMethod}"
        sb.rule()
        sb += row("Item", "Qty", "Amount")
        sb.rule()
        for (l in lines) {
            sb += clip(l.name, COLS)
            sb += row("  @${Format.money(l.price)}", Format.qty(l.qty), Format.money(l.lineTotal))
        }
        sb.rule()
        sb += kv("Sub Total", Format.money(bill.subTotal))
        sb += kv("Tax", Format.money(bill.taxTotal))
        if (bill.additionalCharge != 0.0) sb += kv("Additional", Format.money(bill.additionalCharge))
        if (bill.discount != 0.0) sb += kv("Discount", "-" + Format.money(bill.discount))
        sb.rule()
        sb.add(Line(kv("GRAND TOTAL", Format.money(bill.grandTotal)), bold = true))
        sb.rule()
        sb.center("Thank you! Visit again.")
        return write(context, "invoice_${bill.billNo}", sb)
    }

    fun receipt(context: Context, company: CompanyInfo, r: Receipt): Uri {
        val sb = ArrayList<Line>()
        sb.header(company)
        sb.center("RECEIPT VOUCHER", bold = true)
        sb.rule()
        sb += "No  : ${r.receiptNo}"
        sb += "Date: ${Format.dateTime(r.dateMillis)}"
        sb += "From: ${clip(r.payFrom.ifBlank { r.customerName })}"
        if (r.billNo.isNotBlank()) sb += "Ref : ${r.billNo}"
        sb += "Mode: ${r.paymentMode}"
        sb.rule()
        sb.add(Line(kv("RECEIVED", Format.money(r.amount)), bold = true))
        sb.rule()
        sb.center("Thank you")
        return write(context, "receipt_${r.receiptNo}", sb)
    }

    // ---- rendering -------------------------------------------------------------

    private data class Line(val text: String, val bold: Boolean = false)

    private operator fun ArrayList<Line>.plusAssign(text: String) { add(Line(text)) }
    private fun ArrayList<Line>.center(text: String, bold: Boolean = false) = add(Line(center(text), bold))
    private fun ArrayList<Line>.rule() = add(Line("-".repeat(COLS)))
    private fun ArrayList<Line>.header(company: CompanyInfo) {
        add(Line(center(company.name), bold = true))
        if (company.address.isNotBlank()) add(Line(center(clip(company.address))))
        if (company.phone.isNotBlank()) add(Line(center("Ph: ${company.phone}")))
    }

    private fun write(context: Context, name: String, lines: List<Line>): Uri {
        val body = Paint().apply { typeface = Typeface.MONOSPACE; isAntiAlias = true; color = Color.BLACK; textSize = 10f }
        val content = PAGE_W - 2 * MARGIN
        val measured = body.measureText("0".repeat(COLS)).coerceAtLeast(1f)
        body.textSize = 10f * (content / measured)
        val boldP = Paint(body).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
        val lineH = body.textSize * 1.35f
        val height = (MARGIN * 2 + lines.size * lineH + lineH).toInt().coerceAtLeast(40)

        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W.toInt(), height, 1).create())
        val c = page.canvas
        var y = MARGIN + lineH
        for (l in lines) {
            c.drawText(l.text, MARGIN, y, if (l.bold) boldP else body)
            y += lineH
        }
        doc.finishPage(page)

        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val safe = name.replace(Regex("[^A-Za-z0-9]+"), "_").take(28).ifBlank { "receipt" }
        val file = File(dir, "$safe.pdf")
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    }

    // ---- 32-column text helpers (match the thermal printer) --------------------

    private fun clip(s: String, max: Int = COLS) = if (s.length <= max) s else s.take(max)

    private fun center(s: String): String {
        val t = clip(s, COLS)
        val pad = (COLS - t.length) / 2
        return " ".repeat(pad.coerceAtLeast(0)) + t
    }

    private fun kv(key: String, value: String): String {
        val space = (COLS - key.length - value.length).coerceAtLeast(1)
        return key + " ".repeat(space) + value
    }

    private fun row(a: String, b: String, cRight: String): String {
        val left = a.take(16).padEnd(16)
        val mid = b.take(6).padStart(6)
        val right = cRight.take(10).padStart(10)
        return (left + mid + right).take(COLS)
    }
}
