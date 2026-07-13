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
        SalesReturn::class, SalesReturnItem::class,
        PurchaseReturn::class, PurchaseReturnItem::class,
        PurchaseQuotation::class, PurchaseQuotationItem::class,
        HireInvoice::class, HireInvoiceItem::class,
        HireReturn::class, HireReturnItem::class,
        LabTest::class, LabEvaluation::class, Patient::class,
        LabBill::class, LabBillTest::class, LabResultValue::class,
        LabGroup::class, LabEvalMaster::class, LabHeading::class
    ],
    // v25 quotations; v26 sales returns; v27 purchase returns; v28 purchase quotations (LPO);
    // v29 dual units; v30 rental; v31 medical lab; v32 lab masters + heading rows;
    // v33 page breaks, heading master, lab-bill payment method/paid amount.
    version = 33,
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
    abstract fun salesReturnDao(): SalesReturnDao
    abstract fun purchaseReturnDao(): PurchaseReturnDao
    abstract fun purchaseQuotationDao(): PurchaseQuotationDao
    abstract fun hireInvoiceDao(): HireInvoiceDao
    abstract fun hireReturnDao(): HireReturnDao
    abstract fun labTestDao(): LabTestDao
    abstract fun patientDao(): PatientDao
    abstract fun labBillDao(): LabBillDao
    abstract fun labMasterDao(): LabMasterDao

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
