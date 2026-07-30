package com.billing.pos.ui.billing

/** One line handed from a source document (order, delivery note, quotation) into a new sales bill. */
data class BillPrefillLine(
    val itemId: Long, val name: String, val qty: Double, val price: Double, val unit: String,
    val taxPercent: Double = 0.0
)

/**
 * One-shot hand-off of a customer's saved documents into a new sales bill: the billing screen
 * reads it on open, fills the customer and every line, and clears it. [sourceKind]/[sourceIds]
 * (e.g. "delivery" / the delivery note ids) let the billing screen mark those source documents
 * as converted once the bill this hand-off produced is actually saved.
 */
object OrderToBillLink {
    @Volatile var customerId: Long = 0
    @Volatile var customerName: String = ""
    @Volatile var lines: List<BillPrefillLine> = emptyList()
    @Volatile var sourceKind: String = ""
    @Volatile var sourceIds: List<Long> = emptyList()

    val hasData: Boolean get() = lines.isNotEmpty()

    data class Taken(
        val customerId: Long, val customerName: String, val lines: List<BillPrefillLine>,
        val sourceKind: String, val sourceIds: List<Long>
    )

    fun set(id: Long, name: String, l: List<BillPrefillLine>, kind: String = "", ids: List<Long> = emptyList()) {
        customerId = id; customerName = name; lines = l; sourceKind = kind; sourceIds = ids
    }
    fun take(): Taken {
        val r = Taken(customerId, customerName, lines, sourceKind, sourceIds)
        customerId = 0; customerName = ""; lines = emptyList(); sourceKind = ""; sourceIds = emptyList()
        return r
    }
}
