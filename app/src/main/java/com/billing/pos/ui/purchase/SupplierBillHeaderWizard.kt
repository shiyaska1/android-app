package com.billing.pos.ui.purchase

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import com.billing.pos.ui.common.RegionOcrDialog

private val HEADER_FIELD_LABELS = listOf("the supplier name", "the bill number", "the bill date", "the total amount")

/**
 * Walks the user through marking each header field on [pageUri] one at a time, using the same
 * drag-a-box-and-OCR mechanic already used elsewhere in the app — asking one question at a time
 * reads far more reliably off a real bill than guessing the layout automatically.
 */
@Composable
fun SupplierBillHeaderWizard(
    pageUri: Uri,
    onDone: (supplierText: String, billNo: String, dateText: String, totalText: String) -> Unit,
    onDismiss: () -> Unit
) {
    var step by remember { mutableStateOf(0) }
    val results = remember { mutableStateListOf("", "", "", "") }
    // Keying on step forces a fresh box-selection state for each field instead of keeping the
    // previous field's drawn rectangle on screen.
    key(step) {
        RegionOcrDialog(
            uri = pageUri,
            fieldLabel = HEADER_FIELD_LABELS[step],
            onResult = { text ->
                results[step] = text
                if (step < HEADER_FIELD_LABELS.lastIndex) step++
                else onDone(results[0], results[1], results[2], results[3])
            },
            onDismiss = onDismiss
        )
    }
}
