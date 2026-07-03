package com.billing.pos.auth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.billing.pos.data.User

/** In-memory logged-in user. Cleared on app restart (login required each launch). */
object Session {
    var current by mutableStateOf<User?>(null)
        private set

    fun login(user: User) { current = user }
    fun logout() { current = null }

    val isLoggedIn: Boolean get() = current != null

    // Permission helpers (safe when logged out → false).
    val canCreate: Boolean get() = current?.canCreateInvoice == true
    val canEdit: Boolean get() = current?.canEditInvoice == true
    val canDelete: Boolean get() = current?.canDeleteInvoice == true
    val canExport: Boolean get() = current?.canExport == true
    val canImport: Boolean get() = current?.canImport == true
    val canManageUsers: Boolean get() = current?.canManageUsers == true

    /** Label attached to bills this user creates, so imports can be traced. */
    val sourceLabel: String get() = current?.username ?: ""
}
