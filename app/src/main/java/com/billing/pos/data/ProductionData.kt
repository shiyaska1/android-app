package com.billing.pos.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * A recipe: how much of each raw material (plus a labour cost) it takes to produce
 * [producedQty] of [producedItemName] — one "batch". A production run scales this batch up or
 * down to whatever quantity is actually being produced.
 */
@Entity(tableName = "production_procedures")
data class ProductionProcedure(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val producedItemId: Long,
    val producedItemName: String,
    val producedQty: Double,
    val unit: String = "",
    /** Labour cost for producing exactly [producedQty] — scaled per-run same as the materials. */
    val labourCost: Double = 0.0,
    val remarks: String = ""
)

@Entity(tableName = "production_procedure_materials")
data class ProductionProcedureMaterial(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val procedureId: Long,
    val itemId: Long = 0,
    val name: String,
    /** Quantity of this raw material needed for one batch ([ProductionProcedure.producedQty]). */
    val qty: Double,
    val unit: String = ""
)

data class ProcedureWithMaterials(val procedure: ProductionProcedure, val materials: List<ProductionProcedureMaterial>)

/**
 * One actual production event: procedure X was run to produce [qtyProduced] of the item. The
 * raw-material consumption and the finished-item stock increase are recorded as a linked
 * [MaterialOut] + [MaterialReceipt] (so stock reports need no special-casing for Production) —
 * this row is the cost summary and the link between the two.
 */
@Entity(tableName = "production_runs")
data class ProductionRun(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runNo: String,
    val dateMillis: Long,
    val procedureId: Long,
    val procedureName: String,
    val producedItemId: Long,
    val producedItemName: String,
    val qtyProduced: Double,
    val unit: String = "",
    val materialCost: Double,
    val labourCost: Double,
    val totalCost: Double,
    val unitCost: Double,
    val remarks: String = "",
    val materialOutId: Long = 0,
    val materialReceiptId: Long = 0
)

@Dao
interface ProductionDao {
    // ---- procedures (recipes) ----
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertProcedureHeader(p: ProductionProcedure): Long
    @Insert suspend fun insertProcedureMaterials(lines: List<ProductionProcedureMaterial>)
    @Update suspend fun updateProcedureHeader(p: ProductionProcedure)
    @Query("DELETE FROM production_procedure_materials WHERE procedureId = :id") suspend fun deleteProcedureMaterials(id: Long)
    @Delete suspend fun deleteProcedureHeader(p: ProductionProcedure)

    @Transaction
    suspend fun saveProcedure(p: ProductionProcedure, materials: List<ProductionProcedureMaterial>): Long {
        val id = insertProcedureHeader(p); insertProcedureMaterials(materials.map { it.copy(id = 0, procedureId = id) }); return id
    }
    @Transaction
    suspend fun updateProcedure(p: ProductionProcedure, materials: List<ProductionProcedureMaterial>) {
        updateProcedureHeader(p); deleteProcedureMaterials(p.id); insertProcedureMaterials(materials.map { it.copy(id = 0, procedureId = p.id) })
    }
    @Transaction
    suspend fun deleteProcedure(p: ProductionProcedure) { deleteProcedureMaterials(p.id); deleteProcedureHeader(p) }

    @Query("SELECT * FROM production_procedures ORDER BY name ASC") fun observeProcedures(): Flow<List<ProductionProcedure>>
    @Query("SELECT * FROM production_procedures") suspend fun allProcedures(): List<ProductionProcedure>
    @Query("SELECT * FROM production_procedures WHERE id = :id LIMIT 1") suspend fun procedureById(id: Long): ProductionProcedure?
    @Query("SELECT * FROM production_procedure_materials WHERE procedureId = :id") suspend fun materialsFor(id: Long): List<ProductionProcedureMaterial>
    @Query("SELECT * FROM production_procedure_materials") suspend fun allProcedureMaterials(): List<ProductionProcedureMaterial>

    // ---- runs ----
    @Insert suspend fun insertRun(r: ProductionRun): Long
    @Delete suspend fun deleteRun(r: ProductionRun)
    @Query("SELECT COUNT(*) FROM production_runs") suspend fun countRuns(): Int
    @Query("SELECT * FROM production_runs ORDER BY dateMillis DESC") fun observeRuns(): Flow<List<ProductionRun>>
    @Query("SELECT * FROM production_runs") suspend fun allRuns(): List<ProductionRun>
    @Query("SELECT * FROM production_runs WHERE id = :id LIMIT 1") suspend fun runById(id: Long): ProductionRun?
}
