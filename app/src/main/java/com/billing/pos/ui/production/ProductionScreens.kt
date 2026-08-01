package com.billing.pos.ui.production

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.NoteAdd
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
import androidx.compose.runtime.mutableStateListOf
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
import com.billing.pos.data.Item
import com.billing.pos.data.ProductionProcedure
import com.billing.pos.data.ProductionProcedureMaterial
import com.billing.pos.data.ProductionRun
import com.billing.pos.data.Repository
import com.billing.pos.data.costRate
import com.billing.pos.ui.billing.ItemPickerDialog
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.util.Format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ProductionViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)
    val items: StateFlow<List<Item>> = repo.items.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val procedures: StateFlow<List<ProductionProcedure>> = repo.procedures.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val runs: StateFlow<List<ProductionRun>> = repo.productionRuns.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }

    suspend fun materialsFor(procedureId: Long): List<ProductionProcedureMaterial> = repo.procedureMaterials(procedureId)
    suspend fun procedureById(id: Long): ProductionProcedure? = repo.procedureById(id)

    fun saveProcedure(
        existingId: Long?, name: String, producedItem: Item?, producedQty: Double, unit: String,
        labourCost: Double, remarks: String, materials: List<ProductionProcedureMaterial>, onDone: () -> Unit
    ) {
        if (name.isBlank()) { message.value = "Enter a procedure name"; return }
        if (producedItem == null) { message.value = "Select the item this procedure produces"; return }
        if (producedQty <= 0) { message.value = "Batch quantity must be greater than zero"; return }
        if (materials.isEmpty()) { message.value = "Add at least one raw material"; return }
        viewModelScope.launch {
            val p = ProductionProcedure(
                id = existingId ?: 0, name = name.trim(), producedItemId = producedItem.id,
                producedItemName = producedItem.name, producedQty = producedQty, unit = unit,
                labourCost = labourCost, remarks = remarks.trim()
            )
            if (existingId != null) repo.updateProcedure(p, materials) else repo.saveProcedure(p, materials)
            message.value = "Procedure saved"
            onDone()
        }
    }
    fun deleteProcedure(p: ProductionProcedure) { viewModelScope.launch { repo.deleteProcedure(p); message.value = "Procedure deleted" } }

    fun produce(procedureId: Long, qty: Double, remarks: String, onDone: (ProductionRun) -> Unit) {
        viewModelScope.launch {
            repo.produceRun(procedureId, qty, remarks)
                .onSuccess { run -> message.value = "Produced ${Format.qty(run.qtyProduced)} ${run.unit} — ${run.runNo}"; onDone(run) }
                .onFailure { message.value = it.message ?: "Production failed" }
        }
    }
    fun deleteRun(r: ProductionRun) { viewModelScope.launch { repo.deleteProductionRun(r); message.value = "Run ${r.runNo} deleted (stock reversed)" } }
}

