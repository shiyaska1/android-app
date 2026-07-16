package com.billing.pos.ui.billing

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.billing.pos.util.Format

/**
 * Fast bill — a calculator tape. Type an amount and press Enter (Enter acts as "+"); each amount
 * is added to a big running tape the customer can read, with "=" total at the bottom. Save drops
 * every amount into the bill as its own price-only line (no item name), ready to print.
 *
 * Close/Save live in the TOP bar: the phone's navigation bar can never cover them.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FastBillDialog(
    onSave: (List<Double>) -> Unit,
    onDismiss: () -> Unit
) {
    val entries = remember { mutableStateListOf<Double>() }
    var input by remember { mutableStateOf("") }
    var editIndex by remember { mutableStateOf(-1) }
    val focus = remember { FocusRequester() }
    val scroll = rememberScrollState()
    val total = entries.sum()

    fun addNow() {
        val v = input.toDoubleOrNull()
        if (v != null && v > 0.0) entries.add(v)
        input = ""
        focus.requestFocus()
    }

    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(entries.size) { scroll.animateScrollTo(scroll.maxValue) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Column(
            Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .safeDrawingPadding()
        ) {
            // ---- TOP BAR: actions always reachable ----
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Close") }
                Button(
                    onClick = {
                        val pending = input.toDoubleOrNull()
                        val all = if (pending != null && pending > 0.0) entries + pending else entries.toList()
                        if (all.isNotEmpty()) onSave(all)
                        onDismiss()
                    },
                    enabled = entries.isNotEmpty() || (input.toDoubleOrNull() ?: 0.0) > 0.0,
                    modifier = Modifier.weight(1.4f)
                ) { Text("Save to bill") }
            }
            Divider()

            // ---- The tape: every amount on its own line, with + signs, then = total ----
            Box(
                Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    if (entries.isEmpty()) {
                        Text(
                            "Type an amount and press Enter",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                    entries.forEachIndexed { i, v ->
                        // Long-press an amount to edit or delete it; the total recalculates.
                        Row(
                            Modifier.fillMaxWidth()
                                .combinedClickable(onClick = {}, onLongClick = { editIndex = i })
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (i == 0) " " else "+",
                                fontSize = 30.sp, fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.65f)
                            )
                            Text(
                                Format.money(v),
                                modifier = Modifier.weight(1f),
                                fontSize = 34.sp, fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold, textAlign = TextAlign.End,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    if (entries.isNotEmpty()) {
                        Divider(
                            Modifier.padding(vertical = 8.dp), thickness = 3.dp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
                        )
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "=",
                                fontSize = 40.sp, fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                Format.money(total),
                                modifier = Modifier.weight(1f),
                                fontSize = 52.sp, fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold, textAlign = TextAlign.End,
                                maxLines = 1, softWrap = false,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
            Divider()

            // ---- Entry + running total ----
            Column(Modifier.fillMaxWidth().padding(12.dp).navigationBarsPadding()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Amount") },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(
                            fontSize = 30.sp, fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold, textAlign = TextAlign.End
                        ),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { addNow() }),
                        modifier = Modifier.weight(1f).focusRequester(focus)
                    )
                    IconButton(onClick = { if (input.isNotEmpty()) input = "" else if (entries.isNotEmpty()) entries.removeAt(entries.lastIndex) }) {
                        Icon(Icons.Filled.Backspace, contentDescription = "Remove last")
                    }
                    Button(onClick = { addNow() }) { Text("+", fontSize = 26.sp, fontWeight = FontWeight.Bold) }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("TOTAL  (${entries.size})", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(
                        Format.money(total),
                        fontSize = 34.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }

    // Long-press edit: change or delete one amount, total recalculates.
    if (editIndex in entries.indices) {
        val idx = editIndex
        var text by remember(idx) { mutableStateOf(Format.money(entries[idx])) }
        AlertDialog(
            onDismissRequest = { editIndex = -1 },
            title = { Text("Edit amount ${idx + 1}") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' } },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(
                        fontSize = 30.sp, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold, textAlign = TextAlign.End
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val v = text.toDoubleOrNull()
                    if (v != null && v > 0.0) entries[idx] = v
                    editIndex = -1
                }) { Text("Save") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { entries.removeAt(idx); editIndex = -1 }) { Text("Delete") }
                    TextButton(onClick = { editIndex = -1 }) { Text("Cancel") }
                }
            }
        )
    }
}
