package com.shopledger.pos.data.command

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shopledger.pos.core.ledger.CalendarParts
import com.shopledger.pos.core.ledger.ReceiptNumbers
import com.shopledger.pos.data.local.LpsDatabase
import com.shopledger.pos.testing.TestEnv
import com.shopledger.pos.testing.expectFailure
import com.shopledger.pos.testing.newTestDatabase
import com.shopledger.pos.testing.scalar
import com.shopledger.pos.testing.seedHinge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SalesCommandsTest {
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

    private fun dayKey() = CalendarParts.of(env.clock).dayKey
    private fun twoHinges(variantId: String, actionId: String = "c1", mode: String = "CASH", paid: Long? = null,
                          buyer: String = "", phone: String = "") =
        CheckoutRequest(actionId, listOf(CheckoutLine(variantId, 2_000L, 7_500L)), buyerName = buyer, buyerPhone = phone,
            paymentMode = mode, amountPaidPaise = paid)

    @Test
    fun cashCheckout_writesInvoiceMovementAndOneOutboxRow() = runBlocking<Unit> {
        val variantId = seedHinge(db, env)
        val result = sales.checkout(twoHinges(variantId))
        assertEquals("L-${dayKey()}-0001", result.header.invoiceNumber)
        assertEquals(15_000L, result.header.grandTotalPaise)
        assertEquals(15_000L, result.header.amountPaidPaise)
        assertEquals(0L, result.header.balanceDuePaise)
        assertEquals(5_400L, result.lines.single().lineProfitPaise)
        assertEquals(8_000L, db.inventoryDao().getVariantById(variantId)!!.stockMilliUnits)
        assertEquals(1L, db.scalar("SELECT COUNT(*) FROM sync_outbox WHERE docType = 'INVOICE'"))
        // The sale's movement travels inside the INVOICE document, not as its own outbox row.
        assertEquals(1L, db.scalar("SELECT COUNT(*) FROM sync_outbox WHERE docType = 'MOVEMENT'"))
        assertEquals(0L, db.scalar("SELECT COUNT(*) FROM parties"))
    }

    @Test
    fun creditCheckout_createsPartyAndCreditBill() = runBlocking<Unit> {
        val variantId = seedHinge(db, env)
        val result = sales.checkout(twoHinges(variantId, mode = "CREDIT", buyer = "Ramesh", phone = "98765 43210"))
        val party = db.shopPartyAndLedgerDao().getAllRawParties().single()
        assertEquals(15_000L, party.currentBalancePaise)
        assertEquals(party.id, result.header.partyId)
        val entry = db.shopPartyAndLedgerDao().getAllLedgerEntries().single()
        assertEquals(LedgerEntryType.CREDIT_BILL, entry.entryType)
        assertEquals(result.header.id, entry.refDocId)
    }

    @Test
    fun partialPayment_putsOnlyTheRemainderOnKhata() = runBlocking<Unit> {
        val variantId = seedHinge(db, env)
        val result = sales.checkout(twoHinges(variantId, mode = "CASH", paid = 5_000L, buyer = "Ramesh", phone = "9876543210"))
        assertEquals(10_000L, result.header.balanceDuePaise)
        assertEquals(10_000L, db.shopPartyAndLedgerDao().getAllRawParties().single().currentBalancePaise)
    }

    @Test
    fun existingParty_isMatchedByPhoneDigits() = runBlocking<Unit> {
        KhataCommands(db, env).createParty("p1", "Ramesh", "9876543210")
        val variantId = seedHinge(db, env)
        sales.checkout(twoHinges(variantId, mode = "CREDIT", phone = "+91 98765 43210"))
        assertEquals(1L, db.scalar("SELECT COUNT(*) FROM parties"))
        assertEquals(15_000L, db.shopPartyAndLedgerDao().getAllRawParties().single().currentBalancePaise)
    }

    @Test
    fun sameActionIdTwice_returnsTheSameInvoiceAndWritesOnce() = runBlocking<Unit> {
        val variantId = seedHinge(db, env)
        val first = sales.checkout(twoHinges(variantId))
        val second = sales.checkout(twoHinges(variantId))
        assertFalse(first.replayed)
        assertTrue(second.replayed)
        assertEquals(first.header.id, second.header.id)
        assertEquals(1L, db.scalar("SELECT COUNT(*) FROM invoice_headers"))
        assertEquals(8_000L, db.inventoryDao().getVariantById(variantId)!!.stockMilliUnits)
    }

    @Test
    fun failureAtAnyStep_rollsBackEverything() = runBlocking<Unit> {
        for (step in CheckoutStep.ALL) {
            val db = newTestDatabase()
            val env = TestEnv()
            val variantId = seedHinge(db, env)
            val failing = SalesCommands(db, env, FaultInjector { if (it == step) throw IllegalStateException("boom at $it") })
            val before = snapshot(db)
            val error = expectFailure<IllegalStateException> {
                failing.checkout(CheckoutRequest("act-$step", listOf(CheckoutLine(variantId, 2_000L, 7_500L)),
                    buyerName = "Ramesh", buyerPhone = "9876543210", paymentMode = "CREDIT"))
            }
            assertEquals("boom at $step", error.message)
            assertEquals("state changed after failure at $step", before, snapshot(db))
            db.close()
        }
    }

    private fun snapshot(db: LpsDatabase): List<Long> = listOf(
        "SELECT COUNT(*) FROM invoice_headers",
        "SELECT COUNT(*) FROM invoice_lines",
        "SELECT COUNT(*) FROM stock_movements",
        "SELECT COUNT(*) FROM party_ledger_entries",
        "SELECT COUNT(*) FROM parties",
        "SELECT COUNT(*) FROM sync_outbox",
        "SELECT COUNT(*) FROM applied_actions",
        "SELECT lastInvoiceSeq FROM devices WHERE isLocal = 1",
        "SELECT COALESCE(SUM(stockMilliUnits), 0) FROM item_variants",
        "SELECT COALESCE(SUM(currentBalancePaise), 0) FROM parties"
    ).map { db.scalar(it) }

    @Test
    fun overselling_isAllowedAndReported() = runBlocking<Unit> {
        val variantId = seedHinge(db, env, openingMilli = 1_000L)
        val result = sales.checkout(CheckoutRequest("c1", listOf(CheckoutLine(variantId, 3_000L, 7_500L))))
        assertEquals(-2_000L, db.inventoryDao().getVariantById(variantId)!!.stockMilliUnits)
        assertEquals(listOf(variantId), result.oversoldVariantIds)
    }

    @Test
    fun staffDiscountAboveLimit_needsOwnerPin() = runBlocking<Unit> {
        val variantId = seedHinge(db, env)
        env.role = Role.STAFF
        val request = twoHinges(variantId).copy(discountPaise = 2_000L) // 13.3% > default 10%
        expectFailure<CommandException.PermissionDenied> { sales.checkout(request) }
        val result = sales.checkout(request.copy(ownerPinVerified = true))
        assertEquals(13_000L, result.header.grandTotalPaise)
    }

    @Test
    fun gstEnabled_splitsTaxIntoCgstAndSgst() = runBlocking<Unit> {
        env.gst = true
        val variantId = seedHinge(db, env)
        val result = sales.checkout(twoHinges(variantId).copy(gstRateBps = 1_800))
        assertEquals(1_350L, result.header.cgstPaise)
        assertEquals(1_350L, result.header.sgstPaise)
        assertEquals(17_700L, result.header.grandTotalPaise)
    }

    @Test
    fun concurrentCheckouts_getUniqueGaplessNumbers() = runBlocking<Unit> {
        val variantId = seedHinge(db, env)
        val numbers = (1..1000).map { i ->
            async(Dispatchers.IO) {
                sales.checkout(CheckoutRequest("c$i", listOf(CheckoutLine(variantId, 1_000L, 7_500L)))).header.invoiceNumber
            }
        }.awaitAll()
        val expected = (1L..1000L).map { ReceiptNumbers.format("L", dayKey(), it) }.toSet()
        assertEquals(expected, numbers.toSet())
        assertEquals(1000, numbers.size)
    }

    @Test
    fun newDay_restartsTheSequence() = runBlocking<Unit> {
        val variantId = seedHinge(db, env)
        sales.checkout(twoHinges(variantId, actionId = "d1"))
        env.clock += 24L * 60 * 60 * 1000
        val next = sales.checkout(twoHinges(variantId, actionId = "d2"))
        assertEquals("L-${dayKey()}-0001", next.header.invoiceNumber)
    }
}
