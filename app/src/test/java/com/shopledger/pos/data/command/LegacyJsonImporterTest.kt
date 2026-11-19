package com.shopledger.pos.data.command

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shopledger.pos.data.local.LpsDatabase
import com.shopledger.pos.testing.TestEnv
import com.shopledger.pos.testing.newTestDatabase
import com.shopledger.pos.testing.scalar
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyJsonImporterTest {
    private lateinit var db: LpsDatabase
    private lateinit var importer: LegacyJsonImporter

    @Before
    fun setUp() {
        db = newTestDatabase()
        importer = LegacyJsonImporter(db, TestEnv())
    }

    @After
    fun tearDown() = db.close()

    private val v2Backup = """
        {"schemaVersion":2,
         "items":[{"id":"i1","displayName":"Butt Hinge","sortKey":"BUTT HINGE","category":"Fittings","createdAt":1,"updatedAt":1,"isDeleted":false}],
         "variants":[{"id":"v1","itemId":"i1","sizeLabel":"4 inch","unit":"pcs","purchasePricePaise":4800,"sellingPricePaise":7500,"stockQuantity":8.0,"lowStockThreshold":5.0,"updatedAt":1,"isDeleted":false}],
         "sales":[
           {"id":"s1","receiptNumber":"202611010001","itemId":"i1","variantId":"v1","itemNameSnapshot":"Butt Hinge","variantSummarySnapshot":"4 inch","quantitySold":2.0,"unitSnapshot":"pcs","unitPurchasePricePaise":4800,"unitSellingPricePaise":7500,"totalCostPaise":9600,"totalRevenuePaise":15000,"totalProfitPaise":5400,"soldAt":1000,"isReturned":false},
           {"id":"s2","receiptNumber":"202611010001","itemId":"i1","variantId":"v1","itemNameSnapshot":"Butt Hinge","variantSummarySnapshot":"4 inch","quantitySold":1.0,"unitSnapshot":"pcs","unitPurchasePricePaise":4800,"unitSellingPricePaise":7500,"totalCostPaise":4800,"totalRevenuePaise":7500,"totalProfitPaise":2700,"soldAt":1000,"isReturned":true}],
         "parties":[{"id":"p1","partyType":"CUSTOMER","name":"Ramesh","phone":"9876543210","currentBalancePaise":500,"updatedAt":1,"isDeleted":false}]}
    """.trimIndent()

    @Test
    fun import_buildsLedgerRowsInvoicesAndCreditNotes() = runBlocking<Unit> {
        val report = importer.import(v2Backup)
        assertEquals(LegacyJsonImporter.Report(items = 1, variants = 1, parties = 1, sales = 2), report)
        assertEquals(8_000L, db.scalar("SELECT stockMilliUnits FROM item_variants WHERE id = 'v1'"))
        assertEquals(500L, db.scalar("SELECT currentBalancePaise FROM parties WHERE id = 'p1'"))
        assertEquals(1L, db.scalar("SELECT COUNT(*) FROM invoice_headers WHERE docType = 'SALE'"))
        assertEquals(1L, db.scalar("SELECT COUNT(*) FROM invoice_headers WHERE docType = 'CREDIT_NOTE'"))
        val rows = db.saleDao().getAllRawSales().associateBy { it.id }
        assertTrue(rows.getValue("s2").isReturned)
        assertEquals(false, rows.getValue("s1").isReturned)
    }

    @Test
    fun importingTheSameFileTwice_addsNothing() = runBlocking<Unit> {
        importer.import(v2Backup)
        val again = importer.import(v2Backup)
        assertEquals(LegacyJsonImporter.Report(0, 0, 0, 0), again)
        assertEquals(1L, db.scalar("SELECT COUNT(*) FROM inventory_items"))
    }
}
