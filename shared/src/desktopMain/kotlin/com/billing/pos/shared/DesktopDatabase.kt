package com.billing.pos.shared

import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/** Mirrors the Android app's Customer entity column-for-column (id, name, phone, address,
 * gstin, isDefault, customerType) so a later Room-multiplatform migration is a drop-in swap. */
data class Customer(
    val id: Long = 0,
    val name: String,
    val phone: String = "",
    val address: String = "",
    val gstin: String = "",
    val isDefault: Boolean = false,
    val customerType: String = "General"
)

/** Full parity with the Android app's Item entity — every field, none dropped. */
data class Item(
    val id: Long = 0,
    val name: String,
    val price: Double,
    val purchasePrice: Double = 0.0,
    val mrp: Double = 0.0,
    val taxPercent: Double = 0.0,
    val barcode: String = "",
    val hsn: String = "",
    val category: String = "",
    val openingStock: Double = 0.0,
    val unit: String = "PCS",
    val secondaryUnit: String = "PCS",
    val conversionFactor: Double = 1.0,
    val storeLocation: String = "",
    val chemicalContent: String = ""
)

/** Item category master — a simple name list used to populate the Item Master's category field. */
data class Category(val id: Long = 0, val name: String)

/** Mirrors the Android app's Supplier entity column-for-column. */
data class Supplier(
    val id: Long = 0,
    val name: String,
    val phone: String = "",
    val address: String = "",
    val gstin: String = "",
    val isDefault: Boolean = false
)

/** Core subset of the Android app's Bill entity — enough to record a real sale. */
data class Bill(
    val id: Long = 0,
    val billNo: String,
    val dateMillis: Long,
    val customerId: Long,
    val customerName: String,
    val subTotal: Double,
    val taxTotal: Double,
    val grandTotal: Double
)

/** Core subset of the Android app's BillItem entity. */
data class BillLine(
    val id: Long = 0,
    val billId: Long,
    val name: String,
    val qty: Double,
    val price: Double,
    val taxPercent: Double,
    val lineTotal: Double
)

data class BillWithLines(val bill: Bill, val lines: List<BillLine>)

/** Core subset of the Android app's Purchase entity — enough to record a real purchase. */
data class Purchase(
    val id: Long = 0,
    val purchaseNo: String,
    val dateMillis: Long,
    val supplierId: Long,
    val supplierName: String,
    val subTotal: Double,
    val taxTotal: Double,
    val grandTotal: Double
)

/** Core subset of the Android app's PurchaseItem entity. */
data class PurchaseLine(
    val id: Long = 0,
    val purchaseId: Long,
    val name: String,
    val qty: Double,
    val price: Double,
    val taxPercent: Double,
    val lineTotal: Double
)

/** Core subset of the Android app's Receipt entity — money received from a customer. */
data class Receipt(
    val id: Long = 0,
    val receiptNo: String,
    val customerName: String,
    val dateMillis: Long,
    val amount: Double,
    val paymentMode: String
)

/** Core subset of the Android app's Expense entity — money paid out (a general expense or a
 * payment against a purchase). [payTo] is the supplier/payee name, blank for general expenses. */
data class Expense(
    val id: Long = 0,
    val voucherNo: String,
    val dateMillis: Long,
    val description: String,
    val amount: Double,
    val paymentMode: String,
    val payTo: String = ""
)

/** The desktop app's own embedded SQLite database — plain JDBC for now (Room multiplatform,
 * reusing the Android app's 45 migrations as-is, lands in a later batch). Lives in the
 * OS-standard per-user app-data folder, created automatically on first run: no server, no
 * separate install, exactly the same "just works" story the Android app already has. */
object DesktopDatabase {
    private val dbFile: File by lazy {
        val base = System.getenv("APPDATA")?.let { File(it) }
            ?: File(System.getProperty("user.home"), "AppData/Roaming")
        val dir = File(base, "POSBilling").apply { mkdirs() }
        File(dir, "pos_billing.db")
    }

    /** Shown in Settings for transparency — where the local data actually lives on disk. */
    fun databaseFilePath(): String = dbFile.absolutePath

    /** Same per-user app-data folder as the database — used for staging backup zips. */
    fun appDataDir(): File = dbFile.parentFile

