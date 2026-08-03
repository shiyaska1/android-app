package com.billing.pos.data

import androidx.room.Entity
import androidx.room.PrimaryKey

object RecurringFrequency {
    const val DAILY = "DAILY"
    const val WEEKLY = "WEEKLY"
    const val MONTHLY = "MONTHLY"
    const val YEARLY = "YEARLY"
    val ALL = listOf(DAILY, WEEKLY, MONTHLY, YEARLY)
    fun label(v: String) = when (v) {
        DAILY -> "Daily"; WEEKLY -> "Weekly"; YEARLY -> "Yearly"; else -> "Monthly"
    }
}

/** A recurring journal template (rent, EMI, ...) — generates a real [JournalEntry] each time it
 * comes due, rather than being posted itself. */
@Entity(tableName = "recurring_journals")
data class RecurringJournal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val narration: String = "",
    val frequency: String = RecurringFrequency.MONTHLY,
    val startDate: Long,
    /** Next occurrence still to be posted; advances by [frequency] each time one is generated. */
    val nextDueDate: Long,
    /** 0 = no end date. */
    val endDate: Long = 0,
    val active: Boolean = true,
    val lastGeneratedAt: Long = 0,
    val occurrencesGenerated: Int = 0
)

@Entity(tableName = "recurring_journal_lines")
data class RecurringJournalLine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val templateId: Long,
    val headId: Long,
    val headName: String,
    val amount: Double,
    val isDebit: Boolean
)
