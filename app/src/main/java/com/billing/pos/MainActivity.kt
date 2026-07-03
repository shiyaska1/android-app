package com.billing.pos

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
import androidx.navigation.navArgument
import com.billing.pos.auth.Session
import com.billing.pos.ui.auth.LoginScreen
import com.billing.pos.ui.billing.BillingScreen
import com.billing.pos.ui.invoices.InvoiceListScreen
import com.billing.pos.ui.reports.ReportsScreen
import com.billing.pos.ui.theme.POSTheme
import com.billing.pos.ui.users.UsersScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            POSTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNav()
                }
            }
        }
    }
}

@Composable
private fun AppNav() {
    val nav = rememberNavController()

    NavHost(navController = nav, startDestination = "login") {
        composable("login") {
            LoginScreen(onLoggedIn = {
                nav.navigate("billing") { popUpTo("login") { inclusive = true } }
            })
        }
        composable("billing") {
            BillingScreen(
                editBillId = null,
                onOpenReports = { nav.navigate("reports") },
                onOpenInvoices = { nav.navigate("invoices") },
                onOpenUsers = { nav.navigate("users") },
                onLogout = {
                    Session.logout()
                    nav.navigate("login") { popUpTo(0) { inclusive = true } }
                }
            )
        }
        composable(
            route = "billing/edit/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry ->
            BillingScreen(
                editBillId = entry.arguments?.getLong("id"),
                onOpenReports = { nav.navigate("reports") },
                onOpenInvoices = { nav.navigate("invoices") },
                onOpenUsers = { nav.navigate("users") },
                onLogout = {
                    Session.logout()
                    nav.navigate("login") { popUpTo(0) { inclusive = true } }
                }
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
        composable("users") {
            UsersScreen(onBack = { nav.popBackStack() })
        }
    }
}
