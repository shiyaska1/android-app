package com.billing.pos.ui.common

import android.app.DatePickerDialog
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.billing.pos.util.Format
import java.util.Calendar

/** Reusable filter bar for document lists: voucher no + party name + date range. */
@Composable
fun ListFilters(
    voucher: String, onVoucher: (String) -> Unit,
    name: String, onName: (String) -> Unit, nameLabel: String,
    from: Long?, onFrom: (Long?) -> Unit,
    to: Long?, onTo: (Long?) -> Unit
) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = voucher, onValueChange = onVoucher,
                label = { Text("Voucher no") }, singleLine = true, modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = name, onValueChange = onName,
                label = { Text(nameLabel) }, singleLine = true, modifier = Modifier.weight(1f)
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickDate(context, from) { onFrom(it) } }, modifier = Modifier.weight(1f)) {
                Text("From: " + (from?.let { Format.date(it) } ?: "any"))
            }
            OutlinedButton(onClick = { pickDate(context, to) { onTo(it) } }, modifier = Modifier.weight(1f)) {
                Text("To: " + (to?.let { Format.date(it) } ?: "any"))
            }
            if (from != null || to != null) {
                TextButton(onClick = { onFrom(null); onTo(null) }) { Text("Clear") }
            }
        }
    }
}

/** Filter bar: a free-text search box plus a From/To date range. */
@Composable
fun DateSearchFilter(
    query: String, onQuery: (String) -> Unit,
    from: Long?, onFrom: (Long?) -> Unit,
    to: Long?, onTo: (Long?) -> Unit,
    searchLabel: String = "Search"
) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        OutlinedTextField(
            value = query, onValueChange = onQuery,
            label = { Text(searchLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickDate(context, from) { onFrom(it) } }, modifier = Modifier.weight(1f)) {
                Text("From: " + (from?.let { Format.date(it) } ?: "any"))
            }
            OutlinedButton(onClick = { pickDate(context, to) { onTo(it) } }, modifier = Modifier.weight(1f)) {
                Text("To: " + (to?.let { Format.date(it) } ?: "any"))
            }
            if (from != null || to != null) {
                TextButton(onClick = { onFrom(null); onTo(null) }) { Text("Clear") }
            }
        }
    }
}

/**
 * Searchable party (customer/supplier) filter for a document list: type to narrow, or pick from
 * the dropdown; blank/"All" clears it. Mirrors the "New Bill" customer search field's feel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PartyFilterField(
    names: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    label: String = "Customer",
    allLabel: String = "All",
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf(selected) }
    androidx.compose.runtime.LaunchedEffect(selected) { if (!expanded) query = selected }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = if (expanded) query else selected.ifBlank { allLabel },
            onValueChange = { query = it; expanded = true },
            label = { Text(label) },
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
                .onFocusChanged { fs -> if (fs.isFocused) { query = ""; expanded = true } }
        )
        val matches = names.filter { query.isBlank() || it.contains(query, ignoreCase = true) }
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(allLabel) }, onClick = { onSelect(""); expanded = false })
            matches.forEach { nm ->
                DropdownMenuItem(text = { Text(nm) }, onClick = { onSelect(nm); expanded = false })
            }
        }
    }
}

/** One month back from now — the default window for lists that show "recent" documents. */
fun oneMonthAgoMillis(): Long = Calendar.getInstance().apply { add(Calendar.MONTH, -1) }.timeInMillis

private fun pickDate(context: Context, current: Long?, onPicked: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { if (current != null) timeInMillis = current }
    DatePickerDialog(
        context,
        { _, y, m, d -> c.set(Calendar.YEAR, y); c.set(Calendar.MONTH, m); c.set(Calendar.DAY_OF_MONTH, d); onPicked(c.timeInMillis) },
        c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
    ).show()
}

/** Start of the day (00:00:00.000) for [m]. */
fun startOfDay(m: Long): Long {
    val c = Calendar.getInstance().apply {
        timeInMillis = m
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    return c.timeInMillis
}

/** End of the day (23:59:59.999) for [m]. */
fun endOfDay(m: Long): Long = startOfDay(m) + 24L * 60 * 60 * 1000 - 1
