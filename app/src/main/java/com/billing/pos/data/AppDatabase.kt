package com.billing.pos.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        Customer::class, Item::class, Bill::class, BillItem::class,
        User::class, Receipt::class, Expense::class,
        DiaryEntry::class, DiaryAttachment::class,
        Supplier::class, Purchase::class, PurchaseItem::class
    ],
    version = 7,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun customerDao(): CustomerDao
    abstract fun itemDao(): ItemDao
    abstract fun billDao(): BillDao
    abstract fun userDao(): UserDao
    abstract fun receiptDao(): ReceiptDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun diaryDao(): DiaryDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "pos_billing.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
