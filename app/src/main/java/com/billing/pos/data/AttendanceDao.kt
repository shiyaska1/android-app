package com.billing.pos.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AttendanceDao {
    @Query("SELECT * FROM employees ORDER BY name COLLATE NOCASE")
    fun observeEmployees(): Flow<List<Employee>>

    @Query("SELECT * FROM employees")
    suspend fun allEmployees(): List<Employee>

    @Query("SELECT * FROM employees WHERE id = :id LIMIT 1")
    suspend fun employeeById(id: Long): Employee?

    @Insert
    suspend fun insertEmployee(e: Employee): Long

    @Update
    suspend fun updateEmployee(e: Employee)

    @Delete
    suspend fun deleteEmployee(e: Employee)

    @Query("SELECT * FROM attendance ORDER BY timeMillis DESC")
    fun observeAttendance(): Flow<List<AttendanceRecord>>

    @Query("SELECT * FROM attendance")
    suspend fun allAttendance(): List<AttendanceRecord>

    /** Latest punch for an employee, to decide whether the next is IN or OUT. */
    @Query("SELECT * FROM attendance WHERE employeeId = :employeeId ORDER BY timeMillis DESC LIMIT 1")
    suspend fun lastPunch(employeeId: Long): AttendanceRecord?

    @Insert
    suspend fun insertAttendance(r: AttendanceRecord): Long

    @Delete
    suspend fun deleteAttendance(r: AttendanceRecord)
}
