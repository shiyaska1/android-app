package com.billing.pos.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A customer. The seeded "Cash" customer has isDefault = true. */
@Entity(tableName = "customers")
data class Customer(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phone: String = "",
    val address: String = "",
    val isDefault: Boolean = false
)

/** A saleable item. taxPercent = 0.0 means "without tax". */
@Entity(tableName = "items")
data class Item(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val price: Double,
    val taxPercent: Double = 0.0
)

/** A saved bill (invoice header). `source` marks where it came from ("" = this device). */
@Entity(tableName = "bills")
data class Bill(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val billNo: String,
    val dateMillis: Long,
    val customerId: Long,
    val customerName: String,
    val paymentMethod: String,
    val subTotal: Double,
    val taxTotal: Double,
    val additionalCharge: Double,
    val discount: Double,
    val grandTotal: Double,
    /** Amount already received. Credit invoices start at 0; others = grandTotal. */
    val paidAmount: Double = 0.0,
    val source: String = ""
) {
    val balance: Double get() = (grandTotal - paidAmount).coerceAtLeast(0.0)
    val paymentStatus: String
        get() = when {
            paymentMethod != PaymentMethod.CREDIT.label -> "Paid"
            balance <= 0.001 -> "Paid"
            paidAmount > 0.001 -> "Partial"
            else -> "Credit"
        }
}

/** A single line on a saved bill. */
@Entity(tableName = "bill_items")
data class BillItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val billId: Long,
    val name: String,
    val qty: Double,
    val price: Double,
    val taxPercent: Double,
    val lineTotal: Double
)

/** Convenience wrapper: a bill plus its lines. */
data class BillWithItems(
    val bill: Bill,
    val lines: List<BillItem>
)

enum class PaymentMethod(val label: String) {
    CASH("Cash"), UPI("UPI"), CARD("Card"), CREDIT("Credit")
}

/** Payment mode for receipts and expenses (no "Credit"). */
enum class PayMode(val label: String) {
    CASH("Cash"), UPI("UPI"), CARD("Card")
}

/** A receipt voucher: money received against a (credit) invoice. */
@Entity(tableName = "receipts")
data class Receipt(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val receiptNo: String,
    val billId: Long,
    val billNo: String,
    val customerName: String,
    val dateMillis: Long,
    val amount: Double,
    val paymentMode: String,
    val source: String = ""
)

/** A payment / expense voucher: money paid out. */
@Entity(tableName = "expenses")
data class Expense(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val voucherNo: String,
    val dateMillis: Long,
    val description: String,
    val amount: Double,
    val paymentMode: String,
    val source: String = ""
)

enum class Role(val label: String) {
    SUPER_USER("Super User"), ADMIN("Owner / Admin"), SALESMAN("Salesman")
}

/**
 * An app user. Permissions are decided by a super user at creation time and
 * are never altered by data import.
 */
@Entity(tableName = "users", indices = [Index(value = ["username"], unique = true)])
data class User(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val username: String,
    val passwordHash: String,
    val role: Role,
    // Invoice
    val canCreateInvoice: Boolean = true,
    val canEditInvoice: Boolean = false,
    val canDeleteInvoice: Boolean = false,
    val canViewInvoice: Boolean = true,
    // Receipt
    val canCreateReceipt: Boolean = false,
    val canEditReceipt: Boolean = false,
    val canDeleteReceipt: Boolean = false,
    val canViewReceipt: Boolean = false,
    // Payment / expense
    val canCreatePayment: Boolean = false,
    val canEditPayment: Boolean = false,
    val canDeletePayment: Boolean = false,
    val canViewPayment: Boolean = false,
    // Data & admin
    val canExport: Boolean = true,
    val canImport: Boolean = false,
    val canManageUsers: Boolean = false,
    val active: Boolean = true
)
