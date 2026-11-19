package com.shopledger.pos.core.ledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.TimeZone

class LedgerPrimitivesTest {

    @Test
    fun lineTotal_roundsHalfUpOnMilliUnits() {
        assertEquals(15_000L, InvoiceMath.lineTotalPaise(7_500L, 2_000L))
        assertEquals(3_750L, InvoiceMath.lineTotalPaise(2_500L, 1_500L))
        assertEquals(1L, InvoiceMath.lineTotalPaise(1L, 500L))
        assertEquals(0L, InvoiceMath.lineTotalPaise(1L, 499L))
        assertEquals(-3_750L, InvoiceMath.lineTotalPaise(2_500L, -1_500L))
        assertEquals(0L, InvoiceMath.lineTotalPaise(7_500L, 0L))
    }

    @Test
    fun lineTotal_rejectsNegativeUnitPrice() {
        assertThrows(IllegalArgumentException::class.java) { InvoiceMath.lineTotalPaise(-1L, 1_000L) }
    }

    @Test
    fun receiptNumber_hasDeviceDayAndPaddedSequence() {
        assertEquals("A-261119-0007", ReceiptNumbers.format("a", "261119", 7))
        assertEquals("D1-261119-12345", ReceiptNumbers.format(" d1 ", "261119", 12_345))
    }

    @Test
    fun receiptNumber_rejectsNonPositiveSequence() {
        assertThrows(IllegalArgumentException::class.java) { ReceiptNumbers.format("A", "261119", 0) }
    }

    @Test
    fun calendarParts_followTheGivenTimeZone() {
        // 2026-11-18T19:00:00Z is 2026-11-19 00:30 in India.
        val instant = 1_795_028_400_000L
        val ist = CalendarParts.of(instant, TimeZone.getTimeZone("Asia/Kolkata"))
        assertEquals(CalendarParts(2026, 11, 19), ist)
        assertEquals("261119", ist.dayKey)
        assertEquals(CalendarParts(2026, 11, 18), CalendarParts.of(instant, TimeZone.getTimeZone("UTC")))
    }
}
