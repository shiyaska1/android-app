package com.billing.pos.ui.lab

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.LabEvaluation
import com.billing.pos.data.LabTest
import com.billing.pos.data.Repository
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.util.Format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One editable evaluation row in the test editor. */
class EvalRow(name: String = "", unit: String = "", normal: String = "", group: String = "") {
    var name by mutableStateOf(name)
    var unit by mutableStateOf(unit)
    var normal by mutableStateOf(normal)
    var group by mutableStateOf(group)
    val uid = next()
    companion object { private var c = 0L; fun next() = ++c }
}

class LabTestViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)
    val tests: StateFlow<List<LabTest>> = repo.labTests.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    var name by mutableStateOf("")
    var priceText by mutableStateOf("")
    var sampleType by mutableStateOf("")
    val evals: SnapshotStateList<EvalRow> = mutableStateListOf()
    var editingId by mutableStateOf<Long?>(null); private set
    private var loaded = false

    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }

    init {
        // Seed the built-in sample tests the first time.
        viewModelScope.launch { val n = repo.seedSampleLabTests(); if (n > 0) message.value = "Loaded $n sample tests" }
    }

    fun addEval() { evals.add(EvalRow()) }
    fun removeEval(i: Int) { if (i in evals.indices) evals.removeAt(i) }

    fun newTest() { name = ""; priceText = ""; sampleType = ""; evals.clear(); evals.add(EvalRow()); editingId = null }

    fun load(id: Long) {
        if (loaded || id <= 0) { loaded = true; return }
        loaded = true
        viewModelScope.launch {
            val t = repo.labTestById(id) ?: return@launch
            editingId = t.id; name = t.name; priceText = Format.money(t.price); sampleType = t.sampleType
            evals.clear()
            repo.labEvaluationsFor(id).forEach { evals.add(EvalRow(it.name, it.unit, it.normalValue, it.groupName)) }
            if (evals.isEmpty()) evals.add(EvalRow())
        }
    }

    fun save(onDone: () -> Unit) {
        if (name.isBlank()) { message.value = "Enter a test name"; return }
        viewModelScope.launch {
            val t = LabTest(id = editingId ?: 0, name = name.trim(), price = priceText.toDoubleOrNull() ?: 0.0, sampleType = sampleType.trim())
            val list = evals.filter { it.name.isNotBlank() }.map {
                LabEvaluation(testId = 0, name = it.name.trim(), unit = it.unit.trim(), normalValue = it.normal.trim(), groupName = it.group.trim())
            }
            repo.saveLabTest(t, list)
            message.value = "Test saved"
            onDone()
        }
    }

    fun delete(t: LabTest) { viewModelScope.launch { repo.deleteLabTest(t); message.value = "Test deleted" } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabTestListScreen(onBack: () -> Unit, onOpen: (Long) -> Unit, onNew: () -> Unit, vm: LabTestViewModel = viewModel()) {
    val snackbar = remember { SnackbarHostState() }
    val tests by vm.tests.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }
    var deleteFor by remember { mutableStateOf<LabTest?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Lab Tests") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = onNew) { Icon(Icons.Filled.Add, "New") } }
    ) { pad ->
        if (tests.isEmpty()) Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { Text("No tests yet", color = MaterialTheme.colorScheme.outline) }
        else LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp)) {
            items(tests, key = { it.id }) { t ->
                Row(Modifier.fillMaxWidth().clickable { onOpen(t.id) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.name, fontWeight = FontWeight.Bold)
                        if (t.sampleType.isNotBlank()) Text(t.sampleType, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                    Text(Format.rupee(t.price), fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = { deleteFor = t }) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                }
                Divider()
            }
        }
    }
    deleteFor?.let { t ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Delete ${t.name}?") },
            confirmButton = { TextButton(onClick = { vm.delete(t); deleteFor = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Cancel") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabTestEditScreen(editId: Long?, onBack: () -> Unit, vm: LabTestViewModel = viewModel()) {
    val snackbar = remember { SnackbarHostState() }
    val message by vm.message.collectAsStateSafe()
    LaunchedEffect(Unit) { if (editId != null && editId > 0) vm.load(editId) else if (vm.evals.isEmpty()) vm.newTest() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (vm.editingId != null) "Edit Test" else "New Test") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(12.dp)) {
            item {
                OutlinedTextField(value = vm.name, onValueChange = { vm.name = it }, label = { Text("Test name *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = vm.priceText, onValueChange = { vm.priceText = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Amount") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                    OutlinedTextField(value = vm.sampleType, onValueChange = { vm.sampleType = it }, label = { Text("Sample type") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Text("Evaluations (parameters)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                Text("Group name is optional — evaluations sharing a group print under that heading.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            itemsIndexed(vm.evals, key = { _, e -> e.uid }) { i, e ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(value = e.name, onValueChange = { e.name = it }, label = { Text("Parameter") }, singleLine = true, modifier = Modifier.weight(1f))
                            IconButton(onClick = { vm.removeEval(i) }) { Icon(Icons.Filled.Delete, "Remove", tint = MaterialTheme.colorScheme.error) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = e.unit, onValueChange = { e.unit = it }, label = { Text("Unit") }, singleLine = true, modifier = Modifier.weight(1f))
                            OutlinedTextField(value = e.normal, onValueChange = { e.normal = it }, label = { Text("Normal value") }, singleLine = true, modifier = Modifier.weight(1.4f))
                        }
                        OutlinedTextField(value = e.group, onValueChange = { e.group = it }, label = { Text("Group (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                    }
                }
            }
            item {
                OutlinedButton(onClick = { vm.addEval() }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Icon(Icons.Filled.Add, null); Text("  Add evaluation") }
                Button(onClick = { vm.save { onBack() } }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Save test") }
            }
        }
    }
}
