package com.billing.pos.data

/**
 * Parses "in-store" weighing-scale barcodes: a fixed-width, EAN-13-style code carrying an item's
 * PLU code plus its weighed quantity (or, in Price mode, the price the scale already computed),
 * ending in a standard EAN-13 check digit. Field widths and the prefix digit are configurable in
 * Settings since scale manufacturers vary; the defaults (prefix "2", 5-digit item code, 6-digit
 * value) match the common Indian "in-store" convention — GS1 reserves the "2" prefix for exactly
 * this use, so no real product barcode collides with it.
 */
object WeighScaleBarcode {

    /** [itemCode] matches against an item's own Barcode field. [value] is already converted:
     *  kilograms in Weight mode, rupees in Price mode. */
    data class Parsed(val itemCode: String, val value: Double)

    fun parse(barcode: String, prefs: AppPrefs): Parsed? {
        val code = barcode.trim()
        val prefix = prefs.weighScalePrefix.trim()
        if (prefix.isEmpty() || !code.startsWith(prefix)) return null
        val itemLen = prefs.weighScaleItemCodeLen
        val valueLen = prefs.weighScaleValueLen
        val totalLen = prefix.length + itemLen + valueLen + 1   // +1 for the EAN-13 check digit
        if (code.length != totalLen || !code.all { it.isDigit() }) return null
        if (!hasValidEanCheckDigit(code)) return null

        val itemCode = code.substring(prefix.length, prefix.length + itemLen)
        val valueDigits = code.substring(prefix.length + itemLen, prefix.length + itemLen + valueLen)
        val raw = valueDigits.toLongOrNull() ?: return null
        // Weight mode: raw is grams -> kg. Price mode: raw is paise -> rupees.
        val value = if (prefs.weighScaleValueIsPrice) raw / 100.0 else raw / 1000.0
        return Parsed(itemCode, value)
    }

    /** Standard EAN-13 check digit: from the left, odd positions weight 1, even positions weight
     *  3 over the first 12 digits; the 13th digit must make the weighted sum a multiple of 10. */
    private fun hasValidEanCheckDigit(code: String): Boolean {
        if (code.length < 2) return false
        val digits = code.map { it - '0' }
        val payload = digits.dropLast(1)
        val checkDigit = digits.last()
        var sum = 0
        payload.forEachIndexed { i, d -> sum += if (i % 2 == 0) d else d * 3 }
        val computed = (10 - (sum % 10)) % 10
        return computed == checkDigit
    }
}
