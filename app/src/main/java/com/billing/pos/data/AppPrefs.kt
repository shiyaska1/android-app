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

    fun clearSession() { loggedInUserId = -1L }
}
