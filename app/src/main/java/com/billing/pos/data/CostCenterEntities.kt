package com.billing.pos.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A cost center / department (e.g. "Branch A", "Sales Dept") for tagging journal lines. */
@Entity(tableName = "cost_centers")
data class CostCenter(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val isSystem: Boolean = false
)
