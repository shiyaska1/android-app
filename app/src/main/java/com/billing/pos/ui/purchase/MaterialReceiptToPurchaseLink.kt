package com.billing.pos.ui.purchase

import com.billing.pos.ui.billing.BillPrefillLine

/**
 * One-shot hand-off of a supplier's material receipts into a new purchase entry: the purchase
 * screen reads it on open, fills the supplier and every received line, and clears it. [sourceIds]
 * let the purchase screen mark those receipts as converted once the purchase is actually saved.
 */
object MaterialReceiptToPurchaseLink {
    @Volatile var supplierId: Long = 0
    @Volatile var supplierName: String = ""
    @Volatile var lines: List<BillPrefillLine> = emptyList()
    @Volatile var sourceIds: List<Long> = emptyList()

    val hasData: Boolean get() = lines.isNotEmpty()

    data class Taken(val supplierId: Long, val supplierName: String, val lines: List<BillPrefillLine>, val sourceIds: List<Long>)

    fun set(id: Long, name: String, l: List<BillPrefillLine>, ids: List<Long>) {
        supplierId = id; supplierName = name; lines = l; sourceIds = ids
    }
    fun take(): Taken {
        val r = Taken(supplierId, supplierName, lines, sourceIds)
        supplierId = 0; supplierName = ""; lines = emptyList(); sourceIds = emptyList()
        return r
    }
}
