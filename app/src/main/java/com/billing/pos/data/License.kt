package com.billing.pos.data

import android.content.Context
import android.provider.Settings
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Trial + device-locked activation.
 *
 * The activation key is derived from the device id with a keyed HMAC-SHA256, so it can't be
 * forged without [SECRET]. To activate a customer: they read you their Device ID from the
 * expiry screen, you compute the key (any HMAC-SHA256 tool: key = SECRET, message = the Device
 * ID exactly as shown, take the first 16 hex chars, upper-case), and they type it in.
 */
object License {
    const val TRIAL_DAYS = 30

    // ---- Support / purchase contact, shown wherever a licence key is needed ----
    /** WhatsApp number in international form, no "+" (wa.me wants it that way). */
    const val SUPPORT_WHATSAPP = "919961128378"
    /** Same number, formatted for display and for a plain dial link. */
    const val SUPPORT_PHONE = "+919961128378"
    const val SUPPORT_EMAIL = "shiyaska2009@gmail.com"

    /** Where "Buy app" sends the user — a WhatsApp chat with the support number. */
    const val BUY_URL = "https://wa.me/$SUPPORT_WHATSAPP?text=I%20want%20to%20buy%20POS%20Billing"

    /** WhatsApp chat pre-filled with the device id, so the licence key can be issued straight away. */
    fun buyUrlFor(deviceId: String): String {
        val msg = java.net.URLEncoder.encode(
            "I want to buy POS Billing. My Device ID is $deviceId", "UTF-8"
        )
        return "https://wa.me/$SUPPORT_WHATSAPP?text=$msg"
    }

    /** >>> CHANGE THIS to your own private secret before publishing. Keep it secret. <<< */
    private const val SECRET = "POSB-change-this-secret-2024"

    /** Stable per-device identifier (Android ID), shown to the user for activation. */
    fun deviceId(context: Context): String {
        val id = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        return (if (id.isBlank()) "UNKNOWNDEVICE" else id).uppercase()
    }

    /** Activation key for a device id: first 16 hex chars of HMAC-SHA256(secret, id), grouped. */
    fun activationKey(deviceId: String): String {
        val hex = hmacHex(deviceId.trim().uppercase()).take(16).uppercase()
        return hex.chunked(4).joinToString("-")
    }

    /** True when [key] matches the device's activation key (dashes/spaces/case ignored). */
    fun isValid(deviceId: String, key: String): Boolean {
        val norm = key.uppercase().replace(Regex("[^0-9A-F]"), "")
        return norm.isNotEmpty() && norm == activationKey(deviceId).replace("-", "")
    }

    private fun hmacHex(message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(SECRET.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun daysSince(installMillis: Long): Long {
        if (installMillis <= 0L) return 0L
        return (System.currentTimeMillis() - installMillis) / (1000L * 60 * 60 * 24)
    }

    fun trialExpired(installMillis: Long): Boolean = daysSince(installMillis) >= TRIAL_DAYS

    fun daysLeft(installMillis: Long): Int =
        (TRIAL_DAYS - daysSince(installMillis)).toInt().coerceIn(0, TRIAL_DAYS)
}
