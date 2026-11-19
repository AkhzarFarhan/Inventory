package com.shopledger.pos.data.command

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shopledger.pos.data.local.LpsDatabase
import com.shopledger.pos.testing.TestEnv
import com.shopledger.pos.testing.expectFailure
import com.shopledger.pos.testing.newTestDatabase
import com.shopledger.pos.testing.scalar
import com.shopledger.pos.testing.toEdit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatalogCommandsTest {
    private lateinit var db: LpsDatabase
    private lateinit var env: TestEnv
    private lateinit var catalog: CatalogCommands
    private lateinit var khata: KhataCommands

    @Before
    fun setUp() {
        db = newTestDatabase()
        env = TestEnv()
        catalog = CatalogCommands(db, env)
        khata = KhataCommands(db, env)
    }

    @After
    fun tearDown() = db.close()

    private fun hinge(openingMilli: Long = 10_000L) = VariantSpec(
        sizeLabel = "4 inch", purchasePricePaise = 4_800L, sellingPricePaise = 7_500L, openingStockMilli = openingMilli
    )

    private suspend fun onlyVariant(itemId: String) = db.inventoryDao().getVariantsForItem(itemId).single()

    @Test
    fun createItem_writesOpeningMovementProjectionAndOutbox() = runBlocking {
        val itemId = catalog.createItem("a1", "Butt Hinge", "Fittings", listOf(hinge()))
        assertEquals(10_000L, onlyVariant(itemId).stockMilliUnits)
        val movement = db.shopPartyAndLedgerDao().getAllMovements().single()
        assertEquals(MovementType.OPENING, movement.movementType)
        assertEquals(10_000L, movement.deltaMilliUnits)
        assertEquals(listOf("ITEM", "VARIANT", "MOVEMENT"), db.syncOutboxDao().all().map { it.docType })
        assertEquals(1, env.commits.get())
    }

    @Test
    fun createItem_sameActionIdTwice_createsOnlyOneItem() = runBlocking {
        val first = catalog.createItem("a1", "Butt Hinge", "Fittings", listOf(hinge()))
        val second = catalog.createItem("a1", "Butt Hinge", "Fittings", listOf(hinge()))
        assertEquals(first, second)
        assertEquals(1L, db.scalar("SELECT COUNT(*) FROM inventory_items"))
        assertEquals(1L, db.scalar("SELECT COUNT(*) FROM stock_movements"))
    }

    @Test
    fun createItem_demoItem_isNotQueuedForSync() = runBlocking {
        catalog.createItem("seed-1", "Demo Hinge", "Fittings", listOf(hinge()), isDemo = true)
        assertEquals(0, db.syncOutboxDao().count())
        assertTrue(db.inventoryDao().getAllRawItems().single().isDemo)
        assertEquals(10_000L, db.scalar("SELECT SUM(deltaMilliUnits) FROM stock_movements"))
    }

    @Test
    fun editItem_withStaleUpdatedAt_throwsAndChangesNothing() = runBlocking {
        val itemId = catalog.createItem("a1", "Butt Hinge", "Fittings", listOf(hinge()))
        val item = db.inventoryDao().getItemById(itemId)!!
        val variant = onlyVariant(itemId)
        val outboxBefore = db.syncOutboxDao().count()
        expectFailure<CommandException.StaleEdit> {
            catalog.editItem("a2", itemId, item.updatedAt - 1, "Renamed", "Fittings", variant.id, variant.updatedAt, variant.toEdit())
        }
        assertEquals("Butt Hinge", db.inventoryDao().getItemById(itemId)!!.displayName)
        assertEquals(outboxBefore, db.syncOutboxDao().count())
        assertEquals(1, db.appliedActionDao().count())
    }

    @Test
    fun editItem_targetStock_appendsAdjustmentRelativeToCurrentStock() = runBlocking {
        val itemId = catalog.createItem("a1", "Butt Hinge", "Fittings", listOf(hinge(10_000L)))
        catalog.receivePurchase("a2", listOf(PurchaseLine(onlyVariant(itemId).id, 5_000L)))
        val item = db.inventoryDao().getItemById(itemId)!!
        val variant = onlyVariant(itemId)
        assertEquals(15_000L, variant.stockMilliUnits)
        catalog.editItem("a3", itemId, item.updatedAt, "Butt Hinge", "Fittings", variant.id, variant.updatedAt, variant.toEdit(12_000L))
        assertEquals(12_000L, onlyVariant(itemId).stockMilliUnits)
        val adjustment = db.shopPartyAndLedgerDao().getAllMovements().single { it.movementType == MovementType.ADJUSTMENT }
        assertEquals(-3_000L, adjustment.deltaMilliUnits)
    }

    @Test
    fun editItem_staffChangingCostPrice_isDenied() = runBlocking {
        val itemId = catalog.createItem("a1", "Butt Hinge", "Fittings", listOf(hinge()))
        val item = db.inventoryDao().getItemById(itemId)!!
        val variant = onlyVariant(itemId)
        env.role = Role.STAFF
        expectFailure<CommandException.PermissionDenied> {
            catalog.editItem("a2", itemId, item.updatedAt, "Butt Hinge", "Fittings", variant.id, variant.updatedAt, variant.toEdit().copy(purchasePricePaise = 1L))
        }
        assertEquals(4_800L, onlyVariant(itemId).purchasePricePaise)
    }

    @Test
    fun deleteItem_byStaff_isDenied() = runBlocking {
        val itemId = catalog.createItem("a1", "Butt Hinge", "Fittings", listOf(hinge()))
        env.role = Role.STAFF
        expectFailure<CommandException.PermissionDenied> { catalog.deleteItem("a2", itemId, null) }
        Unit
    }

    @Test
    fun deleteItem_lastVariant_softDeletesItemAndVariant() = runBlocking {
        val itemId = catalog.createItem("a1", "Butt Hinge", "Fittings", listOf(hinge()))
        val variantId = onlyVariant(itemId).id
        catalog.deleteItem("a2", itemId, variantId)
        assertTrue(db.inventoryDao().getItemById(itemId)!!.isDeleted)
        assertTrue(db.inventoryDao().getVariantById(variantId)!!.isDeleted)
        assertEquals(0, db.inventoryDao().getAllActiveItemsSnapshot().size)
    }

    @Test
    fun adjustStock_canGoNegative_withoutClamping() = runBlocking {
        val itemId = catalog.createItem("a1", "Butt Hinge", "Fittings", listOf(hinge(2_000L)))
        val newStock = catalog.adjustStock("a2", onlyVariant(itemId).id, deltaMilli = -5_000L, reason = "Damaged")
        assertEquals(-3_000L, newStock)
        assertTrue(onlyVariant(itemId).isOversold)
    }

    @Test
    fun receivePurchase_onCredit_addsSupplierPayable() = runBlocking {
        val supplierId = khata.createParty("p1", "Gupta Traders", "9000000000", "SUPPLIER")
        val itemId = catalog.createItem("a1", "Butt Hinge", "Fittings", listOf(hinge(10_000L)))
        catalog.receivePurchase(
            "a2", listOf(PurchaseLine(onlyVariant(itemId).id, 4_000L)),
            supplierPartyId = supplierId, billTotalPaise = 20_000L, amountPaidPaise = 5_000L
        )
        assertEquals(14_000L, onlyVariant(itemId).stockMilliUnits)
        assertEquals(-15_000L, db.shopPartyAndLedgerDao().getPartyById(supplierId)!!.currentBalancePaise)
    }
}
