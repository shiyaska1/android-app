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
}
