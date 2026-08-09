package com.billing.pos.ui.common

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.billing.pos.data.License

/**
 * Shown the moment a free-plan shop hits a Pro-gated cap — items online at once, or notifications
 * sent per day (see [License.ONLINE_CATALOG_FREE_LIMIT] / [License.NOTIFICATION_FREE_LIMIT_PER_DAY]).
 * Both caps share the same one-time Pro key, so activating it here from either cap lifts both for
 * good. Offers a call/WhatsApp shortcut to [License.SUPPORT_PHONE] with the device id ready to
 * read out, plus a field to enter the key once it's been issued.
 */
@Composable
fun ProLimitDialog(
    title: String,
    message: String,
    deviceId: String,
    onDismiss: () -> Unit,
    onActivated: () -> Unit,
    onActivate: (String) -> Boolean
) {
    val context = LocalContext.current
    var key by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(message)
                Spacer(Modifier.height(8.dp))
                Text("Device ID: $deviceId", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = {
                    val msg = java.net.URLEncoder.encode(
                        "I want a Pro key for POS Billing. My Device ID is $deviceId", "UTF-8"
                    )
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/${License.SUPPORT_WHATSAPP}?text=$msg"))
                    context.startActivity(intent)
                }) { Text("WhatsApp ${License.SUPPORT_PHONE}") }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it; error = false },
                    label = { Text("Pro key") },
                    singleLine = true,
                    isError = error,
                    supportingText = if (error) { { Text("Key doesn't match this device") } } else null,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (onActivate(key)) onActivated() else error = true
            }) { Text("Activate") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } }
    )
}
