package com.billing.pos.ui.order

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.billing.pos.data.OrderStatus
import com.billing.pos.data.SalesmanMap
import java.util.Calendar

/** Shared by the two order reports (item-wise and per-line): filter widgets, date math, salesman lookup. */

internal fun resolveSalesman(deviceId: String, salesmen: List<SalesmanMap>): String {
    if (deviceId.isBlank()) return "Unassigned"
    return salesmen.firstOrNull { it.deviceId == deviceId }?.name ?: deviceId.take(8)
}

/** Start of the day 30 days ago, through end of today — the default report window. */
internal fun defaultRangeFrom(): Long {
    val c = Calendar.getInstance()
    c.add(Calendar.DAY_OF_YEAR, -30)
    return startOfDay(c.timeInMillis)
}
internal fun defaultRangeTo(): Long = endOfDay(System.currentTimeMillis())

internal fun startOfDay(m: Long): Long = Calendar.getInstance().apply {
    timeInMillis = m
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis
internal fun endOfDay(m: Long): Long = Calendar.getInstance().apply {
    timeInMillis = m
    set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
}.timeInMillis

internal fun pickDate(context: android.content.Context, current: Long, onPicked: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = current }
    android.app.DatePickerDialog(context, { _, y, m, d -> c.set(Calendar.YEAR, y); c.set(Calendar.MONTH, m); c.set(Calendar.DAY_OF_MONTH, d); onPicked(c.timeInMillis) },
        c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
}

/** Plain dropdown over a fixed option list — used for the Status filter, and (in the item report) Item. */
@Composable
internal fun FilterDrop(label: String, value: String, options: List<String>, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(label + ": " + value.take(14), maxLines = 1)
            Icon(Icons.Filled.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(o) }, onClick = { onPick(o); open = false }) }
        }
    }
}

internal val STATUS_FILTER_OPTIONS = listOf("All") + OrderStatus.values().map { it.label }
internal fun statusFilterToEnum(v: String): OrderStatus? = OrderStatus.values().firstOrNull { it.label == v }
