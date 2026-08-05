package com.billing.pos.customer

import android.content.Context
import android.net.Uri
import com.billing.pos.data.AppDatabase
import com.billing.pos.data.AppPrefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lets an already-installed customer app point itself at a different shop, without
 * uninstalling — by scanning the SAME QR code / Play Store link a shop hands out for a fresh
 * install (see [InstallReferrer] for the equivalent at-install-time path; both read the same
 * `mode=customer&shop=...&url=...&type=...&premium=1` payload). Also keeps a short "recent
 * shops" list so switching back doesn't need a re-scan.
 */
object ShopSwitch {

    data class Shop(val shop: String, val url: String, val type: String, val premium: Boolean, val name: String = "")

    /** Accepts either a full Play Store link (extracts its `referrer` param) or a bare
     *  referrer-style query string — same encoding [InstallReferrer] parses at install time. */
    fun parse(scanned: String): Shop? {
        val text = scanned.trim()
        if (text.isBlank()) return null
        val referrerRaw = runCatching { Uri.parse(text).getQueryParameter("referrer") }.getOrNull()
        val queryString = referrerRaw ?: text
        val uri = runCatching { Uri.parse("https://x/?$queryString") }.getOrNull() ?: return null
        val map = uri.queryParameterNames.associateWith { uri.getQueryParameter(it).orEmpty() }
        val shop = map["shop"]?.takeIf { it.isNotBlank() } ?: return null
        val url = map["url"]?.takeIf { it.isNotBlank() } ?: return null
        return Shop(
            shop = shop,
            url = url,
            type = map["type"].orEmpty(),
            premium = map["premium"] == "1" || map["premium"] == "true"
        )
    }

    /** Switches the app to [target]: remembers whatever shop it was pointed at before (so
     *  "switch back" works), clears the cached catalog, points prefs at the new shop, same as a
     *  fresh customer install minus the reinstall. Caller is responsible for re-fetching after. */
    suspend fun switchTo(context: Context, target: Shop) {
        val prefs = AppPrefs(context)
        val previous = Shop(
            shop = prefs.shopCode, url = prefs.onlineCatalogUrl,
            type = prefs.customerBusinessType, premium = prefs.customerPremiumShop,
            name = prefs.shopDisplayName
        )
        if (previous.shop.isNotBlank() && previous.shop != target.shop) {
            rememberRecent(prefs, previous)
        }
        removeRecent(prefs, target.shop)

        prefs.shopCode = target.shop
        prefs.onlineCatalogUrl = target.url
        prefs.customerBusinessType = target.type
        prefs.customerPremiumShop = target.premium
        prefs.catalogLastFetchedAt = 0L
        prefs.shopDisplayName = ""
        prefs.shopContactPhone = ""

        AppDatabase.get(context).shopCatalogDao().deleteAll()
    }

    fun recent(context: Context): List<Shop> {
        val arr = runCatching { JSONArray(AppPrefs(context).customerRecentShops.ifBlank { "[]" }) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Shop(
                shop = o.optString("shop"), url = o.optString("url"),
                type = o.optString("type"), premium = o.optBoolean("premium"), name = o.optString("name")
            ).takeIf { it.shop.isNotBlank() && it.url.isNotBlank() }
        }
    }

    private fun rememberRecent(prefs: AppPrefs, shop: Shop) {
        val entry = JSONObject().apply {
            put("shop", shop.shop); put("url", shop.url); put("type", shop.type)
            put("premium", shop.premium); put("name", shop.name)
        }
        val existing = runCatching { JSONArray(prefs.customerRecentShops.ifBlank { "[]" }) }.getOrElse { JSONArray() }
        val result = JSONArray().apply {
            put(entry)
            for (i in 0 until existing.length()) {
                if (length() >= 5) break
                val o = existing.optJSONObject(i) ?: continue
                if (o.optString("shop") != shop.shop) put(o)
            }
        }
        prefs.customerRecentShops = result.toString()
    }

    private fun removeRecent(prefs: AppPrefs, shopCode: String) {
        val existing = runCatching { JSONArray(prefs.customerRecentShops.ifBlank { "[]" }) }.getOrElse { JSONArray() }
        val filtered = JSONArray().apply {
            for (i in 0 until existing.length()) {
                val o = existing.optJSONObject(i) ?: continue
                if (o.optString("shop") != shopCode) put(o)
            }
        }
        prefs.customerRecentShops = filtered.toString()
    }
}