// ============================== Procedures ==============================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcedureListScreen(onBack: () -> Unit, onOpen: (Long) -> Unit, onNew: () -> Unit, vm: ProductionViewModel = viewModel()) {
    val snackbar = remember { SnackbarHostState() }
    val procedures by vm.procedures.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }
    var deleteFor by remember { mutableStateOf<ProductionProcedure?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Production Procedures") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = onNew) { Icon(Icons.Filled.Add, "New procedure") } }
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp)) {
            items(procedures, key = { it.id }) { p ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(p.id) }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontWeight = FontWeight.Bold)
                        Text(
                            "Makes ${Format.qty(p.producedQty)} ${p.unit} ${p.producedItemName}  •  Labour ${Format.rupee(p.labourCost)}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                        )
                    }
                    IconButton(onClick = { deleteFor = p }) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                }
                Divider()
            }
            if (procedures.isEmpty()) item { Text("No procedures yet. Tap + to define one.", color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(16.dp)) }
        }
    }

    deleteFor?.let { p ->
        AlertDialog(onDismissRequest = { deleteFor = null }, title = { Text("Delete ${p.name}?") },
            text = { Text("Past production runs made with this procedure are kept.") },
            confirmButton = { TextButton(onClick = { vm.deleteProcedure(p); deleteFor = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Cancel") } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcedureEditScreen(editId: Long?, onBack: () -> Unit, vm: ProductionViewModel = viewModel()) {
    val snackbar = remember { SnackbarHostState() }
    val items by vm.items.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    var name by remember { mutableStateOf("") }
    var producedItem by remember { mutableStateOf<Item?>(null) }
    var producedQtyText by remember { mutableStateOf("1") }
    var unit by remember { mutableStateOf("") }
    var labourCostText by remember { mutableStateOf("0") }
    var remarks by remember { mutableStateOf("") }
    val materials = remember { mutableStateListOf<ProductionProcedureMaterial>() }
    var pickingProducedItem by remember { mutableStateOf(false) }
    var pickingMaterial by remember { mutableStateOf(false) }

    LaunchedEffect(editId) {
        if (editId != null && editId > 0) {
            val p = vm.procedureById(editId) ?: return@LaunchedEffect
            name = p.name
            producedItem = Item(id = p.producedItemId, name = p.producedItemName, price = 0.0, unit = p.unit)
            producedQtyText = Format.qty(p.producedQty)
            unit = p.unit
            labourCostText = Format.money(p.labourCost)
            remarks = p.remarks
            materials.clear(); materials.addAll(vm.materialsFor(editId))
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (editId != null && editId > 0) "Edit Procedure" else "New Procedure") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(12.dp).verticalScroll(rememberScrollState())) {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Procedure name") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            Text("Produces", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                OutlinedButton(onClick = { pickingProducedItem = true }, modifier = Modifier.weight(1f)) {
                    Text(producedItem?.name ?: "Choose item", maxLines = 1)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = producedQtyText,
                    onValueChange = { v -> producedQtyText = v.filter { it.isDigit() || it == '.' } },
                    label = { Text("Batch qty") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = unit, onValueChange = { unit = it }, label = { Text("Unit") }, singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            OutlinedTextField(
                value = labourCostText,
                onValueChange = { v -> labourCostText = v.filter { it.isDigit() || it == '.' } },
                label = { Text("Labour cost (for this batch qty)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )

            Divider(Modifier.padding(vertical = 12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Raw materials", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { pickingMaterial = true }) { Icon(Icons.Filled.Add, null); Text(" Add") }
            }
            Card(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                if (materials.isEmpty()) {
                    Text("No raw materials added.", color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(12.dp))
                } else {
                    Column(Modifier.padding(8.dp)) {
                        materials.forEachIndexed { index, m ->
                            var qtyText by remember(m.itemId, index) { mutableStateOf(Format.qty(m.qty)) }
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(m.name, Modifier.weight(1f), maxLines = 1)
                                OutlinedTextField(
                                    value = qtyText,
                                    onValueChange = { v ->
                                        val f = v.filter { it.isDigit() || it == '.' }; qtyText = f
                                        materials[index] = materials[index].copy(qty = f.toDoubleOrNull() ?: 0.0)
                                    },
                                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    modifier = Modifier.width(90.dp)
                                )
                                Text(" " + m.unit, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 4.dp))
                                IconButton(onClick = { materials.removeAt(index) }) { Icon(Icons.Filled.Delete, "Remove", tint = MaterialTheme.colorScheme.error) }
                            }
                            Divider()
                        }
                    }
                }
            }

            OutlinedTextField(
                value = remarks, onValueChange = { remarks = it }, label = { Text("Remarks") },
                minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            )

            Button(
                onClick = {
                    vm.saveProcedure(
                        if (editId != null && editId > 0) editId else null,
                        name, producedItem, producedQtyText.toDoubleOrNull() ?: 0.0, unit.ifBlank { producedItem?.unit ?: "" },
                        labourCostText.toDoubleOrNull() ?: 0.0, remarks, materials.toList()
                    ) { onBack() }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
            ) { Text("Save procedure") }
        }
    }

    if (pickingProducedItem) {
        ItemPickerDialog(
            items = items, onDismiss = { pickingProducedItem = false },
            onPick = { picked -> producedItem = picked; if (unit.isBlank()) unit = picked.unit; pickingProducedItem = false },
            onNewItem = { pickingProducedItem = false }
        )
    }
    if (pickingMaterial) {
        ItemPickerDialog(
            items = items, onDismiss = { pickingMaterial = false },
            onPick = { picked -> materials.add(ProductionProcedureMaterial(0, 0, picked.id, picked.name, 1.0, picked.unit)); pickingMaterial = false },
            onNewItem = { pickingMaterial = false }
        )
    }
}

// ============================== Production runs ==============================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductionRunListScreen(onBack: () -> Unit, onNew: () -> Unit, onReport: () -> Unit, vm: ProductionViewModel = viewModel()) {
    val snackbar = remember { SnackbarHostState() }
    val runs by vm.runs.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }
    var deleteFor by remember { mutableStateOf<ProductionRun?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Production Runs") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = onReport) { Icon(Icons.Filled.NoteAdd, "Production cost report") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = onNew) { Icon(Icons.Filled.Add, "Produce") } }
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp)) {
            items(runs, key = { it.id }) { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.runNo + "  •  " + r.producedItemName, fontWeight = FontWeight.Bold)
                        Text(
                            Format.date(r.dateMillis) + "  •  Qty " + Format.qty(r.qtyProduced) + " " + r.unit + "  •  " + r.procedureName,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                        )
                    }
                    Text(Format.rupee(r.totalCost), fontWeight = FontWeight.Bold)
                    IconButton(onClick = { deleteFor = r }) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                }
                Divider()
            }
            if (runs.isEmpty()) item { Text("No production runs yet.", color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(16.dp)) }
        }
    }

    deleteFor?.let { r ->
        AlertDialog(onDismissRequest = { deleteFor = null }, title = { Text("Delete ${r.runNo}?") },
            text = { Text("This reverses the stock effect: raw materials go back, and the produced quantity is removed.") },
            confirmButton = { TextButton(onClick = { vm.deleteRun(r); deleteFor = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Cancel") } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductionRunScreen(onBack: () -> Unit, vm: ProductionViewModel = viewModel()) {
    val snackbar = remember { SnackbarHostState() }
    val procedures by vm.procedures.collectAsStateSafe()
    val items by vm.items.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    var selectedProcedure by remember { mutableStateOf<ProductionProcedure?>(null) }
    var qtyText by remember { mutableStateOf("") }
    var remarks by remember { mutableStateOf("") }
    var materials by remember { mutableStateOf<List<ProductionProcedureMaterial>>(emptyList()) }
    var showProcedurePicker by remember { mutableStateOf(false) }

    LaunchedEffect(selectedProcedure) {
        val p = selectedProcedure
        if (p != null) { materials = vm.materialsFor(p.id); qtyText = Format.qty(p.producedQty) }
    }

    val qty = qtyText.toDoubleOrNull() ?: 0.0
    val proc = selectedProcedure
    val multiplier = if (proc != null && proc.producedQty > 0) qty / proc.producedQty else 0.0
    val itemById = items.associateBy { it.id }
    val scaledLines = materials.map { m ->
        val rate = itemById[m.itemId]?.costRate ?: 0.0
        val consumed = m.qty * multiplier
        Triple(m, consumed, consumed * rate)
    }
    val materialCost = scaledLines.sumOf { it.third }
    val labourCost = (proc?.labourCost ?: 0.0) * multiplier
    val totalCost = materialCost + labourCost
    val unitCost = if (qty > 0) totalCost / qty else 0.0

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Produce") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(12.dp).verticalScroll(rememberScrollState())) {
            OutlinedButton(onClick = { showProcedurePicker = true }, modifier = Modifier.fillMaxWidth()) {
                Text(proc?.name ?: "Choose a procedure")
            }
            if (proc == null) {
                Text(
                    "Define a procedure first (raw materials + labour cost for a batch), then produce against it here.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 12.dp)
                )
            } else {
                OutlinedTextField(
                    value = qtyText,
                    onValueChange = { v -> qtyText = v.filter { it.isDigit() || it == '.' } },
                    label = { Text("Quantity to produce (${proc.unit})") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                )
                Divider(Modifier.padding(vertical = 12.dp))
                Text("Raw materials consumed", style = MaterialTheme.typography.titleSmall)
                Card(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Column(Modifier.padding(8.dp)) {
                        scaledLines.forEach { (m, consumed, cost) ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Text(m.name, Modifier.weight(1f))
                                Text(Format.qty(consumed) + " " + m.unit, modifier = Modifier.padding(end = 8.dp))
                                Text(Format.rupee(cost), fontWeight = FontWeight.SemiBold)
                            }
                        }
                        if (scaledLines.isEmpty()) Text("No raw materials in this procedure.", color = MaterialTheme.colorScheme.outline)
                    }
                }
                Divider(Modifier.padding(vertical = 12.dp))
                CostRow("Material cost", materialCost)
                CostRow("Labour cost", labourCost)
                Divider(Modifier.padding(vertical = 6.dp))
                CostRow("Total cost", totalCost, bold = true)
                CostRow("Unit cost", unitCost)
                OutlinedTextField(
                    value = remarks, onValueChange = { remarks = it }, label = { Text("Remarks") },
                    singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                )
                Button(
                    onClick = { vm.produce(proc.id, qty, remarks) { onBack() } },
                    enabled = qty > 0,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
                ) { Text("Produce") }
            }
        }
    }

    if (showProcedurePicker) {
        AlertDialog(
            onDismissRequest = { showProcedurePicker = false },
            title = { Text("Choose a procedure") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (procedures.isEmpty()) Text("No procedures defined yet.", color = MaterialTheme.colorScheme.outline)
                    procedures.forEach { p ->
                        Text(
                            p.name + " — " + Format.qty(p.producedQty) + " " + p.unit + " " + p.producedItemName,
                            modifier = Modifier.fillMaxWidth().clickable { selectedProcedure = p; showProcedurePicker = false }.padding(vertical = 10.dp)
                        )
                        Divider()
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showProcedurePicker = false }) { Text("Close") } }
        )
    }
}

@Composable
private fun CostRow(label: String, value: Double, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, Modifier.weight(1f), fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
        Text(
            Format.rupee(value), fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold,
            color = if (bold) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}
