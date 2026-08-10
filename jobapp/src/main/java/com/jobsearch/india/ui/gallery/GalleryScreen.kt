package com.jobsearch.india.ui.gallery

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.jobsearch.india.ui.IMAGES_PER_PAGE
import kotlin.math.ceil

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    sourceUrl: String,
    imageUrls: List<String>,
    page: Int,
    isLoading: Boolean,
    errorMessage: String?,
    onSetSourceUrl: (String) -> Unit,
    onGoToPage: (Int) -> Unit,
    onRefresh: () -> Unit,
    onImageClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showUrlDialog by remember { mutableStateOf(sourceUrl.isBlank()) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Job Posts") },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = { showUrlDialog = true }) {
                        Icon(Icons.Default.Edit, contentDescription = "Set image list URL")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                sourceUrl.isBlank() -> EmptySourceMessage()
                isLoading && imageUrls.isEmpty() -> LoadingState()
                errorMessage != null && imageUrls.isEmpty() -> ErrorState(errorMessage, onRefresh)
                imageUrls.isEmpty() -> EmptyListMessage()
                else -> {
                    val totalPages = ceil(imageUrls.size / IMAGES_PER_PAGE.toDouble()).toInt().coerceAtLeast(1)
                    val safePage = page.coerceIn(0, totalPages - 1)
                    val pageItems = imageUrls
                        .drop(safePage * IMAGES_PER_PAGE)
                        .take(IMAGES_PER_PAGE)

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(pageItems) { url ->
                            AsyncImage(
                                model = url,
                                contentDescription = "Job post image",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(0.75f)
                                    .clickable { onImageClick(url) }
                            )
                        }
                    }

                    PageBar(totalPages = totalPages, currentPage = safePage, onGoToPage = onGoToPage)
                }
            }
        }
    }

    if (showUrlDialog) {
        UrlEntryDialog(
            initialUrl = sourceUrl,
            onDismiss = { showUrlDialog = false },
            onSave = {
                onSetSourceUrl(it)
                showUrlDialog = false
            }
        )
    }
}

@Composable
private fun PageBar(totalPages: Int, currentPage: Int, onGoToPage: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        Text(
            "Page ${currentPage + 1} of $totalPages",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items((0 until totalPages).toList()) { i ->
                FilterChip(
                    selected = i == currentPage,
                    onClick = { onGoToPage(i) },
                    label = { Text("${i + 1}") }
                )
            }
        }
    }
}

@Composable
private fun EmptySourceMessage() {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            "No job post source set yet.\nTap the pencil icon above to enter the joburls.txt link.",
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun EmptyListMessage() {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text("No job post images found in the source list yet.", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun LoadingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Couldn't load job posts", style = MaterialTheme.typography.titleMedium)
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
            Button(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun UrlEntryDialog(initialUrl: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initialUrl) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Job post source") },
        text = {
            Column {
                Text("Enter the joburls.txt link that lists job-post image URLs.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    placeholder = { Text("https://example.com/joburls.txt") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim()) }, enabled = text.isNotBlank()) { Text("Save") }
        },
        dismissButton = {
            if (initialUrl.isNotBlank()) {
                OutlinedButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
