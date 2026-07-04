package com.billing.pos.ui.license

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.License

/** First-run screen: capture the mobile number and start the trial. */
@Composable
fun RegisterScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { AppPrefs(context) }
    var mobile by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Welcome to POS Billing", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Enter your mobile number to start your ${License.TRIAL_DAYS}-day free trial.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 16.dp)
        )
        OutlinedTextField(
            value = mobile,
            onValueChange = { mobile = it.filter { c -> c.isDigit() || c == '+' }; error = null },
            label = { Text("Mobile number") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth()
        )
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        }
        Button(
            onClick = {
                val m = mobile.trim()
                if (m.length < 7) { error = "Enter a valid mobile number"; return@Button }
                prefs.mobileNumber = m
                prefs.installDateMillis = System.currentTimeMillis()
                onDone()
            },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
        ) { Text("Start Trial") }
    }
}

/** Blocking screen shown after the trial ends until a valid license key is entered. */
@Composable
fun LicenseScreen(onActivated: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { AppPrefs(context) }
    var key by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Trial ended", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Your ${License.TRIAL_DAYS}-day trial has finished. Enter your license key to continue.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 12.dp)
        )
        Text(
            "Registered mobile: ${prefs.mobileNumber}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline
        )
        OutlinedTextField(
            value = key,
            onValueChange = { key = it; error = null },
            label = { Text("License key") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
        )
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        }
        Button(
            onClick = {
                if (License.isValid(prefs.mobileNumber, key)) {
                    prefs.licensed = true
                    onActivated()
                } else error = "Invalid license key"
            },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
        ) { Text("Activate") }
        OutlinedButton(
            onClick = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(License.BUY_URL)))
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) { Text("Buy app") }
    }
}
