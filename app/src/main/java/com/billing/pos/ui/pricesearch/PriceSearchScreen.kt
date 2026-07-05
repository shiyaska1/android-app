package com.billing.pos.ui.pricesearch

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.Repository
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.ui.items.ItemStockRow
import com.billing.pos.util.Format
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class PriceSearchViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)

    val rows: StateFlow<List<ItemStockRow>> =
        combine(repo.items, repo.purchaseLines, repo.soldQty) { items, pLines, sold ->
            val purchasedByName = pLines.groupBy { it.name.lowercase() }
            val soldByName = sold.associate { it.name.lowercase() to it.qty }
            items.map { item ->
                val key = item.name.lowercase()
                val lines = purchasedByName[key].orEmpty()
                val purchasedQty = lines.sumOf { it.qty }
                val lastRate = lines.maxByOrNull { it.dateMillis }?.price ?: 0.0
                val soldQty = soldByName[key] ?: 0.0
                ItemStockRow(item, item.openingStock + purchasedQty - soldQty, lastRate)
            }.sortedBy { it.item.name.lowercase() }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val message = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PriceSearchScreen(
    onBack: () -> Unit,
    vm: PriceSearchViewModel = viewModel()
) {
    val snackbar = remember { SnackbarHostState() }
    val rows by vm.rows.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()
    var query by remember { mutableStateOf("") }

    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                .orEmpty()
            if (spoken.isNotBlank()) query = spoken
        }
    }
    fun startSpeech() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Say the item name")
        }
        runCatching { speechLauncher.launch(intent) }
            .onFailure { vm.message.value = "Voice search not available on this device" }
    }

    val q = query.trim().lowercase()
    val results = if (q.isBlank()) rows else rows.filter {
        it.item.name.lowercase().contains(q) ||
            it.item.category.lowercase().contains(q) ||
            it.item.barcode.lowercase().contains(q)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Price Search") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(12.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search item / barcode / category") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    Row {
                        if (query.isNotBlank()) {
                            IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, contentDescription = "Clear") }
                        }
                        IconButton(onClick = { startSpeech() }) {
                            Icon(Icons.Filled.Mic, contentDescription = "Voice search", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            if (results.isEmpty()) {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        if (q.isBlank()) "Type or tap the mic to search" else "No matching item",
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(results, key = { it.item.id }) { row ->
                        PriceCard(row)
                    }
                }
            }
        }
    }
}

@Composable
private fun PriceCard(row: ItemStockRow) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                row.item.name + (if (row.item.category.isNotBlank()) "  ·  ${row.item.category}" else ""),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
            if (row.item.barcode.isNotBlank()) {
                Text(row.item.barcode, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                PriceCell("Sales price", Format.rupee(row.item.price), Modifier.weight(1f))
                PriceCell("Purchase price", Format.rupee(row.purchaseRate), Modifier.weight(1f))
                PriceCell("Stock", Format.qty(row.stock), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PriceCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        Text(value, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
    }
}
