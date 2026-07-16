package com.billing.pos.ui.billing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Full-screen mobile-number confirmation board.
 *
 * You type the number in the box at the bottom (your side); the top half shows it in a very
 * large font rotated 180°, so the customer sitting opposite reads it the right way up and can
 * confirm it. Copy puts it on the clipboard; "Add to items" drops it into the bill's item list.
 */
@Composable
fun MobileNumberDialog(
    initial: String = "",
    onAddToItems: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var number by remember { mutableStateOf(initial.filter { it.isDigit() }) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .systemBarsPadding()
                .imePadding()
        ) {
            // ---- Customer side: upside-down so it reads correctly from the opposite seat ----
            Box(
                Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    Modifier.fillMaxWidth().graphicsLayer { rotationZ = 180f },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val shown = number.ifBlank { "----------" }
                    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                        // Auto-fit: shrink the font so the whole number always fits on one line.
                        val fs = (maxWidth.value / (shown.length.coerceAtLeast(1) * 0.62f)).coerceIn(20f, 110f)
                        Text(
                            shown,
                            modifier = Modifier.fillMaxWidth(),
                            fontSize = fs.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Text(
                        "Please check your number",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }
            }
            Divider()

            // ---- Your side: type the number ----
            Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                OutlinedTextField(
                    value = number,
                    onValueChange = { number = it.filter { c -> c.isDigit() }.take(15) },
                    label = { Text("Mobile number") },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(
                        fontSize = 34.sp, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            if (number.isNotBlank()) {
                                clipboard.setText(AnnotatedString(number))
                                android.widget.Toast.makeText(context, "Number copied", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Copy") }
                    Button(
                        onClick = { if (number.isNotBlank()) { onAddToItems(number); onDismiss() } },
                        modifier = Modifier.weight(1.2f)
                    ) { Text("Add to items") }
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Close") }
                }
            }
        }
    }
}
