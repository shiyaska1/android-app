package com.billing.pos.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A supplier (vendor). The seeded "Cash Supplier" has isDefault = true. */
@Entity(tableName = "suppliers")
data class Supplier(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phone: String = "",
    val address: String = "",
    val gstin: String = "",
    val isDefault: Boolean = false
)

/** A purchase bill header (mirror of Bill, but for buying from a supplier). */
@Entity(tableName = "purchases")
data class Purchase(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val purchaseNo: String,
    val dateMillis: Long,
    val supplierId: Long,
    val supplierName: String,
    val paymentMethod: String,
    val subTotal: Double,
    val taxTotal: Double,
    val additionalCharge: Double,
    val discount: Double,
    val grandTotal: Double,
    val paidAmount: Double = 0.0,
    val supplierGstin: String = "",
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

/** A single line on a purchase. */
@Entity(tableName = "purchase_items")
data class PurchaseItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val purchaseId: Long,
    val name: String,
    val qty: Double,
    val price: Double,
    val taxPercent: Double,
    val lineTotal: Double,
    /** Batch/lot received (when batch tracking is on). */
    val batchNo: String = ""
)
