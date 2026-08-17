package com.billing.pos.shared

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Basic A4 invoice PDF for a saved Sale — reasonably complete (header, customer, item table,
 * totals) but not a pixel-for-pixel match of the Android app's invoice layout. Uses Apache
 * PDFBox since Android's android.graphics.pdf.PdfDocument doesn't exist on desktop JVM.
 */
object InvoicePdf {
    private val dateFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

    fun generate(bill: Bill, lines: List<BillLine>, outFile: File) {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            val fontBold = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)
            val fontRegular = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            val pageWidth = PDRectangle.A4.width
            val left = 50f
            val right = pageWidth - 50f
            var y = 780f

            PDPageContentStream(doc, page).use { cs ->
                fun text(x: Float, yPos: Float, s: String, font: PDType1Font, size: Float) {
                    cs.beginText()
                    cs.setFont(font, size)
                    cs.newLineAtOffset(x, yPos)
                    cs.showText(s)
                    cs.endText()
                }
                fun hline(yPos: Float) {
                    cs.moveTo(left, yPos)
                    cs.lineTo(right, yPos)
                    cs.stroke()
                }

                text(left, y, "TAX INVOICE", fontBold, 18f)
                y -= 30f
                text(left, y, "Invoice No: ${bill.billNo}", fontRegular, 11f)
                text(right - 180f, y, "Date: ${dateFmt.format(Date(bill.dateMillis))}", fontRegular, 11f)
                y -= 20f
                text(left, y, "Bill To: ${bill.customerName}", fontRegular, 11f)
                y -= 20f
                hline(y)
                y -= 20f

                // Item table header
                val colItem = left
                val colQty = left + 250f
                val colPrice = left + 320f
                val colTax = left + 400f
                val colTotal = right - 90f
                text(colItem, y, "Item", fontBold, 10f)
                text(colQty, y, "Qty", fontBold, 10f)
                text(colPrice, y, "Price", fontBold, 10f)
                text(colTax, y, "Tax%", fontBold, 10f)
                text(colTotal, y, "Amount", fontBold, 10f)
                y -= 8f
                hline(y)
                y -= 16f

                lines.forEach { line ->
                    if (y < 100f) return@forEach // simple single-page cap; pagination is a later refinement
                    text(colItem, y, line.name.take(40), fontRegular, 10f)
                    text(colQty, y, "%.2f".format(line.qty), fontRegular, 10f)
                    text(colPrice, y, "%.2f".format(line.price), fontRegular, 10f)
                    text(colTax, y, "%.1f".format(line.taxPercent), fontRegular, 10f)
                    text(colTotal, y, "%.2f".format(line.lineTotal), fontRegular, 10f)
                    y -= 16f
                }

                y -= 8f
                hline(y)
                y -= 20f

                text(colTax, y, "Subtotal:", fontRegular, 11f)
                text(colTotal, y, "%.2f".format(bill.subTotal), fontRegular, 11f)
                y -= 16f
                text(colTax, y, "Tax:", fontRegular, 11f)
                text(colTotal, y, "%.2f".format(bill.taxTotal), fontRegular, 11f)
                y -= 16f
                text(colTax, y, "Grand Total:", fontBold, 12f)
                text(colTotal, y, "%.2f".format(bill.grandTotal), fontBold, 12f)
            }

            doc.save(outFile)
        }
    }
}
