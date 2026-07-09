package com.billing.pos.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A staff member enrolled for face attendance. [embedding] is a CSV of the face vector. */
@Entity(tableName = "employees")
data class Employee(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phone: String = "",
    val role: String = "",
    /** Comma-separated face-embedding floats (empty if not enrolled). */
    val embedding: String = "",
    val photoPath: String = "",
    val active: Boolean = true
)

/** One attendance punch (Time In / Time Out) for an employee. */
@Entity(tableName = "attendance")
data class AttendanceRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val employeeId: Long,
    val employeeName: String,
    val timeMillis: Long,
    /** "IN" or "OUT". */
    val type: String
)
