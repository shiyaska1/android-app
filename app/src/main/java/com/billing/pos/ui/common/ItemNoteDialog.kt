package com.billing.pos.ui.common

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.billing.pos.ocr.rememberImageCamera
import com.billing.pos.util.RichText
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Full-screen description for one line of a voucher.
 *
 * Close and "Add to item list" sit in the top bar, clear of the phone's navigation bar.
 * The text is not shown inline in the item list once saved — the line keeps a note icon
 * instead, and tapping it reopens this editor with the text still there.
 */
@Composable
fun ItemNoteDialog(
    itemName: String,
    initialNote: String,
    loadSuggestions: suspend () -> List<String> = { emptyList() },
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initialNote) }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    // Past notes typed on other items — most-used first, tap to fill (still editable after).
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var showSuggestions by remember { mutableStateOf(false) }
    var suggestQuery by remember { mutableStateOf("") }
    val filteredSuggestions = remember(suggestions, suggestQuery) {
        if (suggestQuery.isBlank()) suggestions
        else suggestions.filter { it.contains(suggestQuery, ignoreCase = true) }
    }
    LaunchedEffect(Unit) { suggestions = loadSuggestions() }

    // Draw, or scan a photo and drag a box over the text to fill the note.
    var drawNote by remember { mutableStateOf(false) }
    var noteOcrUri by remember { mutableStateOf<android.net.Uri?>(null) }
    fun appendNote(t: String) { if (t.isNotBlank()) text = (text.trimEnd() + " " + t).trim() }
    if (drawNote) {
        HandwriteTextDialog(
            onResult = { appendNote(it); drawNote = false },
            onDismiss = { drawNote = false }
        )
    }
    noteOcrUri?.let { u ->
        RegionOcrDialog(
            uri = u,
            fieldLabel = "the description",
            onResult = { appendNote(it); noteOcrUri = null },
            onDismiss = { noteOcrUri = null }
        )
    }
    val noteCamera = rememberImageCamera { uri -> noteOcrUri = uri }
    val noteGallery = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) noteOcrUri = uri }

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
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Close") }
                Button(
                    onClick = { onSave(text.trim()); onDismiss() },
                    modifier = Modifier.weight(1.6f)
                ) { Text("Add to item list") }
            }
            Divider()

            Text(
                itemName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
            )
            Text(
                "This description prints under the item name on the quotation and its PDF.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 14.dp)
            )

            // Fill the description by hand, from a photo (drag a box over the text), or pick a past note.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(onClick = { drawNote = true }, modifier = Modifier.weight(1f)) {
                    Text("Draw", style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(onClick = { noteCamera() }, modifier = Modifier.weight(1f)) {
                    Text("Camera", style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(
                    onClick = {
                        noteGallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Gallery", style = MaterialTheme.typography.labelMedium) }
                if (suggestions.isNotEmpty()) {
                    OutlinedButton(onClick = { showSuggestions = !showSuggestions }, modifier = Modifier.weight(1f)) {
                        Text("Suggest", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            if (showSuggestions && suggestions.isNotEmpty()) {
                Card(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
                    Column(Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = suggestQuery,
                            onValueChange = { suggestQuery = it },
                            placeholder = { Text("Search past notes…") },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(8.dp)
                        )
                        Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                            if (filteredSuggestions.isEmpty()) {
                                Text(
                                    "No matches",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                                )
                            }
                            filteredSuggestions.forEach { note ->
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clickable { text = note; showSuggestions = false }
                                        .padding(start = 12.dp, end = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        note,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        modifier = Modifier.weight(1f).padding(vertical = 10.dp)
                                    )
                                    IconButton(onClick = { clipboard.setText(AnnotatedString(note)) }) {
                                        Icon(
                                            Icons.Filled.ContentCopy,
                                            contentDescription = "Copy to clipboard",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            RichTextEditor(
                value = text,
                onValueChange = { text = it },
                placeholder = "Write the description…",
                modifier = Modifier.fillMaxSize().padding(14.dp)
            )
        }
    }
}

/**
 * Full-screen formatted-text editor, used for anything longer than one line — the
 * quotation's terms and conditions, for instance.
 */
@Composable
fun RichTextFullScreen(
    heading: String,
    hint: String,
    initial: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }

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
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(
                    onClick = { onSave(text.trim()); onDismiss() },
                    modifier = Modifier.weight(1.6f)
                ) { Text("Done") }
            }
            Divider()
            Text(
                heading,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
            )
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 14.dp)
            )
            RichTextEditor(
                value = text,
                onValueChange = { text = it },
                placeholder = "Write here…",
                modifier = Modifier.fillMaxSize().padding(14.dp)
            )
        }
    }
}

/**
 * A text box with bold / italic / bigger / smaller buttons that act on whatever is selected.
 *
 * The formatting is kept as markers in the text itself (see [com.billing.pos.util.RichText]),
 * so the preview underneath shows what will actually be printed.
 */
@Composable
fun RichTextEditor(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    var field by remember { mutableStateOf(TextFieldValue(value)) }
    // Keep in step when the caller replaces the text (loading a saved note, for instance).
    if (field.text != value && field.text.isBlank() && value.isNotBlank()) {
        field = TextFieldValue(value)
    }

    fun apply(transform: (String, Int, Int) -> Pair<String, IntRange>) {
        val sel = field.selection
        val (out, range) = transform(field.text, minOf(sel.start, sel.end), maxOf(sel.start, sel.end))
        field = TextFieldValue(out, TextRange(range.first, range.last))
        onValueChange(out)
    }

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = { apply { t, s, e -> RichText.toggleWrap(t, s, e, "*") } },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) { Text("B", fontWeight = FontWeight.Bold) }
            OutlinedButton(
                onClick = { apply { t, s, e -> RichText.toggleWrap(t, s, e, "_") } },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) { Text("I", fontStyle = FontStyle.Italic) }
            OutlinedButton(
                onClick = { apply { t, s, e -> RichText.stepSize(t, s, e, 3f) } },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) { Text("A+") }
            OutlinedButton(
                onClick = { apply { t, s, e -> RichText.stepSize(t, s, e, -3f) } },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) { Text("A−") }
            Text(
                "select text first",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }

        OutlinedTextField(
            value = field,
            onValueChange = { field = it; onValueChange(it.text) },
            placeholder = { Text(placeholder) },
            modifier = Modifier.fillMaxWidth().weight(1f)
        )

        if (value.isNotBlank()) {
            Text(
                "Preview",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 8.dp)
            )
            Column(Modifier.fillMaxWidth().weight(0.6f).verticalScroll(rememberScrollState())) {
                Text(RichText.annotated(value), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
