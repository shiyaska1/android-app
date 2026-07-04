package com.billing.pos

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.navArgument
import com.billing.pos.auth.PendingImport
import com.billing.pos.auth.Session
import com.billing.pos.data.AppPrefs
import com.billing.pos.ui.auth.BootScreen
import com.billing.pos.ui.auth.LoginScreen
import com.billing.pos.ui.backup.BackupScreen
import com.billing.pos.ui.billing.BillingScreen
import com.billing.pos.ui.cashbook.CashBookScreen
import com.billing.pos.ui.customers.CustomersScreen
import com.billing.pos.ui.dashboard.DashboardScreen
import com.billing.pos.ui.diary.DiaryEditScreen
import com.billing.pos.ui.diary.DiaryListScreen
import com.billing.pos.ui.expenses.ExpensesScreen
import com.billing.pos.ui.invoices.InvoiceListScreen
import com.billing.pos.ui.items.ItemsScreen
import com.billing.pos.ui.license.LicenseScreen
import com.billing.pos.ui.license.RegisterScreen
import com.billing.pos.ui.outstanding.OutstandingScreen
import com.billing.pos.ui.purchase.PurchaseListScreen
import com.billing.pos.ui.purchase.PurchaseScreen
import com.billing.pos.ui.purchase.SuppliersScreen
import com.billing.pos.ui.receipts.ReceiptsScreen
import com.billing.pos.ui.reports.ReportsScreen
import com.billing.pos.ui.settings.SettingsScreen
import com.billing.pos.ui.theme.POSTheme
import com.billing.pos.ui.vat.VatReportScreen
import com.billing.pos.ui.users.UsersScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captureIncoming(intent)
        enableEdgeToEdge()
        setContent {
            POSTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNav()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        captureIncoming(intent)
    }

    /** Pulls a backup Uri out of a SEND/VIEW intent so it can be imported after login. */
    private fun captureIncoming(intent: Intent?) {
        intent ?: return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        if (uri != null) PendingImport.uri = uri
    }
}

@Composable
private fun AppNav() {
    val nav = rememberNavController()
    val context = LocalContext.current
    val logout: () -> Unit = {
        AppPrefs(context).clearSession()
        Session.logout()
        nav.navigate("login") { popUpTo(0) { inclusive = true } }
    }

    @Composable
    fun billing(editId: Long?) {
        BillingScreen(
            editBillId = editId,
            onBack = { nav.popBackStack() },
            onOpenReports = { nav.navigate("reports") },
            onOpenInvoices = { nav.navigate("invoices") },
            onOpenUsers = { nav.navigate("users") },
            onOpenDiary = { nav.navigate("diary") },
            onOpenReceipts = { nav.navigate("receipts") },
            onOpenExpenses = { nav.navigate("expenses") },
            onOpenCashbook = { nav.navigate("cashbook") },
            onOpenCustomers = { nav.navigate("customers") },
            onOpenSettings = { nav.navigate("settings") },
            onOpenBackup = { nav.navigate("backup") },
            onLogout = logout
        )
    }

    NavHost(navController = nav, startDestination = "boot") {
        composable("boot") {
            BootScreen(onResolved = { route ->
                val dest = if (route == "dashboard" && PendingImport.uri != null) "invoices" else route
                nav.navigate(dest) { popUpTo("boot") { inclusive = true } }
            })
        }
        composable("dashboard") {
            DashboardScreen(
                onNewBill = { nav.navigate("billing") },
                onInvoices = { nav.navigate("invoices") },
                onReceipts = { nav.navigate("receipts") },
                onExpenses = { nav.navigate("expenses") },
                onCashbook = { nav.navigate("cashbook") },
                onReports = { nav.navigate("reports") },
                onCustomers = { nav.navigate("customers") },
                onItems = { nav.navigate("items") },
                onNewPurchase = { nav.navigate("purchase") },
                onPurchases = { nav.navigate("purchases") },
                onSuppliers = { nav.navigate("suppliers") },
                onVatReport = { nav.navigate("vat") },
                onOutstanding = { nav.navigate("outstanding") },
                onDiary = { nav.navigate("diary") },
                onUsers = { nav.navigate("users") },
                onSettings = { nav.navigate("settings") },
                onBackup = { nav.navigate("backup") },
                onLogout = logout
            )
        }
        composable("register") {
            RegisterScreen(onDone = {
                nav.navigate("login") { popUpTo(0) { inclusive = true } }
            })
        }
        composable("license") {
            LicenseScreen(onActivated = {
                nav.navigate("login") { popUpTo(0) { inclusive = true } }
            })
        }
        composable("login") {
            LoginScreen(onLoggedIn = {
                val dest = if (PendingImport.uri != null) "invoices" else "dashboard"
                nav.navigate(dest) { popUpTo(0) { inclusive = true } }
            })
        }
        composable("billing") { billing(null) }
        composable(
            route = "billing/edit/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry -> billing(entry.arguments?.getLong("id")) }
        composable("invoices") {
            InvoiceListScreen(
                onBack = { nav.popBackStack() },
                onEdit = { id -> nav.navigate("billing/edit/$id") }
            )
        }
        composable("reports") {
            ReportsScreen(onBack = { nav.popBackStack() })
        }
        composable("receipts") {
            ReceiptsScreen(onBack = { nav.popBackStack() })
        }
        composable("expenses") {
            ExpensesScreen(onBack = { nav.popBackStack() })
        }
        composable("settings") {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
        composable("customers") {
            CustomersScreen(onBack = { nav.popBackStack() })
        }
        composable("purchase") {
            PurchaseScreen(editPurchaseId = null, onBack = { nav.popBackStack() })
        }
        composable(
            route = "purchase/edit/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry ->
            PurchaseScreen(editPurchaseId = entry.arguments?.getLong("id"), onBack = { nav.popBackStack() })
        }
        composable("purchases") {
            PurchaseListScreen(
                onBack = { nav.popBackStack() },
                onEdit = { id -> nav.navigate("purchase/edit/$id") }
            )
        }
        composable("suppliers") {
            SuppliersScreen(onBack = { nav.popBackStack() })
        }
        composable("items") {
            ItemsScreen(onBack = { nav.popBackStack() })
        }
        composable("vat") {
            VatReportScreen(onBack = { nav.popBackStack() })
        }
        composable("outstanding") {
            OutstandingScreen(onBack = { nav.popBackStack() })
        }
        composable("cashbook") {
            CashBookScreen(
                onBack = { nav.popBackStack() },
                onEditInvoice = { id -> nav.navigate("billing/edit/$id") },
                onEditPurchase = { id -> nav.navigate("purchase/edit/$id") }
            )
        }
        composable("backup") {
            BackupScreen(
                onBack = { nav.popBackStack() },
                onRestored = { logout() }
            )
        }
        composable("users") {
            UsersScreen(onBack = { nav.popBackStack() })
        }
        composable("diary") {
            DiaryListScreen(
                onBack = { nav.popBackStack() },
                onOpen = { id -> nav.navigate("diary/edit/$id") }
            )
        }
        composable(
            route = "diary/edit/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry ->
            DiaryEditScreen(
                entryId = entry.arguments?.getLong("id") ?: 0L,
                onBack = { nav.popBackStack() }
            )
        }
    }
}
