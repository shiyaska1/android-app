package com.billing.pos

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.navArgument
import com.billing.pos.auth.PendingDiaryOpen
import com.billing.pos.auth.PendingImport
import com.billing.pos.auth.PendingSharedMedia
import com.billing.pos.auth.Session
import com.billing.pos.diary.EXTRA_OPEN_DIARY_ID
import com.billing.pos.data.AppPrefs
import com.billing.pos.ui.auth.BootScreen
import com.billing.pos.ui.auth.LoginScreen
import com.billing.pos.ui.backup.BackupScreen
import com.billing.pos.ui.accounts.ChartOfAccountsScreen
import com.billing.pos.ui.billing.BillingScreen
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.ui.cashbook.CashBookScreen
import com.billing.pos.ui.customers.CustomersScreen
import com.billing.pos.ui.dashboard.DashboardScreen
import com.billing.pos.ui.diary.DiaryEditScreen
import com.billing.pos.ui.diary.DiaryListScreen
import com.billing.pos.ui.expenses.ExpensesScreen
import com.billing.pos.ui.invoices.InvoiceListScreen
import com.billing.pos.ui.items.ItemsScreen
import com.billing.pos.ui.journal.JournalEntryScreen
import com.billing.pos.ui.journal.JournalListScreen
import com.billing.pos.ui.license.LicenseScreen
import com.billing.pos.ui.outstanding.OutstandingScreen
import com.billing.pos.ui.pricesearch.PriceSearchScreen
import com.billing.pos.ui.printer.PrinterSetupScreen
import com.billing.pos.ui.quickbill.QuickBillScreen
import com.billing.pos.ui.quotation.QuotationListScreen
import com.billing.pos.ui.quotation.QuotationScreen
import com.billing.pos.ui.salesreturn.SalesReturnListScreen
import com.billing.pos.ui.salesreturn.SalesReturnScreen
import com.billing.pos.ui.purchasereturn.PurchaseReturnListScreen
import com.billing.pos.ui.purchasereturn.PurchaseReturnScreen
import com.billing.pos.ui.lpo.PurchaseQuotationListScreen
import com.billing.pos.ui.lpo.PurchaseQuotationScreen
import com.billing.pos.ui.hire.HireInvoiceListScreen
import com.billing.pos.ui.hire.HireInvoiceScreen
import com.billing.pos.ui.hire.HireReturnListScreen
import com.billing.pos.ui.hire.HireReturnScreen
import com.billing.pos.ui.hire.HireItemReportScreen
import com.billing.pos.ui.hire.HireExpiryReportScreen
import com.billing.pos.ui.lab.LabTestListScreen
import com.billing.pos.ui.lab.LabTestEditScreen
import com.billing.pos.ui.lab.LabMasterScreen
import com.billing.pos.ui.lab.PatientListScreen
import com.billing.pos.ui.lab.LabBillScreen
import com.billing.pos.ui.lab.LabBillListScreen
import com.billing.pos.ui.lab.LabResultScreen
import com.billing.pos.ui.materialout.MaterialOutScreen
import com.billing.pos.ui.materialout.MaterialOutListScreen
import com.billing.pos.ui.materialreceipt.MaterialReceiptScreen
import com.billing.pos.ui.materialreceipt.MaterialReceiptListScreen
import com.billing.pos.ui.reports.LpoMaterialReportScreen
import com.billing.pos.ui.materialout.MaterialOutLink
import com.billing.pos.ui.materialout.ItemMovementScreen
import com.billing.pos.ui.reports.StockReportScreen
import com.billing.pos.ui.reports.SalesProfitScreen
import com.billing.pos.ui.reports.SalesItemReportScreen
import com.billing.pos.ui.purchase.PurchaseListScreen
import com.billing.pos.ui.purchase.PurchaseScreen
import com.billing.pos.ui.purchase.SuppliersScreen
import com.billing.pos.ui.receipts.ReceiptsScreen
import com.billing.pos.ui.reports.ReportsScreen
import com.billing.pos.ui.settings.SettingsScreen
import com.billing.pos.ui.theme.POSTheme
import com.billing.pos.ui.vat.VatReportScreen
import com.billing.pos.ui.users.UsersScreen

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the screen awake while the app is in the foreground; normal lock resumes
        // once the app is closed/backgrounded (the flag only applies to this window).
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        captureIncoming(intent)
        enableEdgeToEdge()
        setContent {
            POSTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val ctx = LocalContext.current
                    // App lock (Settings): ask for the phone's own lock once per app start.
                    val lockOn = androidx.compose.runtime.remember { AppPrefs(ctx).appLock }
                    var unlocked by androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(!lockOn || com.billing.pos.ui.common.AppLockGate.unlocked)
                    }
                    if (unlocked) AppNav()
                    else com.billing.pos.ui.common.AppLockScreen(onUnlocked = {
                        com.billing.pos.ui.common.AppLockGate.unlocked = true
                        unlocked = true
                    })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        captureIncoming(intent)
    }

    /**
     * Pulls out of the launch intent: a backup .zip (import), media shared from another app
     * (attach to a new diary entry), or a diary id (reminder tap).
     */
    private fun captureIncoming(intent: Intent?) {
        intent ?: return
        val isZip = intent.type?.let { it == "application/zip" || it == "application/x-zip-compressed" } == true
        // Which app shared to us, so the diary title can name it. Often "" when unknown.
        val source = when (referrer?.host) {
            "com.whatsapp" -> "WhatsApp"
            "com.whatsapp.w4b" -> "WhatsApp Business"
            else -> ""
        }

        when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri != null) {
                    if (isZip) PendingImport.uri = uri
                    else PendingSharedMedia.set(listOf(uri), source)   // WhatsApp photo/voice/video/document
                } else {
                    // A forwarded message: text only, no file.
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                    if (text.isNotBlank()) PendingSharedMedia.setText(text, source)
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                if (!uris.isNullOrEmpty()) PendingSharedMedia.set(uris, source)
            }
            Intent.ACTION_VIEW -> intent.data?.let { PendingImport.uri = it }
        }

        val diaryId = intent.getLongExtra(EXTRA_OPEN_DIARY_ID, 0L)
        if (diaryId > 0L) PendingDiaryOpen.id = diaryId
    }
}

