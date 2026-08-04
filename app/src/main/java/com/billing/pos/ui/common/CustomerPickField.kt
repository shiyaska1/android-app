package com.billing.pos.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import com.billing.pos.data.Customer

/**
 * The customer picker used on a sales entry, in one place so every screen behaves the same:
 * tapping it clears the box and shows the whole list, typing narrows it by name or phone,
 * and leaving it without choosing puts the current selection back.
 *
 * With [allowFreeText] the typed value is kept even when it matches nobody — used where a
 * name need not exist in the customer master, such as a receipt from a one-off payer.
 *
 * Built on a plain field + [SearchPickList], not `ExposedDropdownMenuBox` — that popup-based
 * combobox fights with the keyboard on real devices (typing the first character snaps the
 * field back to the old value and blocks further typing).
 */
@Composable
fun CustomerPickField(
    customers: List<Customer>,
    selectedName: String,
    onPick: (Customer) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Customer",
    allowFreeText: Boolean = false,
    onTyped: (String) -> Unit = {},
    extraOptions: List<String> = emptyList(),
    onPickExtra: (String) -> Unit = {}
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf(selectedName) }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(selectedName) { if (!expanded) query = selectedName }

    val matches = remember(query, customers) {
        if (query.isBlank()) customers
        else customers.filter { it.name.contains(query, ignoreCase = true) || it.phone.contains(query) }
    }
    val extras = remember(query, extraOptions) {
        extraOptions.filter { query.isBlank() || it.contains(query, ignoreCase = true) }
            .filter { name -> customers.none { it.name.equals(name, ignoreCase = true) } }
    }
    val options = remember(matches, extras) {
        matches.map { c ->
            (c.name + if (c.isDefault) "  (default)" else "") to {
                onPick(c); query = c.name; expanded = false; focusManager.clearFocus()
            }
        } + extras.map { name ->
            name to { onPickExtra(name); query = name; expanded = false; focusManager.clearFocus() }
        }
    }

    Column(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it; expanded = true; if (allowFreeText) onTyped(it) },
            label = { Text(label) },
            placeholder = { Text("Search") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().onFocusChanged { fs ->
                // Focus opens the full list; leaving restores whatever is actually selected,
                // unless free text is allowed, where what was typed is the value.
                if (fs.isFocused) { query = ""; expanded = true }
                else { expanded = false; if (!allowFreeText) query = selectedName }
            }
        )
        if (expanded) {
            SearchPickList(items = options, itemLabel = { it.first }, onPick = { it.second() })
        }
    }
}
