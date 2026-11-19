package com.shopledger.pos.data.local.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shopledger.pos.data.local.IntegrityChecker
import com.shopledger.pos.data.local.LpsDatabase
import org.junit.Assert.assertEquals
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
