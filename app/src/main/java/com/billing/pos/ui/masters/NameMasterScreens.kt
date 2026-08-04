package com.billing.pos.ui.masters

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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.Repository
import com.billing.pos.ui.billing.collectAsStateSafe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ItemCategoryMasterViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)
    private val prefs = AppPrefs(app)
    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }

    /** Name -> how many items currently use it. */
    val entries: StateFlow<List<Pair<String, Int>>> = repo.items.map { items ->
        val counts = items.filter { it.category.isNotBlank() }.groupingBy { it.category.trim() }.eachCount()
        val names = (prefs.itemCategories + counts.keys).distinct().sortedBy { it.lowercase() }
        names.map { it to (counts[it] ?: 0) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun add(name: String) {
        val n = name.trim()
        if (n.isBlank()) { message.value = "Enter a name"; return }
        prefs.addItemCategory(n)
        message.value = "Added"
    }

    fun rename(old: String, newName: String) {
        val n = newName.trim()
        if (n.isBlank() || n.equals(old, true)) return
        viewModelScope.launch {
            prefs.itemCategories = prefs.itemCategories.map { if (it.equals(old, true)) n else it }.distinct()
            val toUpdate = repo.itemsAll().filter { it.category.equals(old, true) }
            toUpdate.forEach { repo.updateItem(it.copy(category = n)) }
            message.value = "Renamed to $n" + (if (toUpdate.isNotEmpty()) " (${toUpdate.size} item(s) updated)" else "")
        }
    }

    fun delete(name: String) {
        viewModelScope.launch {
            prefs.itemCategories = prefs.itemCategories.filterNot { it.equals(name, true) }
            val toClear = repo.itemsAll().filter { it.category.equals(name, true) }
            toClear.forEach { repo.updateItem(it.copy(category = "")) }
            message.value = "Deleted" + (if (toClear.isNotEmpty()) " (cleared from ${toClear.size} item(s))" else "")
        }
    }
}

class CustomerTypeMasterViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)
    private val prefs = AppPrefs(app)
    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }

    val entries: StateFlow<List<Pair<String, Int>>> = repo.customers.map { customers ->
        val counts = customers.filter { it.customerType.isNotBlank() }.groupingBy { it.customerType.trim() }.eachCount()
        val names = (listOf("General") + prefs.customerTypes + counts.keys).distinct().sortedBy { it.lowercase() }
        names.map { it to (counts[it] ?: 0) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun add(name: String) {
        val n = name.trim()
        if (n.isBlank()) { message.value = "Enter a name"; return }
        prefs.addCustomerType(n)
        message.value = "Added"
    }

    fun rename(old: String, newName: String) {
        val n = newName.trim()
        if (n.isBlank() || n.equals(old, true)) return
        viewModelScope.launch {
            prefs.customerTypes = prefs.customerTypes.map { if (it.equals(old, true)) n else it }.distinct()
            val toUpdate = repo.customersAll().filter { it.customerType.equals(old, true) }
            toUpdate.forEach { repo.updateCustomer(it.copy(customerType = n)) }
            message.value = "Renamed to $n" + (if (toUpdate.isNotEmpty()) " (${toUpdate.size} customer(s) updated)" else "")
        }
    }

    fun delete(name: String) {
        viewModelScope.launch {
            prefs.customerTypes = prefs.customerTypes.filterNot { it.equals(name, true) }
            val toReset = repo.customersAll().filter { it.customerType.equals(name, true) }
            toReset.forEach { repo.updateCustomer(it.copy(customerType = "General")) }
            message.value = "Deleted" + (if (toReset.isNotEmpty()) " (${toReset.size} customer(s) set back to General)" else "")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemCategoryMasterScreen(onBack: () -> Unit, vm: ItemCategoryMasterViewModel = viewModel()) {
    val entries by vm.entries.collectAsStateSafe()
    NameMasterScreen(
        title = "Item Categories",
        countLabel = { n -> if (n == 1) "1 item" else "$n items" },
        entries = entries,
        message = vm.message,
        onConsumeMessage = vm::consumeMessage,
        onAdd = vm::add,
        onRename = vm::rename,
        onDelete = vm::delete,
        onBack = onBack
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerTypeMasterScreen(onBack: () -> Unit, vm: CustomerTypeMasterViewModel = viewModel()) {
    val entries by vm.entries.collectAsStateSafe()
    NameMasterScreen(
        title = "Customer Types",
        countLabel = { n -> if (n == 1) "1 customer" else "$n customers" },
        entries = entries,
        message = vm.message,
        onConsumeMessage = vm::consumeMessage,
        onAdd = vm::add,
        onRename = vm::rename,
        onDelete = vm::delete,
        onBack = onBack
    )
}

/** Shared list/add/edit/delete UI for a simple named master (item categories, customer types, …)
 *  — a name plus how many records currently use it. Renaming or deleting updates every one of
 *  those records too, so the master and the actual data never drift apart. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NameMasterScreen(
    title: String,
    countLabel: (Int) -> String,
    entries: List<Pair<String, Int>>,
    message: MutableStateFlow<String?>,
    onConsumeMessage: () -> Unit,
    onAdd: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val msg by message.collectAsStateSafe()
    LaunchedEffect(msg) { msg?.let { snackbar.showSnackbar(it); onConsumeMessage() } }

    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) { Icon(Icons.Filled.Add, contentDescription = "Add") }
        }
    ) { pad ->
        if (entries.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(pad), verticalArrangement = Arrangement.Center) {
                Text(
                    "Nothing here yet — tap + to add one.",
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp)) {
                items(entries, key = { it.first }) { (name, count) ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(name, fontWeight = FontWeight.Bold)
                            Text(countLabel(count), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                        IconButton(onClick = { editing = name }) { Icon(Icons.Filled.Edit, "Rename") }
                        IconButton(onClick = { pendingDelete = name }) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                    }
                    Divider()
                }
            }
        }
    }

    if (showAdd) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("New name") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = { onAdd(name); showAdd = false }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Cancel") } }
        )
    }

    editing?.let { old ->
        var name by remember(old) { mutableStateOf(old) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Rename \"$old\"") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("New name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = { onRename(old, name); editing = null }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } }
        )
    }

    pendingDelete?.let { name ->
        val count = entries.firstOrNull { it.first == name }?.second ?: 0
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete \"$name\"?") },
            text = {
                Text(
                    if (count > 0) "Currently used by ${countLabel(count)}. They'll be cleared/reset, not deleted."
                    else "Not currently in use."
                )
            },
            confirmButton = {
                TextButton(onClick = { onDelete(name); pendingDelete = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }
}
