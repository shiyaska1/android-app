package com.billing.pos.ui.customers

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.Customer
import com.billing.pos.data.Repository
import com.billing.pos.ui.billing.collectAsStateSafe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CustomersViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)

    val customers: StateFlow<List<Customer>> =
        repo.customers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }

    fun save(existing: Customer?, name: String, phone: String, address: String, gstin: String, onDone: () -> Unit) {
        if (name.isBlank()) { message.value = "Enter a name"; return }
        viewModelScope.launch {
            if (existing == null) repo.addCustomer(name, phone, address, gstin)
            else repo.updateCustomer(existing.copy(name = name.trim(), phone = phone.trim(), address = address.trim(), gstin = gstin.trim()))
            message.value = "Saved"
            onDone()
        }
    }

    fun delete(customer: Customer) {
        viewModelScope.launch {
            val result = repo.deleteCustomer(customer)
            message.value = if (result.isSuccess) "Customer deleted"
            else result.exceptionOrNull()?.message ?: "Cannot delete"
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomersScreen(
    onBack: () -> Unit,
    vm: CustomersViewModel = viewModel()
) {
    val snackbar = remember { SnackbarHostState() }
    val customers by vm.customers.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()

    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    var showDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Customer?>(null) }
    var deleteFor by remember { mutableStateOf<Customer?>(null) }
    // Picking customers to share. Off by default so the list stays a plain list.
    var selecting by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<Long>() }
    val context = androidx.compose.ui.platform.LocalContext.current

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Customers") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (selecting) {
                        Text("${selected.size}", fontWeight = FontWeight.Bold)
                        IconButton(
                            onClick = {
                                val chosen = customers.filter { it.id in selected }
                                if (chosen.isNotEmpty()) {
                                    com.billing.pos.util.ShareText.share(
                                        context, customerShareText(chosen), "Customer details"
                                    )
                                }
                            },
                            enabled = selected.isNotEmpty()
                        ) { Icon(Icons.Filled.Share, "Share selected") }
                        IconButton(onClick = { selecting = false; selected.clear() }) {
                            Icon(Icons.Filled.Close, "Cancel selection")
                        }
                    } else {
                        IconButton(onClick = { selecting = true }) {
                            Icon(Icons.Filled.Share, "Share customers")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = null; showDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add customer")
            }
        }
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp)) {
            items(customers, key = { it.id }) { c ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable {
                            if (selecting) {
                                if (c.id in selected) selected.remove(c.id) else selected.add(c.id)
                            } else { editing = c; showDialog = true }
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (selecting) {
                        androidx.compose.material3.Checkbox(
                            checked = c.id in selected,
                            onCheckedChange = {
                                if (c.id in selected) selected.remove(c.id) else selected.add(c.id)
                            }
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(c.name + if (c.isDefault) "  (default)" else "", fontWeight = FontWeight.Bold)
                        val sub = listOf(c.phone, c.address).filter { it.isNotBlank() }.joinToString("  •  ")
                        if (sub.isNotBlank()) {
                            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                    if (!c.isDefault && !selecting) {
                        IconButton(onClick = { deleteFor = c }) {
                            Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                Divider()
            }
        }
    }

    if (showDialog) {
        CustomerDialog(
            existing = editing,
            onDismiss = { showDialog = false },
            onSave = { name, phone, addr, gstin -> vm.save(editing, name, phone, addr, gstin) { showDialog = false } }
        )
    }
    deleteFor?.let { c ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Delete ${c.name}?") },
            text = { Text("Only customers with no invoices can be deleted.") },
            confirmButton = { TextButton(onClick = { vm.delete(c); deleteFor = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun CustomerDialog(
    existing: Customer?,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String) -> Unit
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var phone by remember { mutableStateOf(existing?.phone ?: "") }
    var address by remember { mutableStateOf(existing?.address ?: "") }
    var gstin by remember { mutableStateOf(existing?.gstin ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New customer" else "Edit customer") },
        text = {
            Column {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name *") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = phone, onValueChange = { phone = it },
                    label = { Text("Phone / WhatsApp") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                OutlinedTextField(
                    value = gstin, onValueChange = { gstin = it },
                    label = { Text("GSTIN / TIN") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                OutlinedTextField(
                    value = address, onValueChange = { address = it },
                    label = { Text("Address") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, phone, address, gstin) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** The chosen customers as plain text, ready to paste into WhatsApp. */
private fun customerShareText(customers: List<Customer>): String = buildString {
    customers.forEachIndexed { i, c ->
        if (i > 0) appendLine()
        appendLine(c.name)
        if (c.phone.isNotBlank()) appendLine("Phone: " + c.phone)
        if (c.address.isNotBlank()) appendLine("Address: " + c.address)
        if (c.gstin.isNotBlank()) appendLine("GSTIN: " + c.gstin)
    }
}
