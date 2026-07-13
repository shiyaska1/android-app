package com.billing.pos.pdf

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.CompanyInfo
import com.billing.pos.data.LabBill
import com.billing.pos.data.LabResultValue
import com.billing.pos.util.Format
import java.io.File

/** A clean A4 laboratory report: lab header, patient box, grouped results, technician sign-off. */
object LabResultPdf {

    private const val PW = 595f
    private const val PH = 842f
    private const val M = 36f

    fun make(context: Context, company: CompanyInfo, bill: LabBill, results: List<LabResultValue>): Uri {
        val prefs = AppPrefs(context)
        val doc = PdfDocument()

        val title = Paint().apply { color = 0xFF000000.toInt(); isAntiAlias = true; textSize = 22f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val sub = Paint().apply { color = 0xFF333333.toInt(); isAntiAlias = true; textSize = 10f }
        val small = Paint().apply { color = 0xFF555555.toInt(); isAntiAlias = true; textSize = 9f }
        val cell = Paint().apply { color = 0xFF000000.toInt(); isAntiAlias = true; textSize = 10f }
        val cellBold = Paint(cell).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val abn = Paint(cellBold).apply { color = 0xFFC00000.toInt() }
        val line = Paint().apply { color = 0xFF000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 1f }
        val faint = Paint().apply { color = 0xFFBBBBBB.toInt(); style = Paint.Style.STROKE; strokeWidth = 0.7f }

        val x0 = M
        val xEnd = PW - M
        val cResult = xEnd - 210f
        val cUnit = xEnd - 130f
        val cRef = xEnd - 60f

        var page = doc.startPage(PdfDocument.PageInfo.Builder(PW.toInt(), PH.toInt(), 1).create())
        var c = page.canvas
        var y: Float

        val logo = prefs.logoPath.takeIf { it.isNotBlank() }?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
        var textX = x0
        if (logo != null) {
            val h = 56f; val w = h * logo.width / logo.height
            c.drawBitmap(logo, null, Rect(x0.toInt(), M.toInt(), (x0 + w).toInt(), (M + h).toInt()), null)
            textX = x0 + w + 12f
        }
        c.drawText(company.name.ifBlank { "Laboratory" }, textX, M + 20f, title)
        var hy = M + 36f
        if (company.address.isNotBlank()) { c.drawText(company.address, textX, hy, sub); hy += 13f }
        if (company.phone.isNotBlank()) { c.drawText("Phone: ${company.phone}", textX, hy, sub); hy += 13f }
        y = maxOf(hy, M + 60f) + 6f

        c.drawLine(x0, y, xEnd, y, line); y += 18f
        val tp = Paint(cellBold).apply { textSize = 13f; textAlign = Paint.Align.CENTER }
        c.drawText("LABORATORY REPORT", (x0 + xEnd) / 2f, y, tp); y += 16f

        // Patient box
        val boxTop = y
        c.drawText("Patient : ${bill.patientName}", x0 + 4f, y + 14f, cellBold)
        c.drawText("Bill No : ${bill.billNo}", cResult, y + 14f, sub)
        c.drawText("Age/Sex : ${listOf(bill.age, bill.gender).filter { it.isNotBlank() }.joinToString(" / ")}", x0 + 4f, y + 30f, sub)
        c.drawText("Date : ${Format.date(if (bill.resultDateMillis > 0) bill.resultDateMillis else bill.dateMillis)}", cResult, y + 30f, sub)
        c.drawText("Referred by : ${bill.referredBy.ifBlank { "-" }}", x0 + 4f, y + 46f, sub)
        c.drawRect(x0, boxTop, xEnd, boxTop + 56f, line)
        y = boxTop + 56f + 16f

        fun columnHeader() {
            c.drawText("INVESTIGATION", x0 + 2f, y, cellBold)
            c.drawText("RESULT", cResult, y, cellBold)
            c.drawText("UNIT", cUnit, y, cellBold)
            c.drawText("REFERENCE", cRef, y, cellBold)
            y += 4f
            c.drawLine(x0, y, xEnd, y, line); y += 14f
        }
        fun ensureSpace(need: Float) {
            if (y + need > PH - 90f) {
                doc.finishPage(page)
                page = doc.startPage(PdfDocument.PageInfo.Builder(PW.toInt(), PH.toInt(), doc.pages.size + 1).create())
                c = page.canvas; y = M
            }
        }

        // Group by test (preserve first-seen order), then by evaluation group within the test.
        val byTest = LinkedHashMap<String, MutableList<LabResultValue>>()
        results.forEach { byTest.getOrPut(it.testName) { mutableListOf() }.add(it) }

        byTest.forEach { (testName, rows) ->
            ensureSpace(60f)
            c.drawText(testName.uppercase(), x0 + 2f, y, cellBold); y += 6f
            c.drawLine(x0, y, xEnd, y, faint); y += 14f
            columnHeader()
            val byGroup = LinkedHashMap<String, MutableList<LabResultValue>>()
            rows.forEach { byGroup.getOrPut(it.groupName) { mutableListOf() }.add(it) }
            byGroup.forEach { (group, gr) ->
                if (group.isNotBlank()) { ensureSpace(18f); c.drawText(group, x0 + 6f, y, cellBold); y += 15f }
                gr.forEach { r ->
                    ensureSpace(16f)
                    c.drawText(clip(r.evaluationName, 34), x0 + (if (group.isNotBlank()) 12f else 2f), y, cell)
                    c.drawText(r.result.ifBlank { "-" }, cResult, y, if (isOutOfRange(r)) abn else cellBold)
                    c.drawText(clip(r.unit, 12), cUnit, y, cell)
                    c.drawText(clip(r.normalValue, 16), cRef, y, small)
                    y += 15f
                    c.drawLine(x0, y - 4f, xEnd, y - 4f, faint)
                }
            }
            y += 10f
        }

        // Sign-off
        ensureSpace(70f)
        y = maxOf(y, PH - 90f)
        c.drawText("Verified by", xEnd - 140f, y, small)
        c.drawLine(xEnd - 150f, y + 26f, xEnd - 20f, y + 26f, line)
        c.drawText("Lab Technician", xEnd - 140f, y + 40f, small)
        c.drawText("** End of report **", (x0 + xEnd) / 2f, PH - M, Paint(small).apply { textAlign = Paint.Align.CENTER })

        doc.finishPage(page)
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val safe = bill.billNo.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val file = File(dir, "labreport_$safe.pdf")
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    }

    /** Best-effort abnormal flag: numeric result outside a "lo - hi" numeric reference range. */
    private fun isOutOfRange(r: LabResultValue): Boolean {
        val v = r.result.trim().toDoubleOrNull() ?: return false
        val m = Regex("(-?\\d+(?:\\.\\d+)?)\\s*-\\s*(-?\\d+(?:\\.\\d+)?)").find(r.normalValue) ?: return false
        val lo = m.groupValues[1].toDoubleOrNull() ?: return false
        val hi = m.groupValues[2].toDoubleOrNull() ?: return false
        return v < lo || v > hi
    }

    private fun clip(s: String, max: Int) = if (s.length <= max) s else s.take(max - 1) + "…"
}
