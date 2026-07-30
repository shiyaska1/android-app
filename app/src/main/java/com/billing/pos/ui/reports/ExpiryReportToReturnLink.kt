package com.billing.pos.ui.reports

/** One line handed from the Expiry Report into a new purchase return. */
data class ReturnPrefillLine(
    val itemId: Long,
    val name: String,
    val batchNo: String,
    val qty: Double,
    val price: Double,
    val taxPercent: Double,
    val unit: String = "",
    val primaryPerUnit: Double = 1.0
)

/** One supplier's worth of returned batches. */
data class ReturnGroup(val supplierId: Long, val supplierName: String, val lines: List<ReturnPrefillLine>)

/**
 * One-shot hand-off of expiring batches (ticked on the Expiry Report, one bill can only have one
 * supplier) into new purchase returns: the purchase return screen reads it on open, fills the
 * supplier and lines for one group, and — since a selection can span several suppliers — keeps
 * advancing through the remaining groups (one per saved return) until the queue is empty.
 */
object ExpiryReportToReturnLink {
    @Volatile var groups: List<ReturnGroup> = emptyList()

    val hasMore: Boolean get() = groups.isNotEmpty()

    fun set(g: List<ReturnGroup>) { groups = g }

    fun takeNext(): ReturnGroup? {
        val g = groups.firstOrNull()
        if (g != null) groups = groups.drop(1)
        return g
    }
}
