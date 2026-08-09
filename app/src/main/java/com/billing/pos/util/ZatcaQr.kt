package com.billing.pos.util

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * ZATCA (Saudi e-invoicing, Phase 1 "generation" phase) simplified tax invoice QR payload:
 * seller name, VAT registration number, invoice timestamp, invoice total (incl. VAT) and VAT
 * total, TLV-encoded (tag byte + length byte + UTF-8 value) and Base64-encoded as a whole —
 * built and scanned entirely on-device, no ZATCA API call needed for this phase.
 */
object ZatcaQr {

    private fun tlv(tag: Int, value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        out.write(tag)
        out.write(bytes.size)
        out.write(bytes)
        return out.toByteArray()
    }

    fun payload(sellerName: String, vatNumber: String, timestampMillis: Long, invoiceTotal: Double, vatTotal: Double): String {
        val ts = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(timestampMillis))
        val out = ByteArrayOutputStream()
        out.write(tlv(1, sellerName))
        out.write(tlv(2, vatNumber))
        out.write(tlv(3, ts))
        out.write(tlv(4, String.format(Locale.US, "%.2f", invoiceTotal)))
        out.write(tlv(5, String.format(Locale.US, "%.2f", vatTotal)))
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
