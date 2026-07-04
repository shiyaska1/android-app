package com.billing.pos.data

import android.util.Base64

/** Trial + license-key logic. The license key is Base64 of the registered mobile number. */
object License {
    const val TRIAL_DAYS = 30

    /** Where "Buy app" sends the user. Change this to your real purchase/contact link. */
    const val BUY_URL = "https://wa.me/910000000000?text=I%20want%20to%20buy%20POS%20Billing"

    fun expectedKey(mobile: String): String =
        Base64.encodeToString(mobile.trim().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    fun isValid(mobile: String, key: String): Boolean =
        mobile.isNotBlank() && key.trim() == expectedKey(mobile)

    private fun daysSince(installMillis: Long): Long {
        if (installMillis <= 0L) return 0L
        return (System.currentTimeMillis() - installMillis) / (1000L * 60 * 60 * 24)
    }

    fun trialExpired(installMillis: Long): Boolean = daysSince(installMillis) >= TRIAL_DAYS

    fun daysLeft(installMillis: Long): Int =
        (TRIAL_DAYS - daysSince(installMillis)).toInt().coerceIn(0, TRIAL_DAYS)
}
