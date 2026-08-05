package com.billing.pos.ui.masters

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.billing.pos.data.SavedCalc
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

class CalcLabelMasterViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)
    private val prefs = AppPrefs(app)
    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }

    /** Name -> how many saved calculations currently carry an entry with that label. */
    val entries: StateFlow<List<Pair<String, Int>>> = repo.savedCalcs.map { calcs ->
        val counts = HashMap<String, Int>()
        calcs.forEach { c -> c.labelList.filter { it.isNotBlank() }.forEach { l -> counts[l] = (counts[l] ?: 0) + 1 } }
        val names = (prefs.calcLabels + counts.keys).distinct().sortedBy { it.lowercase() }
        names.map { it to (counts[it] ?: 0) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun add(name: String) {
        val n = name.trim()
        if (n.isBlank()) { message.value = "Enter a name"; return }
        prefs.addCalcLabel(n)
        viewModelScope.launch { repo.ensureItemForLabel(n) }
        message.value = "Added"
    }

    fun rename(old: String, newName: String) {
        val n = newName.trim()
        if (n.isBlank() || n.equals(old, true)) return
        viewModelScope.launch {
            prefs.calcLabels = prefs.calcLabels.map { if (it.equals(old, true)) n else it }.distinct()
            val toUpdate = repo.savedCalcsAll().filter { c -> c.labelList.any { it.equals(old, true) } }
            toUpdate.forEach { c ->
                val newLabels = c.labelList.map { l -> if (l.equals(old, true)) n else l }
                repo.saveCalc(c.copy(labels = SavedCalc.packLabels(newLabels)))
            }
            message.value = "Renamed to $n" + (if (toUpdate.isNotEmpty()) " (${toUpdate.size} calculation(s) updated)" else "")
        }
    }

    fun delete(name: String) {
        viewModelScope.launch {
            prefs.calcLabels = prefs.calcLabels.filterNot { it.equals(name, true) }
            val toUpdate = repo.savedCalcsAll().filter { c -> c.labelList.any { it.equals(name, true) } }
            toUpdate.forEach { c ->
                val newLabels = c.labelList.map { l -> if (l.equals(name, true)) "" else l }
                repo.saveCalc(c.copy(labels = SavedCalc.packLabels(newLabels)))
            }
            message.value = "Deleted" + (if (toUpdate.isNotEmpty()) " (cleared from ${toUpdate.size} calculation(s))" else "")
        }
    }

    /** Imports labels from an .xlsx/.csv file — first column of each row (after the header),
     *  skipping names already in the master. Each imported label also becomes a sellable item,
     *  same as typing it fresh in the calculator's label popup. */
    fun importSpreadsheet(uri: android.net.Uri) {
        viewModelScope.launch {
            val rows = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.billing.pos.data.SpreadsheetImport.readRaw(getApplication(), uri)
            }
            val names = rows.drop(1).mapNotNull { it.firstOrNull()?.trim() }.filter { it.isNotBlank() }
            if (names.isEmpty()) { message.value = "No label rows found in the file"; return@launch }
            var added = 0; var skipped = 0
            names.distinct().forEach { n ->
                if (prefs.calcLabels.none { it.equals(n, true) }) {
                    prefs.addCalcLabel(n); repo.ensureItemForLabel(n); added++
                } else skipped++
            }
            message.value = "Imported $added label(s)" + if (skipped > 0) ", skipped $skipped existing" else ""
        }
    }

    /** Writes a blank import template (CSV, one header + one sample row) to Downloads. */
    fun downloadTemplate() {
        viewModelScope.launch {
            val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val f = java.io.File(getApplication<Application>().cacheDir, "label-import-template.csv")
                f.writeText("Label\nSample label\n")
                f
            }
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.billing.pos.data.DownloadSaver.save(getApplication(), file, file.name, "text/csv")
            }
            message.value = if (ok) "Template saved to Downloads: ${file.name}" else "Could not save template"
        }
    }

    /** The current labels as an .xlsx file (one "Label" column), for the Download Excel action. */
    fun buildLabelsXlsx(names: List<String>): java.io.File {
        val file = java.io.File(getApplication<Application>().cacheDir, "calculator-labels.xlsx")
        val rows = listOf(com.billing.pos.data.XlsxWriter.row(com.billing.pos.data.XlsxWriter.text("Label"))) +
            names.map { com.billing.pos.data.XlsxWriter.row(com.billing.pos.data.XlsxWriter.text(it)) }
        com.billing.pos.data.XlsxWriter.write(file, "Labels", rows)
        return file
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalcLabelMasterScreen(
    onBack: () -> Unit,
    onOpenHistory: (String) -> Unit,
    vm: CalcLabelMasterViewModel = viewModel()
) {
    val entries by vm.entries.collectAsStateSafe()
    val downloadXlsx = com.billing.pos.ui.common.rememberXlsxDownloader { msg -> vm.message.value = msg }
    val filePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.importSpreadsheet(uri) }
    var menu by remember { mutableStateOf(false) }
    NameMasterScreen(
        title = "Calculator Labels",
        countLabel = { n -> if (n == 1) "1 calculation" else "$n calculations" },
        entries = entries,
        message = vm.message,
        onConsumeMessage = vm::consumeMessage,
        onAdd = vm::add,
        onRename = vm::rename,
        onDelete = vm::delete,
        onBack = onBack,
        onEntryClick = onOpenHistory,
        actions = {
            IconButton(onClick = { downloadXlsx { vm.buildLabelsXlsx(entries.map { it.first }) } }) {
                Icon(Icons.Filled.GridOn, contentDescription = "Download Excel", tint = MaterialTheme.colorScheme.onPrimary)
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.DocumentScanner, contentDescription = "Import", tint = MaterialTheme.colorScheme.onPrimary)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Import Excel / CSV") },
                        onClick = {
                            menu = false
                            filePicker.launch(arrayOf(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                "application/vnd.ms-excel", "text/csv", "text/comma-separated-values",
                                "application/octet-stream", "*/*"
                            ))
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Download blank template") },
                        onClick = { menu = false; vm.downloadTemplate() }
                    )
                }
            }
        }
    )
}

