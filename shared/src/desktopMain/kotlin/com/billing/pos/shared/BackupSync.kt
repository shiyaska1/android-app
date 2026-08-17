package com.billing.pos.shared

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backup push/pull to the same server endpoint the Android app already uses
 * (server/pos_backup_sync.php) — it just stores/serves raw bytes keyed by
 * ?org=...&device=..., so the desktop app gets its own independent slot on the
 * same infrastructure without needing byte-for-byte compatibility with the
 * Android app's backup format.
 */
object BackupSync {
    private val MASTER_TABLES = listOf(
        Triple("customers", "name", listOf("name", "phone", "address", "gstin", "isDefault", "customerType")),
        Triple("items", "name", listOf("name", "price", "purchasePrice", "taxPercent", "barcode", "category", "unit")),
        Triple("suppliers", "name", listOf("name", "phone", "address", "gstin", "isDefault"))
    )

    // table, naturalKeyColumn, insertColumns (customerId/supplierId deliberately excluded —
    // nothing reads them today, every screen matches by name; re-mapping stale numeric FKs
    // across databases isn't worth the complexity until something actually depends on it).
    private val PARENT_TABLES = listOf(
        Triple("bills", "billNo", listOf("billNo", "dateMillis", "customerId", "customerName", "subTotal", "taxTotal", "grandTotal")),
        Triple("purchases", "purchaseNo", listOf("purchaseNo", "dateMillis", "supplierId", "supplierName", "subTotal", "taxTotal", "grandTotal")),
        Triple("receipts", "receiptNo", listOf("receiptNo", "customerName", "dateMillis", "amount", "paymentMode")),
        Triple("expenses", "voucherNo", listOf("voucherNo", "dateMillis", "description", "amount", "paymentMode", "payTo"))
    )

    private val LINE_TABLES = listOf(
        Triple("bill_items", "bills" to "billId", listOf("billId", "name", "qty", "price", "taxPercent", "lineTotal")),
        Triple("purchase_items", "purchases" to "purchaseId", listOf("purchaseId", "name", "qty", "price", "taxPercent", "lineTotal"))
    )

    private fun tableToJson(conn: Connection, table: String): JSONArray {
        val arr = JSONArray()
        conn.createStatement().use { st ->
            st.executeQuery("SELECT * FROM $table").use { rs ->
                val meta = rs.metaData
                while (rs.next()) {
                    val obj = JSONObject()
                    for (i in 1..meta.columnCount) obj.put(meta.getColumnName(i), rs.getObject(i))
                    arr.put(obj)
                }
            }
        }
        return arr
    }

