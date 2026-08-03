package com.billing.pos.data

import androidx.room.Entity
import androidx.room.PrimaryKey

object DepreciationMethod {
    const val STRAIGHT_LINE = "StraightLine"
    const val WDV = "WDV"
}

/** A fixed asset (e.g. a machine, a vehicle) tracked for book value and depreciation. */
@Entity(tableName = "fixed_assets")
data class FixedAsset(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val category: String = "",
    val purchaseDate: Long,
    val purchaseCost: Double,
    val depreciationMethod: String = DepreciationMethod.STRAIGHT_LINE,
    val ratePercent: Double = 0.0,
    val accumulatedDepreciation: Double = 0.0,
    /** The Purchase this asset was bought against, if any. 0 = entered manually. */
    val linkedPurchaseId: Long = 0,
    val remarks: String = "",
    val deviceId: String = ""
) {
    val bookValue: Double get() = (purchaseCost - accumulatedDepreciation).coerceAtLeast(0.0)
}
