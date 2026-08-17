package com.billing.pos.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private data class Tile(val label: String, val icon: ImageVector, val section: String)

/** Same section groupings and tile set as the Android app's dashboard core — the working
 * subset ported first; more tiles are added here as each screen is ported in later phases. */
private val TILES = listOf(
    Tile("New Sale", Icons.Filled.PointOfSale, "Transactions"),
    Tile("Purchase", Icons.Filled.LocalShipping, "Transactions"),
    Tile("Receipts", Icons.Filled.Payments, "Transactions"),
    Tile("Payments", Icons.Filled.ReceiptLong, "Transactions"),
    Tile("Cash Book", Icons.Filled.AccountBalanceWallet, "Transactions"),
    Tile("Customers", Icons.Filled.Contacts, "Masters"),
    Tile("Suppliers", Icons.Filled.ManageAccounts, "Masters"),
    Tile("Items", Icons.Filled.Inventory2, "Masters"),
    Tile("Categories", Icons.Filled.Category, "Masters"),
    Tile("Price Search", Icons.Filled.Search, "Masters"),
    Tile("Ledger", Icons.Filled.MenuBook, "Accounts"),
    Tile("Trial Balance", Icons.Filled.Summarize, "Accounts"),
    Tile("Profit & Loss", Icons.Filled.TrendingUp, "Accounts"),
    Tile("Balance Sheet", Icons.Filled.AccountBalance, "Accounts"),
    Tile("Day Book", Icons.Filled.Book, "Accounts"),
    Tile("Backup", Icons.Filled.Backup, "Settings"),
    Tile("Settings", Icons.Filled.Settings, "Settings")
)

private val SECTION_ORDER = listOf("Transactions", "Masters", "Accounts", "Settings")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen() {
    var openTile by remember { mutableStateOf<Tile?>(null) }

    val tile = openTile
    if (tile != null) {
        when (tile.label) {
            "Customers" -> CustomersScreen(onBack = { openTile = null })
            "Items" -> ItemsScreen(onBack = { openTile = null })
            "Suppliers" -> SuppliersScreen(onBack = { openTile = null })
            "New Sale" -> SalesScreen(onBack = { openTile = null })
            "Purchase" -> PurchasesScreen(onBack = { openTile = null })
            "Receipts" -> ReceiptsScreen(onBack = { openTile = null })
            "Payments" -> PaymentsScreen(onBack = { openTile = null })
            "Cash Book" -> CashBookScreen(onBack = { openTile = null })
            "Ledger" -> LedgerScreen(onBack = { openTile = null })
            "Trial Balance" -> TrialBalanceScreen(onBack = { openTile = null })
            "Profit & Loss" -> ProfitLossScreen(onBack = { openTile = null })
            "Balance Sheet" -> BalanceSheetScreen(onBack = { openTile = null })
            "Settings", "Backup" -> SettingsScreen(onBack = { openTile = null })
            "Price Search" -> PriceSearchScreen(onBack = { openTile = null })
            else -> TileDetailScreen(tile.label, onBack = { openTile = null })
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("POS Billing") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp)) {
            SECTION_ORDER.forEach { section ->
                val tiles = TILES.filter { it.section == section }
                if (tiles.isNotEmpty()) {
                    Text(
                        section, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)
                    )
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 140.dp),
                        contentPadding = PaddingValues(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(tiles) { t -> TileCard(t) { openTile = t } }
                    }
                }
            }
        }
    }
}

@Composable
private fun TileCard(tile: Tile, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.aspectRatio(1.2f)
    ) {
        Column(
            Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(tile.icon, tile.label, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp))
            Text(tile.label, style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TileDetailScreen(title: String, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "$title is being ported next.",
                style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Text(
                "The dashboard shell you're looking at now is real navigation — each screen behind\n" +
                    "these tiles gets wired to the same database as the Android app in the next batches.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
