package com.billing.pos.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.billing.pos.data.AppPrefs
import com.billing.pos.util.Format
import com.billing.pos.util.UpiQr

/**
 * Shows a UPI payment QR for [amount]. The first time, it asks for the UPI ID to collect to
 * (saved for next time); after that it just shows the code. The customer scans it with any
 * UPI app and the amount is already filled in.
 */
@Composable
fun UpiQrDialog(amount: Double, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { AppPrefs(context) }
    var vpa by remember { mutableStateOf(prefs.upiId) }
    var name by remember { mutableStateOf(prefs.upiName.ifBlank { prefs.companyName }) }
    var editing by remember { mutableStateOf(prefs.upiId.isBlank()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editing) "Your UPI ID" else "Scan to pay") },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (editing) {
                    Text(
                        "The UPI ID money is collected to, e.g. name@okaxis or 9961128378@ybl.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    OutlinedTextField(
                        value = vpa, onValueChange = { vpa = it.trim() },
                        label = { Text("UPI ID (VPA)") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text("Payee name") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                } else {
                    val qr = remember(vpa, name, amount) {
                        UpiQr.bitmap(UpiQr.link(vpa, name, amount, "Bill"))
                    }
                    Text(Format.rupee(amount), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    if (qr != null) Image(
                        bitmap = qr.asImageBitmap(),
                        contentDescription = "UPI QR",
                        modifier = Modifier.size(240.dp).padding(top = 10.dp)
                    ) else Text("Could not make the QR", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
                    Text(
                        vpa,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Text(
                        "Scan with any UPI app to pay.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        },
        confirmButton = {
            if (editing) TextButton(onClick = {
                if (vpa.isNotBlank()) { prefs.upiId = vpa; prefs.upiName = name; editing = false }
            }) { Text("Save") }
            else TextButton(onClick = onDismiss) { Text("Done") }
        },
        dismissButton = {
            if (editing) TextButton(onClick = onDismiss) { Text("Cancel") }
            else TextButton(onClick = { editing = true }) { Text("Change UPI ID") }
        }
    )
}
