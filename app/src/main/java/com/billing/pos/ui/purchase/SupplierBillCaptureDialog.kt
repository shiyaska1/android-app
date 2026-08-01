package com.billing.pos.ui.purchase

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalContext
import android.net.Uri

/**
 * Photographs a supplier bill one page at a time — the camera reopens itself after each shot
 * so a multi-page bill can be worked through without returning to this screen between pages
 * (same shutter-loop as the item photo capture). "From gallery" is the alternative bulk-input
 * path for bills already saved as images.
 */
@Composable
fun SupplierBillCaptureDialog(onDone: (List<Uri>) -> Unit, onDismiss: () -> Unit) {
    val pages = remember { mutableStateListOf<Uri>() }
    var shots by remember { mutableStateOf(0) }
    var keepShooting by remember { mutableStateOf(true) }

    val camera = com.billing.pos.ocr.rememberImageCamera { uri -> pages.add(uri); shots++ }
    val gallery = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(30)
    ) { uris -> pages.addAll(uris) }

    // Fires once on open and again after every shot, so the camera keeps coming back until
    // the user backs out of it (which simply stops the loop, no extra "done" step needed there).
    LaunchedEffect(shots) { if (keepShooting) camera() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Column(
            Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .safeDrawingPadding()
                .imePadding()
                .padding(16.dp)
        ) {
            Text("Supplier bill", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Photograph each page of the bill, or pick photos from the gallery. Back out of the camera when you're done shooting.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { keepShooting = true; camera() }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.PhotoCamera, null); Text(" More pages")
                }
                OutlinedButton(
                    onClick = {
                        keepShooting = false
                        gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    modifier = Modifier.weight(1f)
                ) { Icon(Icons.Filled.PhotoLibrary, null); Text(" Gallery") }
            }

            if (pages.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    pages.forEachIndexed { i, uri -> PageThumb(uri, i + 1) }
                }
            } else {
                Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
                    Text("No pages yet", color = MaterialTheme.colorScheme.outline)
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { keepShooting = false; onDismiss() }, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(
                    onClick = { keepShooting = false; onDone(pages.toList()) },
                    enabled = pages.isNotEmpty(),
                    modifier = Modifier.weight(1.4f)
                ) { Text("Read ${pages.size} page(s)") }
            }
        }
    }
}

/** Small preview of a captured/picked page, decoded from its content:// Uri. */
@Composable
private fun PageThumb(uri: Uri, page: Int) {
    val context = LocalContext.current
    val bmp = remember(uri) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytes()
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 300) sample *= 2
                val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                    ?.asImageBitmap()
            }
        }.getOrNull()
    }
    Box(Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (bmp != null) {
            Image(bmp, contentDescription = "Page $page", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("$page") }
        }
    }
}
