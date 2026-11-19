package com.shopledger.pos.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shopledger.pos.data.command.CheckoutLine
import com.shopledger.pos.data.command.CheckoutRequest
import com.shopledger.pos.data.command.ReturnLine
import com.shopledger.pos.data.command.SalesCommands
import com.shopledger.pos.testing.TestEnv
import com.shopledger.pos.testing.newTestDatabase
import com.shopledger.pos.testing.seedHinge
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Proves the checker flags each invariant when a row is tampered with (fresh healthy DB per test). */
@RunWith(AndroidJUnit4::class)
class IntegrityCheckerTest {
    private lateinit var db: LpsDatabase

    @Before
    fun setUp() = runBlocking<Unit> {
        db = newTestDatabase()
        val env = TestEnv()
        val variantId = seedHinge(db, env)
        val sales = SalesCommands(db, env)
        // Credit sale to a named buyer (creates a Khata party), then a partial return against it.
        val sale = sales.checkout(
            CheckoutRequest(
                actionId = "sale-1",
                lines = listOf(CheckoutLine(variantId, 2_000L, 7_500L)),
                buyerName = "Ravi",
                paymentMode = "CREDIT"
            )
        )
        sales.returnItems("ret-1", sale.header.id, listOf(ReturnLine(sale.lines.single().id, 500L)))
        assertEquals(emptyList<String>(), violations())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun violations() = IntegrityChecker.findViolations(db.openHelper.writableDatabase)

    private fun tamper(sql: String) = db.openHelper.writableDatabase.execSQL(sql)

    private fun assertFlagged(prefix: String) {
        val found = violations()
        assertTrue("expected a '$prefix' violation but got $found", found.any { it.startsWith(prefix) })
    }

    @Test
    fun i1_stockDifferingFromMovements_isFlagged() {
        tamper("UPDATE item_variants SET stockMilliUnits = stockMilliUnits + 1")
        assertFlagged("I1")
    }

    @Test
    fun i2_balanceDifferingFromLedger_isFlagged() {
        tamper("UPDATE parties SET currentBalancePaise = currentBalancePaise + 1")
        assertFlagged("I2")
    }

    @Test
    fun i3_invoiceTotalsInconsistent_isFlagged() {
        tamper("UPDATE invoice_headers SET subtotalPaise = subtotalPaise + 1 WHERE docType = 'SALE'")
        assertFlagged("I3")
    }

    @Test
    fun i4_returnedMoreThanSold_isFlagged() {
        tamper("UPDATE invoice_lines SET qtyMilliUnits = 1 WHERE originalLineId IS NULL")
        assertFlagged("I4 returned more than sold")
    }

    @Test
    fun i4_nonNegativeReturnLine_isFlagged() {
        tamper("UPDATE invoice_lines SET qtyMilliUnits = 500 WHERE originalLineId IS NOT NULL")
        assertFlagged("I4 return line not negative")
    }

    @Test
    fun i5_duplicateInvoiceNumber_isFlagged() {
        // The unique index normally makes a duplicate impossible, so drop it to simulate a corrupt/merged DB.
        tamper("DROP INDEX index_invoice_headers_shopId_invoiceNumber")
        val cols = "shopId, deviceId, deviceShortCode, invoiceNumber, docType, originalInvoiceId, partyId, buyerName, " +
            "buyerPhone, paymentMode, subtotalPaise, discountPaise, cgstPaise, sgstPaise, igstPaise, grandTotalPaise, " +
            "amountPaidPaise, balanceDuePaise, issuedAt, yearInt, monthInt, dayInt, createdByUid"
        tamper("INSERT INTO invoice_headers (id, $cols) SELECT 'dup-header', $cols FROM invoice_headers WHERE docType = 'SALE'")
        assertFlagged("I5")
    }
}
