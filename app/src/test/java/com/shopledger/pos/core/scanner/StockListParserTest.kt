package com.shopledger.pos.core.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StockListParserTest {

    private fun single(text: String): ParsedStockLine {
        val lines = StockListParser.parse(text)
        assertEquals("expected exactly one line for: $text -> $lines", 1, lines.size)
        return lines[0]
    }

    private fun check(
        text: String,
        name: String,
        qty: Long,
        unit: String?,
        rate: Long?,
        amount: Long?,
        consistent: Boolean = true
    ) {
        val l = single(text)
        assertEquals(name, l.name)
        assertEquals(qty, l.qtyMilli)
        assertEquals(unit, l.unit)
        assertEquals(rate, l.ratePaise)
        assertEquals(amount, l.amountPaise)
        assertEquals(consistent, l.consistent)
    }

    @Test fun qtyTimesRateEqualsAmount() =
        check("Door Handle 6\" 10 x 120 = 1200", "Door Handle 6\"", 10000, null, 12000, 120000)

    @Test fun attachedSizeStaysInName() =
        check("Fevicol SH 1kg 5 pkt 235 1175", "Fevicol SH 1kg", 5000, "pkt", 23500, 117500)

    @Test fun pipeFormat() =
        check("Hinge 4in SS | 12 | pcs | 48 | 576", "Hinge 4in SS", 12000, "pcs", 4800, 57600)

    @Test fun leadingQtyUnitWithRate() =
        check("5 pkt Fevicol @235", "Fevicol", 5000, "pkt", 23500, null)

    @Test fun trailingQtyOnly() = check("Hinge 12", "Hinge", 12000, null, null, null)

    @Test fun leadingQtyOnly() = check("12 hinge", "hinge", 12000, null, null, null)

    @Test fun serialPrefixAndDashQty() = check("3. Cement - 5 bags", "Cement", 5000, "bag", null, null)

    @Test fun inconsistentAmountFlagged() =
        check("Tower Bolt 8\" 10 125 1300", "Tower Bolt 8\"", 10000, null, 12500, 130000, consistent = false)

    @Test fun decimalQtyWithSpacedUnit() =
        check("Screw 2.5 kg 180 450", "Screw", 2500, "kg", 18000, 45000)

    @Test fun spacedSizeBeforeQtyUnit() =
        check("Fevicol SH 1 kg 5 pkt 235 1175", "Fevicol SH 1 kg", 5000, "pkt", 23500, 117500)

    @Test fun slashDashAmounts() {
        check("Hinge 12 350/-", "Hinge", 12000, null, 35000, null)
        check("Hinge 12 350/=", "Hinge", 12000, null, 35000, null)
    }

    @Test fun rupeeAfterNumberIsAmount() =
        check("Hinge 12 Rs 576", "Hinge", 12000, null, 4800, 57600)

    @Test fun leadingQtyWord() = check("Qty 5 Hinge", "Hinge", 5000, null, null, null)

    @Test fun unitOnlyNameIsDropped() = assertTrue(StockListParser.parse("12 nos").isEmpty())

    @Test fun crossWithoutSpaces() = check("Hinge 2x3450", "Hinge", 2000, null, 345000, null)

    @Test fun leadingTimesForm() = check("10 x Hinge", "Hinge", 10000, null, null, null)

    @Test fun totalWithTaxWordIsIgnoredForBillTotal() {
        val text = "Hinge | 1 | pcs | 10 | 10\nTotal GST 18\nTotal 10"
        assertEquals(1000L, StockListParser.detectBillTotalPaise(text))
        assertEquals(1000L, StockListParser.detectBillTotalPaise("Hinge | 1 | pcs | 10 | 10\nTotal tax 1\nTotal 10"))
    }

    @Test fun noiseLinesAreSkipped() {
        listOf(
            "GSTIN: 09ABCDE1234F1Z5", "Grand Total 4860", "CGST 9% 120",
            "Ph: 9876543210", "Date 19/11/2026", "Thank you"
        ).forEach { assertTrue("should be noise: $it", StockListParser.parse(it).isEmpty()) }
    }

    @Test fun billWithTotalGstAndPhone() {
        val text = """
            Hinge 4in SS | 12 | pcs | 48 | 576
            Fevicol SH 1kg 5 pkt 235 1175
            Door Handle 6" 10 x 120 = 1200
            GST 18% 531
            Grand Total 3482
            Ph: 9876543210
        """.trimIndent()
        assertEquals(3, StockListParser.parse(text).size)
        assertEquals(348200L, StockListParser.detectBillTotalPaise(text))
    }

    @Test fun billTotalFallsBackToSumOfAmounts() {
        val text = "A | 1 | pcs | 10 | 10\nB | 2 | pcs | 5 | 10"
        assertEquals(2000L, StockListParser.detectBillTotalPaise(text))
        assertNull(StockListParser.detectBillTotalPaise("Hinge 12"))
    }
}
