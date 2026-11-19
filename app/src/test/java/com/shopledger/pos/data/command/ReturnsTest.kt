package com.shopledger.pos.data.command

import androidx.test.ext.junit.runners.AndroidJUnit4
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
class ReturnsTest {
    private lateinit var db: LpsDatabase
    private lateinit var env: TestEnv
    private lateinit var sales: SalesCommands

    @Before
    fun setUp() {
        db = newTestDatabase()
        env = TestEnv()
        sales = SalesCommands(db, env)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun sellTwo(mode: String = "CASH", paid: Long? = null): InvoiceResult {
        val variantId = seedHinge(db, env)
        return sales.checkout(
            CheckoutRequest("sale", listOf(CheckoutLine(variantId, 2_000L, 7_500L)), buyerName = "Ramesh",
                buyerPhone = "9876543210", paymentMode = mode, amountPaidPaise = paid)
        )
    }

    @Test
    fun partialReturn_createsCreditNoteRestoresStockAndUpdatesView() = runBlocking<Unit> {
        val sale = sellTwo()
        val line = sale.lines.single()
        val note = sales.returnItems("r1", sale.header.id, listOf(ReturnLine(line.id, 1_000L)))
        assertEquals(DocType.CREDIT_NOTE, note.header.docType)
        assertEquals(sale.header.id, note.header.originalInvoiceId)
        assertEquals(-7_500L, note.header.grandTotalPaise)
        assertEquals(-1_000L, note.lines.single().qtyMilliUnits)
        assertEquals(9_000L, db.inventoryDao().getVariantById(line.skuId)!!.stockMilliUnits)

        val row = db.saleDao().getAllRawSales().single()
        assertEquals(1_000L, row.returnedMilliUnits)
        assertFalse(row.isReturned)

        sales.returnItems("r2", sale.header.id, listOf(ReturnLine(line.id, 1_000L)))
        assertTrue(db.saleDao().getAllRawSales().single().isReturned)
    }

    @Test
    fun overReturn_throwsAndWritesNothing() = runBlocking<Unit> {
        val sale = sellTwo()
        expectFailure<CommandException.ReturnExceedsSold> {
            sales.returnItems("r1", sale.header.id, listOf(ReturnLine(sale.lines.single().id, 3_000L)))
        }
        assertEquals(1L, db.scalar("SELECT COUNT(*) FROM invoice_headers"))
        assertEquals(8_000L, db.scalar("SELECT stockMilliUnits FROM item_variants"))
    }

    @Test
    fun secondReturnBeyondRemaining_isRejected() = runBlocking<Unit> {
        val sale = sellTwo()
        val lineId = sale.lines.single().id
        sales.returnItems("r1", sale.header.id, listOf(ReturnLine(lineId, 2_000L)))
        expectFailure<CommandException.ReturnExceedsSold> {
            sales.returnItems("r2", sale.header.id, listOf(ReturnLine(lineId, 1_000L)))
        }
        assertEquals(10_000L, db.scalar("SELECT stockMilliUnits FROM item_variants"))
    }

    @Test
    fun returnOfCreditSale_reducesKhataBeforeRefundingCash() = runBlocking<Unit> {
        val sale = sellTwo(mode = "CASH", paid = 5_000L) // Rs 150 bill, Rs 50 paid, Rs 100 on khata
        val note = sales.returnItems("r1", sale.header.id, listOf(ReturnLine(sale.lines.single().id, 2_000L)))
        assertEquals(-10_000L, note.header.balanceDuePaise)
        assertEquals(-5_000L, note.header.amountPaidPaise)
        assertEquals(0L, db.shopPartyAndLedgerDao().getAllRawParties().single().currentBalancePaise)
        val credit = db.shopPartyAndLedgerDao().getAllLedgerEntries().single { it.entryType == LedgerEntryType.CREDIT_NOTE }
        assertEquals(-10_000L, credit.amountDeltaPaise)
    }

    @Test
    fun duplicateLineInOneRequest_isRejected() = runBlocking<Unit> {
        val sale = sellTwo()
        val lineId = sale.lines.single().id
        expectFailure<CommandException.Validation> {
            sales.returnItems("r1", sale.header.id, listOf(ReturnLine(lineId, 1_000L), ReturnLine(lineId, 1_000L)))
        }
    }

    @Test
    fun partialReturn_netColumnsReflectCreditNoteLines() = runBlocking<Unit> {
        val sale = sellTwo()
        sales.returnItems("r1", sale.header.id, listOf(ReturnLine(sale.lines.single().id, 1_000L)))
        val row = db.saleDao().getAllRawSales().single()
        assertEquals(7_500L, row.netRevenuePaise)
        assertEquals(4_800L, row.netCostPaise)
        assertEquals(2_700L, row.netProfitPaise)
    }

    @Test
    fun returnItems_isIdempotentOnActionId() = runBlocking<Unit> {
        val sale = sellTwo()
        val args = listOf(ReturnLine(sale.lines.single().id, 1_000L))
        val first = sales.returnItems("r1", sale.header.id, args)
        val second = sales.returnItems("r1", sale.header.id, args)
        assertFalse(first.replayed)
        assertTrue(second.replayed)
        assertEquals(1L, db.scalar("SELECT COUNT(*) FROM invoice_headers WHERE docType = 'CREDIT_NOTE'"))
        assertEquals(9_000L, db.scalar("SELECT stockMilliUnits FROM item_variants"))
    }

    @Test
    fun twoPartialReturns_reduceKhataBeforeRefundingCash() = runBlocking<Unit> {
        val sale = sellTwo(mode = "CASH", paid = 5_000L) // 15_000 bill, 5_000 paid, 10_000 due
        val lineId = sale.lines.single().id
        val n1 = sales.returnItems("r1", sale.header.id, listOf(ReturnLine(lineId, 1_000L)))
        assertEquals(-7_500L, n1.header.balanceDuePaise)
        assertEquals(0L, n1.header.amountPaidPaise)
        assertEquals(2_500L, db.shopPartyAndLedgerDao().getAllRawParties().single().currentBalancePaise)
        val n2 = sales.returnItems("r2", sale.header.id, listOf(ReturnLine(lineId, 1_000L)))
        assertEquals(-2_500L, n2.header.balanceDuePaise)
        assertEquals(-5_000L, n2.header.amountPaidPaise)
        assertEquals(0L, db.shopPartyAndLedgerDao().getAllRawParties().single().currentBalancePaise)
    }
}
