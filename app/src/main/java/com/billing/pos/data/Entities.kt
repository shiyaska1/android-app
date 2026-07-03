package com.billing.pos.data

import androidx.room.Entity
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

/** A saved bill (invoice header). */
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
    val grandTotal: Double
)

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
