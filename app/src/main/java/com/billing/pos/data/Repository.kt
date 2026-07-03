package com.billing.pos.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

/** Single access point for all data operations. */
class Repository(context: Context) {

    private val db = AppDatabase.get(context)
    private val customerDao = db.customerDao()
    private val itemDao = db.itemDao()
    private val billDao = db.billDao()

    val customers: Flow<List<Customer>> = customerDao.observeAll()
    val items: Flow<List<Item>> = itemDao.observeAll()

    /** Ensures a default "Cash" customer exists. Returns nothing; UI observes the flow. */
    suspend fun ensureDefaults() {
        if (customerDao.count() == 0) {
            customerDao.insert(Customer(name = "Cash Customer", isDefault = true))
        }
    }

    suspend fun addCustomer(name: String, phone: String, address: String): Long =
        customerDao.insert(Customer(name = name.trim(), phone = phone.trim(), address = address.trim()))

    suspend fun addItem(name: String, price: Double, taxPercent: Double): Long =
        itemDao.insert(Item(name = name.trim(), price = price, taxPercent = taxPercent))

    /** Bill number for the *next* bill, e.g. INV-0001. */
    suspend fun nextBillNo(): String {
        val n = billDao.count() + 1
        return "INV-" + n.toString().padStart(4, '0')
    }

    suspend fun saveBill(bill: Bill, lines: List<BillItem>): Long =
        billDao.saveBill(bill, lines)

    fun billsBetween(from: Long, to: Long): Flow<List<Bill>> = billDao.observeBetween(from, to)

    suspend fun linesFor(billId: Long): List<BillItem> = billDao.linesFor(billId)
}
