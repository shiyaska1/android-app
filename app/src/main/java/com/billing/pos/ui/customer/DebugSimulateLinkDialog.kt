package com.billing.pos.ui.customer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Debug-only: lets a sideloaded APK (no Play Store, so no real install referrer) simulate a
 * customer link's shop=/url=/type= without needing an Internal App Sharing upload for every
 * test. Wired behind BuildConfig.DEBUG at the call site — never shown in a release build.
 */
@Composable
fun DebugSimulateLinkDialog(
    onDismiss: () -> Unit,
    onApply: (shop: String, url: String, type: String) -> Unit
) {
    var shop by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Simulate customer link") },
        text = {
            Column {
                Text(
                    "Sets the same prefs a real Play Store referrer would — for testing on a " +
                        "sideloaded APK, no Internal App Sharing upload needed.",
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = shop, onValueChange = { shop = it },
                    label = { Text("Shop code") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text("Catalog URL") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                OutlinedTextField(
                    value = type, onValueChange = { type = it },
                    label = { Text("Business type (optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onApply(shop.trim(), url.trim(), type.trim()) },
                enabled = shop.isNotBlank() && url.isNotBlank()
            ) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
