package com.billing.pos.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RecurringJournalDao {
    @Query("SELECT * FROM recurring_journals ORDER BY nextDueDate ASC")
    fun observeAll(): Flow<List<RecurringJournal>>

    @Query("SELECT * FROM recurring_journals WHERE active = 1 AND nextDueDate <= :now")
    suspend fun due(now: Long): List<RecurringJournal>

    @Query("SELECT * FROM recurring_journal_lines WHERE templateId = :id")
    suspend fun linesFor(id: Long): List<RecurringJournalLine>

    @Insert suspend fun insertTemplate(t: RecurringJournal): Long
    @Insert suspend fun insertLines(lines: List<RecurringJournalLine>)
    @Update suspend fun updateTemplate(t: RecurringJournal)
    @Query("DELETE FROM recurring_journal_lines WHERE templateId = :id") suspend fun deleteLines(id: Long)
    @Delete suspend fun deleteTemplate(t: RecurringJournal)

    @Transaction
    suspend fun saveTemplate(t: RecurringJournal, lines: List<RecurringJournalLine>): Long {
        val id = insertTemplate(t)
        insertLines(lines.map { it.copy(id = 0, templateId = id) })
        return id
    }

    @Transaction
    suspend fun updateTemplateWithLines(t: RecurringJournal, lines: List<RecurringJournalLine>) {
        updateTemplate(t)
        deleteLines(t.id)
        insertLines(lines.map { it.copy(id = 0, templateId = t.id) })
    }

    @Transaction
    suspend fun deleteTemplateWithLines(t: RecurringJournal) {
        deleteLines(t.id)
        deleteTemplate(t)
    }
}