    private val connection: Connection by lazy {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").also { conn ->
            conn.createStatement().use { st ->
                st.execute(
                    "CREATE TABLE IF NOT EXISTS customers (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "name TEXT NOT NULL, phone TEXT NOT NULL DEFAULT '', " +
                        "address TEXT NOT NULL DEFAULT '', gstin TEXT NOT NULL DEFAULT '', " +
                        "isDefault INTEGER NOT NULL DEFAULT 0, customerType TEXT NOT NULL DEFAULT 'General')"
                )
                st.execute(
                    "CREATE TABLE IF NOT EXISTS items (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "name TEXT NOT NULL, price REAL NOT NULL DEFAULT 0, " +
                        "purchasePrice REAL NOT NULL DEFAULT 0, mrp REAL NOT NULL DEFAULT 0, " +
                        "taxPercent REAL NOT NULL DEFAULT 0, barcode TEXT NOT NULL DEFAULT '', " +
                        "hsn TEXT NOT NULL DEFAULT '', category TEXT NOT NULL DEFAULT '', " +
                        "openingStock REAL NOT NULL DEFAULT 0, unit TEXT NOT NULL DEFAULT 'PCS', " +
                        "secondaryUnit TEXT NOT NULL DEFAULT 'PCS', conversionFactor REAL NOT NULL DEFAULT 1, " +
                        "storeLocation TEXT NOT NULL DEFAULT '', chemicalContent TEXT NOT NULL DEFAULT '')"
                )
                // In-place upgrade for databases created before these fields existed — SQLite
                // 3.35+ (bundled by sqlite-jdbc) supports IF NOT EXISTS on ADD COLUMN, so this
                // is a no-op on a fresh database and safe to re-run on every startup.
                listOf(
                    "mrp REAL NOT NULL DEFAULT 0", "hsn TEXT NOT NULL DEFAULT ''",
                    "openingStock REAL NOT NULL DEFAULT 0", "secondaryUnit TEXT NOT NULL DEFAULT 'PCS'",
                    "conversionFactor REAL NOT NULL DEFAULT 1", "storeLocation TEXT NOT NULL DEFAULT ''",
                    "chemicalContent TEXT NOT NULL DEFAULT ''"
                ).forEach { colDef -> st.execute("ALTER TABLE items ADD COLUMN IF NOT EXISTS $colDef") }
                st.execute(
                    "CREATE TABLE IF NOT EXISTS suppliers (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "name TEXT NOT NULL, phone TEXT NOT NULL DEFAULT '', " +
                        "address TEXT NOT NULL DEFAULT '', gstin TEXT NOT NULL DEFAULT '', " +
                        "isDefault INTEGER NOT NULL DEFAULT 0)"
                )
                st.execute(
                    "CREATE TABLE IF NOT EXISTS bills (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "billNo TEXT NOT NULL, dateMillis INTEGER NOT NULL, " +
                        "customerId INTEGER NOT NULL, customerName TEXT NOT NULL, " +
                        "subTotal REAL NOT NULL, taxTotal REAL NOT NULL, grandTotal REAL NOT NULL)"
                )
                st.execute(
                    "CREATE TABLE IF NOT EXISTS bill_items (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, billId INTEGER NOT NULL, " +
                        "name TEXT NOT NULL, qty REAL NOT NULL, price REAL NOT NULL, " +
                        "taxPercent REAL NOT NULL, lineTotal REAL NOT NULL)"
                )
                st.execute(
                    "CREATE TABLE IF NOT EXISTS purchases (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "purchaseNo TEXT NOT NULL, dateMillis INTEGER NOT NULL, " +
                        "supplierId INTEGER NOT NULL, supplierName TEXT NOT NULL, " +
                        "subTotal REAL NOT NULL, taxTotal REAL NOT NULL, grandTotal REAL NOT NULL)"
                )
                st.execute(
                    "CREATE TABLE IF NOT EXISTS purchase_items (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, purchaseId INTEGER NOT NULL, " +
                        "name TEXT NOT NULL, qty REAL NOT NULL, price REAL NOT NULL, " +
                        "taxPercent REAL NOT NULL, lineTotal REAL NOT NULL)"
                )
                st.execute(
                    "CREATE TABLE IF NOT EXISTS receipts (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, receiptNo TEXT NOT NULL, " +
                        "customerName TEXT NOT NULL, dateMillis INTEGER NOT NULL, " +
                        "amount REAL NOT NULL, paymentMode TEXT NOT NULL DEFAULT 'Cash')"
                )
                st.execute(
                    "CREATE TABLE IF NOT EXISTS expenses (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, voucherNo TEXT NOT NULL, " +
                        "dateMillis INTEGER NOT NULL, description TEXT NOT NULL, " +
                        "amount REAL NOT NULL, paymentMode TEXT NOT NULL DEFAULT 'Cash', " +
                        "payTo TEXT NOT NULL DEFAULT '')"
                )
                st.execute(
                    "CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)"
                )
                st.execute(
                    "CREATE TABLE IF NOT EXISTS categories (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE)"
                )
            }
        }
    }

    fun allCategories(): List<Category> {
        connection.createStatement().use { st ->
            st.executeQuery("SELECT * FROM categories ORDER BY name").use { rs ->
                val out = mutableListOf<Category>()
                while (rs.next()) out += Category(id = rs.getLong("id"), name = rs.getString("name"))
                return out
            }
        }
    }

    fun addCategory(name: String) {
        connection.prepareStatement("INSERT OR IGNORE INTO categories (name) VALUES (?)").use { ps ->
            ps.setString(1, name)
            ps.executeUpdate()
        }
    }

    fun deleteCategory(id: Long) {
        connection.prepareStatement("DELETE FROM categories WHERE id = ?").use { ps ->
            ps.setLong(1, id)
            ps.executeUpdate()
        }
    }

    // ---- simple key-value settings (Cloud backup sync Push/Pull URL, Org/Device ID, ...) ----
    fun getSetting(key: String, default: String = ""): String {
        connection.prepareStatement("SELECT value FROM settings WHERE key = ?").use { ps ->
            ps.setString(1, key)
            ps.executeQuery().use { rs -> return if (rs.next()) rs.getString("value") else default }
        }
    }

    fun setSetting(key: String, value: String) {
        connection.prepareStatement("INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)").use { ps ->
            ps.setString(1, key)
            ps.setString(2, value)
            ps.executeUpdate()
        }
    }

    fun allCustomers(): List<Customer> {
        connection.createStatement().use { st ->
            st.executeQuery("SELECT * FROM customers ORDER BY name").use { rs ->
                val out = mutableListOf<Customer>()
                while (rs.next()) {
                    out += Customer(
                        id = rs.getLong("id"), name = rs.getString("name"),
                        phone = rs.getString("phone"), address = rs.getString("address"),
                        gstin = rs.getString("gstin"), isDefault = rs.getInt("isDefault") != 0,
                        customerType = rs.getString("customerType")
                    )
                }
                return out
            }
        }
    }

    fun addCustomer(name: String, phone: String, address: String) {
        connection.prepareStatement("INSERT INTO customers (name, phone, address) VALUES (?, ?, ?)").use { ps ->
            ps.setString(1, name)
            ps.setString(2, phone)
            ps.setString(3, address)
            ps.executeUpdate()
        }
    }

    fun deleteCustomer(id: Long) {
        connection.prepareStatement("DELETE FROM customers WHERE id = ?").use { ps ->
            ps.setLong(1, id)
            ps.executeUpdate()
        }
    }

    fun allItems(): List<Item> {
        connection.createStatement().use { st ->
            st.executeQuery("SELECT * FROM items ORDER BY name").use { rs ->
                val out = mutableListOf<Item>()
                while (rs.next()) {
                    out += Item(
                        id = rs.getLong("id"), name = rs.getString("name"),
                        price = rs.getDouble("price"), purchasePrice = rs.getDouble("purchasePrice"),
                        mrp = rs.getDouble("mrp"), taxPercent = rs.getDouble("taxPercent"),
                        barcode = rs.getString("barcode"), hsn = rs.getString("hsn"),
                        category = rs.getString("category"), openingStock = rs.getDouble("openingStock"),
                        unit = rs.getString("unit"), secondaryUnit = rs.getString("secondaryUnit"),
                        conversionFactor = rs.getDouble("conversionFactor"),
                        storeLocation = rs.getString("storeLocation"), chemicalContent = rs.getString("chemicalContent")
                    )
                }
                return out
            }
        }
    }

    fun addItem(item: Item) {
        connection.prepareStatement(
            "INSERT INTO items (name, price, purchasePrice, mrp, taxPercent, barcode, hsn, category, " +
                "openingStock, unit, secondaryUnit, conversionFactor, storeLocation, chemicalContent) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        ).use { ps ->
            ps.setString(1, item.name)
            ps.setDouble(2, item.price)
            ps.setDouble(3, item.purchasePrice)
            ps.setDouble(4, item.mrp)
            ps.setDouble(5, item.taxPercent)
            ps.setString(6, item.barcode)
            ps.setString(7, item.hsn)
            ps.setString(8, item.category)
            ps.setDouble(9, item.openingStock)
            ps.setString(10, item.unit)
            ps.setString(11, item.secondaryUnit)
            ps.setDouble(12, item.conversionFactor)
            ps.setString(13, item.storeLocation)
            ps.setString(14, item.chemicalContent)
            ps.executeUpdate()
        }
    }

    fun deleteItem(id: Long) {
        connection.prepareStatement("DELETE FROM items WHERE id = ?").use { ps ->
            ps.setLong(1, id)
            ps.executeUpdate()
        }
    }

    fun allSuppliers(): List<Supplier> {
        connection.createStatement().use { st ->
            st.executeQuery("SELECT * FROM suppliers ORDER BY name").use { rs ->
                val out = mutableListOf<Supplier>()
                while (rs.next()) {
                    out += Supplier(
                        id = rs.getLong("id"), name = rs.getString("name"),
                        phone = rs.getString("phone"), address = rs.getString("address"),
                        gstin = rs.getString("gstin"), isDefault = rs.getInt("isDefault") != 0
                    )
                }
                return out
            }
        }
    }

    fun addSupplier(name: String, phone: String, address: String) {
        connection.prepareStatement("INSERT INTO suppliers (name, phone, address) VALUES (?, ?, ?)").use { ps ->
            ps.setString(1, name)
            ps.setString(2, phone)
            ps.setString(3, address)
            ps.executeUpdate()
        }
    }

    fun deleteSupplier(id: Long) {
        connection.prepareStatement("DELETE FROM suppliers WHERE id = ?").use { ps ->
            ps.setLong(1, id)
            ps.executeUpdate()
        }
    }

    fun allBills(): List<Bill> {
        connection.createStatement().use { st ->
            st.executeQuery("SELECT * FROM bills ORDER BY dateMillis DESC, id DESC").use { rs ->
                val out = mutableListOf<Bill>()
                while (rs.next()) {
                    out += Bill(
                        id = rs.getLong("id"), billNo = rs.getString("billNo"),
                        dateMillis = rs.getLong("dateMillis"), customerId = rs.getLong("customerId"),
                        customerName = rs.getString("customerName"), subTotal = rs.getDouble("subTotal"),
                        taxTotal = rs.getDouble("taxTotal"), grandTotal = rs.getDouble("grandTotal")
                    )
                }
                return out
            }
        }
    }

    fun linesFor(billId: Long): List<BillLine> {
        connection.prepareStatement("SELECT * FROM bill_items WHERE billId = ?").use { ps ->
            ps.setLong(1, billId)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<BillLine>()
                while (rs.next()) {
                    out += BillLine(
                        id = rs.getLong("id"), billId = rs.getLong("billId"), name = rs.getString("name"),
                        qty = rs.getDouble("qty"), price = rs.getDouble("price"),
                        taxPercent = rs.getDouble("taxPercent"), lineTotal = rs.getDouble("lineTotal")
                    )
                }
                return out
            }
        }
    }

    fun nextBillNo(): String {
        connection.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) AS n FROM bills").use { rs ->
                rs.next()
                return "INV-" + (rs.getInt("n") + 1).toString().padStart(4, '0')
            }
        }
    }

    /** Saves a bill and its lines in one transaction; [lines] carry name/qty/price/taxPercent
     * only — id/billId/lineTotal are computed here. Returns the saved [Bill]. */
    fun saveBill(customerId: Long, customerName: String, lines: List<BillLine>): Bill {
        val subTotal = lines.sumOf { it.qty * it.price }
        val taxTotal = lines.sumOf { it.qty * it.price * it.taxPercent / 100.0 }
        val grandTotal = subTotal + taxTotal
        val billNo = nextBillNo()
        val dateMillis = System.currentTimeMillis()

        connection.autoCommit = false
        try {
            val billId = connection.prepareStatement(
                "INSERT INTO bills (billNo, dateMillis, customerId, customerName, subTotal, taxTotal, grandTotal) VALUES (?, ?, ?, ?, ?, ?, ?)",
                java.sql.Statement.RETURN_GENERATED_KEYS
            ).use { ps ->
                ps.setString(1, billNo)
                ps.setLong(2, dateMillis)
                ps.setLong(3, customerId)
                ps.setString(4, customerName)
                ps.setDouble(5, subTotal)
                ps.setDouble(6, taxTotal)
                ps.setDouble(7, grandTotal)
                ps.executeUpdate()
                ps.generatedKeys.use { keys -> keys.next(); keys.getLong(1) }
            }
            connection.prepareStatement(
                "INSERT INTO bill_items (billId, name, qty, price, taxPercent, lineTotal) VALUES (?, ?, ?, ?, ?, ?)"
            ).use { ps ->
                lines.forEach { l ->
                    val lineTotal = l.qty * l.price * (1 + l.taxPercent / 100.0)
                    ps.setLong(1, billId)
                    ps.setString(2, l.name)
                    ps.setDouble(3, l.qty)
                    ps.setDouble(4, l.price)
                    ps.setDouble(5, l.taxPercent)
                    ps.setDouble(6, lineTotal)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            connection.commit()
            return Bill(billId, billNo, dateMillis, customerId, customerName, subTotal, taxTotal, grandTotal)
        } catch (e: Exception) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    fun allPurchases(): List<Purchase> {
        connection.createStatement().use { st ->
            st.executeQuery("SELECT * FROM purchases ORDER BY dateMillis DESC, id DESC").use { rs ->
                val out = mutableListOf<Purchase>()
                while (rs.next()) {
                    out += Purchase(
                        id = rs.getLong("id"), purchaseNo = rs.getString("purchaseNo"),
                        dateMillis = rs.getLong("dateMillis"), supplierId = rs.getLong("supplierId"),
                        supplierName = rs.getString("supplierName"), subTotal = rs.getDouble("subTotal"),
                        taxTotal = rs.getDouble("taxTotal"), grandTotal = rs.getDouble("grandTotal")
                    )
                }
                return out
            }
        }
    }

    private fun nextPurchaseNo(): String {
        connection.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) AS n FROM purchases").use { rs ->
                rs.next()
                return "PUR-" + (rs.getInt("n") + 1).toString().padStart(4, '0')
            }
        }
    }

    /** Saves a purchase and its lines in one transaction, same shape as [saveBill]. */
    fun savePurchase(supplierId: Long, supplierName: String, lines: List<PurchaseLine>): Purchase {
        val subTotal = lines.sumOf { it.qty * it.price }
        val taxTotal = lines.sumOf { it.qty * it.price * it.taxPercent / 100.0 }
        val grandTotal = subTotal + taxTotal
        val purchaseNo = nextPurchaseNo()
        val dateMillis = System.currentTimeMillis()

        connection.autoCommit = false
        try {
            val purchaseId = connection.prepareStatement(
                "INSERT INTO purchases (purchaseNo, dateMillis, supplierId, supplierName, subTotal, taxTotal, grandTotal) VALUES (?, ?, ?, ?, ?, ?, ?)",
                java.sql.Statement.RETURN_GENERATED_KEYS
            ).use { ps ->
                ps.setString(1, purchaseNo)
                ps.setLong(2, dateMillis)
                ps.setLong(3, supplierId)
                ps.setString(4, supplierName)
                ps.setDouble(5, subTotal)
                ps.setDouble(6, taxTotal)
                ps.setDouble(7, grandTotal)
                ps.executeUpdate()
                ps.generatedKeys.use { keys -> keys.next(); keys.getLong(1) }
            }
            connection.prepareStatement(
                "INSERT INTO purchase_items (purchaseId, name, qty, price, taxPercent, lineTotal) VALUES (?, ?, ?, ?, ?, ?)"
            ).use { ps ->
                lines.forEach { l ->
                    val lineTotal = l.qty * l.price * (1 + l.taxPercent / 100.0)
                    ps.setLong(1, purchaseId)
                    ps.setString(2, l.name)
                    ps.setDouble(3, l.qty)
                    ps.setDouble(4, l.price)
                    ps.setDouble(5, l.taxPercent)
                    ps.setDouble(6, lineTotal)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            connection.commit()
            return Purchase(purchaseId, purchaseNo, dateMillis, supplierId, supplierName, subTotal, taxTotal, grandTotal)
        } catch (e: Exception) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    fun allReceipts(): List<Receipt> {
        connection.createStatement().use { st ->
            st.executeQuery("SELECT * FROM receipts ORDER BY dateMillis DESC, id DESC").use { rs ->
                val out = mutableListOf<Receipt>()
                while (rs.next()) {
                    out += Receipt(
                        id = rs.getLong("id"), receiptNo = rs.getString("receiptNo"),
                        customerName = rs.getString("customerName"), dateMillis = rs.getLong("dateMillis"),
                        amount = rs.getDouble("amount"), paymentMode = rs.getString("paymentMode")
                    )
                }
                return out
            }
        }
    }

    fun addReceipt(customerName: String, amount: Double, paymentMode: String) {
        val receiptNo = connection.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) AS n FROM receipts").use { rs -> rs.next(); "RCT-" + (rs.getInt("n") + 1).toString().padStart(4, '0') }
        }
        connection.prepareStatement(
            "INSERT INTO receipts (receiptNo, customerName, dateMillis, amount, paymentMode) VALUES (?, ?, ?, ?, ?)"
        ).use { ps ->
            ps.setString(1, receiptNo)
            ps.setString(2, customerName)
            ps.setLong(3, System.currentTimeMillis())
            ps.setDouble(4, amount)
            ps.setString(5, paymentMode)
            ps.executeUpdate()
        }
    }

    fun deleteReceipt(id: Long) {
        connection.prepareStatement("DELETE FROM receipts WHERE id = ?").use { ps ->
            ps.setLong(1, id)
            ps.executeUpdate()
        }
    }

    fun allExpenses(): List<Expense> {
        connection.createStatement().use { st ->
            st.executeQuery("SELECT * FROM expenses ORDER BY dateMillis DESC, id DESC").use { rs ->
                val out = mutableListOf<Expense>()
                while (rs.next()) {
                    out += Expense(
                        id = rs.getLong("id"), voucherNo = rs.getString("voucherNo"),
                        dateMillis = rs.getLong("dateMillis"), description = rs.getString("description"),
                        amount = rs.getDouble("amount"), paymentMode = rs.getString("paymentMode"),
                        payTo = rs.getString("payTo")
                    )
                }
                return out
            }
        }
    }

    fun addExpense(description: String, amount: Double, paymentMode: String, payTo: String) {
        val voucherNo = connection.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) AS n FROM expenses").use { rs -> rs.next(); "PAY-" + (rs.getInt("n") + 1).toString().padStart(4, '0') }
        }
        connection.prepareStatement(
            "INSERT INTO expenses (voucherNo, dateMillis, description, amount, paymentMode, payTo) VALUES (?, ?, ?, ?, ?, ?)"
        ).use { ps ->
            ps.setString(1, voucherNo)
            ps.setLong(2, System.currentTimeMillis())
            ps.setString(3, description)
            ps.setDouble(4, amount)
            ps.setString(5, paymentMode)
            ps.setString(6, payTo)
            ps.executeUpdate()
        }
    }

    fun deleteExpense(id: Long) {
        connection.prepareStatement("DELETE FROM expenses WHERE id = ?").use { ps ->
            ps.setLong(1, id)
            ps.executeUpdate()
        }
    }

    fun allBillLines(): List<BillLine> {
        connection.createStatement().use { st ->
            st.executeQuery("SELECT * FROM bill_items").use { rs ->
                val out = mutableListOf<BillLine>()
                while (rs.next()) {
                    out += BillLine(
                        id = rs.getLong("id"), billId = rs.getLong("billId"), name = rs.getString("name"),
                        qty = rs.getDouble("qty"), price = rs.getDouble("price"),
                        taxPercent = rs.getDouble("taxPercent"), lineTotal = rs.getDouble("lineTotal")
                    )
                }
                return out
            }
        }
    }

    fun allPurchaseLines(): List<PurchaseLine> {
        connection.createStatement().use { st ->
            st.executeQuery("SELECT * FROM purchase_items").use { rs ->
                val out = mutableListOf<PurchaseLine>()
                while (rs.next()) {
                    out += PurchaseLine(
                        id = rs.getLong("id"), purchaseId = rs.getLong("purchaseId"), name = rs.getString("name"),
                        qty = rs.getDouble("qty"), price = rs.getDouble("price"),
                        taxPercent = rs.getDouble("taxPercent"), lineTotal = rs.getDouble("lineTotal")
                    )
                }
                return out
            }
        }
    }

    /** Direct JDBC access for the backup/restore code (BackupSync.kt), which needs bulk
     * table-agnostic reads/writes that don't fit the typed per-entity functions above. */
    fun rawConnection(): Connection = connection
}
