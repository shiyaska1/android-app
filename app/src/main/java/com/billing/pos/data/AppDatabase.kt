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
        ItemPhotoVector::class,
        DiaryType::class,
        ExpenseAttachment::class,
        SalesReturn::class, SalesReturnItem::class,
        PurchaseReturn::class, PurchaseReturnItem::class,
        PurchaseQuotation::class, PurchaseQuotationItem::class,
        HireInvoice::class, HireInvoiceItem::class,
        HireReturn::class, HireReturnItem::class,
        LabTest::class, LabEvaluation::class, Patient::class,
        LabBill::class, LabBillTest::class, LabResultValue::class,
        LabGroup::class, LabEvalMaster::class, LabHeading::class, LabReceipt::class,
        LabDoctor::class, MaterialOut::class, MaterialOutItem::class,
        MaterialReceipt::class, MaterialReceiptItem::class,
        SavedCalc::class, CustomerAttachment::class,
        PurchaseQuote::class, PurchaseQuoteItem::class,
        CustOrder::class, CustOrderItem::class, CustOrderAttachment::class,
        Contact::class, ContactGroup::class, SmsLog::class, SmsTemplate::class,
        GymMember::class, GymFee::class,
        Course::class, CoachingClass::class, CoachSubject::class, CoachStaff::class,
        CoachStudent::class, StudentCourse::class, CoachFee::class, Enquiry::class,
        EnquiryFollowup::class, CoachAttendance::class,
        ServiceType::class, ServiceStatus::class, ServiceJobMaster::class,
        ServiceJobCard::class, ServiceJobLine::class, ServiceJobAttachment::class,
        ServiceEmployee::class, ServiceModel::class,
        ReceiptAttachment::class,
        ReceiptAllocation::class, ExpenseAllocation::class,
        DeliveryNote::class, DeliveryNoteItem::class,
        QuickNote::class, QuickNoteAttachment::class,
        PurchaseAttachment::class,
        SalesmanMap::class,
        ProductionProcedure::class, ProductionProcedureMaterial::class, ProductionRun::class,
        ItemBundle::class, ItemBundleComponent::class,
        ChequeEntry::class, CostCenter::class, FixedAsset::class
    ],
    // v25 quotations; v26 sales returns; v27 purchase returns; v28 purchase quotations (LPO);
    // v29 dual units; v30 rental; v31 medical lab; v32 lab masters + heading rows;
    // v33 page breaks, heading master, lab-bill payment; v34 lab balance receipts;
    // v35 doctor master + patient phone; v36 material out + movement;
    // v37 item purchase price; v38 material receipts + purchase stockReceived/lpoNo.
    // v50 customer orders; v51 Bulk SMS contacts + contact groups; v52 SMS templates;
    // v53 receipt/payment account links; v54 gym; v55 coaching centre;
    // v56 service center job cards; v57 service employees + card assignment;
    // v58 job card priority; v59 model/vehicle master on job cards;
    // v60 diary entry customer link; v61 receipt attachments;
    // v62 receipt/expense allocations (one voucher settling multiple invoices/purchases);
    // v63 delivery notes (goods delivered to a customer — decreases stock);
    // v64 delivery note / material receipt "converted to bill/purchase" tracking.
    // v65 quick notes (dashboard quick-note list, with an optional one-time/daily reminder).
    // v66 purchase supplier-bill-no + remarks + attachments.
    // v67 order line status (pending/delivered/partial/cancelled), order deviceId, salesman map.
    // v68 production: procedures (recipes), production runs (linked to a MaterialOut + MaterialReceipt).
    // v69 item bundles: a named combo of items, sold as one line, expanding into real item lines.
    // v70 deviceId on bills/purchases/receipts/expenses/quotations/estimates, for cloud-merge dedup.
    // v71 isLegacy on bills — quick invoices attached from the Customer dialog (amount+note+date,
    // no items), for opening-balance-style dues that shouldn't count as real sales.
    // v72 convertedBillNo on quotations — marks a quotation as already turned into a sale.
    // v73 isLegacy on purchases — quick purchase invoices attached from the Supplier dialog
    // (amount+note+date, no items), mirroring the customer opening-balance/quick-invoice feature.
    // v74 accounting module batch 1 (Master): nested account groups (parentGroupId), cheque
    // register, cost centers (+ tag on journal lines), fixed asset register.
    // v75 updatedAt on bills/purchases/receipts/expenses/quotations/estimates/journal entries —
    // last-edited timestamp (distinct from dateMillis), driving the cloud-sync push-window filter.
    version = 77,
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
    abstract fun itemPhotoVectorDao(): ItemPhotoVectorDao
    abstract fun diaryTypeDao(): DiaryTypeDao
    abstract fun expenseAttachmentDao(): ExpenseAttachmentDao
    abstract fun savedCalcDao(): SavedCalcDao
    abstract fun customerAttachmentDao(): CustomerAttachmentDao
    abstract fun purchaseQuoteDao(): PurchaseQuoteDao
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
    abstract fun custOrderDao(): CustOrderDao
    abstract fun contactDao(): ContactDao
    abstract fun contactGroupDao(): ContactGroupDao
    abstract fun smsLogDao(): SmsLogDao
    abstract fun smsTemplateDao(): SmsTemplateDao
    abstract fun gymMemberDao(): GymMemberDao
    abstract fun gymFeeDao(): GymFeeDao
    abstract fun coachingDao(): CoachingDao
    abstract fun serviceDao(): ServiceDao
    abstract fun receiptAttachmentDao(): ReceiptAttachmentDao
    abstract fun receiptAllocationDao(): ReceiptAllocationDao
    abstract fun expenseAllocationDao(): ExpenseAllocationDao
    abstract fun deliveryNoteDao(): DeliveryNoteDao
    abstract fun quickNoteDao(): QuickNoteDao
    abstract fun quickNoteAttachmentDao(): QuickNoteAttachmentDao
    abstract fun purchaseAttachmentDao(): PurchaseAttachmentDao
    abstract fun salesmanMapDao(): SalesmanMapDao
    abstract fun productionDao(): ProductionDao
    abstract fun itemBundleDao(): ItemBundleDao
    abstract fun chequeDao(): ChequeDao
    abstract fun costCenterDao(): CostCenterDao
    abstract fun fixedAssetDao(): FixedAssetDao

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

        /** Per-line description on quotations. */
        private val MIGRATION_39_40 = object : androidx.room.migration.Migration(39, 40) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE quotation_items ADD COLUMN note TEXT NOT NULL DEFAULT ''")
            }
        }

        /** Cached image fingerprints for visual item search. */
        private val MIGRATION_40_41 = object : androidx.room.migration.Migration(40, 41) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS item_photo_vectors (" +
                        "path TEXT NOT NULL PRIMARY KEY, itemId INTEGER NOT NULL, " +
                        "vec BLOB NOT NULL, stamp INTEGER NOT NULL)"
                )
            }
        }

        /** Diary categories, and the entry's reference to one. */
        private val MIGRATION_41_42 = object : androidx.room.migration.Migration(41, 42) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS diary_types (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL)"
                )
                db.execSQL("ALTER TABLE diary_entries ADD COLUMN typeId INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Voice notes / photos / documents attached to a payment. */
        private val MIGRATION_42_43 = object : androidx.room.migration.Migration(42, 43) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS expense_attachments (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, expenseId INTEGER NOT NULL, " +
                        "path TEXT NOT NULL, name TEXT NOT NULL, mime TEXT NOT NULL)"
                )
            }
        }

        /** Terms and conditions on a quotation. */
        private val MIGRATION_43_44 = object : androidx.room.migration.Migration(43, 44) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE quotations ADD COLUMN terms TEXT NOT NULL DEFAULT ''")
            }
        }

        /** Saved calculator tapes. */
        private val MIGRATION_44_45 = object : androidx.room.migration.Migration(44, 45) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS saved_calcs (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, dateMillis INTEGER NOT NULL, " +
                        "amounts TEXT NOT NULL, total REAL NOT NULL, title TEXT NOT NULL DEFAULT '')"
                )
            }
        }

        /** Customer and narration on a saved calculation. */
        private val MIGRATION_45_46 = object : androidx.room.migration.Migration(45, 46) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE saved_calcs ADD COLUMN customerId INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE saved_calcs ADD COLUMN customerName TEXT NOT NULL DEFAULT 'Cash Customer'")
                db.execSQL("ALTER TABLE saved_calcs ADD COLUMN narration TEXT NOT NULL DEFAULT ''")
            }
        }

        /** Documents filed against a customer. */
        private val MIGRATION_46_47 = object : androidx.room.migration.Migration(46, 47) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS customer_attachments (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, customerId INTEGER NOT NULL, " +
                        "path TEXT NOT NULL, name TEXT NOT NULL, mime TEXT NOT NULL)"
                )
            }
        }

        /** Purchase quotations received from suppliers. */
        private val MIGRATION_47_48 = object : androidx.room.migration.Migration(47, 48) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS purchase_quotes (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, quoteNo TEXT NOT NULL, " +
                        "dateMillis INTEGER NOT NULL, supplierId INTEGER NOT NULL, supplierName TEXT NOT NULL, " +
                        "subTotal REAL NOT NULL, taxTotal REAL NOT NULL, additionalCharge REAL NOT NULL, " +
                        "discount REAL NOT NULL, grandTotal REAL NOT NULL, remarks TEXT NOT NULL DEFAULT '')"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS purchase_quote_items (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, quoteId INTEGER NOT NULL, " +
                        "itemId INTEGER NOT NULL DEFAULT 0, name TEXT NOT NULL, qty REAL NOT NULL, " +
                        "price REAL NOT NULL, taxPercent REAL NOT NULL, lineTotal REAL NOT NULL, " +
                        "unit TEXT NOT NULL DEFAULT '')"
                )
            }
        }

        /** Customer type (e.g. General, Wholesale). */
        private val MIGRATION_48_49 = object : androidx.room.migration.Migration(48, 49) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE customers ADD COLUMN customerType TEXT NOT NULL DEFAULT 'General'")
            }
        }

        /** Customer orders (stock-neutral copies of a bill). */
        private val MIGRATION_49_50 = object : androidx.room.migration.Migration(49, 50) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS cust_orders (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, orderNo TEXT NOT NULL, " +
                        "dateMillis INTEGER NOT NULL, customerId INTEGER NOT NULL, customerName TEXT NOT NULL, " +
                        "remark TEXT NOT NULL DEFAULT '', latitude REAL NOT NULL DEFAULT 0, longitude REAL NOT NULL DEFAULT 0, " +
                        "grandTotal REAL NOT NULL DEFAULT 0)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS cust_order_items (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, orderId INTEGER NOT NULL, itemId INTEGER NOT NULL DEFAULT 0, " +
                        "name TEXT NOT NULL, qty REAL NOT NULL, price REAL NOT NULL, lineTotal REAL NOT NULL, unit TEXT NOT NULL DEFAULT '')"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS cust_order_attachments (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, orderId INTEGER NOT NULL, " +
                        "path TEXT NOT NULL, name TEXT NOT NULL, mime TEXT NOT NULL)"
                )
            }
        }

        /** Bulk SMS: contacts + a self-referencing group tree (group / sub-group / sub-sub-group). */
        private val MIGRATION_50_51 = object : androidx.room.migration.Migration(50, 51) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS contact_groups (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, " +
                        "parentId INTEGER NOT NULL DEFAULT 0, level INTEGER NOT NULL DEFAULT 1)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS contacts (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, mobile TEXT NOT NULL, " +
                        "groupId INTEGER NOT NULL DEFAULT 0, subGroupId INTEGER NOT NULL DEFAULT 0, subSubGroupId INTEGER NOT NULL DEFAULT 0)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS sms_log (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, dateMillis INTEGER NOT NULL, mobile TEXT NOT NULL, " +
                        "contactName TEXT NOT NULL DEFAULT '', body TEXT NOT NULL, channel TEXT NOT NULL DEFAULT 'Gateway', " +
                        "status TEXT NOT NULL DEFAULT 'QUEUED', response TEXT NOT NULL DEFAULT '', batchId INTEGER NOT NULL DEFAULT 0)"
                )
            }
        }

        /** Bulk SMS: reusable message templates. */
        private val MIGRATION_51_52 = object : androidx.room.migration.Migration(51, 52) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS sms_templates (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, body TEXT NOT NULL)"
                )
            }
        }

        /** Receipts/payments now record the Cash/Bank account they hit (for ledgers). */
        private val MIGRATION_52_53 = object : androidx.room.migration.Migration(52, 53) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE receipts ADD COLUMN toAccountId INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE expenses ADD COLUMN fromAccountId INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Gym: members + fee schedule. */
        private val MIGRATION_53_54 = object : androidx.room.migration.Migration(53, 54) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS gym_members (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, phone TEXT NOT NULL DEFAULT '', " +
                        "gender TEXT NOT NULL DEFAULT '', address TEXT NOT NULL DEFAULT '', photoPath TEXT NOT NULL DEFAULT '', " +
                        "joinDateMillis INTEGER NOT NULL, plan TEXT NOT NULL DEFAULT '', admissionFee REAL NOT NULL DEFAULT 0, " +
                        "totalFee REAL NOT NULL DEFAULT 0, installments INTEGER NOT NULL DEFAULT 1, slot TEXT NOT NULL DEFAULT '', " +
                        "trainer TEXT NOT NULL DEFAULT '', active INTEGER NOT NULL DEFAULT 1)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS gym_fees (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, memberId INTEGER NOT NULL, dueDateMillis INTEGER NOT NULL, " +
                        "amount REAL NOT NULL, paidAmount REAL NOT NULL DEFAULT 0, paidDateMillis INTEGER NOT NULL DEFAULT 0, " +
                        "kind TEXT NOT NULL DEFAULT 'Installment', note TEXT NOT NULL DEFAULT '')"
                )
            }
        }

        /** Coaching centre: masters, students, fees, enquiries, attendance. */
        private val MIGRATION_54_55 = object : androidx.room.migration.Migration(54, 55) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS coach_courses (id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, durationMonths INTEGER NOT NULL DEFAULT 0, admissionFee REAL NOT NULL DEFAULT 0, totalFee REAL NOT NULL DEFAULT 0, description TEXT NOT NULL DEFAULT '')")
                db.execSQL("CREATE TABLE IF NOT EXISTS coach_classes (id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS coach_subjects (id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, classId INTEGER NOT NULL DEFAULT 0, staffId INTEGER NOT NULL DEFAULT 0)")
                db.execSQL("CREATE TABLE IF NOT EXISTS coach_staff (id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, phone TEXT NOT NULL DEFAULT '', designation TEXT NOT NULL DEFAULT '', address TEXT NOT NULL DEFAULT '')")
                db.execSQL("CREATE TABLE IF NOT EXISTS coach_students (id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, phone TEXT NOT NULL DEFAULT '', gender TEXT NOT NULL DEFAULT '', guardian TEXT NOT NULL DEFAULT '', address TEXT NOT NULL DEFAULT '', photoPath TEXT NOT NULL DEFAULT '', classId INTEGER NOT NULL DEFAULT 0, joinDateMillis INTEGER NOT NULL, admissionFee REAL NOT NULL DEFAULT 0, totalFee REAL NOT NULL DEFAULT 0, installments INTEGER NOT NULL DEFAULT 1, active INTEGER NOT NULL DEFAULT 1)")
                db.execSQL("CREATE TABLE IF NOT EXISTS coach_student_courses (id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, studentId INTEGER NOT NULL, courseId INTEGER NOT NULL, courseName TEXT NOT NULL DEFAULT '')")
                db.execSQL("CREATE TABLE IF NOT EXISTS coach_fees (id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, studentId INTEGER NOT NULL, dueDateMillis INTEGER NOT NULL, amount REAL NOT NULL, paidAmount REAL NOT NULL DEFAULT 0, paidDateMillis INTEGER NOT NULL DEFAULT 0, kind TEXT NOT NULL DEFAULT 'Installment', note TEXT NOT NULL DEFAULT '')")
                db.execSQL("CREATE TABLE IF NOT EXISTS coach_enquiries (id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, phone TEXT NOT NULL DEFAULT '', courseInterest TEXT NOT NULL DEFAULT '', dateMillis INTEGER NOT NULL, source TEXT NOT NULL DEFAULT '', status TEXT NOT NULL DEFAULT 'New', notes TEXT NOT NULL DEFAULT '', nextFollowupMillis INTEGER NOT NULL DEFAULT 0, convertedStudentId INTEGER NOT NULL DEFAULT 0)")
                db.execSQL("CREATE TABLE IF NOT EXISTS coach_enquiry_followups (id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, enquiryId INTEGER NOT NULL, dateMillis INTEGER NOT NULL, note TEXT NOT NULL, status TEXT NOT NULL DEFAULT '')")
                db.execSQL("CREATE TABLE IF NOT EXISTS coach_attendance (id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, dateMillis INTEGER NOT NULL, classId INTEGER NOT NULL, subjectId INTEGER NOT NULL, staffId INTEGER NOT NULL DEFAULT 0, studentId INTEGER NOT NULL, present INTEGER NOT NULL DEFAULT 1)")
            }
        }

        /** Service center: masters + job cards with per-job lines and attachments. */
        private val MIGRATION_55_56 = object : androidx.room.migration.Migration(55, 56) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS service_types (id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS service_statuses (id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS service_jobs_master (id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, price REAL NOT NULL DEFAULT 0)")
                db.execSQL("CREATE TABLE IF NOT EXISTS service_job_cards (id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, jobNo TEXT NOT NULL, name TEXT NOT NULL DEFAULT '', dateMillis INTEGER NOT NULL, customerId INTEGER NOT NULL DEFAULT 0, customerName TEXT NOT NULL DEFAULT '', customerPhone TEXT NOT NULL DEFAULT '', typeId INTEGER NOT NULL DEFAULT 0, typeName TEXT NOT NULL DEFAULT '', status TEXT NOT NULL DEFAULT 'Pending', expectedMillis INTEGER NOT NULL DEFAULT 0, jobsTotal REAL NOT NULL DEFAULT 0, otherCharges REAL NOT NULL DEFAULT 0, otherChargesNote TEXT NOT NULL DEFAULT '', grandTotal REAL NOT NULL DEFAULT 0, advance REAL NOT NULL DEFAULT 0, remarks TEXT NOT NULL DEFAULT '')")
                db.execSQL("CREATE TABLE IF NOT EXISTS service_job_lines (id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, cardId INTEGER NOT NULL, jobId INTEGER NOT NULL DEFAULT 0, name TEXT NOT NULL, price REAL NOT NULL DEFAULT 0, status TEXT NOT NULL DEFAULT 'Pending', expectedMillis INTEGER NOT NULL DEFAULT 0)")
                db.execSQL("CREATE TABLE IF NOT EXISTS service_job_attachments (id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, cardId INTEGER NOT NULL, path TEXT NOT NULL, name TEXT NOT NULL, mime TEXT NOT NULL)")
            }
        }

        /** Service center: employee master, and the job card's assigned employee. */
        private val MIGRATION_56_57 = object : androidx.room.migration.Migration(56, 57) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS service_employees (id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, phone TEXT NOT NULL DEFAULT '')")
                db.execSQL("ALTER TABLE service_job_cards ADD COLUMN employeeId INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE service_job_cards ADD COLUMN employeeName TEXT NOT NULL DEFAULT ''")
            }
        }

        /** Service center: High/Medium/Low priority on job cards. */
        private val MIGRATION_57_58 = object : androidx.room.migration.Migration(57, 58) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE service_job_cards ADD COLUMN priority TEXT NOT NULL DEFAULT 'Medium'")
            }
        }

        /** Service center: model/vehicle/item master, referenced from job cards. */
        private val MIGRATION_58_59 = object : androidx.room.migration.Migration(58, 59) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS service_models (id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL)")
                db.execSQL("ALTER TABLE service_job_cards ADD COLUMN modelId INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE service_job_cards ADD COLUMN modelName TEXT NOT NULL DEFAULT ''")
            }
        }

        /** Diary entries can name the customer they are about. */
        private val MIGRATION_59_60 = object : androidx.room.migration.Migration(59, 60) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE diary_entries ADD COLUMN customerId INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE diary_entries ADD COLUMN customerName TEXT NOT NULL DEFAULT ''")
            }
        }

        /** Files attached to a receipt voucher. */
        private val MIGRATION_60_61 = object : androidx.room.migration.Migration(60, 61) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS receipt_attachments (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, receiptId INTEGER NOT NULL, " +
                        "path TEXT NOT NULL, name TEXT NOT NULL, mime TEXT NOT NULL)"
                )
            }
        }

        /** One voucher settling several invoices/purchases: per-invoice split of the total. */
        private val MIGRATION_61_62 = object : androidx.room.migration.Migration(61, 62) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS receipt_allocations (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, receiptId INTEGER NOT NULL, " +
                        "billId INTEGER NOT NULL, billNo TEXT NOT NULL, amount REAL NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS expense_allocations (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, expenseId INTEGER NOT NULL, " +
                        "purchaseId INTEGER NOT NULL, purchaseNo TEXT NOT NULL, amount REAL NOT NULL)"
                )
            }
        }

        /** Delivery notes — goods delivered to a customer (decreases stock). */
        private val MIGRATION_62_63 = object : androidx.room.migration.Migration(62, 63) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS delivery_notes (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, deliveryNo TEXT NOT NULL, " +
                        "dateMillis INTEGER NOT NULL, customerId INTEGER NOT NULL, customerName TEXT NOT NULL, " +
                        "remarks TEXT NOT NULL DEFAULT '')"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS delivery_note_items (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, deliveryId INTEGER NOT NULL, " +
                        "itemId INTEGER NOT NULL DEFAULT 0, name TEXT NOT NULL, qty REAL NOT NULL, " +
                        "price REAL NOT NULL, taxPercent REAL NOT NULL DEFAULT 0, lineTotal REAL NOT NULL DEFAULT 0, " +
                        "batchNo TEXT NOT NULL DEFAULT '', unit TEXT NOT NULL DEFAULT '')"
                )
            }
        }

        /** Tracks which delivery notes / material receipts were converted into a bill/purchase. */
        private val MIGRATION_63_64 = object : androidx.room.migration.Migration(63, 64) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE delivery_notes ADD COLUMN convertedBillNo TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE material_receipts ADD COLUMN convertedPurchaseNo TEXT NOT NULL DEFAULT ''")
            }
        }

        /** Quick notes + their photo attachments — new tables, so existing data is untouched. */
        private val MIGRATION_64_65 = object : androidx.room.migration.Migration(64, 65) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS quick_notes (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL DEFAULT '', " +
                        "createdAt INTEGER NOT NULL DEFAULT 0, updatedAt INTEGER NOT NULL DEFAULT 0, " +
                        "reminderEnabled INTEGER NOT NULL DEFAULT 0, reminderAt INTEGER NOT NULL DEFAULT 0, " +
                        "reminderDaily INTEGER NOT NULL DEFAULT 0)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS quick_note_attachments (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, noteId INTEGER NOT NULL, " +
                        "path TEXT NOT NULL, name TEXT NOT NULL DEFAULT '', mime TEXT NOT NULL DEFAULT 'image/jpeg')"
                )
            }
        }

        /** Supplier bill no + remarks on a purchase, and purchase document attachments. */
        private val MIGRATION_65_66 = object : androidx.room.migration.Migration(65, 66) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE purchases ADD COLUMN supplierBillNo TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE purchases ADD COLUMN remarks TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS purchase_attachments (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, purchaseId INTEGER NOT NULL, " +
                        "path TEXT NOT NULL, name TEXT NOT NULL DEFAULT '', mime TEXT NOT NULL DEFAULT '')"
                )
            }
        }

        /** Order line status, order-origin deviceId, and the deviceId -> salesman name map. */
        private val MIGRATION_66_67 = object : androidx.room.migration.Migration(66, 67) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cust_order_items ADD COLUMN status TEXT NOT NULL DEFAULT 'PENDING'")
                db.execSQL("ALTER TABLE cust_orders ADD COLUMN deviceId TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS salesman_map (" +
                        "deviceId TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL DEFAULT '')"
                )
            }
        }

        /** Production: procedures (recipes) + production runs, and the runs' link fields on material_outs/receipts. */
        private val MIGRATION_67_68 = object : androidx.room.migration.Migration(67, 68) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS production_procedures (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, " +
                        "producedItemId INTEGER NOT NULL, producedItemName TEXT NOT NULL, " +
                        "producedQty REAL NOT NULL, unit TEXT NOT NULL DEFAULT '', " +
                        "labourCost REAL NOT NULL DEFAULT 0, remarks TEXT NOT NULL DEFAULT '')"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS production_procedure_materials (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, procedureId INTEGER NOT NULL, " +
                        "itemId INTEGER NOT NULL DEFAULT 0, name TEXT NOT NULL, qty REAL NOT NULL, " +
                        "unit TEXT NOT NULL DEFAULT '')"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS production_runs (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, runNo TEXT NOT NULL, " +
                        "dateMillis INTEGER NOT NULL, procedureId INTEGER NOT NULL, procedureName TEXT NOT NULL, " +
                        "producedItemId INTEGER NOT NULL, producedItemName TEXT NOT NULL, qtyProduced REAL NOT NULL, " +
                        "unit TEXT NOT NULL DEFAULT '', materialCost REAL NOT NULL, labourCost REAL NOT NULL, " +
                        "totalCost REAL NOT NULL, unitCost REAL NOT NULL, remarks TEXT NOT NULL DEFAULT '', " +
                        "materialOutId INTEGER NOT NULL DEFAULT 0, materialReceiptId INTEGER NOT NULL DEFAULT 0)"
                )
                db.execSQL("ALTER TABLE material_outs ADD COLUMN productionRunId INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE material_receipts ADD COLUMN productionRunId INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Item bundles: a named combo of items sold as one line, and its component lines. */
        private val MIGRATION_68_69 = object : androidx.room.migration.Migration(68, 69) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS item_bundles (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, " +
                        "unit TEXT NOT NULL DEFAULT '', price REAL NOT NULL DEFAULT 0, remarks TEXT NOT NULL DEFAULT '')"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS item_bundle_components (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, bundleId INTEGER NOT NULL, " +
                        "itemId INTEGER NOT NULL DEFAULT 0, name TEXT NOT NULL, qty REAL NOT NULL, " +
                        "unit TEXT NOT NULL DEFAULT '')"
                )
            }
        }

        /** Per-record deviceId, for merge dedup: the same origin document must never re-insert twice. */
        private val MIGRATION_69_70 = object : androidx.room.migration.Migration(69, 70) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bills ADD COLUMN deviceId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE purchases ADD COLUMN deviceId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE receipts ADD COLUMN deviceId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE expenses ADD COLUMN deviceId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE quotations ADD COLUMN deviceId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE estimates ADD COLUMN deviceId TEXT NOT NULL DEFAULT ''")
            }
        }

        /** Quick invoices attached from the Customer dialog: a bill with no items, marking a legacy due. */
        private val MIGRATION_70_71 = object : androidx.room.migration.Migration(70, 71) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bills ADD COLUMN isLegacy INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** A quotation converted into a sale records the bill it became, like delivery notes do. */
        private val MIGRATION_71_72 = object : androidx.room.migration.Migration(71, 72) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE quotations ADD COLUMN convertedBillNo TEXT NOT NULL DEFAULT ''")
            }
        }

        /** Quick purchase invoices attached from the Supplier dialog: a purchase with no items,
         * marking a legacy due, mirroring MIGRATION_70_71 for bills. */
        private val MIGRATION_72_73 = object : androidx.room.migration.Migration(72, 73) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE purchases ADD COLUMN isLegacy INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Accounting module batch 1 (Master): nested account groups, cheque register, cost
         * centers (+ tag on journal lines), fixed asset register. */
        private val MIGRATION_73_74 = object : androidx.room.migration.Migration(73, 74) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE account_groups ADD COLUMN parentGroupId INTEGER")
                db.execSQL("ALTER TABLE journal_lines ADD COLUMN costCenterId INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS cheque_entries (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, chequeNo TEXT NOT NULL, " +
                        "bankName TEXT NOT NULL DEFAULT '', partyName TEXT NOT NULL, amount REAL NOT NULL, " +
                        "dateMillis INTEGER NOT NULL, isReceived INTEGER NOT NULL, status TEXT NOT NULL DEFAULT 'Pending', " +
                        "linkedType TEXT NOT NULL DEFAULT '', linkedId INTEGER NOT NULL DEFAULT 0, " +
                        "remarks TEXT NOT NULL DEFAULT '', deviceId TEXT NOT NULL DEFAULT '')"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS cost_centers (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, " +
                        "isSystem INTEGER NOT NULL DEFAULT 0)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS fixed_assets (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, " +
                        "category TEXT NOT NULL DEFAULT '', purchaseDate INTEGER NOT NULL, purchaseCost REAL NOT NULL, " +
                        "depreciationMethod TEXT NOT NULL DEFAULT 'StraightLine', ratePercent REAL NOT NULL DEFAULT 0, " +
                        "accumulatedDepreciation REAL NOT NULL DEFAULT 0, linkedPurchaseId INTEGER NOT NULL DEFAULT 0, " +
                        "remarks TEXT NOT NULL DEFAULT '', deviceId TEXT NOT NULL DEFAULT '')"
                )
            }
        }

        /** Last-edited timestamp for the cloud-sync "only push recent changes" window. Existing
         * rows get dateMillis as a reasonable baseline (better than 0, which would make every
         * pre-existing row look infinitely old and never eligible once a window is set). */
        private val MIGRATION_74_75 = object : androidx.room.migration.Migration(74, 75) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bills ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE bills SET updatedAt = dateMillis")
                db.execSQL("ALTER TABLE purchases ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE purchases SET updatedAt = dateMillis")
                db.execSQL("ALTER TABLE receipts ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE receipts SET updatedAt = dateMillis")
                db.execSQL("ALTER TABLE expenses ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE expenses SET updatedAt = dateMillis")
                db.execSQL("ALTER TABLE quotations ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE quotations SET updatedAt = dateMillis")
                db.execSQL("ALTER TABLE estimates ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE estimates SET updatedAt = dateMillis")
                db.execSQL("ALTER TABLE journal_entries ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE journal_entries SET updatedAt = dateMillis")
            }
        }

        /** MRP (list price) for items and its snapshot on each sold line — printed on the A4 invoice. */
        private val MIGRATION_75_76 = object : androidx.room.migration.Migration(75, 76) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN mrp REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE bill_items ADD COLUMN mrp REAL NOT NULL DEFAULT 0")
            }
        }

        /** Contra entry (Accounting Batch 2): tags a journal voucher as a Cash/Bank transfer
         * so it lists separately from plain manual journals; existing rows default to JOURNAL. */
        private val MIGRATION_76_77 = object : androidx.room.migration.Migration(76, 77) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE journal_entries ADD COLUMN voucherType TEXT NOT NULL DEFAULT 'JOURNAL'")
            }
        }

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "pos_billing.db"
                )
                    .addMigrations(MIGRATION_36_37, MIGRATION_37_38, MIGRATION_38_39, MIGRATION_39_40, MIGRATION_40_41, MIGRATION_41_42, MIGRATION_42_43, MIGRATION_43_44, MIGRATION_44_45, MIGRATION_45_46, MIGRATION_46_47, MIGRATION_47_48, MIGRATION_48_49, MIGRATION_49_50, MIGRATION_50_51, MIGRATION_51_52, MIGRATION_52_53, MIGRATION_53_54, MIGRATION_54_55, MIGRATION_55_56, MIGRATION_56_57, MIGRATION_57_58, MIGRATION_58_59, MIGRATION_59_60, MIGRATION_60_61, MIGRATION_61_62, MIGRATION_62_63, MIGRATION_63_64, MIGRATION_64_65, MIGRATION_65_66, MIGRATION_66_67, MIGRATION_67_68, MIGRATION_68_69, MIGRATION_69_70, MIGRATION_70_71, MIGRATION_71_72, MIGRATION_72_73, MIGRATION_73_74, MIGRATION_74_75, MIGRATION_75_76, MIGRATION_76_77)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
