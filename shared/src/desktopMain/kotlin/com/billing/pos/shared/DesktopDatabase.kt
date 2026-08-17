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

/** Core subset of the Android app's Item entity — the commonly-used fields ported first;
 * the rest (openingStock, secondaryUnit, conversionFactor, rack location, etc.) are added as
 * the screens that actually need them (Purchases, Stock Report, ...) get ported. */
data class Item(
    val id: Long = 0,
    val name: String,
    val price: Double,
    val purchasePrice: Double = 0.0,
    val taxPercent: Double = 0.0,
    val barcode: String = "",
    val category: String = "",
    val unit: String = "PCS"
)

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
                        "purchasePrice REAL NOT NULL DEFAULT 0, taxPercent REAL NOT NULL DEFAULT 0, " +
                        "barcode TEXT NOT NULL DEFAULT '', category TEXT NOT NULL DEFAULT '', " +
                        "unit TEXT NOT NULL DEFAULT 'PCS')"
                )
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
            }
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
                        taxPercent = rs.getDouble("taxPercent"), barcode = rs.getString("barcode"),
                        category = rs.getString("category"), unit = rs.getString("unit")
                    )
                }
                return out
            }
        }
    }

    fun addItem(name: String, price: Double, taxPercent: Double, barcode: String, category: String) {
        connection.prepareStatement(
            "INSERT INTO items (name, price, taxPercent, barcode, category) VALUES (?, ?, ?, ?, ?)"
        ).use { ps ->
            ps.setString(1, name)
            ps.setDouble(2, price)
            ps.setDouble(3, taxPercent)
            ps.setString(4, barcode)
            ps.setString(5, category)
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
}
