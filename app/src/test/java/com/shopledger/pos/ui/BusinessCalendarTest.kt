package com.shopledger.pos.ui

import com.shopledger.pos.core.ledger.CalendarParts
import com.shopledger.pos.data.command.DocType
import com.shopledger.pos.data.command.LedgerEntryType
import com.shopledger.pos.data.local.entity.InvoiceHeaderEntity
import com.shopledger.pos.data.local.entity.PartyTransactionEntity
import com.shopledger.pos.data.local.entity.PurchaseBillEntity
import com.shopledger.pos.ui.screens.sales.calendar.computeMonthSummary
import com.shopledger.pos.ui.screens.sales.calendar.formatCompactRupees
import com.shopledger.pos.ui.screens.sales.calendar.monthGridCells
import com.shopledger.pos.ui.screens.sales.computeGallaBreakdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class BusinessCalendarTest {

    private fun inv(
        id: String,
        day: Int,
        month: Int = 11,
        year: Int = 2026,
        docType: String = DocType.SALE,
        mode: String = "CASH",
        grand: Long,
        paid: Long = grand,
        due: Long = grand - paid,
    ) = InvoiceHeaderEntity(
        id = id,
        invoiceNumber = "INV-$id",
        docType = docType,
        paymentMode = mode,
        subtotalPaise = grand,
        grandTotalPaise = grand,
        amountPaidPaise = paid,
        balanceDuePaise = due,
        issuedAt = epochOf(year, month, day),
        yearInt = year,
        monthInt = month,
        dayInt = day,
    )

    private fun ledger(
        id: String,
        entryType: String,
        delta: Long,
        mode: String = "CASH",
        createdAt: Long,
    ) = PartyTransactionEntity(
        entryId = id,
        partyId = "p1",
        entryType = entryType,
        amountDeltaPaise = delta,
        paymentMode = mode,
        createdAt = createdAt,
    )

    private fun purchase(
        id: String,
        day: Int,
        month: Int = 11,
        year: Int = 2026,
        supplierId: String?,
        total: Long,
        paid: Long,
        mode: String = "CASH",
    ) = PurchaseBillEntity(
        id = id,
        supplierPartyId = supplierId,
        supplierNameSnapshot = supplierId?.let { "Supplier $it" }.orEmpty(),
        totalPaise = total,
        linesTotalPaise = total,
        paidPaise = paid,
        duePaise = total - paid,
        paymentMode = mode,
        lineCount = 1,
        createdAt = epochOf(year, month, day),
        yearInt = year,
        monthInt = month,
        dayInt = day,
    )

    private fun epochOf(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, 12, 0, 0)
        }.timeInMillis

    @Test
    fun cashUpiCreditSalesSplitLikeGalla() {
        val invoices = listOf(
            inv("1", day = 5, mode = "CASH", grand = 1_000L),
            inv("2", day = 5, mode = "UPI", grand = 2_000L),
            inv("3", day = 5, mode = "CREDIT", grand = 3_000L, paid = 500L, due = 2_500L),
        )
        val summary = computeMonthSummary(2026, 11, invoices, emptyList(), emptyList())
        val d5 = summary.days[5]!!
        assertEquals(3, d5.saleCount)
        assertEquals(6_000L, d5.salesPaise)
        assertEquals(1_500L, d5.cashSalesPaise)
        assertEquals(2_000L, d5.upiSalesPaise)
        assertEquals(2_500L, d5.udhaarGivenPaise)
    }

    @Test
    fun creditNoteReducesSales() {
        val invoices = listOf(
            inv("1", day = 7, mode = "CASH", grand = 5_000L),
            inv("2", day = 7, docType = DocType.CREDIT_NOTE, mode = "CASH", grand = -2_000L, paid = -2_000L, due = 0L),
        )
        val summary = computeMonthSummary(2026, 11, invoices, emptyList(), emptyList())
        val d7 = summary.days[7]!!
        assertEquals(3_000L, d7.salesPaise)
        assertEquals(3_000L, d7.cashSalesPaise)
    }

    @Test
    fun jamaAndPaymentsToDistributors() {
        val entries = listOf(
            ledger("e1", LedgerEntryType.PAYMENT_IN, -700L, "UPI", createdAt = 1L),
            ledger("e2", LedgerEntryType.PAYMENT_OUT, 4_000L, "CASH", createdAt = 2L),
        )
        val summary = computeMonthSummary(
            2026, 11, emptyList(), entries, emptyList(),
            dayOf = { CalendarParts(2026, 11, 9) },
        )
        val d9 = summary.days[9]!!
        assertEquals(700L, d9.udhaarCollectedUpiPaise)
        assertEquals(0L, d9.udhaarCollectedCashPaise)
        assertEquals(4_000L, d9.paidToDistributorsCashPaise)
        assertEquals(0L, d9.paidToDistributorsUpiPaise)
    }

    @Test
    fun stockInWithAndWithoutDistributor() {
        val bills = listOf(
            purchase("bA", day = 12, supplierId = "s1", total = 10_000L, paid = 4_000L, mode = "UPI"),
            purchase("bB", day = 12, supplierId = null, total = 2_000L, paid = 2_000L, mode = "CASH"),
        )
        val entries = listOf(
            ledger("e1", LedgerEntryType.PAYMENT_OUT, 4_000L, "UPI", createdAt = 100L),
        )
        val summary = computeMonthSummary(
            2026, 11, emptyList(), entries, bills,
            dayOf = { CalendarParts(2026, 11, 12) },
        )
        val d12 = summary.days[12]!!
        assertEquals(12_000L, d12.stockInPaise)
        assertEquals(2, d12.stockInBillCount)
        assertEquals(6_000L, d12.stockInOnUdhaarPaise)
        assertEquals(4_000L, d12.paidToDistributorsUpiPaise)
        assertEquals(2_000L, d12.paidToDistributorsCashPaise)
    }

    @Test
    fun otherMonthsIgnored() {
        val invoices = listOf(
            inv("1", day = 5, month = 10, year = 2026, grand = 5_000L),
            inv("2", day = 5, month = 11, year = 2025, grand = 7_000L),
        )
        val bills = listOf(
            purchase("b1", day = 5, month = 10, year = 2026, supplierId = null, total = 3_000L, paid = 3_000L),
        )
        val entries = listOf(
            ledger("e1", LedgerEntryType.PAYMENT_IN, -1_000L, "CASH", createdAt = 1L),
        )
        val summary = computeMonthSummary(
            2026, 11, invoices, entries, bills,
            dayOf = { CalendarParts(2026, 10, 5) },
        )
        assertEquals(emptyMap<Int, Any>(), summary.days)
        assertEquals(0L, summary.total.salesPaise)
        assertEquals(0L, summary.total.stockInPaise)
    }

    @Test
    fun monthTotalIsSumOfDays() {
        val invoices = listOf(
            inv("1", day = 2, mode = "CASH", grand = 4_000L),
            inv("2", day = 15, mode = "UPI", grand = 6_000L),
            inv("3", day = 20, mode = "CREDIT", grand = 5_000L, paid = 1_000L, due = 4_000L),
        )
        val bills = listOf(
            purchase("b1", day = 2, supplierId = null, total = 3_000L, paid = 3_000L, mode = "CASH"),
            purchase("b2", day = 20, supplierId = "s1", total = 8_000L, paid = 0L, mode = "CREDIT"),
        )
        val summary = computeMonthSummary(2026, 11, invoices, emptyList(), bills)
        assertEquals(3, summary.total.saleCount)
        assertEquals(15_000L, summary.total.salesPaise)
        assertEquals(5_000L, summary.total.cashSalesPaise)
        assertEquals(6_000L, summary.total.upiSalesPaise)
        assertEquals(4_000L, summary.total.udhaarGivenPaise)
        assertEquals(11_000L, summary.total.stockInPaise)
        assertEquals(2, summary.total.stockInBillCount)
        assertEquals(8_000L, summary.total.stockInOnUdhaarPaise)
        assertEquals(3_000L, summary.total.paidToDistributorsCashPaise)
    }

    @Test
    fun dayMatchesGallaForThatDay() {
        val day14Ts = epochOf(2026, 11, 14)
        val invoices = listOf(
            inv("1", day = 14, mode = "CASH", grand = 9_500L),
            inv("2", day = 14, mode = "UPI", grand = 4_200L),
            inv("3", day = 14, mode = "CREDIT", grand = 6_000L, paid = 1_500L, due = 4_500L),
            inv("4", day = 14, docType = DocType.CREDIT_NOTE, mode = "CASH", grand = -1_000L, paid = -1_000L, due = 0L),
            inv("5", day = 15, mode = "CASH", grand = 99_000L),
        )
        val entries = listOf(
            ledger("e1", LedgerEntryType.PAYMENT_IN, -2_000L, "CASH", createdAt = day14Ts),
            ledger("e2", LedgerEntryType.PAYMENT_IN, -1_250L, "UPI", createdAt = day14Ts),
            ledger("e3", LedgerEntryType.PAYMENT_IN, -5_000L, "CASH", createdAt = epochOf(2026, 11, 15)),
        )
        val galla = computeGallaBreakdown(invoices, emptyList(), entries, 2026, 11, 14, periodIndex = 0)
        val daySummary = computeMonthSummary(2026, 11, invoices, entries, emptyList()).days[14]!!
        assertEquals(galla.cashSalesPaise, daySummary.cashSalesPaise)
        assertEquals(galla.upiSalesPaise, daySummary.upiSalesPaise)
        assertEquals(galla.udharGivenPaise, daySummary.udhaarGivenPaise)
        assertEquals(galla.jamaCashPaise, daySummary.udhaarCollectedCashPaise)
        assertEquals(galla.jamaUpiPaise, daySummary.udhaarCollectedUpiPaise)
    }

    @Test
    fun compactFormat() {
        assertEquals("₹950", formatCompactRupees(95_000L))
        assertEquals("₹12.4k", formatCompactRupees(1_240_000L))
        assertEquals("₹12k", formatCompactRupees(1_200_000L))
        assertEquals("₹1.2L", formatCompactRupees(12_000_000L))
        assertEquals("₹3.1Cr", formatCompactRupees(3_100_000_000L))
        assertEquals("-₹500", formatCompactRupees(-50_000L))
    }

    @Test
    fun gridStartsOnSunday() {
        val nov2026 = monthGridCells(2026, 11)
        assertEquals(1, nov2026.first())
        assertEquals(0, nov2026.size % 7)
        assertEquals(30, nov2026.filterNotNull().size)

        val dec2026 = monthGridCells(2026, 12)
        assertNull(dec2026[0])
        assertNull(dec2026[1])
        assertEquals(1, dec2026[2])
        assertEquals(0, dec2026.size % 7)
        assertEquals(31, dec2026.filterNotNull().size)
    }
}
