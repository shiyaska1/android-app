package com.billing.pos.customer

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.billing.pos.data.AppPrefs
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
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

    data class Shop(
        val shop: String, val url: String, val type: String, val premium: Boolean, val name: String = "",
        /** The shop owner's own category (Settings > Business type), carried in the catalog
         *  fetch response — always current, unlike [type] which is just whatever the install
         *  link said once. Used to group the "Browse shops" directory. */
        val category: String = "",
        val address: String = "",
        val phone: String = "",
        val bannerImage: String = "",
        /** When this shop's catalog was last fetched (ms since epoch), 0 if never. Lets
         *  [switchTo] show a switched-to shop's cached snapshot instantly, and the UI show
         *  "Updated ..." per shop instead of forcing a refetch on every switch. */
        val lastFetchedAt: Long = 0,
        /** This shop's own UPI ID/payee name (carried in its catalog fetch response) — kept per
         *  shop, not just in the single "current shop" AppPrefs field, so paying a bill from a
         *  notification a *different* known shop sent (see NotificationsDialog's "Pay via UPI
         *  now") always uses that shop's own VPA, never whichever shop happens to be active right
         *  now. Blank when that shop has no UPI ID set. */
        val upi: String = "",
        val upiName: String = ""
    )

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
     *  "switch back" works) and points prefs at the new shop — same as a fresh customer install
     *  minus the reinstall. Unlike before, this no longer wipes the local item cache or forces a
     *  refetch: if [target] has been visited before, its last-fetched items and shop details show
     *  immediately from the cache; the caller only needs to fetch when there's genuinely nothing
     *  cached yet (see [AppPrefs.catalogLastFetchedAt] after this returns). */
    suspend fun switchTo(context: Context, target: Shop) {
        val prefs = AppPrefs(context)
        val previous = Shop(
            shop = prefs.shopCode, url = prefs.onlineCatalogUrl,
            type = prefs.customerBusinessType, premium = prefs.customerPremiumShop,
            name = prefs.shopDisplayName, category = prefs.shopDisplayCategory, address = prefs.shopDisplayAddress,
            phone = prefs.shopContactPhone, bannerImage = prefs.shopBannerImage, lastFetchedAt = prefs.catalogLastFetchedAt,
            upi = prefs.shopUpiId, upiName = prefs.shopUpiName
        )
        if (previous.shop.isNotBlank()) {
            // Keep the outgoing shop's directory entry current before leaving it, so switching
            // back later shows its latest snapshot rather than whatever it looked like at its
            // last catalog fetch.
            rememberKnown(context, previous)
            if (previous.shop != target.shop) rememberRecent(prefs, previous)
        }
        removeRecent(prefs, target.shop)

        prefs.shopCode = target.shop
        prefs.onlineCatalogUrl = target.url
        prefs.customerBusinessType = target.type

        // Restore this shop's own cached snapshot (if any) instead of blanking to "" — the item
        // cache itself is untouched by a switch (see ShopCatalogDao.replaceForShop), so a shop
        // visited before shows its items and details instantly, no network round trip needed.
        val cached = unpack(prefs.customerKnownShops).find { it.shop == target.shop }
        // A shop reached via "Browse shops"/recent (not a fresh QR/referral scan) carries no real
        // premium info of its own — target.premium is just false there by construction — so never
        // let that silently downgrade a premium status this shop already earned from an earlier
        // scan. Either source saying premium is enough; only a genuine never-premium shop stays false.
        prefs.customerPremiumShop = target.premium || (cached?.premium ?: false)
        prefs.shopDisplayName = cached?.name ?: target.name
        prefs.shopContactPhone = cached?.phone.orEmpty()
        prefs.shopBannerImage = cached?.bannerImage.orEmpty()
        prefs.shopDisplayCategory = (cached?.category ?: target.category).ifBlank { target.type }
        prefs.shopDisplayAddress = cached?.address ?: target.address
        prefs.catalogLastFetchedAt = cached?.lastFetchedAt ?: 0L
        prefs.shopUpiId = cached?.upi.orEmpty()
        prefs.shopUpiName = cached?.upiName.orEmpty()
    }

    fun recent(context: Context): List<Shop> = unpack(AppPrefs(context).customerRecentShops)

    /** Every shop this device has ever connected to (current one included) — see [rememberKnown].
     *  Unlike [recent] (capped at 5, excludes the current shop, only for the quick "switch back"
     *  list), this is the full directory the "Browse shops" screen groups by category. */
    fun known(context: Context): List<Shop> = unpack(AppPrefs(context).customerKnownShops)

    /** Upserts [shop] into the full known-shops directory — call after every successful catalog
     *  fetch so the current shop's name/category/address stay fresh there too, not just in prefs. */
    fun rememberKnown(context: Context, shop: Shop) {
        if (shop.shop.isBlank() || shop.url.isBlank()) return
        val prefs = AppPrefs(context)
        val existing = unpack(prefs.customerKnownShops)
        val result = (listOf(shop) + existing.filter { it.shop != shop.shop }).take(30)
        prefs.customerKnownShops = pack(result)
    }

    private fun pack(shops: List<Shop>): String = JSONArray().apply {
        shops.forEach { shop ->
            put(JSONObject().apply {
                put("shop", shop.shop); put("url", shop.url); put("type", shop.type)
                put("premium", shop.premium); put("name", shop.name)
                put("category", shop.category); put("address", shop.address)
                put("phone", shop.phone); put("bannerImage", shop.bannerImage)
                put("lastFetchedAt", shop.lastFetchedAt)
                put("upi", shop.upi); put("upiName", shop.upiName)
            })
        }
    }.toString()

    private fun unpack(json: String): List<Shop> {
        val arr = runCatching { JSONArray(json.ifBlank { "[]" }) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Shop(
                shop = o.optString("shop"), url = o.optString("url"),
                type = o.optString("type"), premium = o.optBoolean("premium"), name = o.optString("name"),
                category = o.optString("category"), address = o.optString("address"),
                phone = o.optString("phone"), bannerImage = o.optString("bannerImage"),
                lastFetchedAt = o.optLong("lastFetchedAt", 0L),
                upi = o.optString("upi"), upiName = o.optString("upiName")
            ).takeIf { it.shop.isNotBlank() && it.url.isNotBlank() }
        }
    }

    private fun rememberRecent(prefs: AppPrefs, shop: Shop) {
        val existing = unpack(prefs.customerRecentShops).filter { it.shop != shop.shop }
        prefs.customerRecentShops = pack((listOf(shop) + existing).take(5))
    }

    /** Decodes a QR code from a still image (e.g. a screenshot shared over WhatsApp, or a photo
     *  of a printed QR) — an alternative to the live camera scan for when the shop's QR only
     *  exists as a picture. Same ZXing approach used elsewhere in the app for photo barcodes. */
    fun decodeFromBitmap(bitmap: Bitmap): String? {
        val hints = mapOf(DecodeHintType.TRY_HARDER to true)
        val w = bitmap.width; val h = bitmap.height
        if (w <= 0 || h <= 0) return null
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val source = RGBLuminanceSource(w, h, pixels)
        return runCatching { MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text }.getOrNull()
            ?: runCatching { MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source.invert())), hints).text }.getOrNull()
    }

    private fun removeRecent(prefs: AppPrefs, shopCode: String) {
        prefs.customerRecentShops = pack(unpack(prefs.customerRecentShops).filter { it.shop != shopCode })
    }

    /** Shop codes muted via [setMuted] — see [AppPrefs.customerMutedShops]. */
    fun mutedShops(context: Context): Set<String> {
        val arr = runCatching { JSONArray(AppPrefs(context).customerMutedShops.ifBlank { "[]" }) }.getOrNull() ?: return emptySet()
        return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }.toSet()
    }

    fun isMuted(context: Context, shop: String): Boolean = shop.isNotBlank() && shop in mutedShops(context)

    /** Stops (or resumes) fetching/showing notifications from [shop] — the catalog and ordering
     *  for that shop are unaffected, only its notifications stop arriving. */
    fun setMuted(context: Context, shop: String, muted: Boolean) {
        if (shop.isBlank()) return
        val prefs = AppPrefs(context)
        val current = mutedShops(context)
        val updated = if (muted) current + shop else current - shop
        prefs.customerMutedShops = JSONArray(updated.toList()).toString()
    }
}
