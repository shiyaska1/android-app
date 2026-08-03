package com.billing.pos.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import androidx.compose.ui.unit.dp
import com.billing.pos.data.Supplier

/**
 * The supplier picker for a purchase-side entry screen, mirroring [CustomerPickField]: tapping
 * clears the box and shows the whole list, typing narrows it by name or phone, and leaving it
 * without choosing puts the current selection back.
 */
@OptIn(ExperimentalMaterial3Api::class)
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

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it; expanded = true },
            label = { Text(label) },
            placeholder = { Text("Search") },
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth().onFocusChanged { fs ->
                if (fs.isFocused) { query = ""; expanded = true }
                else query = selectedName
            }
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 232.dp)
        ) {
            matches.forEach { s ->
                DropdownMenuItem(
                    text = { Text(s.name + if (s.isDefault) "  (default)" else "") },
                    onClick = { onPick(s); query = s.name; expanded = false; focusManager.clearFocus() }
                )
            }
            if (matches.isEmpty()) {
                DropdownMenuItem(text = { Text("No match") }, onClick = { expanded = false })
            }
        }
    }
}
