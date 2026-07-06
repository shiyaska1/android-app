package com.billing.pos.ui.pricesearch

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.Item
import com.billing.pos.data.ItemAttachment
import com.billing.pos.data.Repository
import com.billing.pos.items.ItemAttachmentStore
import com.billing.pos.pdf.ProductPdf
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.ui.common.rememberThumbnail
import com.billing.pos.util.Format
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** An item with its computed stock/rate and its attachments, for the search result card. */
data class PriceRow(
    val item: com.billing.pos.data.Item,
    val stock: Double,
    val purchaseRate: Double,
    val attachments: List<ItemAttachment>
)

class PriceSearchViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)

    val rows: StateFlow<List<PriceRow>> =
        combine(repo.items, repo.purchaseLines, repo.soldQty, repo.itemAttachments) { items, pLines, sold, atts ->
            val purchasedByName = pLines.groupBy { it.name.lowercase() }
            val soldByName = sold.associate { it.name.lowercase() to it.qty }
            val attByItem = atts.groupBy { it.itemId }
            items.map { item ->
                val key = item.name.lowercase()
                val lines = purchasedByName[key].orEmpty()
                val purchasedQty = lines.sumOf { it.qty }
                val lastRate = lines.maxByOrNull { it.dateMillis }?.price ?: 0.0
                val soldQty = soldByName[key] ?: 0.0
                PriceRow(item, item.openingStock + purchasedQty - soldQty, lastRate, attByItem[item.id].orEmpty())
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
    val context = LocalContext.current
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

    var shareFor by remember { mutableStateOf<PriceRow?>(null) }

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
                        PriceCard(row, onShare = { shareFor = row })
                    }
                }
            }
        }
    }

    shareFor?.let { row ->
        AlertDialog(
            onDismissRequest = { shareFor = null },
            title = { Text("Share to WhatsApp") },
            text = { Text("Send \"${row.item.name}\" (name, selling price, photo & catalogue). Choose a format.") },
            confirmButton = { TextButton(onClick = { shareProductPdf(context, row); shareFor = null }) { Text("As PDF") } },
            dismissButton = { TextButton(onClick = { shareProductText(context, row); shareFor = null }) { Text("Text + Photo") } }
        )
    }
}

private fun captionFor(item: Item): String = buildString {
    append(item.name)
    append("\nSelling Price: ").append(Format.rupee(item.price))
    if (item.storeLocation.isNotBlank()) append("\nLocation: ").append(item.storeLocation)
}

/** Tries WhatsApp (personal, then business), else falls back to a generic chooser. */
private fun launchShare(context: Context, base: Intent, title: String) {
    base.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { context.startActivity(Intent(base).setPackage("com.whatsapp")) }.isSuccess) return
    if (runCatching { context.startActivity(Intent(base).setPackage("com.whatsapp.w4b")) }.isSuccess) return
    runCatching { context.startActivity(Intent.createChooser(base, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Shares the item photos with the name + selling price as the caption. */
private fun shareProductText(context: Context, row: PriceRow) {
    val caption = captionFor(row.item)
    val images = row.attachments.filter { it.mime.startsWith("image/") }
    val base = when {
        images.isEmpty() -> Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, caption) }
        images.size == 1 -> Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, ItemAttachmentStore.uriFor(context, images[0]))
            putExtra(Intent.EXTRA_TEXT, caption)
        }
        else -> Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(images.map { ItemAttachmentStore.uriFor(context, it) }))
            putExtra(Intent.EXTRA_TEXT, caption)
        }
    }
    launchShare(context, base, "Share item")
}

/** Shares a generated product PDF (name, price, photo) plus any catalogue PDF, with a text caption. */
private fun shareProductPdf(context: Context, row: PriceRow) {
    val caption = captionFor(row.item)
    val firstImage = row.attachments.firstOrNull { it.mime.startsWith("image/") }?.path
    val productUri = ProductPdf.generate(context, AppPrefs(context).company, row.item, firstImage)
    val catalogues = row.attachments.filter { it.mime == "application/pdf" }.map { ItemAttachmentStore.uriFor(context, it) }
    val base = if (catalogues.isEmpty()) {
        Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, productUri)
            putExtra(Intent.EXTRA_TEXT, caption)
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "application/pdf"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>().apply { add(productUri); addAll(catalogues) })
            putExtra(Intent.EXTRA_TEXT, caption)
        }
    }
    launchShare(context, base, "Share item")
}

@Composable
private fun PriceCard(row: PriceRow, onShare: () -> Unit) {
    val context = LocalContext.current
    val images = row.attachments.filter { it.mime.startsWith("image/") }
    val pdfs = row.attachments.filter { it.mime == "application/pdf" }

    fun openPdf(att: ItemAttachment) {
        runCatching {
            val uri = ItemAttachmentStore.uriFor(context, att)
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        row.item.name + (if (row.item.category.isNotBlank()) "  ·  ${row.item.category}" else ""),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (row.item.barcode.isNotBlank()) {
                        Text(row.item.barcode, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
                IconButton(onClick = onShare) {
                    Icon(Icons.Filled.Share, contentDescription = "Share", tint = MaterialTheme.colorScheme.primary)
                }
            }

            // Item + location photos.
            if (images.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    images.forEach { att ->
                        val bmp = rememberThumbnail(att.path, 400)
                        if (bmp != null) {
                            Box(Modifier.size(96.dp).clip(RoundedCornerShape(8.dp))) {
                                Image(bmp, contentDescription = att.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                if (att.kind == "LOCATION") {
                                    Icon(
                                        Icons.Filled.Place, contentDescription = "Location",
                                        tint = Color.White,
                                        modifier = Modifier.align(Alignment.BottomStart).padding(3.dp).size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Location text.
            if (row.item.storeLocation.isNotBlank()) {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Place, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Text("  ${row.item.storeLocation}", style = MaterialTheme.typography.bodyMedium)
                }
            }

            // PDF catalogue(s).
            if (pdfs.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    pdfs.forEach { att ->
                        OutlinedButton(onClick = { openPdf(att) }) {
                            Icon(Icons.Filled.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(" Catalogue", maxLines = 1)
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                PriceCell("Sales price", Format.rupee(row.item.price), Modifier.weight(1f))
                PriceCell("Purchase price", Format.rupee(row.purchaseRate), Modifier.weight(1f))
                PriceCell("Stock", Format.qty(row.stock) + " " + row.item.unit, Modifier.weight(1f))
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
