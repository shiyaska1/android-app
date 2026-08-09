package com.billing.pos.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.Item
import com.billing.pos.util.Format
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import java.io.File
import kotlin.math.roundToInt

/** Generates a printable PDF of barcode labels for an item, one label per page sized to match a
 *  dedicated barcode-label printer (width/height and which fields to print are set in Settings). */
object BarcodePdf {

    private const val MM_TO_PT = 72f / 25.4f

    /** Returns the generated PDF file, or null if the item has no barcode. */
    fun generate(context: Context, item: Item, count: Int): File? {
        val code = item.barcode.trim()
        if (code.isBlank() || count <= 0) return null
        val prefs = AppPrefs(context)

        val pageW = (prefs.barcodeLabelWidthMm.toFloat() * MM_TO_PT).coerceAtLeast(72f).roundToInt()
        val pageH = (prefs.barcodeLabelHeightMm.toFloat() * MM_TO_PT).coerceAtLeast(36f).roundToInt()
        val margin = 4f

        val lines = buildList {
            if (prefs.barcodeShowCompanyName && prefs.company.name.isNotBlank()) add(prefs.company.name)
            add(item.name)
            val sizeAndPrice = buildString {
                if (prefs.barcodeShowSize && item.unit.isNotBlank()) append(item.unit)
                if (prefs.barcodeShowPrice) {
                    if (isNotEmpty()) append("  •  ")
                    append("Rs. ${Format.money(item.price)}")
                }
            }
            if (sizeAndPrice.isNotBlank()) add(sizeAndPrice)
        }

        val namePaint = Paint().apply { color = Color.BLACK; textSize = 9f; isFakeBoldText = true; isAntiAlias = true }
        val smallPaint = Paint().apply { color = Color.DKGRAY; textSize = 8f; isAntiAlias = true }
        val textBlockH = lines.size * 11f
        val barcodeH = (pageH - 2 * margin - textBlockH).coerceAtLeast(20f)
        val bmp = barcodeBitmap(code, (pageW - 2 * margin).roundToInt().coerceAtLeast(1), barcodeH.roundToInt().coerceAtLeast(1)) ?: return null

        val doc = PdfDocument()
        for (i in 0 until count) {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, i + 1).create())
            val c = page.canvas
            c.drawBitmap(bmp, null, RectF(margin, margin, pageW - margin, margin + barcodeH), null)
            var ty = margin + barcodeH + 9f
            lines.forEachIndexed { idx, line ->
                c.drawText(clip(line, 28), margin, ty, if (idx == 0) namePaint else smallPaint)
                ty += 11f
            }
            doc.finishPage(page)
        }

        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val safe = code.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val file = File(dir, "barcodes-$safe.pdf")
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file
    }

    private fun barcodeBitmap(content: String, w: Int, h: Int): Bitmap? = runCatching {
        val hints = mapOf(EncodeHintType.MARGIN to 2)
        val matrix = MultiFormatWriter().encode(content, BarcodeFormat.CODE_128, w, h, hints)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        for (x in 0 until w) {
            for (y in 0 until h) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        bmp
    }.getOrNull()

    private fun clip(s: String, max: Int) = if (s.length <= max) s else s.take(max)
}
