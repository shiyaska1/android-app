package com.billing.pos.print

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.billing.pos.data.Bill
import com.billing.pos.data.BillItem
import com.billing.pos.data.CompanyInfo
import com.billing.pos.data.Receipt
import com.billing.pos.util.Format
import java.util.UUID

/**
 * Minimal ESC/POS printing over classic Bluetooth (SPP) for 58mm thermal printers (32 cols).
 * Pair the printer in Android Settings first; this picks the first paired printer, or one
 * whose name contains "print"/"pos"/"bt".
 */
object ThermalPrinter {

    private const val COLS = 32
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    class PrinterException(message: String) : Exception(message)

    fun hasConnectPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun printBill(context: Context, company: CompanyInfo, bill: Bill, lines: List<BillItem>) {
        sendBytes(context, buildReceipt(company, bill, lines))
    }

    /** Prints a receipt voucher (money received) to give to the customer. */
    @SuppressLint("MissingPermission")
    fun printReceipt(context: Context, company: CompanyInfo, receipt: Receipt) {
        sendBytes(context, buildReceiptVoucher(company, receipt))
    }

    @SuppressLint("MissingPermission")
    private fun sendBytes(context: Context, bytes: ByteArray) {
        if (!hasConnectPermission(context)) throw PrinterException("Bluetooth permission not granted")

        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: throw PrinterException("No Bluetooth on this device")
        if (!adapter.isEnabled) throw PrinterException("Bluetooth is turned off")

        val device = pickPrinter(adapter)
            ?: throw PrinterException("No paired printer found. Pair it in Settings first.")

        var socket: BluetoothSocket? = null
        try {
            socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            adapter.cancelDiscovery()
            socket.connect()
            val out = socket.outputStream
            out.write(bytes)
            out.flush()
            Thread.sleep(400) // let the buffer flush before closing
        } catch (e: Exception) {
            throw PrinterException(e.message ?: "Failed to print")
        } finally {
            runCatching { socket?.close() }
        }
    }

    @SuppressLint("MissingPermission")
    private fun pickPrinter(adapter: BluetoothAdapter): BluetoothDevice? {
        val bonded = adapter.bondedDevices ?: emptySet()
        return bonded.firstOrNull {
            val n = (it.name ?: "").lowercase()
            n.contains("print") || n.contains("pos") || n.contains("bt") || n.contains("thermal")
        } ?: bonded.firstOrNull()
    }

    // ---- ESC/POS byte building -------------------------------------------------

    private const val ESC = 0x1B
    private const val GS = 0x1D

    private fun buildReceipt(company: CompanyInfo, bill: Bill, lines: List<BillItem>): ByteArray {
        val sb = StringBuilder()
        sb.append(center(company.name)).append('\n')
        if (company.address.isNotBlank()) sb.append(center(company.address)).append('\n')
        if (company.phone.isNotBlank()) sb.append(center("Ph: ${company.phone}")).append('\n')
        sb.append(center("TAX INVOICE")).append('\n')
        sb.append(line()).append('\n')
        sb.append("Bill: ${bill.billNo}\n")
        sb.append("Date: ${Format.dateTime(bill.dateMillis)}\n")
        sb.append("Cust: ${bill.customerName}\n")
        sb.append("Pay : ${bill.paymentMethod}\n")
        sb.append(line()).append('\n')
        sb.append(row("Item", "Qty", "Amount")).append('\n')
        sb.append(line()).append('\n')
        for (l in lines) {
            sb.append(clip(l.name, COLS)).append('\n')
            sb.append(row("  @${Format.money(l.price)}", Format.qty(l.qty), Format.money(l.lineTotal)))
                .append('\n')
        }
        sb.append(line()).append('\n')
        sb.append(kv("Sub Total", Format.money(bill.subTotal))).append('\n')
        sb.append(kv("Tax", Format.money(bill.taxTotal))).append('\n')
        if (bill.additionalCharge != 0.0)
            sb.append(kv("Additional", Format.money(bill.additionalCharge))).append('\n')
        if (bill.discount != 0.0)
            sb.append(kv("Discount", "-" + Format.money(bill.discount))).append('\n')
        sb.append(line()).append('\n')
        sb.append(kv("GRAND TOTAL", Format.money(bill.grandTotal))).append('\n')
        sb.append(line()).append('\n')
        sb.append(center("Thank you! Visit again.")).append('\n')
        sb.append("\n\n\n")

        val text = sb.toString().toByteArray(Charsets.US_ASCII)
        val init = byteArrayOf(ESC.toByte(), '@'.code.toByte())          // initialize
        val cut = byteArrayOf(GS.toByte(), 'V'.code.toByte(), 66, 0)      // partial cut (ignored if unsupported)
        return init + text + cut
    }

    private fun buildReceiptVoucher(company: CompanyInfo, r: Receipt): ByteArray {
        val sb = StringBuilder()
        sb.append(center(company.name)).append('\n')
        if (company.address.isNotBlank()) sb.append(center(company.address)).append('\n')
        if (company.phone.isNotBlank()) sb.append(center("Ph: ${company.phone}")).append('\n')
        sb.append(center("RECEIPT VOUCHER")).append('\n')
        sb.append(line()).append('\n')
        sb.append("No  : ${r.receiptNo}\n")
        sb.append("Date: ${Format.dateTime(r.dateMillis)}\n")
        sb.append("From: ${r.payFrom.ifBlank { r.customerName }}\n")
        if (r.billNo.isNotBlank()) sb.append("Ref : ${r.billNo}\n")
        sb.append("Mode: ${r.paymentMode}\n")
        sb.append(line()).append('\n')
        sb.append(kv("RECEIVED", Format.money(r.amount))).append('\n')
        sb.append(line()).append('\n')
        sb.append(center("Thank you")).append('\n')
        sb.append("\n\n\n")

        val text = sb.toString().toByteArray(Charsets.US_ASCII)
        val init = byteArrayOf(ESC.toByte(), '@'.code.toByte())
        val cut = byteArrayOf(GS.toByte(), 'V'.code.toByte(), 66, 0)
        return init + text + cut
    }

    private fun line() = "-".repeat(COLS)

    private fun center(s: String): String {
        val t = clip(s, COLS)
        val pad = (COLS - t.length) / 2
        return " ".repeat(pad.coerceAtLeast(0)) + t
    }

    /** Left key, right value on the same 32-col line. */
    private fun kv(key: String, value: String): String {
        val space = (COLS - key.length - value.length).coerceAtLeast(1)
        return key + " ".repeat(space) + value
    }

    /** Three columns: left(16) center(6) right(10). */
    private fun row(a: String, b: String, cRight: String): String {
        val left = a.take(16).padEnd(16)
        val mid = b.take(6).padStart(6)
        val right = cRight.take(10).padStart(10)
        return (left + mid + right).take(COLS)
    }

    private fun clip(s: String, max: Int) = if (s.length <= max) s else s.take(max)
}
