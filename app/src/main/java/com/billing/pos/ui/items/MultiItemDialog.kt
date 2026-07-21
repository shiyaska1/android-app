package com.billing.pos.ui.items

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** One row being entered in the multi-item form. */
class MultiItemRow {
    var name by mutableStateOf("")
    var price by mutableStateOf("")
    var category by mutableStateOf("")
    /** Photos picked for this item, copied in only when the whole form is saved. */
    val photos = mutableStateListOf<android.net.Uri>()

    val isFilled: Boolean get() = name.isNotBlank()
}

/**
 * Adds several items in one pass.
 *
 * A row per item — name (typed, handwritten or read off a photo), selling price, category,
 * and any number of photos. Nothing is written until Save, which creates every filled row.
 */
@Composable
fun MultiItemDialog(
    categories: List<String>,
    onSave: (List<MultiItemRow>) -> Unit,
    onDismiss: () -> Unit
) {
    val rows = remember { mutableStateListOf(MultiItemRow(), MultiItemRow(), MultiItemRow()) }

    // Which row is currently using the shared draw / OCR / photo pickers.
    var drawFor by remember { mutableStateOf<MultiItemRow?>(null) }
    var ocrFor by remember { mutableStateOf<MultiItemRow?>(null) }
    var ocrUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var photoFor by remember { mutableStateOf<MultiItemRow?>(null) }

    val ocrCamera = com.billing.pos.ocr.rememberImageCamera { uri -> ocrUri = uri }
    val ocrGallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) ocrUri = uri
    }
    val photoCamera = com.billing.pos.ocr.rememberImageCamera { uri ->
        photoFor?.photos?.add(uri); photoFor = null
    }
    val photoGallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) photoFor?.photos?.add(uri)
        photoFor = null
    }

    drawFor?.let { row ->
        com.billing.pos.ui.common.HandwriteTextDialog(
            onResult = { t -> if (t.isNotBlank()) row.name = (row.name.trimEnd() + " " + t).trim(); drawFor = null },
            onDismiss = { drawFor = null }
        )
    }
    ocrUri?.let { u ->
        val row = ocrFor
        com.billing.pos.ui.common.RegionOcrDialog(
            uri = u,
            onResult = { t -> if (t.isNotBlank() && row != null) row.name = t; ocrUri = null; ocrFor = null },
            onDismiss = { ocrUri = null; ocrFor = null }
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Column(
            Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .safeDrawingPadding()
                .imePadding()
        ) {
            // Actions on top, clear of the navigation bar.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(
                    onClick = { onSave(rows.filter { it.isFilled }.toList()) },
                    enabled = rows.any { it.isFilled },
                    modifier = Modifier.weight(1.6f)
                ) { Text("Save ${rows.count { it.isFilled }} item(s)") }
            }
            Divider()

            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
                itemsIndexed(rows) { index, row ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Column(Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Item ${index + 1}",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { if (rows.size > 1) rows.removeAt(index) },
                                    enabled = rows.size > 1
                                ) {
                                    Icon(Icons.Filled.Delete, "Remove row", tint = MaterialTheme.colorScheme.error)
                                }
                            }

                            OutlinedTextField(
                                value = row.name,
                                onValueChange = { row.name = it },
                                label = { Text("Item name") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            // Name by hand, or read off a label.
                            Row(
                                Modifier.fillMaxWidth().padding(top = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                OutlinedButton(onClick = { drawFor = row }, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Filled.Draw, null, Modifier.size(16.dp))
                                    Text(" Draw", style = MaterialTheme.typography.labelSmall)
                                }
                                OutlinedButton(
                                    onClick = { ocrFor = row; ocrCamera() },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Filled.PhotoCamera, null, Modifier.size(16.dp))
                                    Text(" Camera", style = MaterialTheme.typography.labelSmall)
                                }
                                OutlinedButton(
                                    onClick = {
                                        ocrFor = row
                                        ocrGallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Filled.PhotoLibrary, null, Modifier.size(16.dp))
                                    Text(" Gallery", style = MaterialTheme.typography.labelSmall)
                                }
                            }

                            Row(
                                Modifier.fillMaxWidth().padding(top = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = row.price,
                                    onValueChange = { row.price = it.filter { c -> c.isDigit() || c == '.' } },
                                    label = { Text("Selling price") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = row.category,
                                    onValueChange = { row.category = it },
                                    label = { Text("Category") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            // Item photos, copied in on save.
                            Row(
                                Modifier.fillMaxWidth().padding(top = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedButton(
                                    onClick = { photoFor = row; photoCamera() },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Filled.PhotoCamera, null, Modifier.size(16.dp))
                                    Text(" Photo", style = MaterialTheme.typography.labelSmall)
                                }
                                OutlinedButton(
                                    onClick = {
                                        photoFor = row
                                        photoGallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Filled.PhotoLibrary, null, Modifier.size(16.dp))
                                    Text(" Image", style = MaterialTheme.typography.labelSmall)
                                }
                                if (row.photos.isNotEmpty()) {
                                    Text(
                                        "${row.photos.size} photo(s)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    IconButton(onClick = { row.photos.clear() }) {
                                        Icon(Icons.Filled.Delete, "Clear photos", Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    OutlinedButton(
                        onClick = { rows.add(MultiItemRow()) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
                    ) {
                        Icon(Icons.Filled.Add, null)
                        Text("  Add another row")
                    }
                }
            }
        }
    }
}
