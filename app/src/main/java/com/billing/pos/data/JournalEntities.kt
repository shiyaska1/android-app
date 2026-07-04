package com.billing.pos.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A journal voucher header. */
@Entity(tableName = "journal_entries")
data class JournalEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val voucherNo: String,
    val dateMillis: Long,
    val narration: String = "",
    val source: String = ""
)

/** One posting line of a journal voucher (a debit or a credit to an account head). */
@Entity(tableName = "journal_lines")
data class JournalLine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entryId: Long,
    val headId: Long,
    val headName: String,
    val amount: Double,
    val isDebit: Boolean
)
