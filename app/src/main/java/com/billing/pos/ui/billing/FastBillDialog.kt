package com.billing.pos.ui.billing

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.runtime.collectAsState
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fast bill — a calculator tape. Type an amount and press Enter (Enter acts as "+"); each amount
 * is added to a big running tape the customer can read, with "=" total at the bottom. Save drops
 * every amount into the bill as its own price-only line (no item name), ready to print.
 *
 * Close/Save live in the TOP bar: the phone's navigation bar can never cover them.
 */
/** One-shot hand-off of a calculator tape from the dashboard into a new sale. */
object FastBillLink {
    @Volatile var amounts: List<Double> = emptyList()
    fun take(): List<Double> { val v = amounts; amounts = emptyList(); return v }
}

/** One tape entry: a signed amount, plus an optional name typed in "label mode". */
private data class CalcEntry(val amount: Double, val label: String = "")

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FastBillDialog(
    onSave: (List<Double>) -> Unit,
    onDismiss: () -> Unit,
    /** When > 0, that saved calculation is loaded onto the tape as soon as it's found —
     *  lets another screen (the calculator-label usage history) open straight into it. */
    initialCalcId: Long = 0L
) {
    val entries = remember { mutableStateListOf<CalcEntry>() }
    var input by remember { mutableStateOf("") }
    // Label mode: when on, every + / − asks for a name (with autocomplete) before adding.
    var labelMode by remember { mutableStateOf(false) }
    var pendingSign by remember { mutableStateOf(0) }
    var pendingAmount by remember { mutableStateOf(0.0) }
    var labelInput by remember { mutableStateOf("") }
    var showShareChoice by remember { mutableStateOf(false) }
    var showPrintChoice by remember { mutableStateOf(false) }
    // Chrome-free full-screen tape, sized to fit the whole thing on one screen — for handing
    // the phone to the customer and letting them screenshot it.
    var showFullView by remember { mutableStateOf(false) }
    // "Sale bill": turns Save/Share's usual quick-due-plus-PDF hand-off into a real, priced
    // sale invoice (payment method asked first) instead — see askPaymentMethod below.
    var isSaleBill by remember { mutableStateOf(false) }
    var askPaymentMethod by remember { mutableStateOf(false) }
    var loadedInitialCalc by remember { mutableStateOf(false) }
    // Id of the saved tape being edited, or 0 while this is a fresh calculation.
    var savedId by remember { mutableStateOf(0L) }
    var showSaved by remember { mutableStateOf(false) }
    // The save popup, and what it collects.
    var askSave by remember { mutableStateOf(false) }
    var custName by remember { mutableStateOf(com.billing.pos.data.SavedCalc.DEFAULT_CUSTOMER) }
    var custId by remember { mutableStateOf(0L) }
    var custPhone by remember { mutableStateOf("") }
    var narration by remember { mutableStateOf("") }
    // Which customer the saved list is filtered to; blank means all of them.
    var listFilter by remember { mutableStateOf("") }
    // Which customer type the saved list is filtered to; blank means all of them.
    var typeFilter by remember { mutableStateOf("") }
    // Customer whose type is being changed via the pen icon next to their name in the saved list.
    var editTypeFor by remember { mutableStateOf<com.billing.pos.data.Customer?>(null) }
    // Date range, on by default, showing today only — the common case is "what did I
    // calculate today"; switch it off to see the full history.
    var dateRangeOn by remember { mutableStateOf(true) }
    var editReceipt by remember { mutableStateOf<com.billing.pos.data.Receipt?>(null) }
    var qrAmount by remember { mutableStateOf<Double?>(null) }
    var fromMillis by remember { mutableStateOf(defaultFromMillis()) }
    var toMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var editIndex by remember { mutableStateOf(-1) }
    val focus = remember { FocusRequester() }
    val mulDivFocus = remember { FocusRequester() }
    val labelFocus = remember { FocusRequester() }
    val scroll = rememberScrollState()
    val total = entries.sumOf { it.amount }
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { com.billing.pos.data.AppPrefs(context) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Leaves a little breathing room below the +/-/x/div row, clear of the phone's gesture bar.
    val bottomPad = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * 0.05f

    // --- New states for multiply/divide and remove-last confirmation
    var confirmRemoveLast by remember { mutableStateOf(false) }
    var showMulDivDialog by remember { mutableStateOf(false) }
    var mulDivOp by remember { mutableStateOf('*') }
    var mulDivFactor by remember { mutableStateOf("") }
    var showNoAmountAlert by remember { mutableStateOf(false) }
    var showDivByZeroAlert by remember { mutableStateOf(false) }
    var confirmDeleteCalc by remember { mutableStateOf<com.billing.pos.data.SavedCalc?>(null) }
    var confirmNewCalc by remember { mutableStateOf(false) }

    /** Clears the tape (and the customer/narration it was for) to start over, without closing
     *  the dialog — the "New calculation" toolbar icon. */
    fun resetTape() {
        entries.clear()
        input = ""
        savedId = 0L
        custId = 0L
        custName = com.billing.pos.data.SavedCalc.DEFAULT_CUSTOMER
        custPhone = ""
        narration = ""
        isSaleBill = false
        focus.requestFocus()
    }

    /** [sign] is +1 for the "+" key and -1 for "−"; a minus entry is stored negative. In label
     *  mode, the amount is held pending and a popup asks for its name before it's added. */
    fun addNow(sign: Int = 1) {
        val v = input.toDoubleOrNull()
        if (v == null || v <= 0.0) return
        if (labelMode) {
            pendingAmount = v
            pendingSign = sign
            labelInput = ""
            input = ""
        } else {
            entries.add(CalcEntry(v * sign))
            input = ""
            focus.requestFocus()
        }
    }

    /** The tape as plain text, for sharing and for the diary copy. */
    fun tapeText(): String = buildString {
        entries.forEachIndexed { i, e ->
            val sign = if (e.amount < 0) "-" else if (i == 0) " " else "+"
            append(sign).append(' ')
            if (e.label.isNotBlank()) append(e.label).append(": ")
            append(Format.money(kotlin.math.abs(e.amount))).append('\n')
        }
        append("= ").append(Format.money(entries.sumOf { it.amount }))
    }

    fun saveToDiary() {
        if (entries.isEmpty()) return
        val body = tapeText()
        val sum = Format.money(entries.sumOf { it.amount })
        scope.launch {
            com.billing.pos.diary.QuickDiaryNote.save(context, "Fast bill $sum", body)
        }
    }

    val repo = remember { com.billing.pos.data.Repository(context) }
    val downloadCalcPdf = com.billing.pos.ui.common.rememberPdfDownloader { msg ->
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
    val savedCalcs: List<com.billing.pos.data.SavedCalc> by
        repo.savedCalcs.collectAsState(initial = emptyList())
    val customers by repo.customers.collectAsState(initial = emptyList<com.billing.pos.data.Customer>())

    /** Whatever is on the tape right now, the box's pending typed amount included. */
    fun currentEntries(): List<CalcEntry> {
        val pending = input.toDoubleOrNull()
        return if (pending != null && pending > 0.0) entries + CalcEntry(pending) else entries.toList()
    }

    /** The named customer (typed or picked), creating them if they're new — or null for the
     *  walk-in placeholder. Shared by the save/quick-due path and the sale-invoice path. */
    suspend fun resolveCustomer(): com.billing.pos.data.Customer? {
        val typedName = custName.trim()
        return when {
            typedName.isBlank() || typedName == com.billing.pos.data.SavedCalc.DEFAULT_CUSTOMER -> null
            custId > 0 -> customers.firstOrNull { it.id == custId }
            else -> customers.firstOrNull { it.name.equals(typedName, ignoreCase = true) }
                ?: repo.addCustomerReturning(typedName, custPhone, "General")
        }
    }

    /**
     * Stores the tape (updating the one being edited rather than piling up copies). When a real
     * customer is named (not the walk-in placeholder) and "Sale bill" is off, also attaches the
     * total to them as a credit due dated today, with the tape PDF filed against the customer —
     * same mechanism as a customer's "Add invoice" quick due. With "Sale bill" on, that step is
     * skipped instead: [askPaymentMethod] (shown by the caller) turns the tape into a real,
     * priced invoice instead of a due, so a second attachment would be redundant. [onShare], if
     * given, shares the tape afterward (Save & Share).
     */
    fun storeTape(onDone: (String) -> Unit, onShare: ((String) -> Unit)? = null) {
        val all = currentEntries()
        if (all.isEmpty()) { onDone("Nothing to save"); return }
        val total = all.sumOf { it.amount }
        scope.launch {
            val customer = resolveCustomer()
            val fresh = savedId == 0L
            // Carried forward, then reconciled below — every save keeps the due and its PDF
            // in step with the calculation's current customer/total, not just the first one.
            val prior = if (fresh) null else repo.calcById(savedId)
            val id = repo.saveCalc(
                com.billing.pos.data.SavedCalc(
                    id = savedId,
                    dateMillis = System.currentTimeMillis(),
                    amounts = com.billing.pos.data.SavedCalc.pack(all.map { it.amount }),
                    labels = com.billing.pos.data.SavedCalc.packLabels(all.map { it.label }),
                    total = total,
                    customerId = customer?.id ?: 0L,
                    customerName = customer?.name ?: com.billing.pos.data.SavedCalc.DEFAULT_CUSTOMER,
                    narration = narration.trim(),
                    linkedBillId = prior?.linkedBillId ?: 0L,
                    linkedAttachmentId = prior?.linkedAttachmentId ?: 0L
                )
            )
            savedId = id
            var msg = if (fresh) "Calculation saved" else "Calculation updated"

            // Whatever this calculation had previously created (a quick due, a PDF) no longer
            // applies — the customer was cleared, the total dropped to zero, or "Sale bill" is
            // now on (a real invoice replaces the due, via the payment-method popup instead).
            suspend fun clearStaleLinks() {
                if (prior == null || (prior.linkedBillId <= 0 && prior.linkedAttachmentId <= 0)) return
                prior.linkedAttachmentId.takeIf { it > 0 }?.let { repo.removeCustomerAttachment(it) }
                val oldBill = prior.linkedBillId.takeIf { it > 0 }?.let { repo.billById(it) }
                repo.calcById(id)?.let { repo.saveCalc(it.copy(linkedBillId = 0, linkedAttachmentId = 0)) }
                oldBill?.let { repo.removeQuickInvoiceIfSafe(it) }
            }

            if (isSaleBill) {
                clearStaleLinks()
            } else if (customer != null && total > 0.0) {
                val billId = repo.syncQuickInvoice(customer, total, narration.trim(), prior?.linkedBillId ?: 0L)
                // The PDF is filed fresh every save (old copy removed first) rather than
                // updated in place, same as the Share button's PDF option's content.
                prior?.linkedAttachmentId?.takeIf { it > 0 }?.let { repo.removeCustomerAttachment(it) }
                var attId = 0L
                runCatching {
                    val company = com.billing.pos.data.AppPrefs(context).company
                    com.billing.pos.pdf.ThermalPdf.calcTapeFile(
                        context, company, customer.name, customer.phone, narration.trim(), all.map { it.amount to it.label }
                    )
                }.getOrNull()?.let { pdf ->
                    com.billing.pos.data.CustomerAttachmentStore.importFrom(context, pdf.absolutePath, "application/pdf")
                        ?.let { att -> attId = repo.appendCustomerAttachment(customer.id, att) }
                }
                repo.calcById(id)?.let { repo.saveCalc(it.copy(linkedBillId = billId, linkedAttachmentId = attId)) }
                msg += if (fresh) " • ${Format.rupee(total)} added as credit for ${customer.name}"
                else " • due updated to ${Format.rupee(total)} for ${customer.name}"
            } else {
                clearStaleLinks()
            }
            onDone(msg)
            if (onShare != null) {
                val header = if (customer != null) customer.name + (if (customer.phone.isNotBlank()) " - ${customer.phone}" else "") + "\n\n" else ""
                onShare(header + tapeText())
            }
        }
    }

    /** Turns the current tape into a real, priced sale invoice — the "Sale bill" checkbox's
     *  payment-method popup calls this once a method is picked. */
    fun createSaleInvoice(paymentMethod: String) {
        val all = currentEntries()
        if (all.isEmpty()) return
        scope.launch {
            // No named customer: bill it to the real default "Cash Customer" party (not a
            // fresh one of that name — that party already exists with its own ledger head).
            val customer = resolveCustomer()
                ?: customers.firstOrNull { it.isDefault }
                ?: customers.firstOrNull { it.name.equals(com.billing.pos.data.SavedCalc.DEFAULT_CUSTOMER, ignoreCase = true) }
                ?: repo.addCustomerReturning(com.billing.pos.data.SavedCalc.DEFAULT_CUSTOMER, "", "General")
            all.map { it.label }.filter { it.isNotBlank() }.distinct().forEach { repo.ensureItemForLabel(it) }
            repo.addSaleInvoiceFromTape(customer, all.map { it.amount to it.label }, narration.trim(), System.currentTimeMillis(), paymentMethod)
            android.widget.Toast.makeText(context, "Sale invoice created for ${customer.name}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** Opens a saved tape for editing: the amounts/labels, its customer and its narration all
     *  come back, and saving updates this same entry instead of piling up a copy. */
    fun loadCalc(calc: com.billing.pos.data.SavedCalc) {
        entries.clear()
        val amts = calc.amountList
        val lbls = calc.labelList
        entries.addAll(amts.mapIndexed { i, a -> CalcEntry(a, lbls.getOrElse(i) { "" }) })
        savedId = calc.id
        custId = calc.customerId
        custName = calc.customerName
        custPhone = customers.firstOrNull { it.id == calc.customerId }?.phone ?: ""
        narration = calc.narration
        input = ""
    }

    // A specific past calculation to open straight into (from the label-history screen).
    LaunchedEffect(initialCalcId, savedCalcs) {
        if (initialCalcId > 0 && !loadedInitialCalc) {
            savedCalcs.firstOrNull { it.id == initialCalcId }?.let { loadCalc(it); loadedInitialCalc = true }
        }
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
                .padding(bottom = bottomPad)
        ) {
            // ---- TOP BAR: icon-only, evenly spread so it never crowds ----
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close")
                }
                IconButton(onClick = { if (entries.isNotEmpty() || input.isNotBlank()) confirmNewCalc = true else resetTape() }) {
                    Icon(Icons.Filled.RestartAlt, contentDescription = "New calculation")
                }
                // Label mode: on, every + / − asks for a name before the amount is added.
                androidx.compose.material3.Checkbox(
                    checked = labelMode,
                    onCheckedChange = { labelMode = it }
                )
                Text("Label", style = MaterialTheme.typography.labelSmall)
                IconButton(onClick = { showSaved = true }) {
                    Icon(Icons.Filled.ListAlt, contentDescription = "Saved calculations")
                }
                IconButton(
                    onClick = { askSave = true },
                    enabled = entries.isNotEmpty() || (input.toDoubleOrNull() ?: 0.0) > 0.0
                ) { Icon(Icons.Filled.Save, contentDescription = "Save calculation") }
                IconButton(
                    onClick = { if (entries.isNotEmpty()) showShareChoice = true },
                    enabled = entries.isNotEmpty()
                ) { Icon(Icons.Filled.Share, contentDescription = "Share") }
                IconButton(
                    onClick = { if (entries.isNotEmpty()) showFullView = true },
                    enabled = entries.isNotEmpty()
                ) { Icon(Icons.Filled.Fullscreen, contentDescription = "Full screen — show customer") }
                IconButton(
                    // A UPI payment QR for the current total; the customer scans to pay.
                    onClick = {
                        val pending = input.toDoubleOrNull() ?: 0.0
                        val amt = entries.sumOf { it.amount } + if (pending > 0.0) pending else 0.0
                        if (amt > 0.0) qrAmount = amt
                    },
                    enabled = entries.isNotEmpty() || (input.toDoubleOrNull() ?: 0.0) > 0.0
                ) { Icon(Icons.Filled.QrCode2, contentDescription = "UPI QR") }
                // Save the tape into the bill — the primary action, tinted so it stands out.
                IconButton(
                    onClick = {
                        val pending = input.toDoubleOrNull()
                        val all = if (pending != null && pending > 0.0) entries.map { it.amount } + pending else entries.map { it.amount }
                        saveToDiary()
                        if (all.isNotEmpty()) onSave(all)
                        onDismiss()
                    },
                    enabled = entries.isNotEmpty() || (input.toDoubleOrNull() ?: 0.0) > 0.0
                ) { Icon(Icons.Filled.PointOfSale, contentDescription = "Save to bill", tint = MaterialTheme.colorScheme.primary) }
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
                    entries.forEachIndexed { i, e ->
                        // Long-press an amount to edit or delete it; the total recalculates.
                        Row(
                            Modifier.fillMaxWidth()
                                .combinedClickable(onClick = {}, onLongClick = { editIndex = i })
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (e.amount < 0) "-" else if (i == 0) " " else "+",
                                fontSize = 30.sp, fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.65f)
                            )
                            // With a label: two columns — the name on the left, the amount on
                            // the right. Without one: the amount alone, as before.
                            if (e.label.isNotBlank()) {
                                Text(
                                    e.label,
                                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                                    fontSize = 18.sp, fontWeight = FontWeight.Medium,
                                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    Format.money(kotlin.math.abs(e.amount)),
                                    fontSize = 28.sp, fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            } else {
                                Text(
                                    Format.money(kotlin.math.abs(e.amount)),
                                    modifier = Modifier.weight(1f),
                                    fontSize = 34.sp, fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold, textAlign = TextAlign.End,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
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
                // Amount box and remove-last share their own row...
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
                    IconButton(onClick = { if (input.isNotEmpty()) input = "" else if (entries.isNotEmpty()) confirmRemoveLast = true }) {
                        Icon(Icons.Filled.Backspace, contentDescription = "Remove last")
                    }
                }
                // ...×, ÷, − and + get their own row below, so neither row is crowded.
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val cur = input.toDoubleOrNull()
                            if (cur == null) { showNoAmountAlert = true; return@OutlinedButton }
                            mulDivOp = '*'
                            mulDivFactor = ""
                            showMulDivDialog = true
                        }
                    ) { Text("×", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val cur = input.toDoubleOrNull()
                            if (cur == null) { showNoAmountAlert = true; return@OutlinedButton }
                            mulDivOp = '/'
                            mulDivFactor = ""
                            showMulDivDialog = true
                        }
                    ) { Text("÷", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                    OutlinedButton(modifier = Modifier.weight(1f), onClick = { addNow(-1) }) {
                        Text("−", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    }
                    Button(modifier = Modifier.weight(1f), onClick = { addNow(1) }) {
                        Text("+", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    }
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
                // Breathing room below the total so it never sits flush against the edge —
                // the tape above (weight(1f)) shrinks to make room for it automatically.
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    // ---- Save popup: who it is for, and why ----
    if (askSave) {
        var custMenu by remember { mutableStateOf(false) }
        var newCust by remember { mutableStateOf(false) }
        var newCustName by remember { mutableStateOf("") }

        if (newCust) {
            var newCustPhone by remember { mutableStateOf("") }
            var newCustType by remember { mutableStateOf("General") }
            var drawCustName by remember { mutableStateOf(false) }
            if (drawCustName) {
                com.billing.pos.ui.common.HandwriteTextDialog(
                    onResult = { if (it.isNotBlank()) newCustName = it; drawCustName = false },
                    onDismiss = { drawCustName = false }
                )
            }
            AlertDialog(
                onDismissRequest = { newCust = false },
                title = { Text("New customer") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = newCustName, onValueChange = { newCustName = it },
                            label = { Text("Customer name") }, singleLine = true,
                            trailingIcon = {
                                IconButton(onClick = { drawCustName = true }) {
                                    Icon(Icons.Filled.Gesture, "Write name")
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = newCustPhone,
                            onValueChange = { v -> newCustPhone = v.filter { it.isDigit() } },
                            label = { Text("Mobile number (optional)") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        )
                        com.billing.pos.ui.common.CustomerTypeField(
                            value = newCustType, onValue = { newCustType = it },
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val name = newCustName.trim()
                        if (name.isNotBlank()) scope.launch {
                            // Added to the customer list proper, so it is there next time too.
                            val c = repo.addCustomerReturning(name, newCustPhone.trim(), newCustType)
                            custId = c.id; custName = c.name; custPhone = c.phone
                        }
                        newCustName = ""; newCust = false
                    }) { Text("Add") }
                },
                dismissButton = { TextButton(onClick = { newCust = false }) { Text("Cancel") } }
            )
        }

        AlertDialog(
            onDismissRequest = { askSave = false },
            title = { Text("Save calculation") },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        com.billing.pos.ui.common.CustomerPickField(
                            customers = customers,
                            selectedName = custName,
                            onPick = { c -> custName = c.name; custId = c.id; custPhone = c.phone },
                            allowFreeText = true,
                            onTyped = { custName = it; custId = 0L; custPhone = "" },
                            extraOptions = listOf(com.billing.pos.data.SavedCalc.DEFAULT_CUSTOMER),
                            onPickExtra = { custName = it; custId = 0L; custPhone = "" },
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { newCust = true }) {
                            Icon(Icons.Filled.Add, "Add customer", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    OutlinedTextField(
                        value = narration,
                        onValueChange = { narration = it },
                        label = { Text("Narration") },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(checked = isSaleBill, onCheckedChange = { isSaleBill = it })
                        Text("Sale bill", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        askSave = false
                        storeTape(onDone = { msg -> android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show() })
                        if (isSaleBill) askPaymentMethod = true
                    }) { Text("Save") }
                    TextButton(onClick = {
                        askSave = false
                        storeTape(
                            onDone = { msg -> android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show() },
                            onShare = { text -> shareCalcTextTo(context, custName, custPhone, text) }
                        )
                        if (isSaleBill) askPaymentMethod = true
                    }) { Text("Save & Share") }
                }
            },
            dismissButton = { TextButton(onClick = { askSave = false }) { Text("Cancel") } }
        )
    }

    // Saved tapes: newest first, tap one to carry on adding to it.
    if (showSaved) {
        val receipts by repo.allReceipts.collectAsState(initial = emptyList<com.billing.pos.data.Receipt>())
        val savedCustomers by repo.customers.collectAsState(initial = emptyList<com.billing.pos.data.Customer>())
        val typeById = remember(savedCustomers) { savedCustomers.associate { it.id to it.customerType } }
        val availableTypes = remember(savedCustomers) {
            savedCustomers.map { it.customerType }.filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase() }
        }
        val shown = savedCalcs.filter {
            (listFilter.isBlank() || it.customerName.contains(listFilter.trim(), ignoreCase = true)) &&
                (typeFilter.isBlank() || typeById[it.customerId].orEmpty().equals(typeFilter, ignoreCase = true)) &&
                (!dateRangeOn || (it.dateMillis >= startOfDayMillis(fromMillis) && it.dateMillis <= endOfDayMillis(toMillis)))
        }
        val shownTotal = shown.sumOf { it.total }

        // Money already received from a customer since a tape was saved, up to the next
        // tape for that same customer, so a receipt is counted against one calculation only.
        fun receiptsFor(calc: com.billing.pos.data.SavedCalc): List<com.billing.pos.data.Receipt> {
            val nextForCustomer = shown
                .filter { it.customerName.equals(calc.customerName, true) && it.dateMillis > calc.dateMillis }
                .minByOrNull { it.dateMillis }?.dateMillis ?: Long.MAX_VALUE
            return receipts.filter { r ->
                val who = r.payFrom.ifBlank { r.customerName }
                who.equals(calc.customerName, ignoreCase = true) &&
                    r.dateMillis >= calc.dateMillis && r.dateMillis < nextForCustomer
            }
        }
        val shownReceived = shown.sumOf { c -> receiptsFor(c).sumOf { it.amount } }
        var filterMenu by remember { mutableStateOf(false) }

        Dialog(
            onDismissRequest = { showSaved = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            Column(
                Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
                    .safeDrawingPadding()
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Saved calculations",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { shareCalcList(context, shown, listFilter) },
                        enabled = shown.isNotEmpty()
                    ) { Icon(Icons.Filled.Share, "Share list") }
                    IconButton(
                        onClick = { downloadCalcPdf { buildCalcListPdf(context, shown, listFilter) } },
                        enabled = shown.isNotEmpty()
                    ) { Icon(Icons.Filled.PictureAsPdf, "Save as PDF") }
                    OutlinedButton(onClick = { showSaved = false }) { Text("Close") }
                }

                // Filter by customer; "All customers" is the default.
                Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    // Type to narrow, or pick from the arrow — both work on the same box.
                    OutlinedTextField(
                        value = listFilter,
                        onValueChange = { listFilter = it; filterMenu = it.isNotBlank() },
                        label = { Text("Customer (all)") },
                        placeholder = { Text("All customers") },
                        singleLine = true,
                        trailingIcon = {
                            Row {
                                if (listFilter.isNotBlank()) IconButton(onClick = { listFilter = ""; filterMenu = false }) {
                                    Icon(Icons.Filled.Close, "Show all")
                                }
                                IconButton(onClick = { filterMenu = !filterMenu }) {
                                    Icon(Icons.Filled.ArrowDropDown, "Pick customer")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    val names = savedCalcs.map { it.customerName }.distinct()
                        .filter { listFilter.isBlank() || it.contains(listFilter.trim(), ignoreCase = true) }
                        .sorted()
                    androidx.compose.material3.DropdownMenu(
                        expanded = filterMenu, onDismissRequest = { filterMenu = false }
                    ) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("All customers") },
                            onClick = { listFilter = ""; filterMenu = false }
                        )
                        names.forEach { name ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(name) },
                                onClick = { listFilter = name; filterMenu = false }
                            )
                        }
                    }
                }
                // Filter by customer type; "All" is the default.
                if (availableTypes.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        androidx.compose.material3.FilterChip(
                            selected = typeFilter.isBlank(),
                            onClick = { typeFilter = "" },
                            label = { Text("All types", style = MaterialTheme.typography.labelSmall) }
                        )
                        availableTypes.forEach { t ->
                            androidx.compose.material3.FilterChip(
                                selected = typeFilter.equals(t, ignoreCase = true),
                                onClick = { typeFilter = if (typeFilter.equals(t, ignoreCase = true)) "" else t },
                                label = { Text(t, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }
                // Date range, on by default (today) — switch off to see the full history.
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.Checkbox(
                        checked = dateRangeOn,
                        onCheckedChange = { dateRangeOn = it }
                    )
                    Text("Date range", style = MaterialTheme.typography.labelLarge)
                    if (dateRangeOn) {
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(onClick = { pickCalcDate(context, fromMillis) { fromMillis = it } }) {
                            Text(Format.date(fromMillis), style = MaterialTheme.typography.labelSmall)
                        }
                        Text(" — ", style = MaterialTheme.typography.labelSmall)
                        OutlinedButton(onClick = { pickCalcDate(context, toMillis) { toMillis = it } }) {
                            Text(Format.date(toMillis), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Divider()

                if (shown.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("Nothing saved yet", color = MaterialTheme.colorScheme.outline)
                    }
                } else {
                    androidx.compose.foundation.lazy.LazyColumn(
                        Modifier.fillMaxHeight(0.75f).fillMaxWidth()
                    ) {
                        items(shown, key = { it.id }) { calc ->
                            val paid = receiptsFor(calc)
                            val received = paid.sumOf { it.amount }
                            Row(
                                Modifier.fillMaxWidth()
                                    .combinedClickable(onClick = {
                                        // Open in edit mode: the tape, its customer and its
                                        // narration all come back, and saving updates this entry.
                                        loadCalc(calc)
                                        showSaved = false
                                    })
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(calc.customerName, fontWeight = FontWeight.Bold)
                                        if (calc.customerId > 0) {
                                            IconButton(
                                                onClick = { editTypeFor = savedCustomers.firstOrNull { it.id == calc.customerId } },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(Icons.Filled.Edit, "Change customer type", modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    }
                                    Text(
                                        Format.dateTime(calc.dateMillis) + "  •  " + calc.amountList.size + " amount(s)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                    if (calc.narration.isNotBlank()) Text(
                                        calc.narration,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        Format.money(calc.total),
                                        fontSize = 22.sp, fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        textDecoration = if (received > 0.0)
                                            androidx.compose.ui.text.style.TextDecoration.LineThrough else null
                                    )
                                    if (received > 0.0) Text(
                                        "Balance " + Format.money(calc.total - received),
                                        fontSize = 18.sp, fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                IconButton(onClick = { confirmDeleteCalc = calc }) {
                                    Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                            // Each receipt is its own line: tap to correct the amount.
                            paid.forEach { r ->
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clickable { editReceipt = r }
                                        .padding(start = 24.dp, end = 14.dp, bottom = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "Received " + r.receiptNo + "  " + Format.date(r.dateMillis),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        "- " + Format.money(r.amount),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Divider()
                        }
                    }
                }

                // Total of exactly what is listed, so it follows the filter.
                Divider(thickness = 2.dp)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)
                        .navigationBarsPadding(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "TOTAL",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        // The money, big. What is still outstanding once receipts are taken
                        // off is the figure that matters, with the gross above it.
                        if (shownReceived > 0.0) Text(
                            Format.money(shownTotal) + "  −  " + Format.money(shownReceived),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Text(
                            Format.money(shownTotal - shownReceived),
                            fontSize = 40.sp, fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1, softWrap = false,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        "${shown.size} saved",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }

    // Delete a saved calculation only after the user confirms — it can't be undone.
    confirmDeleteCalc?.let { calc ->
        AlertDialog(
            onDismissRequest = { confirmDeleteCalc = null },
            title = { Text("Delete this calculation?") },
            text = { Text(calc.customerName + "  •  " + Format.money(calc.total) + "\nThis cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { repo.deleteCalc(calc.id) }
                    confirmDeleteCalc = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteCalc = null }) { Text("Cancel") } }
        )
    }

    // Change a customer's type right from the saved-calc list, via the pen icon by their name.
    editTypeFor?.let { cust ->
        val prefs = remember { com.billing.pos.data.AppPrefs(context) }
        val allCustomers by repo.customers.collectAsState(initial = emptyList<com.billing.pos.data.Customer>())
        val typeOptions = remember(cust, allCustomers) {
            val inUse = allCustomers.map { it.customerType }.filter { it.isNotBlank() }
            (listOf("General") + prefs.customerTypes + inUse).distinct().sortedBy { it.lowercase() }
        }
        AlertDialog(
            onDismissRequest = { editTypeFor = null },
            title = { Text("Customer type — ${cust.name}") },
            text = {
                Column {
                    Text(
                        "Current: ${cust.customerType.ifBlank { "General" }}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                        typeOptions.forEach { t ->
                            Text(
                                t,
                                fontWeight = if (t.equals(cust.customerType.ifBlank { "General" }, true)) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.fillMaxWidth()
                                    .clickable {
                                        scope.launch { repo.updateCustomer(cust.copy(customerType = t)) }
                                        editTypeFor = null
                                    }
                                    .padding(vertical = 10.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { editTypeFor = null }) { Text("Close") } }
        )
    }

    // Correcting a receipt straight from the calculation it was received against.
    qrAmount?.let { amt ->
        com.billing.pos.ui.common.UpiQrDialog(amount = amt, onDismiss = { qrAmount = null })
    }

    editReceipt?.let { r ->
        var amountText by remember(r.id) { mutableStateOf(Format.money(r.amount)) }
        var mode by remember(r.id) { mutableStateOf(runCatching { com.billing.pos.data.PayMode.valueOf(r.paymentMode.uppercase()) }.getOrDefault(com.billing.pos.data.PayMode.CASH)) }
        AlertDialog(
            onDismissRequest = { editReceipt = null },
            title = { Text("Receipt " + r.receiptNo) },
            text = {
                Column {
                    Text(
                        r.payFrom.ifBlank { r.customerName } + "  •  " + Format.date(r.dateMillis),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Amount") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        com.billing.pos.data.PayMode.values().forEach { m ->
                            androidx.compose.material3.FilterChip(
                                selected = mode == m,
                                onClick = { mode = m },
                                label = { Text(m.label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val v = amountText.toDoubleOrNull()
                    if (v != null && v > 0.0) scope.launch { repo.updateReceipt(r, v, mode) }
                    editReceipt = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editReceipt = null }) { Text("Cancel") } }
        )
    }

    // Long-press edit: change or delete one amount (and its label), total recalculates.
    if (editIndex in entries.indices) {
        val idx = editIndex
        var text by remember(idx) { mutableStateOf(Format.money(kotlin.math.abs(entries[idx].amount))) }
        // Sign is edited here too, so a line entered as + can be switched to − and the
        // total recalculates without deleting and re-typing it.
        var plus by remember(idx) { mutableStateOf(entries[idx].amount >= 0) }
        var labelText by remember(idx) { mutableStateOf(entries[idx].label) }
        AlertDialog(
            onDismissRequest = { editIndex = -1 },
            title = { Text("Edit amount ${idx + 1}") },
            text = {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.material3.FilterChip(
                            selected = plus,
                            onClick = { plus = true },
                            label = { Text("+ Add", fontWeight = FontWeight.Bold) }
                        )
                        androidx.compose.material3.FilterChip(
                            selected = !plus,
                            onClick = { plus = false },
                            label = { Text("− Subtract", fontWeight = FontWeight.Bold) }
                        )
                    }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' } },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(
                            fontSize = 30.sp, fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold, textAlign = TextAlign.End
                        ),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    OutlinedTextField(
                        value = labelText,
                        onValueChange = { labelText = it },
                        label = { Text("Label") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val v = text.toDoubleOrNull()
                    if (v != null && v > 0.0) entries[idx] = CalcEntry(if (plus) v else -v, labelText.trim())
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

    // ---- New dialogs: confirm remove-last and multiply/divide ----
    if (confirmRemoveLast) {
        AlertDialog(
            onDismissRequest = { confirmRemoveLast = false },
            title = { Text("Remove last amount?") },
            text = { Text("This removes the last entered amount from the tape.") },
            confirmButton = {
                TextButton(onClick = { confirmRemoveLast = false; if (entries.isNotEmpty()) entries.removeAt(entries.lastIndex) }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemoveLast = false }) { Text("Cancel") } }
        )
    }

    if (showMulDivDialog) {
        val keyboardController = LocalSoftwareKeyboardController.current
        AlertDialog(
            onDismissRequest = { showMulDivDialog = false },
            title = { Text(if (mulDivOp == '*') "Multiply" else "Divide") },
            text = {
                Column {
                    LaunchedEffect(Unit) {
                        runCatching { mulDivFocus.requestFocus() }
                        keyboardController?.show()
                    }
                    OutlinedTextField(
                        value = mulDivFactor,
                        onValueChange = { mulDivFactor = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Factor") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth().focusRequester(mulDivFocus)
                    )
                    Text(
                        "Applies to the current amount in the box. The result replaces the amount.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val cur = input.toDoubleOrNull() ?: run { showNoAmountAlert = true; showMulDivDialog = false; return@TextButton }
                    val f = mulDivFactor.toDoubleOrNull() ?: run { showNoAmountAlert = true; showMulDivDialog = false; return@TextButton }
                    if (mulDivOp == '/' && f == 0.0) { showDivByZeroAlert = true; showMulDivDialog = false; return@TextButton }
                    val res = if (mulDivOp == '*') cur * f else cur / f
                    input = Format.money(res)
                    focus.requestFocus()
                    showMulDivDialog = false
                }) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { showMulDivDialog = false }) { Text("Cancel") } }
        )
    }

    if (showNoAmountAlert) {
        AlertDialog(
            onDismissRequest = { showNoAmountAlert = false },
            title = { Text("No amount") },
            text = { Text("Enter an amount before using × or ÷.") },
            confirmButton = { TextButton(onClick = { showNoAmountAlert = false }) { Text("OK") } },
            dismissButton = {}
        )
    }

    if (showDivByZeroAlert) {
        AlertDialog(
            onDismissRequest = { showDivByZeroAlert = false },
            title = { Text("Division by zero") },
            text = { Text("Cannot divide by zero.") },
            confirmButton = { TextButton(onClick = { showDivByZeroAlert = false }) { Text("OK") } },
            dismissButton = {}
        )
    }

    // Label mode's popup: name this amount before it lands on the tape. Previously typed
    // labels (the calc-label master) are offered as suggestions, filtered as it's typed.
    if (pendingSign != 0) {
        val suggestions = remember(labelInput) {
            val q = labelInput.trim()
            prefs.calcLabels.filter { q.isBlank() || it.contains(q, ignoreCase = true) }
                .sortedBy { it.lowercase() }.take(6)
        }
        var drawLabel by remember { mutableStateOf(false) }
        val voiceLabel = com.billing.pos.ui.common.rememberVoiceInput { text -> labelInput = text }
        val cameraLabel = com.billing.pos.ocr.rememberImageCamera { uri ->
            scope.launch {
                com.billing.pos.ocr.TextOcr.lines(context, uri).firstOrNull()?.let { labelInput = it }
            }
        }
        if (drawLabel) {
            com.billing.pos.ui.common.HandwriteTextDialog(
                onResult = { if (it.isNotBlank()) labelInput = it; drawLabel = false },
                onDismiss = { drawLabel = false }
            )
        }
        LaunchedEffect(Unit) { runCatching { labelFocus.requestFocus() } }
        AlertDialog(
            onDismissRequest = { pendingSign = 0 },
            title = {
                Text(
                    (if (pendingSign < 0) "− " else "+ ") + Format.money(pendingAmount),
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    OutlinedTextField(
                        value = labelInput,
                        onValueChange = { labelInput = it },
                        label = { Text("Label") },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        trailingIcon = {
                            Row {
                                IconButton(onClick = { drawLabel = true }) { Icon(Icons.Filled.Gesture, "Write") }
                                IconButton(onClick = { voiceLabel() }) { Icon(Icons.Filled.Mic, "Speak") }
                                IconButton(onClick = { cameraLabel() }) { Icon(Icons.Filled.PhotoCamera, "Read from photo") }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().focusRequester(labelFocus)
                    )
                    if (suggestions.isNotEmpty()) {
                        Column(Modifier.padding(top = 8.dp)) {
                            suggestions.forEach { s ->
                                Text(
                                    s,
                                    fontSize = 18.sp,
                                    modifier = Modifier.fillMaxWidth()
                                        .clickable { labelInput = s }
                                        .padding(vertical = 10.dp)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val lbl = labelInput.trim()
                    entries.add(CalcEntry(pendingAmount * pendingSign, lbl))
                    if (lbl.isNotBlank()) {
                        prefs.addCalcLabel(lbl)
                        scope.launch { repo.ensureItemForLabel(lbl) }
                    }
                    pendingSign = 0
                    focus.requestFocus()
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = {
                    entries.add(CalcEntry(pendingAmount * pendingSign))
                    pendingSign = 0
                    focus.requestFocus()
                }) { Text("Skip") }
            }
        )
    }

    // Share button: text, a PDF (label/amount columns, thermal-receipt width), or Print —
    // same Bluetooth/WiFi choice as a sales invoice. A real customer attached gets the
    // text/PDF sent straight to their WhatsApp chat instead of a generic chooser. "Sale bill"
    // additionally turns this share into a real, priced invoice once a payment method is picked.
    if (showShareChoice) {
        AlertDialog(
            onDismissRequest = { showShareChoice = false },
            title = { Text("Share as") },
            text = {
                Column {
                    Text(
                        "Text", fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth().clickable {
                            showShareChoice = false
                            saveToDiary()
                            if (isSaleBill) askPaymentMethod = true
                            val header = if (custName.isNotBlank() && custName != com.billing.pos.data.SavedCalc.DEFAULT_CUSTOMER)
                                custName + (if (custPhone.isNotBlank()) " - $custPhone" else "") + "\n\n" else ""
                            shareCalcTextTo(context, custName, custPhone, header + tapeText())
                        }.padding(vertical = 12.dp)
                    )
                    Text(
                        "PDF", fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth().clickable {
                            showShareChoice = false
                            saveToDiary()
                            if (isSaleBill) askPaymentMethod = true
                            val all = currentEntries()
                            runCatching {
                                val company = com.billing.pos.data.AppPrefs(context).company
                                com.billing.pos.pdf.ThermalPdf.calcTape(context, company, custName, custPhone, narration, all.map { it.amount to it.label })
                            }.onSuccess { uri -> shareCalcPdfTo(context, custName, custPhone, uri) }
                        }.padding(vertical = 12.dp)
                    )
                    Text(
                        "Print", fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth().clickable {
                            showShareChoice = false
                            if (isSaleBill) askPaymentMethod = true
                            showPrintChoice = true
                        }.padding(vertical = 12.dp)
                    )
                    Divider(Modifier.padding(vertical = 8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(checked = isSaleBill, onCheckedChange = { isSaleBill = it })
                        Text("Sale bill", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showShareChoice = false }) { Text("Cancel") } }
        )
    }

    // Print: same Bluetooth (thermal, paired) / WiFi (system print, no pairing) choice as a
    // sales invoice's Print button.
    if (showPrintChoice) {
        val printPermission = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                val all = currentEntries()
                scope.launch {
                    val company = com.billing.pos.data.AppPrefs(context).company
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            com.billing.pos.print.ThermalPrinter.printCalcTape(context, company, custName, custPhone, narration, all.map { it.amount to it.label })
                        }
                    }
                    result.onSuccess { android.widget.Toast.makeText(context, "Sent to printer", android.widget.Toast.LENGTH_SHORT).show() }
                        .onFailure { android.widget.Toast.makeText(context, it.message ?: "Print failed", android.widget.Toast.LENGTH_SHORT).show() }
                }
            } else {
                android.widget.Toast.makeText(context, "Allow 'Nearby devices' permission to print", android.widget.Toast.LENGTH_LONG).show()
            }
        }
        AlertDialog(
            onDismissRequest = { showPrintChoice = false },
            title = { Text("Print") },
            text = { Text("Choose the printer connection.") },
            confirmButton = {
                TextButton(onClick = {
                    showPrintChoice = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !com.billing.pos.print.ThermalPrinter.hasConnectPermission(context)) {
                        printPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    } else {
                        val all = currentEntries()
                        scope.launch {
                            val company = com.billing.pos.data.AppPrefs(context).company
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    com.billing.pos.print.ThermalPrinter.printCalcTape(context, company, custName, custPhone, narration, all.map { it.amount to it.label })
                                }
                            }
                            result.onSuccess { android.widget.Toast.makeText(context, "Sent to printer", android.widget.Toast.LENGTH_SHORT).show() }
                                .onFailure { android.widget.Toast.makeText(context, it.message ?: "Print failed", android.widget.Toast.LENGTH_SHORT).show() }
                        }
                    }
                }) { Text("Bluetooth (thermal)") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPrintChoice = false
                    val all = currentEntries()
                    runCatching {
                        val company = com.billing.pos.data.AppPrefs(context).company
                        val uri = com.billing.pos.pdf.ThermalPdf.calcTape(context, company, custName, custPhone, narration, all.map { it.amount to it.label })
                        com.billing.pos.print.SystemPrint.printPdf(context, uri, "Calculation")
                    }
                }) { Text("WiFi / System printer") }
            }
        )
    }

    // "Sale bill" payment method: Save/Share create the calculation as before; this turns it
    // into a real, priced invoice too, once a payment method is picked.
    if (askPaymentMethod) {
        AlertDialog(
            onDismissRequest = { askPaymentMethod = false },
            title = { Text("Payment method") },
            text = {
                Column {
                    com.billing.pos.data.PaymentMethod.values().forEach { m ->
                        Text(
                            m.label,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { askPaymentMethod = false; createSaleInvoice(m.label) }
                                .padding(vertical = 12.dp)
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { askPaymentMethod = false }) { Text("Cancel") } }
        )
    }

    // Confirm before wiping an unsaved tape — the "New calculation" toolbar icon.
    if (confirmNewCalc) {
        AlertDialog(
            onDismissRequest = { confirmNewCalc = false },
            title = { Text("Start a new calculation?") },
            text = { Text("This clears the current tape. Unsaved amounts will be lost.") },
            confirmButton = { TextButton(onClick = { confirmNewCalc = false; resetTape() }) { Text("New") } },
            dismissButton = { TextButton(onClick = { confirmNewCalc = false }) { Text("Cancel") } }
        )
    }

    // Chrome-free full-screen tape for the customer to read and screenshot.
    if (showFullView) {
        val fvEntries = currentEntries()
        CalcFullView(
            customerName = custName,
            entries = fvEntries,
            total = fvEntries.sumOf { it.amount },
            onClose = { showFullView = false }
        )
    }

    // ---- end of dialog file helpers ----
}

/**
 * A bare, toolbar-free tape: every entry's label and amount, plus the total, scaled to fit
 * the whole thing on one screen with no scrolling — meant to be handed to the customer and
 * screenshotted. Row height (and so font size) shrinks as entries pile up instead of scrolling
 * off; a single small close button is the only chrome left.
 */
@Composable
private fun CalcFullView(
    customerName: String,
    entries: List<CalcEntry>,
    total: Double,
    onClose: () -> Unit
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        BoxWithConstraints(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).safeDrawingPadding()
        ) {
            // One row per entry, one for the total, one held back for the close button's row —
            // whatever height that leaves each row shrinks the font to fit them all with no
            // scrolling, down to a floor that stays legible in a screenshot.
            val rowCount = (entries.size + 2).coerceAtLeast(3)
            val rowHeight = (maxHeight / rowCount).coerceIn(22.dp, 72.dp)
            val amountFontSize = (rowHeight.value * 0.42f).coerceIn(12f, 34f).sp
            val labelFontSize = (amountFontSize.value * 0.62f).sp

            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Close", modifier = Modifier.size(20.dp))
                    }
                }
                if (customerName.isNotBlank() && customerName != com.billing.pos.data.SavedCalc.DEFAULT_CUSTOMER) {
                    Text(customerName, fontWeight = FontWeight.Bold, fontSize = labelFontSize, modifier = Modifier.padding(bottom = 4.dp))
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    entries.forEachIndexed { i, e ->
                        Row(
                            Modifier.fillMaxWidth().height(rowHeight),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (e.amount < 0) "-" else if (i == 0) " " else "+",
                                fontSize = amountFontSize, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
                            )
                            Text(
                                e.label.ifBlank { "" },
                                modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                                fontSize = labelFontSize, fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            Text(
                                Format.money(kotlin.math.abs(e.amount)),
                                fontSize = amountFontSize, fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold, textAlign = TextAlign.End
                            )
                        }
                    }
                }
                Divider(thickness = 2.dp)
                Row(
                    Modifier.fillMaxWidth().height(rowHeight.coerceAtLeast(40.dp)),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("TOTAL", fontSize = labelFontSize, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(
                        Format.money(total),
                        fontSize = (amountFontSize.value * 1.25f).sp, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

/** Shares the calculator tape as text, preferring WhatsApp and falling back to a chooser. */
private fun shareTapeToWhatsApp(context: android.content.Context, text: String) {
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, text)
    }
    for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
        val direct = android.content.Intent(send).setPackage(pkg)
        if (direct.resolveActivity(context.packageManager) != null) {
            runCatching { context.startActivity(direct) }.onSuccess { return }
        }
    }
    runCatching {
        context.startActivity(
            android.content.Intent.createChooser(send, "Share total")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** Shares the tape's text straight to a real customer's WhatsApp chat (same "jid" trick as a
 *  sales invoice share) when one is attached and has a phone number; otherwise the usual
 *  WhatsApp-preferred chooser. */
private fun shareCalcTextTo(context: android.content.Context, custName: String, custPhone: String, text: String) {
    val digits = custPhone.filter { it.isDigit() }
    if (custName.isNotBlank() && custName != com.billing.pos.data.SavedCalc.DEFAULT_CUSTOMER && digits.isNotEmpty()) {
        fun tryPackage(pkg: String): Boolean {
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, text)
                putExtra("jid", "$digits@s.whatsapp.net")
                setPackage(pkg)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return runCatching { context.startActivity(intent) }.isSuccess
        }
        if (tryPackage("com.whatsapp")) return
        if (tryPackage("com.whatsapp.w4b")) return
    }
    shareTapeToWhatsApp(context, text)
}

/** Shares the tape's PDF straight to a real customer's WhatsApp chat when one is attached and
 *  has a phone number; otherwise the usual chooser. */
private fun shareCalcPdfTo(context: android.content.Context, custName: String, custPhone: String, uri: android.net.Uri) {
    val digits = custPhone.filter { it.isDigit() }
    if (custName.isNotBlank() && custName != com.billing.pos.data.SavedCalc.DEFAULT_CUSTOMER && digits.isNotEmpty()) {
        fun tryPackage(pkg: String): Boolean {
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                putExtra("jid", "$digits@s.whatsapp.net")
                setPackage(pkg)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return runCatching { context.startActivity(intent) }.isSuccess
        }
        if (tryPackage("com.whatsapp")) return
        if (tryPackage("com.whatsapp.w4b")) return
    }
    shareTapePdf(context, uri)
}

/** Shares the current tape's PDF via the ordinary chooser. */
private fun shareTapePdf(context: android.content.Context, uri: android.net.Uri) {
    runCatching {
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            )
        }
        context.startActivity(
            android.content.Intent.createChooser(send, "Share calculation")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** The saved-calculation list as a PDF: one row per tape, filtered exactly as shown. */
private fun buildCalcListPdf(
    context: android.content.Context,
    rows: List<com.billing.pos.data.SavedCalc>,
    customerFilter: String
): java.io.File {
    val cols = listOf(
        com.billing.pos.pdf.TablePdf.Col("Date", 1.5f),
        com.billing.pos.pdf.TablePdf.Col("Customer", 1.6f),
        com.billing.pos.pdf.TablePdf.Col("Narration", 2.4f),
        com.billing.pos.pdf.TablePdf.Col("Amounts", 0.8f, right = true),
        com.billing.pos.pdf.TablePdf.Col("Total", 1.2f, right = true)
    )
    val data = rows.map {
        listOf(
            Format.dateTime(it.dateMillis), it.customerName, it.narration,
            it.amountList.size.toString(), Format.money(it.total)
        )
    }
    return com.billing.pos.pdf.TablePdf.generate(
        context,
        com.billing.pos.data.AppPrefs(context).company,
        "Saved Calculations",
        if (customerFilter.isBlank()) "All customers" else "Customer: " + customerFilter,
        cols, data,
        listOf("Total" to Format.money(rows.sumOf { it.total }))
    )
}

/** Shares that same list as a PDF attachment. */
private fun shareCalcList(
    context: android.content.Context,
    rows: List<com.billing.pos.data.SavedCalc>,
    customerFilter: String
) {
    runCatching {
        val file = buildCalcListPdf(context, rows, customerFilter)
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, context.packageName + ".provider", file
        )
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            )
        }
        context.startActivity(
            android.content.Intent.createChooser(send, "Share calculations")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** The date the range starts on by default: today. */
private fun defaultFromMillis(): Long = System.currentTimeMillis()

private fun startOfDayMillis(m: Long): Long = java.util.Calendar.getInstance().apply {
    timeInMillis = m
    set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
    set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
}.timeInMillis

private fun endOfDayMillis(m: Long): Long = startOfDayMillis(m) + 24L * 60 * 60 * 1000 - 1

private fun pickCalcDate(context: android.content.Context, current: Long, onPicked: (Long) -> Unit) {
    val c = java.util.Calendar.getInstance().apply { timeInMillis = current }
    android.app.DatePickerDialog(
        context,
        { _, y, mo, d ->
            c.set(java.util.Calendar.YEAR, y); c.set(java.util.Calendar.MONTH, mo)
            c.set(java.util.Calendar.DAY_OF_MONTH, d)
            onPicked(c.timeInMillis)
        },
        c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH), c.get(java.util.Calendar.DAY_OF_MONTH)
    ).show()
}
