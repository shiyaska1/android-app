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

    /**
     * India GST mode. When on: item entry labels its tax field "GST %", invoices always
     * title "TAX INVOICE" and split the tax total into CGST + SGST (assumes intra-state
     * sales, the common case for a single-location shop), and print the customer's GSTIN
     * with a B2B/B2C tag based on whether one was entered for that customer.
     */
    var gstEnabled: Boolean
        get() = p.getBoolean("gst_enabled", false)
        set(v) { p.edit().putBoolean("gst_enabled", v).apply() }

    /**
     * Composition scheme dealer. A composition dealer legally cannot show CGST/SGST/IGST as a
     * separate line on the invoice — only visible when [gstEnabled] is also on. Invoices title
     * as "BILL OF SUPPLY" and the tax breakup is hidden entirely (only the grand total shows).
     */
    var compositionScheme: Boolean
        get() = p.getBoolean("gst_composition", false)
        set(v) { p.edit().putBoolean("gst_composition", v).apply() }

    /**
     * True (the default — matches every version of this app before the setting existed): item
     * prices already include tax+cess, so it's extracted out of the entered price. False: prices
     * exclude tax+cess, so it's added on top. Only Billing and Purchase respect this.
     */
    var priceIncludesTax: Boolean
        get() = p.getBoolean("price_includes_tax", true)
        set(v) { p.edit().putBoolean("price_includes_tax", v).apply() }

    /**
     * GST Compensation Cess (tobacco, aerated drinks, coal, luxury vehicles, ...) — off by
     * default since almost no business needs it. Only visible when [gstEnabled] is also on;
     * turning it on reveals a per-item Cess % field.
     */
    var cessEnabled: Boolean
        get() = p.getBoolean("gst_cess_enabled", false)
        set(v) { p.edit().putBoolean("gst_cess_enabled", v).apply() }

    /**
     * No Tax Invoice — off by default. Turning it on adds a per-sale "No Tax Invoice" switch to
     * the Billing screen: that invoice prints with no company header, no title, and no tax
     * shown (only date + bill no, then the same item table/totals as usual), uses its own
     * numbering series ([noTaxInvoicePrefix]), and is excluded from every GST/VAT report and
     * the main Invoice list — it only shows in the separate No Tax Invoices list.
     */
    var noTaxInvoiceEnabled: Boolean
        get() = p.getBoolean("no_tax_invoice_enabled", false)
        set(v) { p.edit().putBoolean("no_tax_invoice_enabled", v).apply() }

    /** Number-series prefix for No Tax Invoices, e.g. "NT-" giving NT-0001, NT-0002, ... */
    var noTaxInvoicePrefix: String
        get() = p.getString("no_tax_invoice_prefix", "NT-") ?: "NT-"
        set(v) { p.edit().putString("no_tax_invoice_prefix", v.trim()).apply() }

    /**
     * Weighing-scale barcodes — off by default. Turns on parsing "in-store" barcodes (a fixed
     * prefix digit + item PLU code + weight-or-price value + a standard EAN-13 check digit) so
     * scanning a scale-printed label during a sale looks the item up by its PLU and fills the
     * cart line's quantity (or price) straight from the label, no typing needed.
     */
    var weighScaleEnabled: Boolean
        get() = p.getBoolean("weigh_scale_enabled", false)
        set(v) { p.edit().putBoolean("weigh_scale_enabled", v).apply() }

    /** Leading digit that marks a barcode as scale-printed (GS1 reserves "2" for this — no real
     *  product barcode starts with it, so this is a safe, low-collision signal). */
    var weighScalePrefix: String
        get() = p.getString("weigh_scale_prefix", "2") ?: "2"
        set(v) { p.edit().putString("weigh_scale_prefix", v.trim().ifBlank { "2" }).apply() }

    /** Digits after the prefix that carry the item's PLU code — matched against that item's own
     *  Barcode field, so no separate PLU field is needed. */
    var weighScaleItemCodeLen: Int
        get() = p.getInt("weigh_scale_item_len", 5)
        set(v) { p.edit().putInt("weigh_scale_item_len", v.coerceIn(1, 9)).apply() }

    /** Digits after the item code that carry the weight or price value. */
    var weighScaleValueLen: Int
        get() = p.getInt("weigh_scale_value_len", 6)
        set(v) { p.edit().putInt("weigh_scale_value_len", v.coerceIn(1, 9)).apply() }

    /** False (default): the value is the weighed quantity in grams — the cart line's quantity is
     *  set from it and priced at the item's own rate. True: the value is the price (in paise) the
     *  scale already computed — the cart line's quantity is back-computed from the item's rate so
     *  price × qty still equals the scanned total. */
    var weighScaleValueIsPrice: Boolean
        get() = p.getBoolean("weigh_scale_value_is_price", false)
        set(v) { p.edit().putBoolean("weigh_scale_value_is_price", v).apply() }

    /** Physical label size for a dedicated barcode-label printer, in millimetres. Each printed
     *  label becomes its own PDF page at this size (rather than a multi-up A4 sheet) so it lines
     *  up with the printer's roll/gap settings. */
    var barcodeLabelWidthMm: Double
        get() = p.getFloat("barcode_label_w_mm", 40f).toDouble()
        set(v) { p.edit().putFloat("barcode_label_w_mm", v.toFloat()).apply() }
    var barcodeLabelHeightMm: Double
        get() = p.getFloat("barcode_label_h_mm", 25f).toDouble()
        set(v) { p.edit().putFloat("barcode_label_h_mm", v.toFloat()).apply() }
    var barcodeShowPrice: Boolean
        get() = p.getBoolean("barcode_show_price", true)
        set(v) { p.edit().putBoolean("barcode_show_price", v).apply() }
    var barcodeShowCompanyName: Boolean
        get() = p.getBoolean("barcode_show_company", false)
        set(v) { p.edit().putBoolean("barcode_show_company", v).apply() }
    var barcodeShowSize: Boolean
        get() = p.getBoolean("barcode_show_size", false)
        set(v) { p.edit().putBoolean("barcode_show_size", v).apply() }

    /** UPI ID (VPA) money is collected to, e.g. name@okaxis, and the payee name shown. */
    var upiId: String
        get() = p.getString("upi_id", "") ?: ""
        set(v) { p.edit().putString("upi_id", v.trim()).apply() }
    var upiName: String
        get() = p.getString("upi_name", "") ?: ""
        set(v) { p.edit().putString("upi_name", v.trim()).apply() }
    /** When on, a UPI payment QR for the bill total is drawn at the foot of invoices. */
    var showUpiQrOnPrint: Boolean
        get() = p.getBoolean("upi_qr_print", false)
        set(v) { p.edit().putBoolean("upi_qr_print", v).apply() }

    /**
     * When on (APK build only), a background service voice-records continuously and, every hour,
     * saves a diary entry titled with the date-time, of type "voice", with the recording attached.
     */
    var autoVoiceDiary: Boolean
        get() = p.getBoolean("auto_voice_diary", false)
        set(v) { p.edit().putBoolean("auto_voice_diary", v).apply() }

    // ---- LAN sync (two-counter, offline over WiFi) ----
    /** Short label for this device (e.g. "A"), prefixed onto bill numbers so two phones don't clash. */
    var deviceTag: String
        get() = (p.getString("sync_device_tag", "") ?: "").trim()
        set(v) { p.edit().putString("sync_device_tag", v.trim()).apply() }
    /** One-time: have this device's pre-existing (deviceId-less) records been tagged with its own id yet? */
    var legacyDeviceIdBackfilled: Boolean
        get() = p.getBoolean("legacy_device_id_backfilled", false)
        set(v) { p.edit().putBoolean("legacy_device_id_backfilled", v).apply() }
    /** TCP port this device's sync server listens on when acting as host. */
    var syncPort: Int
        get() = p.getInt("sync_port", 8765)
        set(v) { p.edit().putInt("sync_port", v).apply() }
    /** Last host IP a client connected to, so "Sync now" is one tap next time. */
    var syncHostIp: String
        get() = (p.getString("sync_host_ip", "") ?: "").trim()
        set(v) { p.edit().putString("sync_host_ip", v.trim()).apply() }
    /** When on, this device runs the sync server so other phones can reach it. */
    var syncHostMode: Boolean
        get() = p.getBoolean("sync_host_mode", false)
        set(v) { p.edit().putBoolean("sync_host_mode", v).apply() }
    /** When on, a client re-syncs with the host automatically every few seconds while the app is open. */
    var syncAuto: Boolean
        get() = p.getBoolean("sync_auto", false)
        set(v) { p.edit().putBoolean("sync_auto", v).apply() }

    // ---- Cloud backup sync (push/pull the backup zip to/from your own server) ----
    /** Where "Push" uploads the backup zip. The server should overwrite, not version, the file. */
    var backupPushUrl: String
        get() = (p.getString("backup_push_url", "") ?: "").trim()
        set(v) { p.edit().putString("backup_push_url", v.trim()).apply() }
    /** Where "Pull" downloads the backup zip from. */
    var backupPullUrl: String
        get() = (p.getString("backup_pull_url", "") ?: "").trim()
        set(v) { p.edit().putString("backup_pull_url", v.trim()).apply() }
    /** Identifies the organisation on a shared server (multiple businesses, one endpoint). */
    var backupOrgId: String
        get() = (p.getString("backup_org_id", "") ?: "").trim()
        set(v) { p.edit().putString("backup_org_id", v.trim()).apply() }
    /** Identifies this device. Blank = fall back to [License.deviceId] at push/pull time. */
    var backupDeviceId: String
        get() = (p.getString("backup_device_id", "") ?: "").trim()
        set(v) { p.edit().putString("backup_device_id", v.trim()).apply() }
    /** When off, Push skips photos/attachments — smaller, faster, and less likely to hit a host's upload limits. Data and settings still sync in full. */
    var backupPushIncludeAttachments: Boolean
        get() = p.getBoolean("backup_push_include_attachments", true)
        set(v) { p.edit().putBoolean("backup_push_include_attachments", v).apply() }
    /** When on, a background loop pulls the server backup, merges it in, then pushes — data only, no attachments — every [cloudAutoSyncIntervalSec] seconds while the app is open. */
    var cloudAutoSync: Boolean
        get() = p.getBoolean("cloud_auto_sync", false)
        set(v) { p.edit().putBoolean("cloud_auto_sync", v).apply() }
    /** Seconds between auto pull+merge+push cycles. Callers should floor this at ~10s before use so a mistyped value can't hammer the server. */
    var cloudAutoSyncIntervalSec: Int
        get() = p.getInt("cloud_auto_sync_interval_sec", 300)
        set(v) { p.edit().putInt("cloud_auto_sync_interval_sec", v).apply() }
    /** Only push records created/edited in the last N days (0 = push everything, the default).
     * Reduces upload size on a phone with years of history, at the cost of a device syncing for
     * the first time only receiving this recent window — use "Full resync now" for that case. */
    var cloudPushWindowDays: Int
        get() = p.getInt("cloud_push_window_days", 0)
        set(v) { p.edit().putInt("cloud_push_window_days", v.coerceAtLeast(0)).apply() }
    /** Result of the most recent sync attempt (manual or automatic), so the user can check what
     * happened without needing to catch a toast in the moment. */
    var lastCloudSyncAt: Long
        get() = p.getLong("last_cloud_sync_at", 0)
        set(v) { p.edit().putLong("last_cloud_sync_at", v).apply() }
    var lastCloudSyncOk: Boolean
        get() = p.getBoolean("last_cloud_sync_ok", true)
        set(v) { p.edit().putBoolean("last_cloud_sync_ok", v).apply() }
    var lastCloudSyncMessage: String
        get() = p.getString("last_cloud_sync_message", "") ?: ""
        set(v) { p.edit().putString("last_cloud_sync_message", v).apply() }

    // ---- Bulk SMS gateway (generic, provider-agnostic) ----
    /**
     * Send URL template with placeholders {number} {message} {apikey} {sender}. Example:
     * https://api.msg91.com/api/v5/flow/?authkey={apikey}&mobiles={number}&message={message}&sender={sender}
     */
    var smsGatewayUrl: String
        get() = (p.getString("sms_url", "") ?: "").trim()
        set(v) { p.edit().putString("sms_url", v.trim()).apply() }
    /** "GET" or "POST". */
    var smsGatewayMethod: String
        get() = (p.getString("sms_method", "GET") ?: "GET")
        set(v) { p.edit().putString("sms_method", v).apply() }
    var smsApiKey: String
        get() = (p.getString("sms_apikey", "") ?: "").trim()
        set(v) { p.edit().putString("sms_apikey", v.trim()).apply() }
    var smsSenderId: String
        get() = (p.getString("sms_sender", "") ?: "").trim()
        set(v) { p.edit().putString("sms_sender", v.trim()).apply() }
    /** Optional balance-check URL with {apikey} placeholder. */
    var smsBalanceUrl: String
        get() = (p.getString("sms_balance_url", "") ?: "").trim()
        set(v) { p.edit().putString("sms_balance_url", v.trim()).apply() }
    /** Default send channel: "Gateway" or "SIM" (SIM works in the APK build only). */
    var smsChannel: String
        get() = (p.getString("sms_channel", "Gateway") ?: "Gateway")
        set(v) { p.edit().putString("sms_channel", v).apply() }
    /**
     * Optional JSON request body for token-style APIs (e.g. LM6/gjinfotech). When non-blank the
     * gateway POSTs this JSON (Content-Type application/json) with {number} {message} {sender}
     * {apikey} filled in, instead of query/form parameters.
     */
    var smsJsonBody: String
        get() = (p.getString("sms_json_body", "") ?: "")
        set(v) { p.edit().putString("sms_json_body", v).apply() }
    /** When on, send Authorization: Bearer <API key> header (token APIs). */
    var smsBearer: Boolean
        get() = p.getBoolean("sms_bearer", false)
        set(v) { p.edit().putBoolean("sms_bearer", v).apply() }

    /** Gym training slots / batch times (a saved list, like customer types). */
    var gymSlots: List<String>
        get() = (p.getString("gym_slots", "") ?: "").split("|").map { it.trim() }.filter { it.isNotBlank() }
        set(v) { p.edit().putString("gym_slots", v.joinToString("|")).apply() }
    fun addGymSlot(name: String) {
        val n = name.trim()
        if (n.isBlank()) return
        if (gymSlots.none { it.equals(n, true) }) gymSlots = gymSlots + n
    }

    /** User-added customer types (a saved set, in addition to any already used by customers). */
    var customerTypes: List<String>
        get() = (p.getString("customer_types", "") ?: "").split("|").map { it.trim() }.filter { it.isNotBlank() }
        set(v) { p.edit().putString("customer_types", v.joinToString("|")).apply() }

    fun addCustomerType(name: String) {
        val n = name.trim()
        if (n.isBlank()) return
        if (customerTypes.none { it.equals(n, true) }) customerTypes = customerTypes + n
    }

    // ---- licensing / trial ----
    var mobileNumber: String
        get() = p.getString("mobile", "") ?: ""
        set(v) { p.edit().putString("mobile", v).apply() }

    var installDateMillis: Long
        get() = p.getLong("install_date", 0L)
        set(v) { p.edit().putLong("install_date", v).apply() }

    /**
     * Highest renewal milestone already activated (0 = none). An existing customer who
     * activated under the old single-key scheme counts as milestone 1, so they are not
     * asked again the moment they update.
     */
    var licensedMilestone: Int
        get() {
            val stored = p.getInt("licensed_milestone", 0)
            return if (stored == 0 && licensed) 1 else stored
        }
        set(v) { p.edit().putInt("licensed_milestone", v).apply() }

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

        const val OCR_ENGLISH = "English"
        const val OCR_MALAYALAM = "Malayalam"
        const val OCR_ARABIC = "Arabic"
        const val OCR_AUTO = "Auto"
        val OCR_LANGUAGES = listOf(OCR_ENGLISH, OCR_MALAYALAM, OCR_ARABIC, OCR_AUTO)
        /** Monospace columns for a given width. */
        fun colsFor(width: String): Int = when (width) { "80mm" -> 48; "A4" -> 64; else -> 32 }
        /** PDF page width in points for a given width (58mm ≈ 165pt). */
        fun pageWidthFor(width: String): Float = when (width) { "80mm" -> 227f; "A4" -> 560f; else -> 165f }
    }

    // ---- item batch tracking (batch no + expiry) ----
    var requireItemBatch: Boolean
        get() = p.getBoolean("require_item_batch", false)
        set(v) { p.edit().putBoolean("require_item_batch", v).apply() }

    /** When on (and batch tracking is on), sales auto-pick the oldest-expiry batch with stock instead of prompting. */
    var fifoAutoPickBatch: Boolean
        get() = p.getBoolean("fifo_auto_pick_batch", false)
        set(v) { p.edit().putBoolean("fifo_auto_pick_batch", v).apply() }

    /** Business vertical, drives medical (chemical content) and restaurant (sizes) features. */
    /**
     * True once the one-time business-type question has been answered. An install that
     * already has a business type set counts as onboarded, so existing users are never
     * asked.
     */
    var onboarded: Boolean
        get() = p.getBoolean("onboarded", false) || businessType.isNotBlank()
        set(v) { p.edit().putBoolean("onboarded", v).apply() }

    /** Personal mode hides the shop-keeping features, leaving the everyday tools. */
    val isPersonal: Boolean get() = businessType == "Personal"

    /** JSON of the most recent merge-restore, so its log can be reopened. */
    var lastMergeReport: String
        get() = p.getString("last_merge_report", "") ?: ""
        set(v) { p.edit().putString("last_merge_report", v).apply() }

    var businessType: String
        get() = p.getString("business_type", "") ?: ""
        set(v) { p.edit().putString("business_type", v).apply() }

    /**
     * Which script the camera/gallery OCR should read.
     * "English" = ML Kit Latin, "Malayalam" = Tesseract, "Auto" = try Latin then fall back.
     */
    var ocrLanguage: String
        get() = p.getString("ocr_language", OCR_ENGLISH) ?: OCR_ENGLISH
        set(v) { p.edit().putString("ocr_language", v).apply() }

    /** When on, a full-screen handwriting sticky-note canvas opens on launch (before the dashboard). */
    var stickyNoteOnLaunch: Boolean
        get() = p.getBoolean("sticky_note", false)
        set(v) { p.edit().putBoolean("sticky_note", v).apply() }

    /** When on, opening the app asks for the phone's own lock (PIN/pattern/fingerprint). */
    var appLock: Boolean
        get() = p.getBoolean("app_lock", false)
        set(v) { p.edit().putBoolean("app_lock", v).apply() }

    // ---- medical store: expiring-stock alerts ----
    /** Medical store only: warn about batches nearing expiry (daily notification + popup on open). */
    var expiryAlert: Boolean
        get() = p.getBoolean("expiry_alert", false)
        set(v) { p.edit().putBoolean("expiry_alert", v).apply() }

    /** How many days before expiry the warning starts. */
    var expiryAlertDays: Int
        get() = p.getInt("expiry_alert_days", 30)
        set(v) { p.edit().putInt("expiry_alert_days", v.coerceIn(1, 3650)).apply() }

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
