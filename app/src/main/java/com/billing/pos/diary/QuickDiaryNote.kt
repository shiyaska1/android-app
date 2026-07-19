package com.billing.pos.diary

import android.content.Context
import com.billing.pos.data.DiaryEntry
import com.billing.pos.data.DiaryRepository

/**
 * Saves a one-line note straight into the diary, without opening the diary screen.
 *
 * Used by the counter tools — the mobile-number board and the fast-bill tape — so a number
 * or a calculation is kept automatically instead of being lost when the popup closes.
 */
object QuickDiaryNote {

    /** Writes [title] / [body] as a new diary entry and returns its id (0 on failure). */
    suspend fun save(context: Context, title: String, body: String): Long = runCatching {
        val now = System.currentTimeMillis()
        DiaryRepository(context).upsert(
            DiaryEntry(
                title = title.take(120),
                remarks = body,
                createdAt = now,
                updatedAt = now
            )
        )
    }.getOrDefault(0L)
}
