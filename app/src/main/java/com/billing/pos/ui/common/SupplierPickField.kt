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
import com.billing.pos.data.Supplier

/**
 * The supplier picker for a purchase-side entry screen, mirroring [CustomerPickField]: tapping
 * clears the box and shows the whole list, typing narrows it by name or phone, and leaving it
 * without choosing puts the current selection back.
 *
 * Built on a plain field + [SearchPickList], not `ExposedDropdownMenuBox` — that popup-based
 * combobox fights with the keyboard on real devices (typing the first character snaps the
 * field back to the old value and blocks further typing).
 */
@Composable
fun SupplierPickField(
    suppliers: List<Supplier>,
    selectedName: String,
    onPick: (Supplier) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Supplier"
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf(selectedName) }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(selectedName) { if (!expanded) query = selectedName }

    val matches = remember(query, suppliers) {
        if (query.isBlank()) suppliers
        else suppliers.filter { it.name.contains(query, ignoreCase = true) || it.phone.contains(query) }
    }

    Column(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it; expanded = true },
            label = { Text(label) },
            placeholder = { Text("Search") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().onFocusChanged { fs ->
                if (fs.isFocused) { query = ""; expanded = true }
                else { expanded = false; query = selectedName }
            }
        )
        if (expanded) {
            SearchPickList(
                items = matches,
                itemLabel = { it.name + if (it.isDefault) "  (default)" else "" },
                onPick = { s -> onPick(s); query = s.name; expanded = false; focusManager.clearFocus() }
            )
        }
    }
}
