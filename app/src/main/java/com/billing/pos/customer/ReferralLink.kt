package com.billing.pos.customer

import android.net.Uri
import com.billing.pos.data.AppPrefs

/**
 * Builds the exact same install link (and Play Store referrer payload) as the shop owner's
 * standalone `server/customer-link-builder.html` tool — but from inside the customer app itself,
 * using whatever shop is currently open. Lets any customer invite a friend to this same shop in
 * one tap instead of asking the shop owner to run the builder tool for them.
 */
object ReferralLink {
    private const val PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=com.billing.pos&pcampaignid=web_share"

    /** Null if this install isn't pointed at a shop yet (shouldn't happen once past onboarding,
     *  since a shop code/catalog URL is required to reach the catalog screen this is shared from). */
    fun build(prefs: AppPrefs): String? {
        val shop = prefs.shopCode
        val url = prefs.onlineCatalogUrl
        if (shop.isBlank() || url.isBlank()) return null
        val referrerParts = mutableListOf(
            "mode=customer",
            "shop=" + Uri.encode(shop),
            "url=" + Uri.encode(url)
        )
        val type = prefs.shopDisplayCategory.ifBlank { prefs.customerBusinessType }
        if (type.isNotBlank()) referrerParts += "type=" + Uri.encode(type)
        if (prefs.customerPremiumShop) referrerParts += "premium=1"
        val referrer = referrerParts.joinToString("&")
        val sep = if (PLAY_STORE_URL.contains("?")) "&" else "?"
        return PLAY_STORE_URL + sep + "referrer=" + Uri.encode(referrer)
    }

    /**
     * The same install link, built from the SHOP OWNER's own Settings (shop code, catalog URL,
     * business type) instead of an already-onboarded customer's — so an owner can generate and
     * share their own QR/link with a walk-in customer, no cost and no need to ask the developer.
     * [shop] is passed in rather than read from [prefs] directly since the Settings screen already
     * falls back to the Device ID when the shop-code field is still blank. Deliberately never adds
     * "premium=1": the order-with-photo premium tier stays a paid, developer-issued add-on (see
     * server/customer-link-builder.html's Premium checkbox) — a shop owner can't grant it to
     * themselves for free from here.
     */
    fun buildForOwner(prefs: AppPrefs, shop: String): String? {
        val url = prefs.onlineCatalogUrl
        if (shop.isBlank() || url.isBlank()) return null
        val referrerParts = mutableListOf(
            "mode=customer",
            "shop=" + Uri.encode(shop),
            "url=" + Uri.encode(url)
        )
        val type = prefs.businessType
        if (type.isNotBlank()) referrerParts += "type=" + Uri.encode(type)
        val referrer = referrerParts.joinToString("&")
        val sep = if (PLAY_STORE_URL.contains("?")) "&" else "?"
        return PLAY_STORE_URL + sep + "referrer=" + Uri.encode(referrer)
    }

    /**
     * The iPhone equivalent of [buildForOwner] — a plain link straight to wherever the shop owner
     * hosted server/ios/pos_customer_app_ios.php (see [AppPrefs.iosCustomerAppUrl]), not a Play
     * Store install link: an iPhone customer just opens it in Safari, no app store involved.
     * Same shop/url/type query params as the standalone server/ios/customer-link-builder-ios.html
     * tool builds, appended directly (not wrapped in a "referrer=" param — that's an Android/Play
     * Store-only mechanism). Never adds "premium=1" for the same reason as [buildForOwner].
     */
    fun buildForOwnerIos(prefs: AppPrefs, shop: String): String? {
        val base = prefs.iosCustomerAppUrl
        val url = prefs.onlineCatalogUrl
        if (base.isBlank() || shop.isBlank() || url.isBlank()) return null
        val parts = mutableListOf(
            "shop=" + Uri.encode(shop),
            "url=" + Uri.encode(url)
        )
        val type = prefs.businessType
        if (type.isNotBlank()) parts += "type=" + Uri.encode(type)
        val sep = if (base.contains("?")) "&" else "?"
        return base + sep + parts.joinToString("&")
    }
}
