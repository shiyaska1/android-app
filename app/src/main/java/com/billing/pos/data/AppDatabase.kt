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
        DiaryEntry::class, DiaryAttachment::class, DiaryBlock::class,
        Supplier::class, Purchase::class, PurchaseItem::class,
        AccountGroup::class, AccountHead::class,
        JournalEntry::class, JournalLine::class,
        ItemAttachment::class, BillAttachment::class,
        ItemBatch::class, ItemSize::class,
        Quotation::class, QuotationItem::class,
        Estimate::class, EstimateItem::class,
        SalesReturn::class, SalesReturnItem::class,
        PurchaseReturn::class, PurchaseReturnItem::class,
        PurchaseQuotation::class, PurchaseQuotationItem::class,
        HireInvoice::class, HireInvoiceItem::class,
        HireReturn::class, HireReturnItem::class,
        LabTest::class, LabEvaluation::class, Patient::class,
        LabBill::class, LabBillTest::class, LabResultValue::class,
        LabGroup::class, LabEvalMaster::class, LabHeading::class, LabReceipt::class,
        LabDoctor::class, MaterialOut::class, MaterialOutItem::class,
        MaterialReceipt::class, MaterialReceiptItem::class
    ],
    // v25 quotations; v26 sales returns; v27 purchase returns; v28 purchase quotations (LPO);
    // v29 dual units; v30 rental; v31 medical lab; v32 lab masters + heading rows;
    // v33 page breaks, heading master, lab-bill payment; v34 lab balance receipts;
    // v35 doctor master + patient phone; v36 material out + movement;
    // v37 item purchase price; v38 material receipts + purchase stockReceived/lpoNo.
    version = 39,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun customerDao(): CustomerDao
    abstract fun itemDao(): ItemDao
    abstract fun itemAttachmentDao(): ItemAttachmentDao
    abstract fun billAttachmentDao(): BillAttachmentDao
    abstract fun billDao(): BillDao
    abstract fun userDao(): UserDao
    abstract fun receiptDao(): ReceiptDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun diaryDao(): DiaryDao
    abstract fun supplierDao(): SupplierDao
    abstract fun purchaseDao(): PurchaseDao
    abstract fun accountDao(): AccountDao
    abstract fun journalDao(): JournalDao
    abstract fun itemBatchDao(): ItemBatchDao
    abstract fun itemSizeDao(): ItemSizeDao
    abstract fun quotationDao(): QuotationDao
    abstract fun estimateDao(): EstimateDao
    abstract fun salesReturnDao(): SalesReturnDao
    abstract fun purchaseReturnDao(): PurchaseReturnDao
    abstract fun purchaseQuotationDao(): PurchaseQuotationDao
    abstract fun hireInvoiceDao(): HireInvoiceDao
    abstract fun hireReturnDao(): HireReturnDao
    abstract fun labTestDao(): LabTestDao
    abstract fun patientDao(): PatientDao
    abstract fun labBillDao(): LabBillDao
    abstract fun labMasterDao(): LabMasterDao
    abstract fun labReceiptDao(): LabReceiptDao
    abstract fun materialOutDao(): MaterialOutDao
    abstract fun materialReceiptDao(): MaterialReceiptDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        /**
         * Adds Item.purchasePrice. A real migration (not a destructive one) so existing
         * items, bills and stock survive the upgrade.
         */
        private val MIGRATION_36_37 = object : androidx.room.migration.Migration(36, 37) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN purchasePrice REAL NOT NULL DEFAULT 0")
            }
        }

        /** Adds Material Receipt Notes and the purchase stockReceived/lpoNo columns. Non-destructive. */
        private val MIGRATION_37_38 = object : androidx.room.migration.Migration(37, 38) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE purchases ADD COLUMN stockReceived INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE purchases ADD COLUMN lpoNo TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS material_receipts (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, receiptNo TEXT NOT NULL, " +
                        "dateMillis INTEGER NOT NULL, supplierId INTEGER NOT NULL, supplierName TEXT NOT NULL, " +
                        "lpoId INTEGER NOT NULL DEFAULT 0, lpoNo TEXT NOT NULL DEFAULT '', remarks TEXT NOT NULL DEFAULT '')"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS material_receipt_items (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, receiptId INTEGER NOT NULL, " +
                        "itemId INTEGER NOT NULL DEFAULT 0, name TEXT NOT NULL, qty REAL NOT NULL, " +
                        "price REAL NOT NULL, taxPercent REAL NOT NULL DEFAULT 0, lineTotal REAL NOT NULL DEFAULT 0, " +
                        "batchNo TEXT NOT NULL DEFAULT '', unit TEXT NOT NULL DEFAULT '')"
                )
            }
        }

        /** Estimates: a new pair of tables, so existing data is untouched. */
        private val MIGRATION_38_39 = object : androidx.room.migration.Migration(38, 39) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS estimates (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, estimateNo TEXT NOT NULL, " +
                        "dateMillis INTEGER NOT NULL, customerId INTEGER NOT NULL, customerName TEXT NOT NULL, " +
                        "paymentMethod TEXT NOT NULL, subTotal REAL NOT NULL, taxTotal REAL NOT NULL, " +
                        "additionalCharge REAL NOT NULL, discount REAL NOT NULL, grandTotal REAL NOT NULL, " +
                        "customerGstin TEXT NOT NULL DEFAULT '', remarks TEXT NOT NULL DEFAULT '')"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS estimate_items (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, estimateId INTEGER NOT NULL, " +
                        "name TEXT NOT NULL, qty REAL NOT NULL, price REAL NOT NULL, " +
                        "taxPercent REAL NOT NULL, lineTotal REAL NOT NULL, unit TEXT NOT NULL DEFAULT '')"
                )
            }
        }

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "pos_billing.db"
                )
                    .addMigrations(MIGRATION_36_37, MIGRATION_37_38, MIGRATION_38_39)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
