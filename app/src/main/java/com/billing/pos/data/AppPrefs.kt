package com.billing.pos.data

import android.content.Context

data class CompanyInfo(val name: String, val address: String, val phone: String, val gstin: String = "")

/** Simple SharedPreferences store for the persisted session and company/print settings. */
class AppPrefs(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("pos_prefs", Context.MODE_PRIVATE)

    var loggedInUserId: Long
        get() = p.getLong("user_id", -1L)
        set(v) { p.edit().putLong("user_id", v).apply() }

    var companyName: String
        get() = p.getString("company_name", "My Shop") ?: "My Shop"
        set(v) { p.edit().putString("company_name", v).apply() }

    var companyAddress: String
        get() = p.getString("company_address", "") ?: ""
        set(v) { p.edit().putString("company_address", v).apply() }

    var companyPhone: String
        get() = p.getString("company_phone", "") ?: ""
        set(v) { p.edit().putString("company_phone", v).apply() }

    var companyGstin: String
        get() = p.getString("company_gstin", "") ?: ""
        set(v) { p.edit().putString("company_gstin", v).apply() }

    val company: CompanyInfo get() = CompanyInfo(companyName, companyAddress, companyPhone, companyGstin)

    // ---- licensing / trial ----
    var mobileNumber: String
        get() = p.getString("mobile", "") ?: ""
        set(v) { p.edit().putString("mobile", v).apply() }

    var installDateMillis: Long
        get() = p.getLong("install_date", 0L)
        set(v) { p.edit().putLong("install_date", v).apply() }

    var licensed: Boolean
        get() = p.getBoolean("licensed", false)
        set(v) { p.edit().putBoolean("licensed", v).apply() }

    // ---- thermal printer ----
    /** Bluetooth MAC address of the chosen thermal printer ("" = auto-pick). */
    var printerAddress: String
        get() = p.getString("printer_addr", "") ?: ""
        set(v) { p.edit().putString("printer_addr", v).apply() }

    var printerName: String
        get() = p.getString("printer_name", "") ?: ""
        set(v) { p.edit().putString("printer_name", v).apply() }

    /** Print/paper width for receipts & PDFs: "58mm", "80mm" or "A4". */
    var receiptWidth: String
        get() = p.getString("receipt_width", "58mm") ?: "58mm"
        set(v) { p.edit().putString("receipt_width", v).apply() }

    /** Absolute path to the company logo image ("" = none). Shown on A4 invoices. */
    var logoPath: String
        get() = p.getString("logo_path", "") ?: ""
        set(v) { p.edit().putString("logo_path", v).apply() }

    /** True if the logo image is a full-width banner (contains name/address itself). */
    var logoFullWidth: Boolean
        get() = p.getBoolean("logo_full_width", false)
        set(v) { p.edit().putBoolean("logo_full_width", v).apply() }

    companion object {
        val RECEIPT_WIDTHS = listOf("58mm", "80mm", "A4")
        /** Monospace columns for a given width. */
        fun colsFor(width: String): Int = when (width) { "80mm" -> 48; "A4" -> 64; else -> 32 }
        /** PDF page width in points for a given width (58mm ≈ 165pt). */
        fun pageWidthFor(width: String): Float = when (width) { "80mm" -> 227f; "A4" -> 560f; else -> 165f }
    }

    // ---- item batch tracking (batch no + expiry) ----
    var requireItemBatch: Boolean
        get() = p.getBoolean("require_item_batch", false)
        set(v) { p.edit().putBoolean("require_item_batch", v).apply() }

    /** Business vertical, drives medical (chemical content) and restaurant (sizes) features. */
    var businessType: String
        get() = p.getString("business_type", "") ?: ""
        set(v) { p.edit().putString("business_type", v).apply() }

    /** When on, a full-screen handwriting sticky-note canvas opens on launch (before the dashboard). */
    var stickyNoteOnLaunch: Boolean
        get() = p.getBoolean("sticky_note", false)
        set(v) { p.edit().putBoolean("sticky_note", v).apply() }

    /** When on, opening the app asks for the phone's own lock (PIN/pattern/fingerprint). */
    var appLock: Boolean
        get() = p.getBoolean("app_lock", false)
        set(v) { p.edit().putBoolean("app_lock", v).apply() }

    // ---- medical lab print assets ----
    /** Seal/stamp image path shown above the technician sign-off ("" = none). */
    var labSealPath: String
        get() = p.getString("lab_seal_path", "") ?: ""
        set(v) { p.edit().putString("lab_seal_path", v).apply() }

    /** Technician signature image path ("" = none). */
    var labSignaturePath: String
        get() = p.getString("lab_sign_path", "") ?: ""
        set(v) { p.edit().putString("lab_sign_path", v).apply() }

    /** Pre-printed letterhead (JPG/PNG/PDF) drawn as the page background ("" = none). */
    var labLetterheadPath: String
        get() = p.getString("lab_letterhead_path", "") ?: ""
        set(v) { p.edit().putString("lab_letterhead_path", v).apply() }

    /** Blank lines to skip at the TOP of a letterhead page before results start. */
    var labTopSkipLines: Int
        get() = p.getInt("lab_top_skip", 6)
        set(v) { p.edit().putInt("lab_top_skip", v).apply() }

    /** Blank lines to leave at the BOTTOM (above the printed footer) on a letterhead page. */
    var labBottomSkipLines: Int
        get() = p.getInt("lab_bottom_skip", 5)
        set(v) { p.edit().putInt("lab_bottom_skip", v).apply() }

    fun clearSession() { loggedInUserId = -1L }
}
