package com.billing.pos.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class AttachmentType { IMAGE, VIDEO, AUDIO, DOCUMENT, LOCATION }

/** A personal diary entry with optional reminder. */
@Entity(tableName = "diary_entries")
data class DiaryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val remarks: String,
    val createdAt: Long,
    val updatedAt: Long,
    val reminderEnabled: Boolean = false,
    /** For one-time reminders: exact date+time. For daily: the time-of-day used. */
    val reminderAt: Long = 0,
    val reminderDaily: Boolean = false
)

/** A file attached to a diary entry (copied into app storage). */
@Entity(tableName = "diary_attachments")
data class DiaryAttachment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entryId: Long,
    val path: String,
    val name: String,
    val mime: String,
    val type: AttachmentType
)
