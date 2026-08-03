package com.billing.pos.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Marks one ledger posting (to a Cash/Bank account head) as matched against a bank statement.
 * The ledger itself has no single source table — postings are assembled on the fly from bills,
 * purchases, receipts, expenses and journal lines by [com.billing.pos.report.AccountingEngine] —
 * so a posting is identified here by its natural composite key (account + voucher + date +
 * signed amount) rather than a foreign key into any one of those tables.
 */
@Entity(tableName = "bank_reconciliations")
data class BankReconciliation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val headId: Long,
    val vch: String,
    val dateMillis: Long,
    /** Signed net of the posting (debit − credit), so a Dr and a Cr on the same voucher/date/head don't collide. */
    val amount: Double,
    val reconciledAt: Long = System.currentTimeMillis()
)
