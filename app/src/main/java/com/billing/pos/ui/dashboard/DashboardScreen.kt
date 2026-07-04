package com.billing.pos.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoneyOff
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.billing.pos.auth.Session

private data class Tile(val label: String, val icon: ImageVector, val onClick: () -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onNewBill: () -> Unit,
    onInvoices: () -> Unit,
    onReceipts: () -> Unit,
    onExpenses: () -> Unit,
    onCashbook: () -> Unit,
    onReports: () -> Unit,
    onCustomers: () -> Unit,
    onItems: () -> Unit,
    onNewPurchase: () -> Unit,
    onPurchases: () -> Unit,
    onSuppliers: () -> Unit,
    onVatReport: () -> Unit,
    onOutstanding: () -> Unit,
    onAccounts: () -> Unit,
    onJournal: () -> Unit,
    onDiary: () -> Unit,
    onUsers: () -> Unit,
    onSettings: () -> Unit,
    onBackup: () -> Unit,
    onLogout: () -> Unit
) {
    val tiles = buildList {
        add(Tile("New Bill", Icons.Filled.PointOfSale, onNewBill))
        if (Session.canViewInvoice) add(Tile("Invoices", Icons.Filled.ReceiptLong, onInvoices))
        if (Session.canViewReceipt) add(Tile("Receipts", Icons.Filled.Payments, onReceipts))
        if (Session.canViewPayment) add(Tile("Payments", Icons.Filled.MoneyOff, onExpenses))
        if (Session.canViewCashbook) add(Tile("Cash Book", Icons.Filled.AccountBalanceWallet, onCashbook))
        if (Session.canViewInvoice) add(Tile("Sales Report", Icons.Filled.Assessment, onReports))
        add(Tile("Customers", Icons.Filled.People, onCustomers))
        add(Tile("Items", Icons.Filled.Category, onItems))
        add(Tile("New Purchase", Icons.Filled.ShoppingCart, onNewPurchase))
        if (Session.canViewInvoice) add(Tile("Purchases", Icons.Filled.Inventory2, onPurchases))
        add(Tile("Suppliers", Icons.Filled.LocalShipping, onSuppliers))
        if (Session.canViewInvoice) add(Tile("VAT Report", Icons.Filled.Description, onVatReport))
        if (Session.canViewInvoice) add(Tile("Outstanding", Icons.Filled.AccountBalance, onOutstanding))
        if (Session.canManageUsers) add(Tile("Accounts", Icons.Filled.AccountTree, onAccounts))
        if (Session.canManageUsers) add(Tile("Journal", Icons.Filled.Book, onJournal))
        add(Tile("My Diary", Icons.Filled.MenuBook, onDiary))
        if (Session.canManageUsers) add(Tile("Users", Icons.Filled.ManageAccounts, onUsers))
        if (Session.canManageUsers) add(Tile("Settings", Icons.Filled.Settings, onSettings))
        if (Session.canExport || Session.canImport) add(Tile("Backup", Icons.Filled.Backup, onBackup))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dashboard — ${Session.current?.username ?: ""}") },
                actions = {
                    IconButton(onClick = onLogout) {
                        Icon(Icons.Filled.PowerSettingsNew, contentDescription = "Logout")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize().padding(pad).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(tiles) { tile ->
                Card(
                    Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .clickable { tile.onClick() }
                ) {
                    Column(
                        Modifier.fillMaxSize().padding(12.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            tile.icon,
                            contentDescription = tile.label,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp)
                        )
                        Text(
                            tile.label,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        }
    }
}
