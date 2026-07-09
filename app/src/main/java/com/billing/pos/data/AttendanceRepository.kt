package com.billing.pos.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

class AttendanceRepository(context: Context) {
    private val dao = AppDatabase.get(context).attendanceDao()

    val employees: Flow<List<Employee>> = dao.observeEmployees()
    val attendance: Flow<List<AttendanceRecord>> = dao.observeAttendance()

    suspend fun allEmployees(): List<Employee> = dao.allEmployees()
    suspend fun employeeById(id: Long): Employee? = dao.employeeById(id)

    suspend fun upsertEmployee(e: Employee): Long =
        if (e.id == 0L) dao.insertEmployee(e) else { dao.updateEmployee(e); e.id }

    suspend fun deleteEmployee(e: Employee) {
        if (e.photoPath.isNotBlank()) runCatching { java.io.File(e.photoPath).delete() }
        dao.deleteEmployee(e)
    }

    /** Records a punch, auto-choosing IN/OUT from the employee's last punch. */
    suspend fun punch(employee: Employee, timeMillis: Long): String {
        val last = dao.lastPunch(employee.id)
        val type = if (last?.type == "IN") "OUT" else "IN"
        dao.insertAttendance(
            AttendanceRecord(employeeId = employee.id, employeeName = employee.name, timeMillis = timeMillis, type = type)
        )
        return type
    }

    suspend fun punchExplicit(employee: Employee, timeMillis: Long, type: String) {
        dao.insertAttendance(
            AttendanceRecord(employeeId = employee.id, employeeName = employee.name, timeMillis = timeMillis, type = type)
        )
    }

    suspend fun deleteAttendance(r: AttendanceRecord) = dao.deleteAttendance(r)
}
