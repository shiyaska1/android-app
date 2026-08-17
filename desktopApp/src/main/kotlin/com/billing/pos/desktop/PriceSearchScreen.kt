package com.billing.pos.desktop

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.billing.pos.shared.DesktopDatabase

/** Blank-by-default text search over items by name/barcode/category — same "don't load
 * everything up front" principle used on Android's Price Search / Diary lists, even though the
 * desktop dataset is small enough right now that it wouldn't matter functionally yet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PriceSearchScreen(onBack: () -> Unit) {
    val allItems = remember { DesktopDatabase.allItems() }
    var query by remember { mutableStateOf("") }
    val results = remember(query) {
        if (query.isBlank()) emptyList()
        else allItems.filter {
            it.name.contains(query, ignoreCase = true) ||
                it.barcode.contains(query, ignoreCase = true) ||
                it.category.contains(query, ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Price Search") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                label = { Text("Search by name, barcode, or category") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (query.isBlank()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Start typing to search items.", color = MaterialTheme.colorScheme.outline)
                }
            } else if (results.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No items match \"$query\".", color = MaterialTheme.colorScheme.outline)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(top = 12.dp)) {
                    items(results, key = { it.id }) { i ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(i.name, fontWeight = FontWeight.Bold)
                                Text(
                                    (if (i.category.isNotBlank()) "${i.category}  •  " else "") +
                                        (if (i.barcode.isNotBlank()) "Barcode: ${i.barcode}" else "Unit: ${i.unit}"),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("₹${i.price}", fontWeight = FontWeight.Bold)
                                if (i.taxPercent > 0) {
                                    Text("+${i.taxPercent}% tax", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
