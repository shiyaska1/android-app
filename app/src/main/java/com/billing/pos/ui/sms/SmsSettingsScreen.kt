package com.billing.pos.ui.sms

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.billing.pos.data.AppPrefs
import com.billing.pos.sms.SmsSender
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmsSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { AppPrefs(context) }
    val scope = rememberCoroutineScope()

    var url by remember { mutableStateOf(prefs.smsGatewayUrl) }
    var method by remember { mutableStateOf(prefs.smsGatewayMethod) }
    var apiKey by remember { mutableStateOf(prefs.smsApiKey) }
    var sender by remember { mutableStateOf(prefs.smsSenderId) }
    var balanceUrl by remember { mutableStateOf(prefs.smsBalanceUrl) }
    var channel by remember { mutableStateOf(prefs.smsChannel) }
    var methodMenu by remember { mutableStateOf(false) }
    var channelMenu by remember { mutableStateOf(false) }
    var balanceMsg by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SMS Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState())) {
            Text("Gateway (works on Play & APK)", style = MaterialTheme.typography.titleSmall)
            Text(
                "Paste your provider's send URL and use these placeholders: {number} {message} {apikey} {sender}. " +
                    "They are filled and URL-encoded automatically for each message.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
            )
            OutlinedTextField(value = url, onValueChange = { url = it; prefs.smsGatewayUrl = it },
                label = { Text("Send URL template") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))

            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                    OutlinedTextField(value = method, onValueChange = {}, readOnly = true, label = { Text("Method") },
                        trailingIcon = { IconButton(onClick = { methodMenu = true }) { Icon(Icons.Filled.ArrowDropDown, null) } },
                        modifier = Modifier.fillMaxWidth())
                    DropdownMenu(expanded = methodMenu, onDismissRequest = { methodMenu = false }) {
                        listOf("GET", "POST").forEach { m ->
                            DropdownMenuItem(text = { Text(m) }, onClick = { method = m; prefs.smsGatewayMethod = m; methodMenu = false })
                        }
                    }
                }
            }
            OutlinedTextField(value = apiKey, onValueChange = { apiKey = it; prefs.smsApiKey = it },
                label = { Text("API key / auth key") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            OutlinedTextField(value = sender, onValueChange = { sender = it; prefs.smsSenderId = it },
                label = { Text("Sender ID") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))

            OutlinedTextField(value = balanceUrl, onValueChange = { balanceUrl = it; prefs.smsBalanceUrl = it },
                label = { Text("Balance-check URL (optional, use {apikey})") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    checking = true; balanceMsg = "Checking…"
                    scope.launch { val r = SmsSender.checkBalance(context); checking = false; balanceMsg = r.response }
                }, enabled = !checking) { Text("Check balance") }
                if (balanceMsg.isNotBlank()) Text("  $balanceMsg", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
            }

            // Default channel
            androidx.compose.foundation.layout.Box(Modifier.padding(top = 16.dp)) {
                OutlinedTextField(value = channel, onValueChange = {}, readOnly = true, label = { Text("Default send channel") },
                    trailingIcon = { IconButton(onClick = { channelMenu = true }) { Icon(Icons.Filled.ArrowDropDown, null) } },
                    modifier = Modifier.fillMaxWidth())
                DropdownMenu(expanded = channelMenu, onDismissRequest = { channelMenu = false }) {
                    val options = if (com.billing.pos.BuildConfig.DEBUG) listOf("Gateway", "SIM") else listOf("Gateway")
                    options.forEach { c ->
                        DropdownMenuItem(text = { Text(c) }, onClick = { channel = c; prefs.smsChannel = c; channelMenu = false })
                    }
                }
            }
            if (com.billing.pos.BuildConfig.DEBUG) {
                Text("SIM sending uses this phone's SIM and its normal SMS rate. Available in this APK build only.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
