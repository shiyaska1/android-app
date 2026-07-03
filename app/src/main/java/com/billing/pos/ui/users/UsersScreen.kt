package com.billing.pos.ui.users

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.Repository
import com.billing.pos.data.Role
import com.billing.pos.data.User
import com.billing.pos.ui.billing.collectAsStateSafe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class UsersViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)

    val users: StateFlow<List<User>> =
        repo.users.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }

    fun create(user: User, password: String, onDone: () -> Unit) {
        if (user.username.isBlank() || password.isBlank()) { message.value = "Username and password required"; return }
        viewModelScope.launch {
            val result = repo.createUser(user, password)
            if (result.isSuccess) { message.value = "User created"; onDone() }
            else message.value = "Username already exists"
        }
    }

    fun delete(user: User) {
        if (user.role == Role.SUPER_USER) { message.value = "Cannot delete a super user"; return }
        viewModelScope.launch { repo.deleteUser(user); message.value = "User deleted" }
    }
}

/** Default permission set implied by a role (super user may still tweak). */
private fun defaultPerms(role: Role): User = when (role) {
    Role.SUPER_USER -> User(username = "", passwordHash = "", role = role,
        canCreateInvoice = true, canEditInvoice = true, canDeleteInvoice = true,
        canExport = true, canImport = true, canManageUsers = true)
    Role.ADMIN -> User(username = "", passwordHash = "", role = role,
        canCreateInvoice = true, canEditInvoice = true, canDeleteInvoice = true,
        canExport = true, canImport = true, canManageUsers = false)
    Role.SALESMAN -> User(username = "", passwordHash = "", role = role,
        canCreateInvoice = true, canEditInvoice = false, canDeleteInvoice = false,
        canExport = true, canImport = false, canManageUsers = false)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsersScreen(
    onBack: () -> Unit,
    vm: UsersViewModel = viewModel()
) {
    val users by vm.users.collectAsStateSafe()
    var showCreate by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Manage Users") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add user")
            }
        }
    ) { pad ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(12.dp)
        ) {
            items(users, key = { it.id }) { u ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(u.username, fontWeight = FontWeight.Bold)
                        Text(u.role.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        Text(permSummary(u), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                    if (u.role != Role.SUPER_USER) {
                        IconButton(onClick = { vm.delete(u) }) {
                            Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                Divider()
            }
        }
    }

    if (showCreate) {
        CreateUserDialog(
            onDismiss = { showCreate = false },
            onCreate = { user, pwd -> vm.create(user, pwd) { showCreate = false } }
        )
    }
}

private fun permSummary(u: User): String {
    val perms = buildList {
        if (u.canCreateInvoice) add("create")
        if (u.canEditInvoice) add("edit")
        if (u.canDeleteInvoice) add("delete")
        if (u.canExport) add("export")
        if (u.canImport) add("import")
        if (u.canManageUsers) add("users")
    }
    return if (perms.isEmpty()) "no permissions" else perms.joinToString(", ")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateUserDialog(
    onDismiss: () -> Unit,
    onCreate: (User, String) -> Unit
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(Role.SALESMAN) }
    var perms by remember { mutableStateOf(defaultPerms(Role.SALESMAN)) }
    var roleExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New User") },
        text = {
            Column {
                OutlinedTextField(
                    value = username, onValueChange = { username = it },
                    label = { Text("Username *") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text("Password *") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )

                ExposedDropdownMenuBox(
                    expanded = roleExpanded,
                    onExpandedChange = { roleExpanded = it },
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    OutlinedTextField(
                        readOnly = true,
                        value = role.label,
                        onValueChange = {},
                        label = { Text("Role") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(roleExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    androidx.compose.material3.ExposedDropdownMenu(
                        expanded = roleExpanded,
                        onDismissRequest = { roleExpanded = false }
                    ) {
                        Role.values().forEach { r ->
                            DropdownMenuItem(
                                text = { Text(r.label) },
                                onClick = {
                                    role = r
                                    perms = defaultPerms(r)   // reset toggles to role defaults
                                    roleExpanded = false
                                }
                            )
                        }
                    }
                }

                Text("Permissions", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp))
                PermRow("Create invoice", perms.canCreateInvoice) { perms = perms.copy(canCreateInvoice = it) }
                PermRow("Edit invoice", perms.canEditInvoice) { perms = perms.copy(canEditInvoice = it) }
                PermRow("Delete invoice", perms.canDeleteInvoice) { perms = perms.copy(canDeleteInvoice = it) }
                PermRow("Export / share", perms.canExport) { perms = perms.copy(canExport = it) }
                PermRow("Import data", perms.canImport) { perms = perms.copy(canImport = it) }
                PermRow("Manage users", perms.canManageUsers) { perms = perms.copy(canManageUsers = it) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onCreate(perms.copy(username = username.trim(), role = role), password)
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun PermRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label)
        Checkbox(checked = checked, onCheckedChange = onChange)
    }
}
