package com.billing.pos.pdf

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import com.billing.pos.data.CompanyInfo
import com.billing.pos.data.DeliveryNote
import com.billing.pos.data.DeliveryNoteItem
import com.billing.pos.util.Format
import java.io.File

/** Renders a Delivery Note (goods delivered to a customer) to a shareable PDF and returns a content:// Uri. */
object DeliveryNotePdf {

    private const val PAGE_W = 420
    private const val MARGIN = 24f

    fun generate(context: Context, company: CompanyInfo, note: DeliveryNote, lines: List<DeliveryNoteItem>): Uri {
        val doc = PdfDocument()
        val lineHeight = 20f
        val estHeight = (280 + lines.size * lineHeight + 140).toInt()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, estHeight, 1).create())
        val c = page.canvas

        val title = Paint().apply { color = Color.BLACK; textSize = 20f; isFakeBoldText = true }
        val h = Paint().apply { color = Color.BLACK; textSize = 12f; isFakeBoldText = true }
        val p = Paint().apply { color = Color.DKGRAY; textSize = 11f }
        val rightP = Paint(p).apply { textAlign = Paint.Align.RIGHT }
        val rightH = Paint(h).apply { textAlign = Paint.Align.RIGHT }
        val rule = Paint().apply { color = Color.LTGRAY; strokeWidth = 1f }

        val right = PAGE_W - MARGIN
        var y = 40f

        c.drawText(company.name, MARGIN, y, title); y += 22f
        if (company.address.isNotBlank()) { c.drawText(company.address, MARGIN, y, p); y += 14f }
        if (company.phone.isNotBlank()) { c.drawText("Ph: ${company.phone}", MARGIN, y, p); y += 14f }
        y += 4f
        c.drawText("DELIVERY NOTE", MARGIN, y, h); y += 18f

        c.drawText("Delivery No: ${note.deliveryNo}", MARGIN, y, p)
        c.drawText("Date: ${Format.date(note.dateMillis)}", right, y, rightP); y += 16f
        c.drawText("Customer: ${note.customerName}", MARGIN, y, p); y += 14f
        c.drawLine(MARGIN, y, right, y, rule); y += 18f

        c.drawText("Item", MARGIN, y, h)
        c.drawText("Qty", MARGIN + 190, y, h)
        c.drawText("Rate", MARGIN + 240, y, h)
        c.drawText("Amount", right, y, rightH); y += 6f
        c.drawLine(MARGIN, y, right, y, rule); y += 18f

        for (l in lines) {
            c.drawText(clip(l.name + if (l.batchNo.isNotBlank()) " [${l.batchNo}]" else "", 24), MARGIN, y, p)
            c.drawText(Format.qty(l.qty), MARGIN + 190, y, p)
            c.drawText(Format.money(l.price), MARGIN + 240, y, p)
            c.drawText(Format.money(l.lineTotal), right, y, rightP)
            y += lineHeight
        }
        c.drawLine(MARGIN, y, right, y, rule); y += 20f

        c.drawText("TOTAL", MARGIN + 190, y, h)
        c.drawText(Format.money(lines.sumOf { it.lineTotal }), right, y, rightH); y += 18f

        if (note.remarks.isNotBlank()) {
            y += 6f
            c.drawText("Remarks: ${clip(note.remarks, 50)}", MARGIN, y, p)
        }

        doc.finishPage(page)

        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val safe = note.deliveryNo.ifBlank { "delivery_note" }.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val file = File(dir, "$safe.pdf")
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    }

    private fun clip(s: String, max: Int) = if (s.length <= max) s else s.take(max - 1) + "…"
}
