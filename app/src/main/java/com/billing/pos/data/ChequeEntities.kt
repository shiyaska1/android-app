package com.billing.pos.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A cheque received or issued, tracked separately from the payment mode it was recorded under. */
@Entity(tableName = "cheque_entries")
data class ChequeEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chequeNo: String,
    val bankName: String = "",
    val partyName: String,
    val amount: Double,
    val dateMillis: Long,
    /** True = a cheque we received (from a customer); false = a cheque we issued (to a supplier). */
    val isReceived: Boolean,
    val status: String = ChequeStatus.PENDING,
    /** Optional link to the voucher this cheque belongs to (e.g. "RECEIPT"/id, "EXPENSE"/id). Blank/0 = standalone. */
    val linkedType: String = "",
    val linkedId: Long = 0,
    val remarks: String = "",
    val deviceId: String = ""
)

object ChequeStatus {
    const val PENDING = "Pending"
    const val DEPOSITED = "Deposited"
    const val CLEARED = "Cleared"
    const val BOUNCED = "Bounced"
    val ALL = listOf(PENDING, DEPOSITED, CLEARED, BOUNCED)
}
