package com.billing.pos.pdf

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.Bill
import com.billing.pos.data.BillItem
import com.billing.pos.data.CompanyInfo
import com.billing.pos.util.Format
import java.io.File

/** A rich, full-page A4 invoice (bordered table, big header, optional logo). */
object A4InvoicePdf {

    private const val PW = 595f      // A4 width in points (72 dpi)
    private const val PH = 842f      // A4 height
    private const val M = 36f        // margin

    fun invoice(context: Context, company: CompanyInfo, bill: Bill, lines: List<BillItem>): Uri {
        val prefs = AppPrefs(context)
        val doc = PdfDocument()

        val black = Paint().apply { color = 0xFF000000.toInt(); isAntiAlias = true }
        val title = Paint(black).apply { textSize = 24f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val sub = Paint(black).apply { textSize = 11f }
        val small = Paint(black).apply { textSize = 9f; color = 0xFF555555.toInt() }
        val cell = Paint(black).apply { textSize = 10f }
        val cellBold = Paint(cell).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val line = Paint().apply { color = 0xFF000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 1f }
        val head = Paint(cellBold).apply { textSize = 10.5f }

        // Column geometry
        val x0 = M
        val xEnd = PW - M
        // Column start-x boundaries (# | Item | Qty | Rate | Amount ... xEnd)
        val cNo = x0
        val cItem = x0 + 28f
        val cQty = xEnd - 210f
        val cRate = xEnd - 140f
        val cAmt = xEnd - 70f

        val logo = prefs.logoPath.takeIf { it.isNotBlank() }?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }

        var page = doc.startPage(PdfDocument.PageInfo.Builder(PW.toInt(), PH.toInt(), 1).create())
        var c = page.canvas
        var y: Float

        // ---- Header ----
        if (logo != null && prefs.logoFullWidth) {
            val w = xEnd - x0
            val h = w * logo.height / logo.width
            val cappedH = h.coerceAtMost(140f)
            val cappedW = cappedH * logo.width / logo.height
            c.drawBitmap(logo, null, Rect(x0.toInt(), M.toInt(), (x0 + cappedW).toInt(), (M + cappedH).toInt()), null)
            y = M + cappedH + 14f
        } else {
            var textX = x0
            if (logo != null) {
                val h = 70f; val w = h * logo.width / logo.height
                c.drawBitmap(logo, null, Rect(x0.toInt(), M.toInt(), (x0 + w).toInt(), (M + h).toInt()), null)
                textX = x0 + w + 14f
            }
            c.drawText(company.name.ifBlank { "My Shop" }, textX, M + 22f, title)
            var hy = M + 40f
            if (company.address.isNotBlank()) { c.drawText(company.address, textX, hy, sub); hy += 15f }
            if (company.phone.isNotBlank()) { c.drawText("Phone: ${company.phone}", textX, hy, sub); hy += 15f }
            if (company.gstin.isNotBlank()) { c.drawText("GSTIN: ${company.gstin}", textX, hy, sub); hy += 15f }
            y = maxOf(hy, M + 78f) + 8f
        }

        // ---- Title bar ----
        c.drawLine(x0, y, xEnd, y, line)
        y += 20f
        val tp = Paint(cellBold).apply { textSize = 15f; textAlign = Paint.Align.CENTER }
        c.drawText("TAX INVOICE", (x0 + xEnd) / 2f, y, tp)
        y += 18f

        // ---- Bill meta ----
        c.drawText("Invoice No: ${bill.billNo}", x0, y, sub)
        c.drawText("Date: ${Format.date(bill.dateMillis)}", cRate - 40f, y, sub)
        y += 15f
        c.drawText("Bill To: ${bill.customerName}", x0, y, sub)
        c.drawText("Payment: ${bill.paymentMethod}", cRate - 40f, y, sub)
        y += 12f

        // ---- Table header ----
        val rowH = 20f
        var secTop = y
        fun tableHeader() {
            secTop = y
            c.drawRect(x0, y, xEnd, y + rowH, line)
            val ty = y + 14f
            c.drawText("#", cNo + 4f, ty, head)
            c.drawText("Item", cItem + 4f, ty, head)
            val ra = Paint(head).apply { textAlign = Paint.Align.RIGHT }
            c.drawText("Qty", cRate - 4f, ty, ra)
            c.drawText("Rate", cAmt - 4f, ty, ra)
            c.drawText("Amount", xEnd - 4f, ty, ra)
            y += rowH
        }
        tableHeader()

        val rightCell = Paint(cell).apply { textAlign = Paint.Align.RIGHT }
        lines.forEachIndexed { i, l ->
            if (y + rowH > PH - 120f) {
                drawVerticalBorders(c, line, x0, xEnd, cItem, cQty, cRate, cAmt, secTop, y)
                doc.finishPage(page)
                page = doc.startPage(PdfDocument.PageInfo.Builder(PW.toInt(), PH.toInt(), doc.pages.size + 1).create())
                c = page.canvas
                y = M
                tableHeader()
            }
            c.drawText("${i + 1}", cNo + 4f, y + 14f, cell)
            c.drawText(clip(l.name, 44), cItem + 4f, y + 14f, cell)
            c.drawText(Format.qty(l.qty), cRate - 4f, y + 14f, rightCell)
            c.drawText(Format.money(l.price), cAmt - 4f, y + 14f, rightCell)
            c.drawText(Format.money(l.lineTotal), xEnd - 4f, y + 14f, rightCell)
            y += rowH
            c.drawLine(x0, y, xEnd, y, line)
        }

        // vertical borders (sides + column separators) for the current page's table
        drawVerticalBorders(c, line, x0, xEnd, cItem, cQty, cRate, cAmt, secTop, y)

        // ---- Totals ----
        y += 12f
        val lblX = cRate - 10f
        fun total(label: String, value: String, bold: Boolean = false) {
            val lp = if (bold) cellBold else cell
            val vp = Paint(if (bold) cellBold else cell).apply { textAlign = Paint.Align.RIGHT }
            c.drawText(label, lblX, y, lp)
            c.drawText(value, cAmt - 4f, y, vp)
            y += 17f
        }
        total("Sub Total", Format.money(bill.subTotal))
        if (bill.taxTotal != 0.0) total("Tax", Format.money(bill.taxTotal))
        if (bill.additionalCharge != 0.0) total("Additional", Format.money(bill.additionalCharge))
        if (bill.discount != 0.0) total("Discount", "-" + Format.money(bill.discount))
        c.drawLine(lblX, y - 4f, xEnd, y - 4f, line)
        y += 4f
        val gt = Paint(cellBold).apply { textSize = 13f }
        val gtv = Paint(gt).apply { textAlign = Paint.Align.RIGHT }
        c.drawText("GRAND TOTAL", lblX, y + 4f, gt)
        c.drawText(Format.money(bill.grandTotal), cAmt - 4f, y + 4f, gtv)
        y += 30f

        if (bill.remarks.isNotBlank()) {
            c.drawText("Note: ${clip(bill.remarks, 90)}", x0, y, small); y += 16f
        }
        c.drawText("Thank you for your business!", x0, PH - M, small)

        doc.finishPage(page)

        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "invoice_${bill.billNo}.pdf")
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    }

    private fun drawVerticalBorders(c: Canvas, line: Paint, x0: Float, xEnd: Float, cItem: Float, cQty: Float, cRate: Float, cAmt: Float, startY: Float, endY: Float) {
        for (x in listOf(x0, cItem, cQty, cRate, cAmt, xEnd)) c.drawLine(x, startY, x, endY, line)
    }

    private fun clip(s: String, max: Int) = if (s.length <= max) s else s.take(max - 1) + "…"
}
