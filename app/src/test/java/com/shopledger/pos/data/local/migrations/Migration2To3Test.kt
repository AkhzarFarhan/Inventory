package com.shopledger.pos.data.local.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shopledger.pos.data.command.FaultInjector
import com.shopledger.pos.data.local.IntegrityChecker
import com.shopledger.pos.data.local.LpsDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration2To3Test {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LpsDatabase::class.java)

    @Test
    fun migrate2To3_preservesStockBalancesSalesAndReturns() {
        helper.createDatabase(TEST_DB, 2).apply {
            seedV2(this)
            close()
        }
        // validateDroppedTables = false: the v2 movement log is intentionally kept as stock_movements_v2_archive.
        val db = helper.runMigrationsAndValidate(
            TEST_DB, 3, false,
            Migration2To3(MigrationContext(activeShopId = "default_shop", legacyDeviceCode = "d1"), clock = { 5_000L }, newDeviceId = { "device-1" })
        )
        fun long(sql: String) = db.query(sql).use { it.moveToFirst(); it.getLong(0) }
        fun text(sql: String) = db.query(sql).use { it.moveToFirst(); it.getString(0) }

        // Stock: the Double the shopkeeper saw (8.5) wins over the drifted milli column (8000).
        assertEquals(8_500L, long("SELECT stockMilliUnits FROM item_variants WHERE id = 'v1'"))
        assertEquals(8_500L, long("SELECT deltaMilliUnits FROM stock_movements WHERE movementId = 'v3open_v1'"))
        assertEquals(1L, long("SELECT COUNT(*) FROM stock_movements_v2_archive"))
        assertEquals(1L, long("SELECT isDemo FROM item_variants WHERE id = 'v2'"))
        assertEquals(1L, long("SELECT isDemo FROM inventory_items WHERE id = 'i2'"))

        // Khata: balances unchanged; clamped history repaired with an OPENING entry; JAMA → PAYMENT_IN.
        assertEquals(700L, long("SELECT currentBalancePaise FROM parties WHERE id = 'p1'"))
        assertEquals(0L, long("SELECT COUNT(*) FROM party_ledger_entries WHERE entryId = 'v3open_p1'"))
        assertEquals(0L, long("SELECT currentBalancePaise FROM parties WHERE id = 'p2'"))
        assertEquals(300L, long("SELECT amountDeltaPaise FROM party_ledger_entries WHERE entryId = 'v3open_p2'"))
        assertEquals(0L, long("SELECT COUNT(*) FROM party_ledger_entries WHERE entryType LIKE 'JAMA%'"))

        // Sales: 2 invoice lines + 1 orphan sale line, returned line becomes a credit note.
        assertEquals(3L, long("SELECT COUNT(*) FROM sale_records"))
        assertEquals(1L, long("SELECT isReturned FROM sale_records WHERE id = 'l2'"))
        assertEquals(0L, long("SELECT isReturned FROM sale_records WHERE id = 'l1'"))
        assertEquals(-7_500L, long("SELECT grandTotalPaise FROM invoice_headers WHERE id = 'v3cn_inv1'"))
        assertEquals("202611010001-R", text("SELECT invoiceNumber FROM invoice_headers WHERE id = 'v3cn_inv1'"))
        assertEquals(7_500L, long("SELECT grandTotalPaise FROM invoice_headers WHERE invoiceNumber = '202611010002'"))

        // New tables and rows.
        assertEquals("D1", text("SELECT deviceCode FROM devices WHERE isLocal = 1"))
        assertEquals("device-1", text("SELECT deviceId FROM devices WHERE isLocal = 1"))
        assertEquals(1000L, long("SELECT maxStaffDiscountBps FROM shops WHERE shopId = 'default_shop'"))
        assertEquals(1L, long("SELECT COUNT(*) FROM sync_outbox WHERE docType = 'ITEM'")) // demo item i2 excluded
        assertEquals(3L, long("SELECT COUNT(*) FROM sync_outbox WHERE docType = 'INVOICE'"))
        assertEquals(emptyList<String>(), IntegrityChecker.findViolations(db))
    }

    // ---- fix round 1 -------------------------------------------------------------------------------------

    private val ctx = MigrationContext(activeShopId = "default_shop", legacyDeviceCode = "d1")

    private fun migrateFrom(
        name: String,
        faults: FaultInjector = FaultInjector.NONE,
        seed: (SupportSQLiteDatabase) -> Unit
    ): SupportSQLiteDatabase {
        helper.createDatabase(name, 2).apply {
            seed(this)
            close()
        }
        return helper.runMigrationsAndValidate(
            name, 3, false,
            Migration2To3(ctx, clock = { 5_000L }, newDeviceId = { "device-1" }, faults = faults)
        )
    }

    private fun SupportSQLiteDatabase.long(sql: String): Long = query(sql).use { it.moveToFirst(); it.getLong(0) }
    private fun SupportSQLiteDatabase.text(sql: String): String = query(sql).use { it.moveToFirst(); it.getString(0) }
    private fun SupportSQLiteDatabase.row(sql: String): List<Long> =
        query(sql).use { c -> c.moveToFirst(); (0 until c.columnCount).map { c.getLong(it) } }

    private fun item(db: SupportSQLiteDatabase, id: String = "i1") = db.execSQL(
        "INSERT INTO inventory_items (id, shopId, displayName, sortKey, category, defaultHsnCode, defaultGstRateBps, createdAt, updatedAt, isDeleted, syncStatus) VALUES " +
            "('$id','default_shop','Item $id','ITEM $id','Fittings','',0,1,1,0,'SYNCED')"
    )

    private fun variant(db: SupportSQLiteDatabase, id: String, itemId: String, stock: Double, milli: Long) = db.execSQL(
        "INSERT INTO item_variants (id, shopId, itemId, skuCode, barcode, sizeLabel, materialLabel, colorFinishLabel, unit, mrpPaise, purchasePricePaise, sellingPricePaise, gstRateBps, stockQuantity, lowStockThreshold, stockMilliUnits, lowStockMilliUnits, updatedAt, isDeleted, syncStatus) VALUES " +
            "('$id','default_shop','$itemId','','','4 inch','','','pcs',0,4000,5000,0,$stock,1.0,$milli,1000,1,0,'SYNCED')"
    )

    private fun party(db: SupportSQLiteDatabase, id: String, balance: Long, deleted: Int = 0) = db.execSQL(
        "INSERT INTO parties (id, shopId, partyType, name, phone, gstin, billingAddress, openingBalancePaise, currentBalancePaise, updatedAt, isDeleted, syncStatus) VALUES " +
            "('$id','default_shop','CUSTOMER','Party $id','','','',0,$balance,1,$deleted,'SYNCED')"
    )

    private fun ledger(db: SupportSQLiteDatabase, id: String, partyId: String, amount: Long) = db.execSQL(
        "INSERT INTO party_ledger_entries (entryId, shopId, partyId, refDocId, entryType, amountDeltaPaise, paymentMode, note, createdAt, syncStatus) VALUES " +
            "('$id','default_shop','$partyId','','CREDIT_BILL',$amount,'CREDIT','',1,'SYNCED')"
    )

    private fun v2Header(
        db: SupportSQLiteDatabase, id: String, number: String, partyId: String?,
        subtotal: Long, discount: Long, cgst: Long, sgst: Long, grand: Long, paid: Long, due: Long
    ) = db.execSQL(
        "INSERT INTO invoice_headers (id, shopId, deviceShortCode, invoiceNumber, docType, partyId, buyerName, buyerPhone, paymentMode, subtotalPaise, discountPaise, cgstPaise, sgstPaise, igstPaise, grandTotalPaise, amountPaidPaise, balanceDuePaise, issuedAt, yearInt, monthInt, dayInt, isReturned, updatedAt, syncStatus) VALUES " +
            "('$id','default_shop','','$number','SALE',${partyId?.let { "'$it'" } ?: "NULL"},'Buyer','','CREDIT',$subtotal,$discount,$cgst,$sgst,0,$grand,$paid,$due,1000,2026,11,1,0,1000,'SYNCED')"
    )

    private fun v2Line(db: SupportSQLiteDatabase, id: String, invoiceId: String, qtyMilli: Long, total: Long) = db.execSQL(
        "INSERT INTO invoice_lines (id, shopId, invoiceId, productId, skuId, itemNameSnapshot, variantSummarySnapshot, hsnSnapshot, qtyMilliUnits, unitSnapshot, unitCostPaise, unitSellPaise, gstRateBps, lineTaxPaise, lineTotalPaise, lineProfitPaise, isReturned) VALUES " +
            "('$id','default_shop','$invoiceId','i1','v1','Item','4 inch','',$qtyMilli,'pcs',4000,5000,0,0,$total,${total / 5},0)"
    )

    private fun saleRow(
        db: SupportSQLiteDatabase, id: String, receipt: String, qty: Double, revenue: Long,
        discount: Long = 0, gst: Long = 0, due: Long = 0, returned: Int = 0, partyId: String? = null
    ) = db.execSQL(
        "INSERT INTO sale_records (id, shopId, receiptNumber, buyerName, buyerPhone, partyId, itemId, variantId, itemNameSnapshot, variantSummarySnapshot, quantitySold, unitSnapshot, unitPurchasePricePaise, unitSellingPricePaise, totalCostPaise, totalRevenuePaise, totalProfitPaise, gainMarkupPercent, grossMarginPercent, paymentMode, amountPaidPaise, balanceDuePaise, discountPaise, gstPaise, soldAt, yearInt, monthInt, dayInt, isReturned, updatedAt, syncStatus) VALUES " +
            "('$id','default_shop','$receipt','Buyer','',${partyId?.let { "'$it'" } ?: "NULL"},'i1','v1','Item','4 inch',$qty,'pcs',4000,5000,${revenue * 4 / 5},$revenue,${revenue / 5},25.0,20.0,'CREDIT',0,$due,$discount,$gst,3000,2026,11,1,$returned,3000,'SYNCED')"
    )

    @Test
    fun v2InvoiceWithDiscountGstAndCredit_keepsHeaderTotals() {
        val db = migrateFrom("mig-header.db") {
            item(it); variant(it, "v1", "i1", 0.0, 0)
            party(it, "p1", 4_000); ledger(it, "e1", "p1", 4_000)
            v2Header(it, "inv1", "N1", "p1", 10_000, 500, 450, 450, 10_400, 6_400, 4_000)
            v2Line(it, "l1", "inv1", 2_000, 10_000)
            saleRow(it, "l1", "N1", 2.0, 10_000, discount = 500, gst = 900, due = 4_000)
        }
        assertEquals(
            listOf(10_000L, 500L, 450L, 450L, 0L, 10_400L, 6_400L, 4_000L),
            db.row("SELECT subtotalPaise, discountPaise, cgstPaise, sgstPaise, igstPaise, grandTotalPaise, amountPaidPaise, balanceDuePaise FROM invoice_headers WHERE id = 'inv1'")
        )
        assertEquals(4_000L, db.long("SELECT currentBalancePaise FROM parties WHERE id = 'p1'"))
        assertEquals(emptyList<String>(), IntegrityChecker.findViolations(db))
    }

    @Test
    fun orphanSale_carriesDiscountGstAndBalanceDue() {
        val db = migrateFrom("mig-orphan.db") {
            item(it); variant(it, "v1", "i1", 0.0, 0)
            party(it, "p1", 2_000); ledger(it, "e1", "p1", 2_000)
            saleRow(it, "s1", "R1", 2.0, 6_000, discount = 500, gst = 901, due = 2_000, partyId = "p1")
            saleRow(it, "s2", "R1", 1.0, 4_000, discount = 500, gst = 901, due = 2_000, partyId = "p1")
        }
        assertEquals(
            listOf(10_000L, 500L, 450L, 451L, 0L, 10_401L, 8_401L, 2_000L),
            db.row("SELECT subtotalPaise, discountPaise, cgstPaise, sgstPaise, igstPaise, grandTotalPaise, amountPaidPaise, balanceDuePaise FROM invoice_headers WHERE id = 'v3mig_default_shop_R1'")
        )
        assertEquals(2L, db.long("SELECT COUNT(*) FROM invoice_lines WHERE invoiceId = 'v3mig_default_shop_R1'"))
        assertEquals(2_000L, db.long("SELECT currentBalancePaise FROM parties WHERE id = 'p1'"))
        assertEquals(2L, db.long("SELECT COUNT(*) FROM sale_records_v2_archive"))
        assertEquals(emptyList<String>(), IntegrityChecker.findViolations(db))
    }

    @Test
    fun orphanSale_balanceDueIsClampedIntoGrandTotal() {
        val db = migrateFrom("mig-clamp.db") {
            item(it); variant(it, "v1", "i1", 0.0, 0)
            saleRow(it, "s1", "R9", 1.0, 1_000, due = 99_999)
            saleRow(it, "s2", "R8", 1.0, 1_000, due = -5)
        }
        assertEquals(listOf(1_000L, 0L), db.row("SELECT grandTotalPaise - amountPaidPaise, amountPaidPaise FROM invoice_headers WHERE id = 'v3mig_default_shop_R9'"))
        assertEquals(listOf(0L, 1_000L), db.row("SELECT balanceDuePaise, amountPaidPaise FROM invoice_headers WHERE id = 'v3mig_default_shop_R8'"))
        assertEquals(emptyList<String>(), IntegrityChecker.findViolations(db))
    }

    @Test
    fun orphanSaleWithReturnedLine_getsCreditNote() {
        val db = migrateFrom("mig-orphan-return.db") {
            item(it); variant(it, "v1", "i1", 0.0, 0)
            saleRow(it, "s1", "R1", 2.0, 6_000)
            saleRow(it, "s2", "R1", 1.0, 4_000, returned = 1)
        }
        assertEquals(-4_000L, db.long("SELECT grandTotalPaise FROM invoice_headers WHERE id = 'v3cn_v3mig_default_shop_R1'"))
        assertEquals("v3mig_default_shop_R1", db.text("SELECT originalInvoiceId FROM invoice_headers WHERE id = 'v3cn_v3mig_default_shop_R1'"))
        assertEquals("R1-R", db.text("SELECT invoiceNumber FROM invoice_headers WHERE id = 'v3cn_v3mig_default_shop_R1'"))
        assertEquals(-1_000L, db.long("SELECT qtyMilliUnits FROM invoice_lines WHERE id = 'v3cn_s2'"))
        assertEquals(1L, db.long("SELECT isReturned FROM sale_records WHERE id = 's2'"))
        assertEquals(emptyList<String>(), IntegrityChecker.findViolations(db))
    }

    @Test
    fun orphanReceiptNumberCollidingWithInvoice_getsMSuffix() {
        val db = migrateFrom("mig-collision.db") {
            item(it); variant(it, "v1", "i1", 0.0, 0)
            v2Header(it, "inv1", "R1", null, 5_000, 0, 0, 0, 5_000, 5_000, 0)
            v2Line(it, "l1", "inv1", 1_000, 5_000)
            saleRow(it, "l1", "R1", 1.0, 5_000)
            saleRow(it, "orphan", "R1", 1.0, 3_000)
        }
        assertEquals("R1-M", db.text("SELECT invoiceNumber FROM invoice_headers WHERE id = 'v3mig_default_shop_R1'"))
        assertEquals("R1", db.text("SELECT invoiceNumber FROM invoice_headers WHERE id = 'inv1'"))
        assertEquals(2L, db.long("SELECT COUNT(*) FROM sale_records"))
        assertEquals(emptyList<String>(), IntegrityChecker.findViolations(db))
    }

    @Test
    fun realShopRow_isPreserved() {
        val db = migrateFrom("mig-shop.db") {
            it.execSQL(
                "INSERT INTO shops (shopId, shopName, verticalType, ownerUid, gstin, stateCode, gstEnabled, upiId, whatsappPhone, deviceShortCode, invoicePrefix, cipherMode, cipherWord10, ownerPinHash, isSetupCompleted, updatedAt) VALUES " +
                    "('default_shop','Sharma Hardware','HARDWARE','uid-1','29ABCDE1234F1Z5','29',1,'sharma@upi','9999999999','SH','SHW','CUSTOM_CODE','BLACKHORSE','pinhash-xyz',1,77)"
            )
        }
        assertEquals("Sharma Hardware", db.text("SELECT shopName FROM shops WHERE shopId = 'default_shop'"))
        assertEquals("29ABCDE1234F1Z5", db.text("SELECT gstin FROM shops WHERE shopId = 'default_shop'"))
        assertEquals("sharma@upi", db.text("SELECT upiId FROM shops WHERE shopId = 'default_shop'"))
        assertEquals("pinhash-xyz", db.text("SELECT ownerPinHash FROM shops WHERE shopId = 'default_shop'"))
        assertEquals("SHW", db.text("SELECT invoicePrefix FROM shops WHERE shopId = 'default_shop'"))
        assertEquals(1_000L, db.long("SELECT maxStaffDiscountBps FROM shops WHERE shopId = 'default_shop'"))
        assertEquals(1L, db.long("SELECT COUNT(*) FROM shops"))
    }

    @Test
    fun deletedPartyWithBalance_isPreserved() {
        val db = migrateFrom("mig-deleted-party.db") {
            party(it, "pd", 450, deleted = 1); ledger(it, "e1", "pd", 450)
        }
        assertEquals(450L, db.long("SELECT currentBalancePaise FROM parties WHERE id = 'pd'"))
        assertEquals(1L, db.long("SELECT isDeleted FROM parties WHERE id = 'pd'"))
        assertEquals(emptyList<String>(), IntegrityChecker.findViolations(db))
    }

    @Test
    fun zeroAndNegativeStock_areHandled() {
        val db = migrateFrom("mig-stock.db") {
            item(it)
            variant(it, "vz", "i1", 0.0, 0)
            variant(it, "vn", "i1", -2.0, 0)
        }
        assertEquals(0L, db.long("SELECT stockMilliUnits FROM item_variants WHERE id = 'vz'"))
        assertEquals(0L, db.long("SELECT COUNT(*) FROM stock_movements WHERE skuId = 'vz'"))
        assertEquals(-2_000L, db.long("SELECT stockMilliUnits FROM item_variants WHERE id = 'vn'"))
        assertEquals(-2_000L, db.long("SELECT deltaMilliUnits FROM stock_movements WHERE movementId = 'v3open_vn'"))
        assertEquals(emptyList<String>(), IntegrityChecker.findViolations(db))
    }

    @Test
    fun zeroQuantityReturnedLine_doesNotBlockUpgrade() {
        val db = migrateFrom("mig-zero-return.db") {
            item(it); variant(it, "v1", "i1", 0.0, 0)
            v2Header(it, "inv1", "N1", null, 5_000, 0, 0, 0, 5_000, 5_000, 0)
            v2Line(it, "lz", "inv1", 0, 0)
            v2Line(it, "lk", "inv1", 1_000, 5_000)
            saleRow(it, "lz", "N1", 0.0, 0, returned = 1)
            saleRow(it, "lk", "N1", 1.0, 5_000)
            saleRow(it, "oz", "R5", 0.0, 0, returned = 1)
        }
        assertEquals(0L, db.long("SELECT COUNT(*) FROM invoice_headers WHERE docType = 'CREDIT_NOTE'"))
        assertEquals(emptyList<String>(), IntegrityChecker.findViolations(db))
    }

    @Test
    fun failedUpgrade_leavesV2DatabaseIntact() {
        val name = "mig-fault.db"
        var threw = false
        try {
            migrateFrom(name, faults = { step -> if (step == "POST_MIGRATE") throw IllegalStateException("injected") }) {
                item(it); variant(it, "v1", "i1", 4.0, 4_000)
                party(it, "p1", 100); ledger(it, "e1", "p1", 100)
                saleRow(it, "s1", "R1", 1.0, 5_000)
            }
        } catch (t: Throwable) {
            threw = true
            val chain = generateSequence(t) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
            assertTrue("expected injected POST_MIGRATE fault, got: $chain", chain.contains("injected"))
        }
        assertTrue("migration must throw", threw)
        val file = InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath(name)
        android.database.sqlite.SQLiteDatabase.openDatabase(file.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { raw ->
            assertEquals(2, raw.version)
            fun n(sql: String) = raw.rawQuery(sql, null).use { it.moveToFirst(); it.getLong(0) }
            assertEquals(1L, n("SELECT COUNT(*) FROM sale_records"))
            assertEquals(1L, n("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'sale_records'"))
            assertEquals(0L, n("SELECT COUNT(*) FROM sqlite_master WHERE name = 'devices'"))
            assertEquals(0L, n("SELECT COUNT(*) FROM sqlite_master WHERE name = 'sale_records_v2_archive'"))
            assertEquals(1L, n("SELECT COUNT(*) FROM party_ledger_entries"))
            assertEquals(1L, n("SELECT COUNT(*) FROM item_variants WHERE stockQuantity = 4.0"))
            assertEquals(0L, n("SELECT COUNT(*) FROM sqlite_master WHERE name LIKE 'tmp_v3%'"))
        }
    }

    /** Non-asserting timing probe for item D: prints the elapsed ms; only asserts completion and a clean check. */
    @Test
    fun largeShop_upgradeTiming() {
        val name = "mig-large.db"
        helper.createDatabase(name, 2).apply {
            beginTransaction()
            try {
                execSQL(
                    "WITH RECURSIVE c(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM c WHERE n < 5000) " +
                        "INSERT INTO inventory_items (id, shopId, displayName, sortKey, category, defaultHsnCode, defaultGstRateBps, createdAt, updatedAt, isDeleted, syncStatus) " +
                        "SELECT 'i' || n, 'default_shop', 'Item ' || n, 'ITEM ' || n, 'Fittings', '', 0, 1, 1, 0, 'SYNCED' FROM c"
                )
                execSQL(
                    "WITH RECURSIVE c(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM c WHERE n < 5000) " +
                        "INSERT INTO item_variants (id, shopId, itemId, skuCode, barcode, sizeLabel, materialLabel, colorFinishLabel, unit, mrpPaise, purchasePricePaise, sellingPricePaise, gstRateBps, stockQuantity, lowStockThreshold, stockMilliUnits, lowStockMilliUnits, updatedAt, isDeleted, syncStatus) " +
                        "SELECT 'v' || n, 'default_shop', 'i' || n, '', '', '', '', '', 'pcs', 0, 4000, 5000, 0, n % 50, 1.0, (n % 50) * 1000, 1000, 1, 0, 'SYNCED' FROM c"
                )
                execSQL(
                    "WITH RECURSIVE c(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM c WHERE n < 2000) " +
                        "INSERT INTO parties (id, shopId, partyType, name, phone, gstin, billingAddress, openingBalancePaise, currentBalancePaise, updatedAt, isDeleted, syncStatus) " +
                        "SELECT 'p' || n, 'default_shop', 'CUSTOMER', 'Party ' || n, '', '', '', 0, 100, 1, 0, 'SYNCED' FROM c"
                )
                execSQL(
                    "WITH RECURSIVE c(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM c WHERE n < 20000) " +
                        "INSERT INTO party_ledger_entries (entryId, shopId, partyId, refDocId, entryType, amountDeltaPaise, paymentMode, note, createdAt, syncStatus) " +
                        "SELECT 'e' || n, 'default_shop', 'p' || (n % 2000 + 1), '', 'CREDIT_BILL', 10, 'CREDIT', '', 1, 'SYNCED' FROM c"
                )
                execSQL(
                    "WITH RECURSIVE c(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM c WHERE n < 5000) " +
                        "INSERT INTO stock_movements (movementId, shopId, skuId, deviceShortCode, deltaMilliUnits, movementType, refDocId, note, createdAt, syncStatus) " +
                        "SELECT 'm' || n, 'default_shop', 'v' || n, '', -1000, 'SALE', '', '', 1, 'SYNCED' FROM c"
                )
                setTransactionSuccessful()
            } finally {
                endTransaction()
            }
            close()
        }
        val start = System.nanoTime()
        val db = helper.runMigrationsAndValidate(name, 3, false, Migration2To3(ctx, clock = { 5_000L }, newDeviceId = { "device-1" }))
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        println("MIGRATION_TIMING_MS=$elapsedMs (5000 variants, 2000 parties, 20000 ledger entries, 5000 movements)")
        assertEquals(0L, db.long("SELECT COUNT(*) FROM sqlite_master WHERE name LIKE 'tmp_v3%'"))
        assertEquals(emptyList<String>(), IntegrityChecker.findViolations(db))
    }

    private fun seedV2(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO inventory_items (id, shopId, displayName, sortKey, category, defaultHsnCode, defaultGstRateBps, createdAt, updatedAt, isDeleted, syncStatus) VALUES " +
                "('i1','default_shop','Butt Hinge','BUTT HINGE','Fittings','',0,1,1,0,'SYNCED'), " +
                "('i2','default_shop','Demo Screw','DEMO SCREW','Fasteners','',0,1,1,0,'DEMO_SEED')"
        )
        db.execSQL(
            "INSERT INTO item_variants (id, shopId, itemId, skuCode, barcode, sizeLabel, materialLabel, colorFinishLabel, unit, mrpPaise, purchasePricePaise, sellingPricePaise, gstRateBps, stockQuantity, lowStockThreshold, stockMilliUnits, lowStockMilliUnits, updatedAt, isDeleted, syncStatus) VALUES " +
                "('v1','default_shop','i1','','','4 inch','SS','','pcs',0,4800,7500,0,8.5,5.0,8000,5000,1,0,'SYNCED'), " +
                "('v2','default_shop','i2','','','1 inch','','','box',0,1000,1500,0,3.0,1.0,3000,1000,1,0,'DEMO_SEED')"
        )
        db.execSQL(
            "INSERT INTO parties (id, shopId, partyType, name, phone, gstin, billingAddress, openingBalancePaise, currentBalancePaise, updatedAt, isDeleted, syncStatus) VALUES " +
                "('p1','default_shop','CUSTOMER','Ramesh','9876543210','','',0,700,1,0,'SYNCED'), " +
                "('p2','default_shop','CUSTOMER','Suresh','9123456780','','',0,0,1,0,'SYNCED')"
        )
        db.execSQL(
            "INSERT INTO party_ledger_entries (entryId, shopId, partyId, refDocId, entryType, amountDeltaPaise, paymentMode, note, createdAt, syncStatus) VALUES " +
                "('e1','default_shop','p1','202611010001','CREDIT_BILL',1000,'CREDIT','',1,'SYNCED'), " +
                "('e2','default_shop','p1','','JAMA_CASH',-300,'CASH','',2,'SYNCED'), " +
                "('e3','default_shop','p2','x','CREDIT_BILL',200,'CREDIT','',1,'SYNCED'), " +
                "('e4','default_shop','p2','','JAMA_UPI',-500,'UPI','',2,'SYNCED')"
        )
        db.execSQL(
            "INSERT INTO invoice_headers (id, shopId, deviceShortCode, invoiceNumber, docType, partyId, buyerName, buyerPhone, paymentMode, subtotalPaise, discountPaise, cgstPaise, sgstPaise, igstPaise, grandTotalPaise, amountPaidPaise, balanceDuePaise, issuedAt, yearInt, monthInt, dayInt, isReturned, updatedAt, syncStatus) VALUES " +
                "('inv1','default_shop','','202611010001','SALE',NULL,'Walk-in','','CASH',22500,0,0,0,0,22500,22500,0,1000,2026,11,1,0,1000,'SYNCED')"
        )
        db.execSQL(
            "INSERT INTO invoice_lines (id, shopId, invoiceId, productId, skuId, itemNameSnapshot, variantSummarySnapshot, hsnSnapshot, qtyMilliUnits, unitSnapshot, unitCostPaise, unitSellPaise, gstRateBps, lineTaxPaise, lineTotalPaise, lineProfitPaise, isReturned) VALUES " +
                "('l1','default_shop','inv1','i1','v1','Butt Hinge','4 inch','',2000,'pcs',4800,7500,0,0,15000,5400,0), " +
                "('l2','default_shop','inv1','i1','v1','Butt Hinge','4 inch','',1000,'pcs',4800,7500,0,0,7500,2700,0)"
        )
        val saleColumns = "id, shopId, receiptNumber, buyerName, buyerPhone, partyId, itemId, variantId, itemNameSnapshot, variantSummarySnapshot, quantitySold, unitSnapshot, unitPurchasePricePaise, unitSellingPricePaise, totalCostPaise, totalRevenuePaise, totalProfitPaise, gainMarkupPercent, grossMarginPercent, paymentMode, amountPaidPaise, balanceDuePaise, discountPaise, gstPaise, soldAt, yearInt, monthInt, dayInt, isReturned, updatedAt, syncStatus"
        db.execSQL(
            "INSERT INTO sale_records ($saleColumns) VALUES " +
                "('l1','default_shop','202611010001','Walk-in','',NULL,'i1','v1','Butt Hinge','4 inch',2.0,'pcs',4800,7500,9600,15000,5400,56.25,36.0,'CASH',22500,0,0,0,1000,2026,11,1,0,1000,'SYNCED'), " +
                "('l2','default_shop','202611010001','Walk-in','',NULL,'i1','v1','Butt Hinge','4 inch',1.0,'pcs',4800,7500,4800,7500,2700,56.25,36.0,'CASH',22500,0,0,0,1000,2026,11,1,1,2000,'SYNCED'), " +
                "('s3','default_shop','202611010002','','',NULL,'i1','v1','Butt Hinge','4 inch',1.0,'pcs',4800,7500,4800,7500,2700,56.25,36.0,'CASH',7500,0,0,0,3000,2026,11,1,0,3000,'SYNCED')"
        )
        db.execSQL(
            "INSERT INTO stock_movements (movementId, shopId, skuId, deviceShortCode, deltaMilliUnits, movementType, refDocId, note, createdAt, syncStatus) VALUES " +
                "('m1','default_shop','v1','',-2000,'SALE','202611010001','Sale',1000,'SYNCED')"
        )
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
