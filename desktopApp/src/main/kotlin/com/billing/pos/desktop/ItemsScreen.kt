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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemsScreen(onBack: () -> Unit) {
    var itemList by remember { mutableStateOf(DesktopDatabase.allItems()) }
    var showDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Items") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showDialog = true }) { Icon(Icons.Filled.Add, "Add item") }
        }
    ) { pad ->
        if (itemList.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text("No items yet — tap + to add one.", color = MaterialTheme.colorScheme.outline)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
                items(itemList, key = { it.id }) { i ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(i.name, fontWeight = FontWeight.Bold)
                            Text(
                                "₹${i.price}" +
                                    (if (i.hsn.isNotBlank()) "  •  HSN ${i.hsn}" else "") +
                                    (if (i.category.isNotBlank()) "  •  ${i.category}" else "") +
                                    "  •  ${i.unit}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                            )
                        }
                        IconButton(onClick = {
                            DesktopDatabase.deleteItem(i.id)
                            itemList = DesktopDatabase.allItems()
                        }) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    if (showDialog) {
        ItemFormDialog(
            onDismiss = { showDialog = false },
            onSave = { item ->
                DesktopDatabase.addItem(item)
                itemList = DesktopDatabase.allItems()
                showDialog = false
            }
        )
    }
}
