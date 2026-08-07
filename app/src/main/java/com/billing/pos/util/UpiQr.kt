package com.billing.pos.util

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.util.Locale

/**
 * Builds UPI payment QR codes on the phone — no gateway, no server.
 *
 * The QR just encodes the standard `upi://pay` link. Any UPI app (GPay, PhonePe, Paytm)
 * that scans it opens payment with the payee and amount already filled in; the payer only
 * enters their PIN. Money goes straight to the merchant's own UPI ID.
 */
object UpiQr {

    /** The upi:// link for a fixed amount. [tr] is a unique transaction reference (order id, bill
     *  number, or similar) — optional per the UPI spec, but including one is standard practice and
     *  makes a payer's UPI app (GPay etc.) less likely to flag an intent-based payment from a
     *  third-party app as suspicious, especially for repeated small amounts to the same payee. */
    fun link(vpa: String, name: String, amount: Double, note: String = "", tr: String = ""): String {
        val am = String.format(Locale.US, "%.2f", amount)
        val sb = StringBuilder("upi://pay?pa=").append(Uri.encode(vpa.trim()))
        if (name.isNotBlank()) sb.append("&pn=").append(Uri.encode(name.trim()))
        sb.append("&am=").append(am).append("&cu=INR")
        if (note.isNotBlank()) sb.append("&tn=").append(Uri.encode(note.trim()))
        if (tr.isNotBlank()) sb.append("&tr=").append(Uri.encode(tr.trim()))
        return sb.toString()
    }

    /**
     * The reverse of [link] — pulls the VPA (and payee name, if present) back out of a scanned
     * UPI QR code, e.g. a shop owner's own bank-issued QR sticker. Any standard `upi://pay?...`
     * (or a bare `pa=...&pn=...` query string, some banks encode it without the scheme) works;
     * null if the scanned text isn't a UPI QR at all. Lets Settings auto-fill the UPI ID field
     * from a scan instead of the owner having to know/type their own VPA.
     */
    fun parseVpa(scanned: String): Pair<String, String>? {
        val text = scanned.trim()
        if (text.isBlank()) return null
        val uri = runCatching { Uri.parse(text) }.getOrNull()
        val fromQuery = uri?.getQueryParameter("pa")?.takeIf { it.isNotBlank() }
            ?: runCatching { Uri.parse("upi://pay?$text").getQueryParameter("pa") }.getOrNull()?.takeIf { it.isNotBlank() }
        // Some bank-issued QR stickers encode nothing but the bare VPA, no upi:// wrapper at all.
        val pa = fromQuery ?: text.takeIf { Regex("^[\\w.\\-]+@[\\w.\\-]+$").matches(it) }
        if (pa.isNullOrBlank()) return null
        val pn = uri?.getQueryParameter("pn").orEmpty()
        return pa to pn
    }

    /** Encodes any text as a square QR bitmap, or null if it can't. */
    fun bitmap(content: String, size: Int = 640): Bitmap? = runCatching {
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        bmp
    }.getOrNull()
}
