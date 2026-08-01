package com.billing.pos.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import kotlin.math.abs

/**
 * A calculator tape that hands its total back.
 *
 * Enter an amount and press + or −; each entry is added to a running tape with the total
 * at the bottom. OK returns that total to the caller — used to fill an amount field
 * without the user having to add things up on a separate calculator.
 */
@Composable
fun CalculatorDialog(
    initial: Double = 0.0,
    onOk: (Double) -> Unit,
    onDismiss: () -> Unit
) {
    val entries = remember { mutableStateListOf<Double>().apply { if (initial > 0.0) add(initial) } }
    var input by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val mulDivFocus = remember { FocusRequester() }
    val scroll = rememberScrollState()
    val total = entries.sum()

    var confirmRemoveLast by remember { mutableStateOf(false) }
    var showMulDivDialog by remember { mutableStateOf(false) }
    var mulDivOp by remember { mutableStateOf('*') }
    var mulDivFactor by remember { mutableStateOf("") }
    var showNoAmountAlert by remember { mutableStateOf(false) }
    var showDivByZeroAlert by remember { mutableStateOf(false) }

    fun add(sign: Int) {
        val v = input.toDoubleOrNull()
        if (v != null && v > 0.0) entries.add(v * sign)
        input = ""
        focus.requestFocus()
    }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(entries.size) { runCatching { scroll.animateScrollTo(scroll.maxValue) } }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Column(
            Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .safeDrawingPadding()
                .imePadding()
        ) {
            // Actions on top, clear of the navigation bar.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(
                    onClick = {
                        // Anything still typed counts, so OK never silently drops it.
                        val pending = input.toDoubleOrNull() ?: 0.0
                        onOk(total + if (pending > 0.0) pending else 0.0)
                    },
                    modifier = Modifier.weight(1.4f)
                ) { Text("OK — use total") }
            }
            Divider()

            // Capped rather than weight(1f): a full-height tape pushes the amount box and the
            // +/- keys under the keyboard and the navigation bar, where they can't be reached.
            Box(
                Modifier.fillMaxWidth().fillMaxHeight(0.62f)
                    .background(MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    if (entries.isEmpty()) {
                        Text(
                            "Type an amount, then + or −",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                    entries.forEachIndexed { i, v ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (v < 0) "-" else if (i == 0) " " else "+",
                                fontSize = 28.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.65f)
                            )
                            Text(
                                Format.money(abs(v)),
                                modifier = Modifier.weight(1f),
                                fontSize = 30.sp, fontFamily = FontFamily.Monospace,
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
                                "=", fontSize = 34.sp, fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                Format.money(total),
                                modifier = Modifier.weight(1f),
                                fontSize = 44.sp, fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold, textAlign = TextAlign.End,
                                maxLines = 1, softWrap = false,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
            Divider()

            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Amount") },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(
                        fontSize = 26.sp, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold, textAlign = TextAlign.End
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add(1) }),
                    modifier = Modifier.weight(1f).focusRequester(focus)
                )
                IconButton(onClick = {
                    if (input.isNotEmpty()) input = "" else if (entries.isNotEmpty()) confirmRemoveLast = true
                }) { Icon(Icons.Filled.Backspace, contentDescription = "Remove last") }
                // Multiplication button
                OutlinedButton(onClick = {
                    val cur = input.toDoubleOrNull()
                    if (cur == null) { showNoAmountAlert = true; return@OutlinedButton }
                    mulDivOp = '*'
                    mulDivFactor = ""
                    showMulDivDialog = true
                }) { Text("×", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                // Division button
                OutlinedButton(onClick = {
                    val cur = input.toDoubleOrNull()
                    if (cur == null) { showNoAmountAlert = true; return@OutlinedButton }
                    mulDivOp = '/'
                    mulDivFactor = ""
                    showMulDivDialog = true
                }) { Text("÷", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                OutlinedButton(onClick = { add(-1) }) { Text("−", fontSize = 24.sp, fontWeight = FontWeight.Bold) }
                Button(onClick = { add(1) }) { Text("+", fontSize = 24.sp, fontWeight = FontWeight.Bold) }
            }
        }

        // Confirm delete last entry
        if (confirmRemoveLast) {
            AlertDialog(
                onDismissRequest = { confirmRemoveLast = false },
                title = { Text("Delete last entry?") },
                text = { Text("This will remove the last amount from the tape. Continue?") },
                confirmButton = {
                    Button(onClick = { confirmRemoveLast = false; if (entries.isNotEmpty()) entries.removeAt(entries.lastIndex) }) { Text("Delete") }
                },
                dismissButton = { OutlinedButton(onClick = { confirmRemoveLast = false }) { Text("Cancel") } }
            )
        }

        // Mul/Div factor dialog
        if (showMulDivDialog) {
            val keyboardController = LocalSoftwareKeyboardController.current
            AlertDialog(
                onDismissRequest = { showMulDivDialog = false },
                title = { Text(if (mulDivOp == '*') "Multiply amount" else "Divide amount") },
                text = {
                    Column {
                        LaunchedEffect(Unit) {
                            runCatching { mulDivFocus.requestFocus() }
                            keyboardController?.show()
                        }
                        OutlinedTextField(
                            value = mulDivFactor,
                            onValueChange = { mulDivFactor = it.filter { c -> c.isDigit() || c == '.' } },
                            label = { Text("Enter number") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                            modifier = Modifier.focusRequester(mulDivFocus).fillMaxWidth()
                        )
                        if (mulDivOp == '/' && mulDivFactor.toDoubleOrNull() == 0.0 && mulDivFactor.isNotBlank()) {
                            Text("Cannot divide by zero", color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        val factor = mulDivFactor.toDoubleOrNull()
                        val cur = input.toDoubleOrNull()
                        if (factor == null || cur == null) { showMulDivDialog = false; return@Button }
                        if (mulDivOp == '/' && factor == 0.0) {
                            showMulDivDialog = false; showDivByZeroAlert = true; return@Button
                        }
                        val res = if (mulDivOp == '*') cur * factor else cur / factor
                        input = Format.money(res)
                        showMulDivDialog = false
                        focus.requestFocus()
                    }) { Text("Apply") }
                },
                dismissButton = { OutlinedButton(onClick = { showMulDivDialog = false }) { Text("Cancel") } }
            )
        }

        if (showNoAmountAlert) {
            AlertDialog(
                onDismissRequest = { showNoAmountAlert = false },
                title = { Text("No amount") },
                text = { Text("Enter an amount first in the Amount field before using × or ÷.") },
                confirmButton = { Button(onClick = { showNoAmountAlert = false }) { Text("OK") } }
            )
        }

        if (showDivByZeroAlert) {
            AlertDialog(
                onDismissRequest = { showDivByZeroAlert = false },
                title = { Text("Cannot divide") },
                text = { Text("Division by zero is not allowed.") },
                confirmButton = { Button(onClick = { showDivByZeroAlert = false }) { Text("OK") } }
            )
        }
    }
}
