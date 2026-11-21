package com.shopledger.pos.data.local.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shopledger.pos.data.local.IntegrityChecker
import com.shopledger.pos.data.local.LpsDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration3To4Test {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LpsDatabase::class.java)

    private fun migrateV3(name: String, seed: (SupportSQLiteDatabase) -> Unit): SupportSQLiteDatabase {
        helper.createDatabase(name, 3).apply {
            seed(this)
            close()
        }
        return helper.runMigrationsAndValidate(
            name,
            4,
            true,
            Migration3To4(clock = { 9_000L })
        )
    }

    private fun SupportSQLiteDatabase.long(sql: String): Long =
        query(sql).use { it.moveToFirst(); it.getLong(0) }

    private fun SupportSQLiteDatabase.text(sql: String): String =
        query(sql).use { it.moveToFirst(); it.getString(0) }

    private fun insertItem(db: SupportSQLiteDatabase, id: String = "i1", name: String = "Butt Hinge") {
        db.execSQL(
            "INSERT INTO inventory_items (id, shopId, displayName, sortKey, category, defaultHsnCode, defaultGstRateBps, createdAt, updatedAt, isDeleted, isDemo, createdByUid, deviceId) VALUES " +
                "('$id','default_shop','$name','${name.uppercase()}','Fittings','',0,1000,1000,0,0,'','d1')"
        )
    }

    private fun insertVariant(
        db: SupportSQLiteDatabase,
        id: String,
        itemId: String = "i1",
        unit: String = "pcs",
        stockMilli: Long,
    ) {
        db.execSQL(
            "INSERT INTO item_variants (id, shopId, itemId, skuCode, barcode, sizeLabel, materialLabel, colorFinishLabel, unit, mrpPaise, purchasePricePaise, sellingPricePaise, gstRateBps, stockMilliUnits, lowStockMilliUnits, createdAt, updatedAt, isDeleted, isDemo, createdByUid, deviceId) VALUES " +
                "('$id','default_shop','$itemId','','','4 inch','','','$unit',0,4000,5000,0,$stockMilli,1000,1000,1000,0,0,'','d1')"
        )
    }

    private fun insertSupplier(db: SupportSQLiteDatabase, id: String, name: String, balance: Long) {
        db.execSQL(
            "INSERT INTO parties (id, shopId, partyType, name, phone, gstin, billingAddress, currentBalancePaise, createdAt, updatedAt, isDeleted, createdByUid, deviceId) VALUES " +
                "('$id','default_shop','SUPPLIER','$name','','','',$balance,1000,1000,0,'','d1')"
        )
    }

    private fun insertMovement(
        db: SupportSQLiteDatabase,
        id: String,
        skuId: String,
        deltaMilli: Long,
        movementType: String,
        refDocId: String,
        createdAt: Long = 2_000L,
    ) {
        db.execSQL(
            "INSERT INTO stock_movements (movementId, shopId, skuId, deviceId, deviceShortCode, deltaMilliUnits, movementType, refDocId, note, createdAt, createdByUid) VALUES " +
                "('$id','default_shop','$skuId','d1','D1',$deltaMilli,'$movementType','$refDocId','',$createdAt,'')"
        )
    }

    private fun insertLedger(
        db: SupportSQLiteDatabase,
        id: String,
        partyId: String,
        deltaPaise: Long,
        entryType: String,
        refDocId: String,
        createdAt: Long = 2_000L,
    ) {
        db.execSQL(
            "INSERT INTO party_ledger_entries (entryId, shopId, partyId, refDocId, entryType, amountDeltaPaise, paymentMode, note, createdAt, createdByUid, deviceId) VALUES " +
                "('$id','default_shop','$partyId','$refDocId','$entryType',$deltaPaise,'CREDIT','',$createdAt,'','d1')"
        )
    }

    @Test
    fun oldStockInWithSupplierBecomesLegacyBill() {
        val db = migrateV3("v3-to-v4-supplier.db") {
            insertSupplier(it, "s1", "Gupta Traders", -15_000L)
            insertItem(it, "i1", "Butt Hinge")
            insertVariant(it, "v1", "i1", unit = "pcs", stockMilli = 5_000L)
            insertVariant(it, "v2", "i1", unit = "box", stockMilli = 2_000L)
            insertMovement(it, "m1", "v1", 5_000L, "PURCHASE", "p1")
            insertMovement(it, "m2", "v2", 2_000L, "PURCHASE", "p1")
            insertLedger(it, "e1", "s1", -15_000L, "CREDIT_BILL", "p1")
        }
        assertEquals(1L, db.long("SELECT COUNT(*) FROM purchase_bills"))
        assertEquals("s1", db.text("SELECT supplierPartyId FROM purchase_bills WHERE id = 'p1'"))
        assertEquals("Gupta Traders", db.text("SELECT supplierNameSnapshot FROM purchase_bills WHERE id = 'p1'"))
        assertEquals(15_000L, db.long("SELECT totalPaise FROM purchase_bills WHERE id = 'p1'"))
        assertEquals(15_000L, db.long("SELECT duePaise FROM purchase_bills WHERE id = 'p1'"))
        assertEquals(0L, db.long("SELECT paidPaise FROM purchase_bills WHERE id = 'p1'"))
        assertEquals(1L, db.long("SELECT isLegacy FROM purchase_bills WHERE id = 'p1'"))
        assertEquals(2L, db.long("SELECT lineCount FROM purchase_bills WHERE id = 'p1'"))
        assertEquals(5_000L, db.long("SELECT qtyMilliUnits FROM purchase_bill_lines WHERE purchaseId = 'p1' AND skuId = 'v1'"))
        assertEquals(2_000L, db.long("SELECT qtyMilliUnits FROM purchase_bill_lines WHERE purchaseId = 'p1' AND skuId = 'v2'"))
        assertEquals(emptyList<String>(), IntegrityChecker.findViolations(db))
        db.close()
    }

    @Test
    fun oldStockInWithoutSupplierBecomesBillWithNoSupplier() {
        val db = migrateV3("v3-to-v4-nosupplier.db") {
            insertItem(it, "i1", "Screw")
            insertVariant(it, "v1", "i1", unit = "pcs", stockMilli = 4_000L)
            insertMovement(it, "m1", "v1", 4_000L, "PURCHASE", "p2")
        }
        assertEquals(1L, db.long("SELECT COUNT(*) FROM purchase_bills"))
        val supplierIsNull = db.query("SELECT supplierPartyId FROM purchase_bills WHERE id = 'p2'").use { c ->
            c.moveToFirst() && c.isNull(0)
        }
        assertTrue(supplierIsNull)
        assertEquals(0L, db.long("SELECT totalPaise FROM purchase_bills WHERE id = 'p2'"))
        assertEquals(0L, db.long("SELECT paidPaise FROM purchase_bills WHERE id = 'p2'"))
        assertEquals(0L, db.long("SELECT duePaise FROM purchase_bills WHERE id = 'p2'"))
        assertEquals(1L, db.long("SELECT isLegacy FROM purchase_bills WHERE id = 'p2'"))
        assertEquals(emptyList<String>(), IntegrityChecker.findViolations(db))
        db.close()
    }

    @Test
    fun nonPurchaseMovementsAreIgnored() {
        val db = migrateV3("v3-to-v4-nonpurchase.db") {
            insertItem(it, "i1", "Tap")
            insertVariant(it, "v1", "i1", unit = "pcs", stockMilli = 8_000L)
            insertMovement(it, "m_open", "v1", 10_000L, "OPENING", "open1")
            insertMovement(it, "m_sale", "v1", -2_000L, "SALE", "inv1")
        }
        assertEquals(0L, db.long("SELECT COUNT(*) FROM purchase_bills"))
        assertEquals(0L, db.long("SELECT COUNT(*) FROM purchase_bill_lines"))
        assertEquals(emptyList<String>(), IntegrityChecker.findViolations(db))
        db.close()
    }

    @Test
    fun outboxHasOneRowPerBill() {
        val db = migrateV3("v3-to-v4-outbox.db") {
            insertSupplier(it, "s1", "Shree Metals", -10_000L)
            insertItem(it, "i1", "Bolt")
            insertVariant(it, "v1", "i1", unit = "pcs", stockMilli = 7_000L)
            insertVariant(it, "v2", "i1", unit = "pcs", stockMilli = 3_000L)
            insertMovement(it, "m1", "v1", 5_000L, "PURCHASE", "p1")
            insertMovement(it, "m2", "v2", 3_000L, "PURCHASE", "p1")
            insertLedger(it, "e1", "s1", -10_000L, "CREDIT_BILL", "p1")
            insertMovement(it, "m3", "v1", 2_000L, "PURCHASE", "p2")
        }
        assertEquals(2L, db.long("SELECT COUNT(*) FROM sync_outbox WHERE docType = 'PURCHASE' AND op = 'APPEND'"))
        assertEquals(9_000L, db.long("SELECT createdAt FROM sync_outbox WHERE docType = 'PURCHASE' AND docId = 'p1'"))
        assertEquals(1L, db.long("SELECT COUNT(*) FROM sync_outbox WHERE docType = 'PURCHASE' AND docId = 'p2'"))
        db.close()
    }
}
