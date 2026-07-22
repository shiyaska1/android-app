package com.billing.pos.ui.common

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** A screen set aside, with the exact route needed to bring it back. */
data class Parked(val route: String, val label: String)

/**
 * Screens the user has put aside to do something else.
 *
 * Android has no per-screen minimise, and a back stack cannot hold two background screens
 * and switch freely between them, so this keeps a small register of parked routes and the
 * navigation host restores each one with its saved state.
 *
 * One of each kind: parking a second sales entry replaces the first, because both are the
 * same destination and there is only one saved state per destination.
 */
object ParkedScreens {
    val items = mutableStateListOf<Parked>()

    /** Set by a screen asking to be parked; the navigation host acts on it and clears it. */
    var pendingLabel by mutableStateOf<String?>(null)
        private set

    fun minimize(label: String) { pendingLabel = label }
    fun consumePending(): String? { val l = pendingLabel; pendingLabel = null; return l }

    fun remember(route: String, label: String) {
        items.removeAll { it.route == route }
        items.add(Parked(route, label))
    }

    fun forget(route: String) { items.removeAll { it.route == route } }

    /** What a route is called in the tray. Falls back to the route itself. */
    fun labelFor(route: String): String {
        val base = route.substringBefore("/edit").substringBefore("/")
        return when (base) {
            "billing" -> "Sales entry"
            "estimate" -> "Estimate"
            "quotation" -> "Quotation"
            "purchase" -> "Purchase"
            "salesreturn" -> "Sales return"
            "purchasereturn" -> "Purchase return"
            "lpo" -> "Purchase order"
            "pquote" -> "Purchase quotation"
            "materialreceipt" -> "Material receipt"
            "materialout" -> "Material out"
            "hire" -> "Hire invoice"
            "hirereturn" -> "Hire return"
            "labbill" -> "Lab bill"
            "labtest" -> "Lab test"
            "patients" -> "Patient"
            "diary" -> "Diary entry"
            "items" -> "Item"
            else -> base.replaceFirstChar { it.uppercase() }
        }
    }

    /** Routes worth parking: the ones you fill in, not the lists and reports. */
    fun isMinimizable(routePattern: String?): Boolean {
        val base = (routePattern ?: return false).substringBefore("/edit").substringBefore("/")
        return base in setOf(
            "billing", "estimate", "quotation", "purchase", "salesreturn", "purchasereturn",
            "lpo", "pquote", "materialreceipt", "materialout", "hire", "hirereturn",
            "labbill", "labtest", "patients", "diary"
        )
    }
}
