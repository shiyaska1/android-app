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

data class SupplierBillParsed(
    val invoiceNo: String = "",
    /** 0 = no date could be read; caller should default to today. */
    val dateMillis: Long = 0L,
    /** 0 = no total could be read. Informational only — the cart total is computed from the lines. */
    val total: Double = 0.0,
    val lines: List<SupplierBillLine>
)

/**
 * Best-effort extraction of a supplier bill's header fields (invoice no / date / total) and its
 * item lines, from the raw OCR text of one or more photographed pages (already concatenated in
 * page order). Real bills vary enormously in layout, so every field degrades gracefully to
 * "not found" rather than guessing — the caller always shows a review step before anything is
 * saved.
 */
object SupplierBillParser {

    private val invoiceNoRegex = Regex(
        "(?i)\\b(?:invoice|inv|bill)\\s*(?:no|number|#)?\\s*[:\\-]?\\s*([A-Za-z0-9][A-Za-z0-9\\-/]{2,})"
    )
    private val dateRegex = Regex("\\b(\\d{1,2})[/\\-.](\\d{1,2})[/\\-.](\\d{2,4})\\b")
    private val totalRegex = Regex(
        "(?i)\\b(grand\\s*total|net\\s*amount|total\\s*amount|total)\\b\\s*[:\\-]?\\s*(?:₹|rs\\.?|inr)?\\s*([\\d,]+\\.?\\d*)"
    )
    private val expiryRegex = Regex(
        "(?i)\\b(?:exp(?:iry)?)\\s*[:\\-]?\\s*(\\d{1,2})[/\\-](\\d{2,4})\\b"
    )
    private val unitRegex = Regex(
        "(?i)\\b(PCS|NOS|BOX|BOXES|CTN|CARTON|KG|GM|G|LTR|L|ML|STRIP|STRIPS|BOTTLE|BOTTLES|PACK|PACKS|DOZEN|UNIT|UNITS)\\b"
    )
    private val leadingQtyRegex = Regex("^\\s*(\\d+(?:\\.\\d+)?)\\s*[xX*]?\\s*")

    fun parse(lines: List<String>): SupplierBillParsed {
        val invoiceNo = lines.firstNotNullOfOrNull { invoiceNoRegex.find(it)?.groupValues?.get(1) } ?: ""
        val dateMillis = lines.firstNotNullOfOrNull { parseDate(it) } ?: 0L
        val total = bestTotal(lines)

        // Lines already consumed as header fields are dropped before parsing items, so the
        // invoice-no / total lines don't also show up as junk item rows.
        val headerLineIndexes = mutableSetOf<Int>()
        lines.forEachIndexed { i, l ->
            if (invoiceNoRegex.containsMatchIn(l) || totalRegex.containsMatchIn(l)) headerLineIndexes.add(i)
        }
        val itemLines = lines.filterIndexed { i, _ -> i !in headerLineIndexes }
        val scanned = ItemListParser.parse(itemLines)

        val result = scanned.map { s ->
            val src = lines.firstOrNull { it.contains(s.name, ignoreCase = true) } ?: s.name
            val unit = unitRegex.find(src)?.groupValues?.get(1)?.uppercase(Locale.ROOT) ?: ""
            val qty = leadingQtyRegex.find(src)?.groupValues?.get(1)?.toDoubleOrNull() ?: 1.0
            val expiry = parseExpiry(src)
            SupplierBillLine(name = s.name, price = s.price, qty = if (qty > 0) qty else 1.0, unit = unit, expiryMillis = expiry)
        }
        return SupplierBillParsed(invoiceNo, dateMillis, total, result)
    }

    private fun bestTotal(lines: List<String>): Double {
        var grand: Double? = null
        var net: Double? = null
        var plain: Double? = null
        lines.forEach { l ->
            val m = totalRegex.find(l) ?: return@forEach
            val label = m.groupValues[1].lowercase(Locale.ROOT)
            val value = m.groupValues[2].replace(",", "").toDoubleOrNull() ?: return@forEach
            when {
                label.contains("grand") -> grand = value
                label.contains("net") -> net = value
                else -> plain = value
            }
        }
        return grand ?: net ?: plain ?: 0.0
    }

    private fun parseDate(line: String): Long? {
        val m = dateRegex.find(line) ?: return null
        val (d, mo, y) = m.destructured
        val year = if (y.length == 2) "20$y" else y
        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.US).apply { isLenient = false }
        return runCatching { sdf.parse("$d/$mo/$year")?.time }.getOrNull()
    }

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
