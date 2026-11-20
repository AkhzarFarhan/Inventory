package com.shopledger.pos.data.command

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shopledger.pos.data.local.IntegrityChecker
import com.shopledger.pos.data.local.LpsDatabase
import com.shopledger.pos.testing.TestEnv
import com.shopledger.pos.testing.expectFailure
import com.shopledger.pos.testing.newTestDatabase
import com.shopledger.pos.testing.scalar
import com.shopledger.pos.testing.seedHinge
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StockInCommandsTest {
    private lateinit var db: LpsDatabase
    private lateinit var env: TestEnv
    private lateinit var stockIn: StockInCommands

    @Before
    fun setUp() {
        db = newTestDatabase()
        env = TestEnv()
        stockIn = StockInCommands(db, env)
    }

    @After
    fun tearDown() = db.close()

    private fun mixed(hingeId: String, actionId: String = "si1") = StockInRequest(
        actionId = actionId,
        rows = listOf(
            StockInRow(existingVariantId = hingeId, qtyMilli = 5_000L, costPaise = 5_000L, sellPaise = null),
            StockInRow(existingVariantId = null, newItemName = "Door Stopper", newCategory = "Fittings",
                qtyMilli = 12_000L, costPaise = 1_500L, sellPaise = 2_500L)
        ),
        newSupplierName = "Sharma Traders",
        billTotalPaise = 20_000L,
        amountPaidPaise = 5_000L
    )

    private fun snapshot(db: LpsDatabase): List<Long> = listOf(
        "SELECT COUNT(*) FROM inventory_items",
        "SELECT COUNT(*) FROM item_variants",
        "SELECT COUNT(*) FROM stock_movements",
        "SELECT COUNT(*) FROM party_ledger_entries",
        "SELECT COUNT(*) FROM parties",
        "SELECT COUNT(*) FROM sync_outbox",
        "SELECT COUNT(*) FROM applied_actions",
        "SELECT COALESCE(SUM(stockMilliUnits), 0) FROM item_variants",
        "SELECT COALESCE(SUM(purchasePricePaise), 0) FROM item_variants",
        "SELECT COALESCE(SUM(currentBalancePaise), 0) FROM parties"
    ).map { db.scalar(it) }

    @Test
    fun mixedRequest_savesEverythingAtomically() = runBlocking<Unit> {
        val hingeId = seedHinge(db, env)
        val result = stockIn.receiveScannedStock(mixed(hingeId))
        assertFalse(result.replayed)
        assertEquals(1, result.createdItems)
        assertEquals(1, result.restockedItems)
        assertEquals(17_000L, result.totalQtyMilli)
        assertEquals(15_000L, result.supplierDuePaise)

        val hinge = db.inventoryDao().getVariantById(hingeId)!!
        assertEquals(15_000L, hinge.stockMilliUnits)
        assertEquals(5_000L, hinge.purchasePricePaise)

        val stopper = db.inventoryDao().getAllRawVariants().single { it.id != hingeId }
        assertEquals(12_000L, stopper.stockMilliUnits)
        assertEquals(2_500L, stopper.sellingPricePaise)
        assertEquals(1_500L, stopper.purchasePricePaise)

        val supplier = db.shopPartyAndLedgerDao().getAllRawParties().single()
        assertEquals("SUPPLIER", supplier.partyType)
        assertEquals(-15_000L, supplier.currentBalancePaise)
        assertEquals(2L, db.scalar("SELECT COUNT(*) FROM stock_movements WHERE movementType = 'PURCHASE' AND refDocId = '${result.purchaseId}'"))
        assertEquals(1L, db.scalar("SELECT COUNT(*) FROM party_ledger_entries WHERE entryType = 'CREDIT_BILL'"))
        assertEquals(emptyList<String>(), IntegrityChecker.findViolations(db.openHelper.writableDatabase))
    }

    @Test
    fun sameActionIdTwice_replaysWithoutDuplicates() = runBlocking<Unit> {
        val hingeId = seedHinge(db, env)
        val first = stockIn.receiveScannedStock(mixed(hingeId))
        val before = snapshot(db)
        val second = stockIn.receiveScannedStock(mixed(hingeId))
        assertTrue(second.replayed)
        assertEquals(first.purchaseId, second.purchaseId)
        assertEquals(first.copy(replayed = true), second)
        assertEquals(before, snapshot(db))
    }

    @Test
    fun failureAtAnyStep_rollsBackEverything() = runBlocking<Unit> {
        for (step in StockInStep.ALL) {
            val db = newTestDatabase()
            val env = TestEnv()
            val hingeId = seedHinge(db, env)
            val failing = StockInCommands(db, env, FaultInjector { if (it == step) throw IllegalStateException("boom at $it") })
            val before = snapshot(db)
            val error = expectFailure<IllegalStateException> { failing.receiveScannedStock(mixed(hingeId, "act-$step")) }
            assertEquals("boom at $step", error.message)
            assertEquals("state changed after failure at $step", before, snapshot(db))
            db.close()
        }
    }

    @Test
    fun invalidRequests_throwAndWriteNothing() = runBlocking<Unit> {
        val hingeId = seedHinge(db, env)
        val before = snapshot(db)
        val existing = StockInRow(hingeId, qtyMilli = 1_000L, costPaise = 4_800L, sellPaise = null)
        val newRow = StockInRow(null, newItemName = "Lock", qtyMilli = 1_000L, costPaise = 100L, sellPaise = 200L)
        val bad = listOf(
            StockInRequest("v1", emptyList()),
            StockInRequest("v2", listOf(newRow.copy(sellPaise = null))),
            StockInRequest("v3", listOf(newRow.copy(newItemName = "  "))),
            StockInRequest("v4", listOf(existing, existing.copy(qtyMilli = 2_000L))),
            StockInRequest("v5", listOf(existing), newSupplierName = "S", billTotalPaise = 1_000L, amountPaidPaise = 2_000L),
            StockInRequest("v6", listOf(existing.copy(qtyMilli = 0L))),
            StockInRequest("v7", listOf(existing.copy(costPaise = -1L)))
        )
        for (request in bad) {
            expectFailure<CommandException.Validation> { stockIn.receiveScannedStock(request) }
            assertEquals("changed after ${request.actionId}", before, snapshot(db))
        }
    }

    @Test
    fun unknownSupplierId_isNotFoundAndWritesNothing() = runBlocking<Unit> {
        val hingeId = seedHinge(db, env)
        val before = snapshot(db)
        expectFailure<CommandException.NotFound> {
            stockIn.receiveScannedStock(
                StockInRequest("n1", listOf(StockInRow(hingeId, qtyMilli = 1_000L, costPaise = 4_800L, sellPaise = null)),
                    supplierPartyId = "nope", billTotalPaise = 100L)
            )
        }
        assertEquals(before, snapshot(db))
    }

    private fun hingeRow(id: String, cost: Long) = StockInRow(id, qtyMilli = 1_000L, costPaise = cost, sellPaise = null)

    @Test
    fun staff_changedCost_isDeniedAndWritesNothing() = runBlocking<Unit> {
        val hingeId = seedHinge(db, env)
        env.role = Role.STAFF
        val before = snapshot(db)
        expectFailure<CommandException.PermissionDenied> {
            stockIn.receiveScannedStock(StockInRequest("s1", listOf(hingeRow(hingeId, 5_000L)), newSupplierName = "S"))
        }
        assertEquals(before, snapshot(db))
    }

    @Test
    fun staff_unchangedCost_succeeds() = runBlocking<Unit> {
        val hingeId = seedHinge(db, env)
        env.role = Role.STAFF
        val result = stockIn.receiveScannedStock(StockInRequest("s2", listOf(hingeRow(hingeId, 4_800L))))
        assertEquals(1, result.restockedItems)
        assertEquals(11_000L, db.inventoryDao().getVariantById(hingeId)!!.stockMilliUnits)
    }

    @Test
    fun existingParty_mustBeSupplierOrBoth() = runBlocking<Unit> {
        val hingeId = seedHinge(db, env)
        val customer = KhataCommands(db, env).createParty("c1", "Ramesh", "", "CUSTOMER")
        val before = snapshot(db)
        expectFailure<CommandException.Validation> {
            stockIn.receiveScannedStock(StockInRequest("p1", listOf(hingeRow(hingeId, 4_800L)), supplierPartyId = customer, billTotalPaise = 100L))
        }
        assertEquals(before, snapshot(db))
    }

    @Test
    fun unpaidWithoutSupplier_isRejected() = runBlocking<Unit> {
        val hingeId = seedHinge(db, env)
        val before = snapshot(db)
        for (name in listOf(null, "", "   ")) {
            expectFailure<CommandException.Validation> {
                stockIn.receiveScannedStock(
                    StockInRequest("u-$name", listOf(hingeRow(hingeId, 4_800L)), newSupplierName = name, billTotalPaise = 100L)
                )
            }
        }
        assertEquals(before, snapshot(db))
    }

    @Test
    fun existingSupplierId_postsNegativeDue() = runBlocking<Unit> {
        val hingeId = seedHinge(db, env)
        val supplier = KhataCommands(db, env).createParty("s1", "Gupta", "", "SUPPLIER")
        val result = stockIn.receiveScannedStock(
            StockInRequest("e1", listOf(hingeRow(hingeId, 4_800L)), supplierPartyId = supplier, billTotalPaise = 3_000L, amountPaidPaise = 1_000L)
        )
        assertEquals(2_000L, result.supplierDuePaise)
        assertEquals(-2_000L, db.shopPartyAndLedgerDao().getPartyById(supplier)!!.currentBalancePaise)
    }

    @Test
    fun unchangedCost_writesNoVariantOutboxAndKeepsUpdatedAt() = runBlocking<Unit> {
        val hingeId = seedHinge(db, env)
        val updatedAt = db.inventoryDao().getVariantById(hingeId)!!.updatedAt
        val outboxBefore = db.scalar("SELECT COUNT(*) FROM sync_outbox WHERE docType = 'VARIANT'")
        env.clock += 1_000L
        stockIn.receiveScannedStock(StockInRequest("k1", listOf(hingeRow(hingeId, 4_800L))))
        assertEquals(outboxBefore, db.scalar("SELECT COUNT(*) FROM sync_outbox WHERE docType = 'VARIANT'"))
        assertEquals(updatedAt, db.inventoryDao().getVariantById(hingeId)!!.updatedAt)
    }

    @Test
    fun changedCost_enqueuesOneVariantUpsert() = runBlocking<Unit> {
        val hingeId = seedHinge(db, env)
        val sql = "SELECT COUNT(*) FROM sync_outbox WHERE docType = 'VARIANT' AND docId = '$hingeId' AND op = 'UPSERT'"
        val outboxBefore = db.scalar(sql)
        stockIn.receiveScannedStock(StockInRequest("k2", listOf(hingeRow(hingeId, 5_000L))))
        assertEquals(outboxBefore + 1, db.scalar(sql))
    }

    @Test
    fun fullyPaid_postsNoLedgerEntry() = runBlocking<Unit> {
        val hingeId = seedHinge(db, env)
        val result = stockIn.receiveScannedStock(
            StockInRequest("f1", listOf(hingeRow(hingeId, 4_800L)), newSupplierName = "Sharma", billTotalPaise = 4_000L, amountPaidPaise = 4_000L)
        )
        assertEquals(0L, result.supplierDuePaise)
        assertEquals(0L, db.scalar("SELECT COUNT(*) FROM party_ledger_entries"))
    }

    @Test
    fun negativeAmountsAndSellPrice_areRejected() = runBlocking<Unit> {
        val hingeId = seedHinge(db, env)
        val before = snapshot(db)
        val newRow = StockInRow(null, newItemName = "Lock", qtyMilli = 1_000L, costPaise = 100L, sellPaise = -1L)
        val bad = listOf(
            StockInRequest("n1", listOf(newRow)),
            StockInRequest("n2", listOf(hingeRow(hingeId, 4_800L)), newSupplierName = "S", billTotalPaise = 100L, amountPaidPaise = -1L),
            StockInRequest("n3", listOf(hingeRow(hingeId, 4_800L)), newSupplierName = "S", billTotalPaise = -100L, amountPaidPaise = -200L)
        )
        for (request in bad) {
            expectFailure<CommandException.Validation> { stockIn.receiveScannedStock(request) }
        }
        assertEquals(before, snapshot(db))
    }
}