@Composable
private fun AppNav() {
    val nav = rememberNavController()
    val context = LocalContext.current

    // Media shared in from another app (WhatsApp etc.).
    //
    // Cold start is routed by boot / login / change-password below. This effect covers only a
    // WARM start (app already open, share arriving via onNewIntent). The bootDone guard is
    // essential: without it this also fires on a cold start, navigates to the diary, the diary
    // consumes the files, and boot then routes to the dashboard on top of it.
    var bootDone by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val sharedGeneration = PendingSharedMedia.generation
    androidx.compose.runtime.LaunchedEffect(sharedGeneration) {
        if (bootDone && PendingSharedMedia.awaitingDiary && Session.isLoggedIn) {
            PendingSharedMedia.markRouted()
            nav.navigate("diary/edit/0") { launchSingleTop = true }
        }
    }

    // Reminder tap → once logged in, open that diary entry in edit mode.
    val pendingDiaryId = PendingDiaryOpen.id
    androidx.compose.runtime.LaunchedEffect(pendingDiaryId, Session.current) {
        val did = PendingDiaryOpen.id
        if (did != null && Session.isLoggedIn) {
            PendingDiaryOpen.consume()
            nav.navigate("diary/edit/$did") { launchSingleTop = true }
        }
    }

    val logout: () -> Unit = {
        AppPrefs(context).clearSession()
        Session.logout()
        nav.navigate("login") { popUpTo(0) { inclusive = true } }
    }

    @Composable
    fun billing(editId: Long?, estimate: Boolean = false) {
        BillingScreen(
            estimate = estimate,
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
                val dest = when {
                    route == "dashboard" && PendingSharedMedia.awaitingDiary -> {
                        PendingSharedMedia.markRouted(); "diary/edit/0"
                    }
                    route == "dashboard" && PendingImport.uri != null -> "invoices"
                    else -> route
                }
                bootDone = true
                nav.navigate(dest) { popUpTo("boot") { inclusive = true } }
            })
        }
        composable("dashboard") {
            // Catch-all for an incoming share: whatever route led here, if a shared file is
            // still waiting, hand it to the diary. This does not depend on the boot/login
            // handshake winning a race, so it holds even if an earlier redirect was missed.
            androidx.compose.runtime.LaunchedEffect(PendingSharedMedia.generation) {
                if (PendingSharedMedia.awaitingDiary) {
                    PendingSharedMedia.markRouted()
                    nav.navigate("diary/edit/0")
                }
            }
            // Sticky note on launch (once per app start).
            androidx.compose.runtime.LaunchedEffect(Unit) {
                if (PendingSharedMedia.awaitingDiary) return@LaunchedEffect   // share wins
                if (!com.billing.pos.ui.sticky.StickyGate.shown && com.billing.pos.data.AppPrefs(context).stickyNoteOnLaunch) {
                    com.billing.pos.ui.sticky.StickyGate.shown = true
                    nav.navigate("stickynote")
                }
            }
            // Medical store: expiring-medicine popup, once per app start.
            val prefsNow = androidx.compose.runtime.remember { AppPrefs(context) }
            if (prefsNow.expiryAlert && prefsNow.businessType == "Medical store") {
                val expVm: com.billing.pos.ui.medical.ExpiryAlertViewModel =
                    androidx.lifecycle.viewmodel.compose.viewModel()
                val expRows by expVm.rows.collectAsStateSafe()
                var showExpiry by androidx.compose.runtime.remember {
                    androidx.compose.runtime.mutableStateOf(false)
                }
                androidx.compose.runtime.LaunchedEffect(expRows) {
                    if (!com.billing.pos.ui.medical.ExpiryGate.shown && expRows.isNotEmpty()) {
                        com.billing.pos.ui.medical.ExpiryGate.shown = true
                        showExpiry = true
                    }
                }
                androidx.compose.runtime.LaunchedEffect(Unit) { com.billing.pos.medical.ExpiryAlarm.sync(context) }
                if (showExpiry) {
                    com.billing.pos.ui.medical.ExpiryAlertDialog(
                        rows = expRows,
                        onOpenPurchase = { id -> showExpiry = false; nav.navigate("purchase/edit/$id") },
                        onDismiss = { showExpiry = false }
                    )
                }
            }
            DashboardScreen(
                onStickyNote = { nav.navigate("stickynote") },
                onNewBill = { nav.navigate("billing") },
                onQuickBill = { nav.navigate("quickbill") },
                onPriceSearch = { nav.navigate("pricesearch") },
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
                onQuotations = { nav.navigate("quotations") },
                onEstimates = { nav.navigate("estimates") },
                onPoster = { nav.navigate("poster") },
                onCalculatorToBill = { nav.navigate("billing") },
                onSalesReturns = { nav.navigate("salesreturns") },
                onPurchaseReturns = { nav.navigate("purchasereturns") },
                onLpos = { nav.navigate("lpos") },
                onHireInvoices = { nav.navigate("hires") },
                onHireReturns = { nav.navigate("hirereturns") },
                onHireItemReport = { nav.navigate("hireitemreport") },
                onHireExpiryReport = { nav.navigate("hireexpiry") },
                onLabTests = { nav.navigate("labtests") },
                onPatients = { nav.navigate("patients") },
                onLabBills = { nav.navigate("labbills") },
                onMaterialOut = { nav.navigate("materialouts") },
                onMaterialReceipt = { nav.navigate("materialreceipts") },
                onLpoMaterialReport = { nav.navigate("lpomaterialreport") },
                onItemMovement = { nav.navigate("itemmovement") },
                onStockReport = { nav.navigate("stockreport") },
                onSalesProfit = { nav.navigate("salesprofit") },
                onSalesItemReport = { nav.navigate("salesitemreport") },
                onVatReport = { nav.navigate("vat") },
                onOutstanding = { nav.navigate("outstanding") },
                onAccounts = { nav.navigate("accounts") },
                onJournal = { nav.navigate("journal") },
                onDiary = { nav.navigate("diary") },
                onUsers = { nav.navigate("users") },
                onSettings = { nav.navigate("settings") },
                onBackup = { nav.navigate("backup") },
                onLogout = logout
            )
        }
        composable("license") {
            LicenseScreen(onActivated = {
                nav.navigate("login") { popUpTo(0) { inclusive = true } }
            })
        }
        composable("login") {
            LoginScreen(onLoggedIn = { mustChangePassword ->
                val dest = when {
                    mustChangePassword -> "changepassword"
                    PendingSharedMedia.awaitingDiary -> {
                        PendingSharedMedia.markRouted(); "diary/edit/0"
                    }
                    PendingImport.uri != null -> "invoices"
                    else -> "dashboard"
                }
                nav.navigate(dest) { popUpTo(0) { inclusive = true } }
            })
        }
        composable("changepassword") {
            com.billing.pos.ui.auth.ChangePasswordScreen(onDone = {
                val dest = when {
                    PendingSharedMedia.awaitingDiary -> {
                        PendingSharedMedia.markRouted(); "diary/edit/0"
                    }
                    PendingImport.uri != null -> "invoices"
                    else -> "dashboard"
                }
                nav.navigate(dest) { popUpTo(0) { inclusive = true } }
            })
        }
        composable("billing") { billing(null) }
        composable(
            route = "billing/edit/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry -> billing(entry.arguments?.getLong("id")) }
        // Estimate: the same sales-entry screen, saved to its own table.
        composable("estimate") { billing(null, estimate = true) }
        composable(
            route = "estimate/edit/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry -> billing(entry.arguments?.getLong("id"), estimate = true) }
        composable("estimates") {
            com.billing.pos.ui.estimate.EstimateListScreen(
                onBack = { nav.popBackStack() },
                onOpen = { id -> nav.navigate("estimate/edit/$id") },
                onNew = { nav.navigate("estimate") }
            )
        }
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
            SettingsScreen(onBack = { nav.popBackStack() }, onOpenPrinter = { nav.navigate("printer") })
        }
        composable("printer") {
            PrinterSetupScreen(onBack = { nav.popBackStack() })
        }
        composable("quickbill") {
            QuickBillScreen(onBack = { nav.popBackStack() })
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
        composable("quotations") {
            QuotationListScreen(
                onBack = { nav.popBackStack() },
                onOpen = { id -> nav.navigate("quotation/edit/$id") },
                onNew = { nav.navigate("quotation") }
            )
        }
        composable("quotation") { QuotationScreen(editId = null, onBack = { nav.popBackStack() }) }
        composable(
            route = "quotation/edit/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry -> QuotationScreen(editId = entry.arguments?.getLong("id"), onBack = { nav.popBackStack() }) }
        composable("salesreturns") {
            SalesReturnListScreen(
                onBack = { nav.popBackStack() },
                onOpen = { id -> nav.navigate("salesreturn/edit/$id") },
                onNew = { nav.navigate("salesreturn") }
            )
        }
        composable("salesreturn") { SalesReturnScreen(editId = null, onBack = { nav.popBackStack() }) }
        composable(
            route = "salesreturn/edit/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry -> SalesReturnScreen(editId = entry.arguments?.getLong("id"), onBack = { nav.popBackStack() }) }
        composable("purchasereturns") {
            PurchaseReturnListScreen(
                onBack = { nav.popBackStack() },
                onOpen = { id -> nav.navigate("purchasereturn/edit/$id") },
                onNew = { nav.navigate("purchasereturn") }
            )
        }
        composable("purchasereturn") { PurchaseReturnScreen(editId = null, onBack = { nav.popBackStack() }) }
        composable(
            route = "purchasereturn/edit/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry -> PurchaseReturnScreen(editId = entry.arguments?.getLong("id"), onBack = { nav.popBackStack() }) }
        composable("lpos") {
            PurchaseQuotationListScreen(
                onBack = { nav.popBackStack() },
                onOpen = { id -> nav.navigate("lpo/edit/$id") },
                onNew = { nav.navigate("lpo") }
            )
        }
        composable("lpo") { PurchaseQuotationScreen(editId = null, onBack = { nav.popBackStack() }) }
        composable(
            route = "lpo/edit/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry -> PurchaseQuotationScreen(editId = entry.arguments?.getLong("id"), onBack = { nav.popBackStack() }) }
        composable("hires") {
            HireInvoiceListScreen(
                onBack = { nav.popBackStack() },
                onOpen = { id -> nav.navigate("hire/edit/$id") },
                onNew = { nav.navigate("hire") }
            )
        }
        composable("hire") { HireInvoiceScreen(editId = null, onBack = { nav.popBackStack() }) }
        composable(
            route = "hire/edit/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry -> HireInvoiceScreen(editId = entry.arguments?.getLong("id"), onBack = { nav.popBackStack() }) }
        composable("hirereturns") {
            HireReturnListScreen(
                onBack = { nav.popBackStack() },
                onOpen = { id -> nav.navigate("hirereturn/edit/$id") },
                onNew = { nav.navigate("hirereturn") }
            )
        }
        composable("hirereturn") { HireReturnScreen(editId = null, onBack = { nav.popBackStack() }) }
        composable(
            route = "hirereturn/edit/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry -> HireReturnScreen(editId = entry.arguments?.getLong("id"), onBack = { nav.popBackStack() }) }
        composable("hireitemreport") {
            HireItemReportScreen(onBack = { nav.popBackStack() }, onOpenHire = { id -> nav.navigate("hire/edit/$id") })
        }
        composable("hireexpiry") {
            HireExpiryReportScreen(onBack = { nav.popBackStack() }, onOpenHire = { id -> nav.navigate("hire/edit/$id") })
        }
        composable("labtests") {
            LabTestListScreen(
                onBack = { nav.popBackStack() },
                onOpen = { id -> nav.navigate("labtest/edit/$id") },
                onNew = { nav.navigate("labtest") },
                onMasters = { nav.navigate("labmasters") }
            )
        }
        composable("labmasters") { LabMasterScreen(onBack = { nav.popBackStack() }) }
        composable("labtest") { LabTestEditScreen(editId = null, onBack = { nav.popBackStack() }) }
        composable(
            route = "labtest/edit/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry -> LabTestEditScreen(editId = entry.arguments?.getLong("id"), onBack = { nav.popBackStack() }) }
        composable("patients") { PatientListScreen(onBack = { nav.popBackStack() }) }
        composable("labbills") {
            LabBillListScreen(
                onBack = { nav.popBackStack() },
                onOpen = { id -> nav.navigate("labbill/edit/$id") },
                onResult = { id -> nav.navigate("labresult/$id") },
                onNew = { nav.navigate("labbill") },
                onUsedMaterials = { nav.navigate("materialout") }
            )
        }
        composable("labbill") { LabBillScreen(editId = null, onBack = { nav.popBackStack() }) }
        composable(
            route = "labbill/edit/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry -> LabBillScreen(editId = entry.arguments?.getLong("id"), onBack = { nav.popBackStack() }, onNewMaterial = { nav.navigate("materialout") }) }
        composable(
            route = "labresult/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry -> LabResultScreen(billId = entry.arguments?.getLong("id") ?: 0L, onBack = { nav.popBackStack() }) }
        composable("materialouts") {
            MaterialOutListScreen(
                onBack = { nav.popBackStack() },
                onOpen = { id -> nav.navigate("materialout/edit/$id") },
                onNew = { nav.navigate("materialout") }
            )
        }
        composable("materialout") {
            val (refs, tests) = remember { MaterialOutLink.take() }
            MaterialOutScreen(editId = null, resultRefs = refs, resultTests = tests, onBack = { nav.popBackStack() })
        }
        composable(
            route = "materialout/edit/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry -> MaterialOutScreen(editId = entry.arguments?.getLong("id"), resultRefs = null, resultTests = null, onBack = { nav.popBackStack() }) }

        // Material Receipt Note (goods received against an LPO).
        composable("materialreceipts") {
            MaterialReceiptListScreen(onBack = { nav.popBackStack() }, onOpen = { id -> nav.navigate("materialreceipt/edit/$id") }, onNew = { nav.navigate("materialreceipt") })
        }
        composable("materialreceipt") { MaterialReceiptScreen(editId = null, onBack = { nav.popBackStack() }) }
        composable(
            route = "materialreceipt/edit/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry -> MaterialReceiptScreen(editId = entry.arguments?.getLong("id"), onBack = { nav.popBackStack() }) }
        composable("lpomaterialreport") {
            LpoMaterialReportScreen(onBack = { nav.popBackStack() }, onOpenLpo = { id -> nav.navigate("lpo/edit/$id") })
        }
        composable("stickynote") {
            com.billing.pos.ui.sticky.StickyNoteScreen(
                onClose = { nav.popBackStack() },
                onOcrToSales = { nav.navigate("billing") }
            )
        }
        composable("stockreport") { StockReportScreen(onBack = { nav.popBackStack() }) }
        composable("salesprofit") {
            SalesProfitScreen(onBack = { nav.popBackStack() }, onOpenInvoice = { id -> nav.navigate("billing/edit/$id") })
        }
        composable("salesitemreport") {
            SalesItemReportScreen(
                onBack = { nav.popBackStack() },
                onOpenItem = { id -> nav.navigate("items/edit/$id") },
                onOpenInvoice = { id -> nav.navigate("billing/edit/$id") }
            )
        }
        composable("itemmovement") {
            ItemMovementScreen(
                onBack = { nav.popBackStack() },
                onOpenVoucher = { kind, id ->
                    when (kind) {
                        "SALE" -> nav.navigate("billing/edit/$id")
                        "PURCHASE" -> nav.navigate("purchase/edit/$id")
                        "MATERIAL" -> nav.navigate("materialout/edit/$id")
                    }
                }
            )
        }
        composable("items") {
            ItemsScreen(onBack = { nav.popBackStack() })
        }
        composable(
            route = "items/edit/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry ->
            ItemsScreen(onBack = { nav.popBackStack() }, initialEditItemId = entry.arguments?.getLong("id"))
        }
        composable("poster") {
            com.billing.pos.ui.poster.PosterScreen(onBack = { nav.popBackStack() })
        }
        composable("pricesearch") {
            PriceSearchScreen(
                onBack = { nav.popBackStack() },
                onEditItem = { id -> nav.navigate("items/edit/$id") },
                onAddToSale = { nav.navigate("billing") }
            )
        }
        composable("vat") {
            VatReportScreen(onBack = { nav.popBackStack() })
        }
        composable("outstanding") {
            OutstandingScreen(onBack = { nav.popBackStack() })
        }
        composable("accounts") {
            ChartOfAccountsScreen(onBack = { nav.popBackStack() })
        }
        composable("journal") {
            JournalListScreen(
                onBack = { nav.popBackStack() },
                onOpen = { id -> nav.navigate("journal/edit/$id") }
            )
        }
        composable(
            route = "journal/edit/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry ->
            JournalEntryScreen(
                entryId = entry.arguments?.getLong("id") ?: 0L,
                onBack = { nav.popBackStack() }
            )
        }
        composable("cashbook") {
            CashBookScreen(
                onBack = { nav.popBackStack() },
                onEditInvoice = { id -> nav.navigate("billing/edit/$id") },
                onEditPurchase = { id -> nav.navigate("purchase/edit/$id") },
                onEditJournal = { id -> nav.navigate("journal/edit/$id") }
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
