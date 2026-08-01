package com.billing.pos.ocr

import java.text.SimpleDateFormat
import java.util.Locale

/** One item line read off a supplier bill: name/price/qty plus whatever unit or expiry was found. */
data class SupplierBillLine(
    val name: String,
    val price: Double,
    val qty: Double = 1.0,
    val unit: String = "",
    /** 0 = no expiry read for this line. */
    val expiryMillis: Long = 0L
)

/**
 * Reads a supplier bill's item lines from OCR text, and converts the small bits of free text the
 * header wizard reads (date, total) into usable values. The header fields themselves (supplier
 * name / bill no / date / total) are no longer guessed automatically — a generic layout-agnostic
 * regex pass over a whole bill proved unreliable, so the user marks each one with a box instead
 * (see SupplierBillHeaderWizard); this object only turns what was read in that single marked
 * region into a date/number, and still parses the item lines from the full page text.
 */
object SupplierBillParser {

    private val dateRegex = Regex("\\b(\\d{1,2})[/\\-.](\\d{1,2})[/\\-.](\\d{2,4})\\b")
    private val amountRegex = Regex("[\\d,]+\\.?\\d*")
    private val expiryRegex = Regex(
        "(?i)\\b(?:exp(?:iry)?)\\s*[:\\-]?\\s*(\\d{1,2})[/\\-](\\d{2,4})\\b"
    )
    private val unitRegex = Regex(
        "(?i)\\b(PCS|NOS|BOX|BOXES|CTN|CARTON|KG|GM|G|LTR|L|ML|STRIP|STRIPS|BOTTLE|BOTTLES|PACK|PACKS|DOZEN|UNIT|UNITS)\\b"
    )
    private val leadingQtyRegex = Regex("^\\s*(\\d+(?:\\.\\d+)?)\\s*[xX*]?\\s*")

    /** Item lines from the bill's full OCR text (all pages, already concatenated in page order). */
    fun parseItems(lines: List<String>): List<SupplierBillLine> {
        val scanned = ItemListParser.parse(lines)
        return scanned.map { s ->
            val src = lines.firstOrNull { it.contains(s.name, ignoreCase = true) } ?: s.name
            val unit = unitRegex.find(src)?.groupValues?.get(1)?.uppercase(Locale.ROOT) ?: ""
            val qty = leadingQtyRegex.find(src)?.groupValues?.get(1)?.toDoubleOrNull() ?: 1.0
            val expiry = parseExpiry(src)
            SupplierBillLine(name = s.name, price = s.price, qty = if (qty > 0) qty else 1.0, unit = unit, expiryMillis = expiry)
        }
    }

    /** Best-effort date from the text marked as "the bill date". Null if nothing parseable. */
    fun parseDateText(text: String): Long? {
        val m = dateRegex.find(text) ?: return null
        val (d, mo, y) = m.destructured
        val year = if (y.length == 2) "20$y" else y
        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.US).apply { isLenient = false }
        return runCatching { sdf.parse("$d/$mo/$year")?.time }.getOrNull()
    }

    /** Best-effort number from the text marked as "the total amount". 0.0 if nothing parseable. */
    fun parseAmountText(text: String): Double =
        amountRegex.find(text)?.value?.replace(",", "")?.toDoubleOrNull() ?: 0.0

    private fun parseExpiry(line: String): Long {
        val m = expiryRegex.find(line) ?: return 0L
        val (mo, y) = m.destructured
        val year = if (y.length == 2) "20$y" else y
        return runCatching {
            val sdf = SimpleDateFormat("MM/yyyy", Locale.US).apply { isLenient = false }
            sdf.parse("$mo/$year")?.time ?: 0L
        }.getOrDefault(0L)
    }
}
