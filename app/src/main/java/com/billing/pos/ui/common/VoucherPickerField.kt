package com.billing.pos.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import com.billing.pos.data.Bill
import com.billing.pos.data.Purchase
import com.billing.pos.data.PurchaseQuotation
import com.billing.pos.util.Format

/**
 * A searchable dropdown of past sales invoices. Type to filter by invoice no / customer;
 * picking one calls [onPick]. Shows the picked invoice number in the field.
 *
 * Built on a plain field + [SearchPickList], not `ExposedDropdownMenuBox` — that popup-based
 * combobox fights with the keyboard on real devices (typing the first character snaps the
 * field back to the old value and blocks further typing).
 */
@Composable
fun InvoicePickerField(
    bills: List<Bill>,
    selectedNo: String,
    onPick: (Bill) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf(selectedNo) }
    LaunchedEffect(selectedNo) { if (!expanded) query = selectedNo }
    val filtered = remember(query, bills) {
        val q = query.trim()
        (if (q.isBlank()) bills
        else bills.filter { it.billNo.contains(q, true) || it.customerName.contains(q, true) })
            .sortedByDescending { it.dateMillis }
            .take(50)
    }
    Column(modifier.fillMaxWidth().padding(top = 6.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it; expanded = true },
            label = { Text("Return against invoice") },
            placeholder = { Text("Search invoice no or customer…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
                .onFocusChanged { fs ->
                    if (fs.isFocused) { query = ""; expanded = true }
                    else { expanded = false; query = selectedNo }
                }
        )
        if (expanded) {
            SearchPickList(
                items = filtered,
                itemLabel = { "${it.billNo}   ${Format.rupee(it.grandTotal)}\n${it.customerName} • ${Format.date(it.dateMillis)}" },
                onPick = { b -> onPick(b); query = b.billNo; expanded = false },
                emptyText = "No matching invoice"
            )
        }
    }
}

/** Same as [InvoicePickerField] but for past purchases (purchase return). */
@Composable
fun PurchasePickerField(
    purchases: List<Purchase>,
    selectedNo: String,
    onPick: (Purchase) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf(selectedNo) }
    LaunchedEffect(selectedNo) { if (!expanded) query = selectedNo }
    val filtered = remember(query, purchases) {
        val q = query.trim()
        (if (q.isBlank()) purchases
        else purchases.filter { it.purchaseNo.contains(q, true) || it.supplierName.contains(q, true) })
            .sortedByDescending { it.dateMillis }
            .take(50)
    }
    Column(modifier.fillMaxWidth().padding(top = 6.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it; expanded = true },
            label = { Text("Return against purchase") },
            placeholder = { Text("Search purchase no or supplier…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
                .onFocusChanged { fs ->
                    if (fs.isFocused) { query = ""; expanded = true }
                    else { expanded = false; query = selectedNo }
                }
        )
        if (expanded) {
            SearchPickList(
                items = filtered,
                itemLabel = { "${it.purchaseNo}   ${Format.rupee(it.grandTotal)}\n${it.supplierName} • ${Format.date(it.dateMillis)}" },
                onPick = { p -> onPick(p); query = p.purchaseNo; expanded = false },
                emptyText = "No matching purchase"
            )
        }
    }
}

/** Searchable dropdown of purchase orders (LPO), filtered to a supplier when [supplierId] > 0. */
@Composable
fun LpoPickerField(
    lpos: List<PurchaseQuotation>,
    supplierId: Long,
    selectedNo: String,
    label: String = "Against purchase order (LPO)",
    onPick: (PurchaseQuotation) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf(selectedNo) }
    LaunchedEffect(selectedNo) { if (!expanded) query = selectedNo }
    val filtered = remember(query, lpos, supplierId) {
        val q = query.trim()
        lpos.filter { (supplierId <= 0 || it.supplierId == supplierId) &&
            (q.isBlank() || it.lpoNo.contains(q, true) || it.supplierName.contains(q, true)) }
            .sortedByDescending { it.dateMillis }
            .take(50)
    }
    Column(modifier.fillMaxWidth().padding(top = 6.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it; expanded = true },
            label = { Text(label) },
            placeholder = { Text("Search LPO no or supplier…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
                .onFocusChanged { fs ->
                    if (fs.isFocused) { query = ""; expanded = true }
                    else { expanded = false; query = selectedNo }
                }
        )
        if (expanded) {
            SearchPickList(
                items = filtered,
                itemLabel = { "${it.lpoNo}   ${Format.rupee(it.grandTotal)}\n${it.supplierName} • ${Format.date(it.dateMillis)}" },
                onPick = { l -> onPick(l); query = l.lpoNo; expanded = false },
                emptyText = "No matching LPO"
            )
        }
    }
}
