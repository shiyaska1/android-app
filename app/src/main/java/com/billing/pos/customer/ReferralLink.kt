package com.billing.pos.customer

import android.net.Uri
import com.billing.pos.data.AppPrefs

/**
 * Builds the exact same install link (and Play Store referrer payload) as the shop owner's
 * standalone `tools/customer-link-builder.html` tool — but from inside the customer app itself,
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
}
