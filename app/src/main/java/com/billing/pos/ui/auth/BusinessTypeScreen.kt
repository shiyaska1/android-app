package com.billing.pos.ui.auth

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.Repository
import com.billing.pos.ui.customer.DebugSimulateLinkDialog
import com.billing.pos.ui.settings.BUSINESS_TYPES
import kotlinx.coroutines.launch

/**
 * One-time question on first launch: what is this app for?
 *
 * The choice drives which features the dashboard shows. "Personal" strips it back to the
 * everyday tools; the shop types show the full billing app.
 */
@Composable
fun BusinessTypeScreen(onChosen: () -> Unit, onCustomerLinkSimulated: () -> Unit = {}) {
    val context = LocalContext.current
    val prefs = androidx.compose.runtime.remember { AppPrefs(context) }
    val scope = rememberCoroutineScope()
    var showSimulateLink by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text(
            "Welcome",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 24.dp)
        )
        Text(
            "How will you use the app? This sets up your home screen and can be changed later in Settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 6.dp, bottom = 16.dp)
        )
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(BUSINESS_TYPES) { type ->
                Card(
                    Modifier.fillMaxWidth().clickable {
                        prefs.businessType = type
                        if (type == "Medical store") prefs.requireItemBatch = true
                        prefs.onboarded = true
                        scope.launch { Repository(context).seedSampleItems(type) }
                        onChosen()
                    }
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(type, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (type == "Personal") {
                            Text(
                                "Diary, sticky notes, calculator, payments, receipts, cash book — no shop billing.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        }
        if (com.billing.pos.BuildConfig.DEBUG) {
            TextButton(onClick = { showSimulateLink = true }, modifier = Modifier.padding(top = 8.dp)) {
                Text("Debug: simulate customer link")
            }
        }
    }

    if (showSimulateLink) {
        DebugSimulateLinkDialog(
            onDismiss = { showSimulateLink = false },
            onApply = { shop, url, type ->
                prefs.customerMode = true
                prefs.shopCode = shop
                prefs.onlineCatalogUrl = url
                if (type.isNotBlank()) prefs.customerBusinessType = type
                prefs.onboarded = true
                showSimulateLink = false
                onCustomerLinkSimulated()
            }
        )
    }
}
