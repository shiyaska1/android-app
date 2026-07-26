package com.billing.pos.report

import com.billing.pos.data.AccountGroup
import com.billing.pos.data.AccountHead
import com.billing.pos.data.AccountNature
import com.billing.pos.data.Bill
import com.billing.pos.data.Expense
import com.billing.pos.data.JournalEntry
import com.billing.pos.data.JournalLine
import com.billing.pos.data.Purchase
import com.billing.pos.data.Receipt

/** One balanced posting derived from a source document. */
data class Posting(
    val head: String,
    val group: String,
    val nature: AccountNature,
    val debit: Double,
    val credit: Double,
    val date: Long
)

/**
 * Derives a balanced double-entry view from all source documents, so the Trial Balance, P&L and
 * Balance Sheet reconcile even though the app stores documents (not journal vouchers) as the truth.
 *
 * Conventions per document:
 *  - Sales bill: Dr party (credit) or Cash/Bank (paid); Cr Sales (net) + Cr Output Tax.
 *  - Purchase:   Dr Purchase (net) + Dr Input Tax; Cr party (credit) or Cash/Bank (paid).
 *  - Receipt:    Dr Cash/Bank; Cr customer.
 *  - Payment:    Dr supplier (against purchase) or Indirect Expenses; Cr Cash/Bank.
 *  - Manual journal lines and head opening balances are included as-is.
 */
object AccountingEngine {

    private const val OPENING_DATE = Long.MIN_VALUE

    fun build(
        heads: List<AccountHead>,
        groups: List<AccountGroup>,
        bills: List<Bill>,
        purchases: List<Purchase>,
        receipts: List<Receipt>,
        expenses: List<Expense>,
        jEntries: List<JournalEntry>,
        jLines: List<JournalLine>
    ): List<Posting> {
        val groupById = groups.associateBy { it.id }
        val headById = heads.associateBy { it.id }
        val out = ArrayList<Posting>()

        fun natureOfGroup(name: String): AccountNature =
            groups.firstOrNull { it.name == name }?.nature ?: AccountNature.ASSET

        // Cash/Bank head for a payment mode or an explicit account id.
        fun cashBank(accountId: Long, mode: String): Triple<String, String, AccountNature> {
            headById[accountId]?.let { h ->
                val g = groupById[h.groupId]
                return Triple(h.name, g?.name ?: "Cash-in-hand", g?.nature ?: AccountNature.ASSET)
            }
            val (nm, grp) = when (mode) {
                "UPI" -> "UPI" to "Cash-in-hand"
                "Card" -> "Card" to "Cash-in-hand"
                "Cheque" -> "Cheque" to "Cash-in-hand"
                "Bank" -> "Bank" to "Bank Accounts"
                else -> "Cash" to "Cash-in-hand"
            }
            return Triple(nm, grp, AccountNature.ASSET)
        }

        // ---- opening balances ----
        heads.forEach { h ->
            if (h.openingBalance != 0.0) {
                val g = groupById[h.groupId]
                out.add(
                    Posting(h.name, g?.name ?: "", g?.nature ?: AccountNature.ASSET,
                        if (h.openingIsDebit) h.openingBalance else 0.0,
                        if (h.openingIsDebit) 0.0 else h.openingBalance, OPENING_DATE)
                )
            }
        }

        // ---- sales bills ----
        bills.forEach { b ->
            val salesNet = b.subTotal + b.additionalCharge - b.discount
            if (b.paymentMethod == "Credit") {
                out.add(Posting(b.customerName, "Sundry Debtors", AccountNature.ASSET, b.grandTotal, 0.0, b.dateMillis))
            } else {
                val (nm, grp, nat) = cashBank(0, b.paymentMethod)
                out.add(Posting(nm, grp, nat, b.grandTotal, 0.0, b.dateMillis))
            }
            out.add(Posting("Sales", "Sales Account", natureOfGroup("Sales Account"), 0.0, salesNet, b.dateMillis))
            if (b.taxTotal != 0.0)
                out.add(Posting("Output Tax (GST/VAT)", "Duties & Taxes", natureOfGroup("Duties & Taxes"), 0.0, b.taxTotal, b.dateMillis))
        }

        // ---- purchases ----
        purchases.forEach { p ->
            val purNet = p.subTotal + p.additionalCharge - p.discount
            out.add(Posting("Purchase", "Purchase Account", natureOfGroup("Purchase Account"), purNet, 0.0, p.dateMillis))
            if (p.taxTotal != 0.0)
                out.add(Posting("Input Tax (GST/VAT)", "Duties & Taxes", natureOfGroup("Duties & Taxes"), p.taxTotal, 0.0, p.dateMillis))
            if (p.paymentMethod == "Credit") {
                out.add(Posting(p.supplierName, "Sundry Creditors", AccountNature.LIABILITY, 0.0, p.grandTotal, p.dateMillis))
            } else {
                val (nm, grp, nat) = cashBank(0, p.paymentMethod)
                out.add(Posting(nm, grp, nat, 0.0, p.grandTotal, p.dateMillis))
            }
        }

        // ---- receipts (money in) ----
        receipts.forEach { r ->
            val (nm, grp, nat) = cashBank(r.toAccountId, r.paymentMode)
            out.add(Posting(nm, grp, nat, r.amount, 0.0, r.dateMillis))
            val party = r.payFrom.ifBlank { r.customerName }.ifBlank { "Sundry Debtors" }
            out.add(Posting(party, "Sundry Debtors", AccountNature.ASSET, 0.0, r.amount, r.dateMillis))
        }

        // ---- payments / expenses (money out) ----
        expenses.forEach { e ->
            val (nm, grp, nat) = cashBank(e.fromAccountId, e.paymentMode)
            if (e.purchaseId > 0 && e.payTo.isNotBlank()) {
                out.add(Posting(e.payTo, "Sundry Creditors", AccountNature.LIABILITY, e.amount, 0.0, e.dateMillis))
            } else {
                out.add(Posting(e.description.ifBlank { "Indirect Expenses" }, "Indirect Expenses", natureOfGroup("Indirect Expenses"), e.amount, 0.0, e.dateMillis))
            }
            out.add(Posting(nm, grp, nat, 0.0, e.amount, e.dateMillis))
        }

        // ---- manual journal lines ----
        val entryById = jEntries.associateBy { it.id }
        jLines.forEach { l ->
            val e = entryById[l.entryId] ?: return@forEach
            val h = headById[l.headId]
            val g = h?.let { groupById[it.groupId] }
            out.add(
                Posting(l.headName, g?.name ?: "", g?.nature ?: AccountNature.ASSET,
                    if (l.isDebit) l.amount else 0.0, if (!l.isDebit) l.amount else 0.0, e.dateMillis)
            )
        }

        return out
    }
}