    /** Builds a zip with one full-backup.json holding every table's rows — the desktop
     * equivalent of the Android app's FullBackup, sized to this simplified schema. */
    fun createBackupZip(): File {
        val conn = DesktopDatabase.rawConnection()
        val root = JSONObject()
        (MASTER_TABLES.map { it.first } + PARENT_TABLES.map { it.first } + LINE_TABLES.map { it.first })
            .forEach { table -> root.put(table, tableToJson(conn, table)) }

        val zipFile = File(DesktopDatabase.appDataDir(), "desktop-backup.zip")
        ZipOutputStream(zipFile.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("full-backup.json"))
            zos.write(root.toString().toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return zipFile
    }

    data class RestoreReport(val rowsRestored: Int, val rowsSkipped: Int)

    private fun bindJsonValue(ps: PreparedStatement, index: Int, obj: JSONObject, col: String) {
        when (val v = obj.opt(col)) {
            is Int -> ps.setLong(index, v.toLong())
            is Long -> ps.setLong(index, v)
            is Double -> ps.setDouble(index, v)
            is Boolean -> ps.setInt(index, if (v) 1 else 0)
            is String -> ps.setString(index, v)
            else -> ps.setString(index, "")
        }
    }

    private fun existingKeys(conn: Connection, table: String, keyCol: String): Set<String> {
        val keys = mutableSetOf<String>()
        conn.createStatement().use { st ->
            st.executeQuery("SELECT $keyCol FROM $table").use { rs -> while (rs.next()) keys += rs.getString(1) }
        }
        return keys
    }

    /** Restores from a backup zip. Replace = wipe all tables first, then insert everything.
     * Merge = keep existing rows, skip an incoming row if a natural key (name for masters,
     * voucher number for vouchers) already exists locally. Line items follow their parent
     * voucher via an old-id -> new-id map, and are skipped along with a skipped parent. */
    fun restoreBackupZip(zipFile: File, merge: Boolean): RestoreReport {
        var json: JSONObject? = null
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == "full-backup.json") json = JSONObject(String(zis.readBytes(), Charsets.UTF_8))
                entry = zis.nextEntry
            }
        }
        val root = json ?: return RestoreReport(0, 0)
        val conn = DesktopDatabase.rawConnection()
        var restored = 0
        var skipped = 0

        if (!merge) {
            conn.createStatement().use { st ->
                (LINE_TABLES.map { it.first } + PARENT_TABLES.map { it.first } + MASTER_TABLES.map { it.first })
                    .forEach { st.execute("DELETE FROM $it") }
            }
        }

        MASTER_TABLES.forEach { (table, keyCol, cols) ->
            val arr = root.optJSONArray(table) ?: return@forEach
            val existing = if (merge) existingKeys(conn, table, keyCol) else emptySet()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (merge && obj.optString(keyCol) in existing) { skipped++; continue }
                conn.prepareStatement("INSERT INTO $table (${cols.joinToString(", ")}) VALUES (${cols.joinToString(", ") { "?" }})").use { ps ->
                    cols.forEachIndexed { idx, col -> bindJsonValue(ps, idx + 1, obj, col) }
                    ps.executeUpdate()
                }
                restored++
            }
        }

        val parentIdMaps = mutableMapOf<String, MutableMap<Long, Long>>()
        PARENT_TABLES.forEach { (table, keyCol, cols) ->
            val arr = root.optJSONArray(table) ?: return@forEach
            val existing = if (merge) existingKeys(conn, table, keyCol) else emptySet()
            val idMap = parentIdMaps.getOrPut(table) { mutableMapOf() }
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (merge && obj.optString(keyCol) in existing) { skipped++; continue }
                val oldId = obj.optLong("id")
                conn.prepareStatement(
                    "INSERT INTO $table (${cols.joinToString(", ")}) VALUES (${cols.joinToString(", ") { "?" }})",
                    Statement.RETURN_GENERATED_KEYS
                ).use { ps ->
                    cols.forEachIndexed { idx, col ->
                        // customerId/supplierId aren't read anywhere today — store 0 rather
                        // than a stale numeric id copied from a different database.
                        if (col == "customerId" || col == "supplierId") ps.setLong(idx + 1, 0) else bindJsonValue(ps, idx + 1, obj, col)
                    }
                    ps.executeUpdate()
                    ps.generatedKeys.use { keys -> if (keys.next()) idMap[oldId] = keys.getLong(1) }
                }
                restored++
            }
        }

        LINE_TABLES.forEach { (table, parentInfo, cols) ->
            val (parentTable, parentIdCol) = parentInfo
            val arr = root.optJSONArray(table) ?: return@forEach
            val idMap = parentIdMaps[parentTable] ?: emptyMap()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val newParentId = idMap[obj.optLong(parentIdCol)]
                if (newParentId == null) { skipped++; continue }
                conn.prepareStatement("INSERT INTO $table (${cols.joinToString(", ")}) VALUES (${cols.joinToString(", ") { "?" }})").use { ps ->
                    cols.forEachIndexed { idx, col ->
                        if (col == parentIdCol) ps.setLong(idx + 1, newParentId) else bindJsonValue(ps, idx + 1, obj, col)
                    }
                    ps.executeUpdate()
                }
                restored++
            }
        }

        return RestoreReport(restored, skipped)
    }

    /** POSTs [zip]'s raw bytes to [pushUrl]?org=...&device=..., same wire contract as the
     * Android app's CloudSyncManager against server/pos_backup_sync.php. Returns null on
     * success, or an error message. */
    fun push(pushUrl: String, org: String, device: String, zip: File): String? {
        return try {
            val sep = if (pushUrl.contains("?")) "&" else "?"
            val conn = URL("$pushUrl${sep}org=$org&device=$device").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.setRequestProperty("Content-Type", "application/zip")
            val len = zip.length()
            conn.setFixedLengthStreamingMode(len)
            val sent = conn.outputStream.use { out -> zip.inputStream().use { it.copyTo(out) } }
            val code = conn.responseCode
            conn.disconnect()
            if (sent != len || code !in 200..299) "Push failed (HTTP $code)" else null
        } catch (e: Exception) {
            "${e.javaClass.simpleName}${e.message?.let { " — $it" } ?: ""}"
        }
    }

    /** GETs the zip back from [pullUrl]?org=...&device=..., saving it to a temp file.
     * Returns the file on success, or null with [onError] called with a message. */
    fun pull(pullUrl: String, org: String, device: String, onError: (String) -> Unit): File? {
        return try {
            val sep = if (pullUrl.contains("?")) "&" else "?"
            val conn = URL("$pullUrl${sep}org=$org&device=$device").openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            val code = conn.responseCode
            if (code != 200) {
                conn.disconnect()
                onError(if (code == 404) "No backup found on the server for this Org ID / Device ID yet" else "Pull failed (HTTP $code)")
                return null
            }
            val dest = File(DesktopDatabase.appDataDir(), "desktop-pulled.zip")
            conn.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
            conn.disconnect()
            dest
        } catch (e: Exception) {
            onError("${e.javaClass.simpleName}${e.message?.let { " — $it" } ?: ""}")
            null
        }
    }
}