/** Shared list/add/edit/delete UI for a simple named master (item categories, customer types, …)
 *  — a name plus how many records currently use it. Renaming or deleting updates every one of
 *  those records too, so the master and the actual data never drift apart. [onEntryClick], when
 *  given, makes the row itself (not its edit/delete icons) open something else — the calculator
 *  labels master uses it for "see who bought this". [actions] adds extra top-bar buttons (the
 *  calculator labels master's Excel import/export). */
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
    onBack: () -> Unit,
    onEntryClick: ((String) -> Unit)? = null,
    actions: (@Composable () -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val msg by message.collectAsStateSafe()
    LaunchedEffect(msg) { msg?.let { snackbar.showSnackbar(it); onConsumeMessage() } }

    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    val shown = remember(entries, search) {
        entries.filter { search.isBlank() || it.first.contains(search.trim(), ignoreCase = true) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = { actions?.invoke() },
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
        Column(Modifier.fillMaxSize().padding(pad)) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("Search") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            )
            if (shown.isEmpty()) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                    Text(
                        if (entries.isEmpty()) "Nothing here yet — tap + to add one." else "No match for \"$search\"",
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    items(shown, key = { it.first }) { (name, count) ->
                        Row(
                            Modifier.fillMaxWidth()
                                .let { m -> if (onEntryClick != null) m.clickable { onEntryClick(name) } else m }
                                .padding(vertical = 8.dp),
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

/** One-shot hand-off of which label to show — a nav route argument would need URL-encoding
 *  free-typed label text (slashes, spaces, …); a static holder avoids that entirely, same
 *  pattern as [com.billing.pos.ui.billing.FastBillLink]. */
object CalcLabelNav {
    @Volatile var label: String = ""
}

/** Who bought a given label, and when — up to the last 100 tapes that used it, newest first,
 *  reached by tapping the label in its master. Tapping a row re-opens the calculator loaded
 *  with that whole tape (amounts, labels, customer, narration), via [FastBillDialog]'s
 *  initialCalcId. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalcLabelHistoryScreen(onBack: () -> Unit) {
    val label = remember { CalcLabelNav.label }
    val context = androidx.compose.ui.platform.LocalContext.current
    val repo = remember { Repository(context) }
    val savedCalcs by repo.savedCalcs.collectAsState(initial = emptyList<SavedCalc>())
    var customerSearch by remember { mutableStateOf("") }
    var dateRangeOn by remember { mutableStateOf(false) }
    var fromMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var toMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var showCalc by remember { mutableStateOf(false) }
    var openCalcId by remember { mutableStateOf(0L) }

    fun startOfDay(m: Long) = java.util.Calendar.getInstance().apply {
        timeInMillis = m
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    fun endOfDay(m: Long) = startOfDay(m) + 24L * 60 * 60 * 1000 - 1
    fun pickDate(current: Long, onPicked: (Long) -> Unit) {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = current }
        android.app.DatePickerDialog(
            context,
            { _, y, mo, d -> c.set(y, mo, d); onPicked(c.timeInMillis) },
            c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH), c.get(java.util.Calendar.DAY_OF_MONTH)
        ).show()
    }

    val rows = remember(savedCalcs, customerSearch, dateRangeOn, fromMillis, toMillis) {
        savedCalcs
            .filter { c -> c.labelList.any { it.equals(label, ignoreCase = true) } }
            .filter { c -> customerSearch.isBlank() || c.customerName.contains(customerSearch.trim(), ignoreCase = true) }
            .filter { c -> !dateRangeOn || (c.dateMillis in startOfDay(fromMillis)..endOfDay(toMillis)) }
            .sortedByDescending { it.dateMillis }
            .take(100)
    }

    androidx.compose.material3.Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("\"$label\" — history") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            OutlinedTextField(
                value = customerSearch,
                onValueChange = { customerSearch = it },
                label = { Text("Search customer") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.Checkbox(checked = dateRangeOn, onCheckedChange = { dateRangeOn = it })
                Text("Date range", style = MaterialTheme.typography.labelLarge)
                if (dateRangeOn) {
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    androidx.compose.material3.OutlinedButton(onClick = { pickDate(fromMillis) { fromMillis = it } }) {
                        Text(com.billing.pos.util.Format.date(fromMillis), style = MaterialTheme.typography.labelSmall)
                    }
                    Text(" — ", style = MaterialTheme.typography.labelSmall)
                    androidx.compose.material3.OutlinedButton(onClick = { pickDate(toMillis) { toMillis = it } }) {
                        Text(com.billing.pos.util.Format.date(toMillis), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Divider()
            if (rows.isEmpty()) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                    Text(
                        "No calculations with this label yet.",
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    items(rows, key = { it.id }) { calc ->
                        val idxs = calc.labelList.withIndex().filter { it.value.equals(label, ignoreCase = true) }.map { it.index }
                        val labelAmount = idxs.sumOf { calc.amountList.getOrElse(it) { 0.0 } }
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { openCalcId = calc.id; showCalc = true }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(calc.customerName, fontWeight = FontWeight.Bold)
                                Text(
                                    com.billing.pos.util.Format.dateTime(calc.dateMillis),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                                Text(com.billing.pos.util.Format.money(labelAmount), fontWeight = FontWeight.Bold)
                                Text(
                                    "Tape total " + com.billing.pos.util.Format.money(calc.total),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                        Divider()
                    }
                }
            }
        }
    }

    if (showCalc) {
        com.billing.pos.ui.billing.FastBillDialog(
            initialCalcId = openCalcId,
            onSave = { amounts ->
                com.billing.pos.ui.billing.FastBillLink.amounts = amounts
                showCalc = false
            },
            onDismiss = { showCalc = false }
        )
    }
}
