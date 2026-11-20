package com.shopledger.pos.core.scanner

import com.shopledger.pos.core.nlp.ShopVertical
import com.shopledger.pos.data.local.entity.InventoryItemEntity
import com.shopledger.pos.data.local.entity.ItemVariantEntity
import com.shopledger.pos.data.model.InventoryItemWithVariants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StockLineMatcherTest {

    private fun item(id: String, name: String, cost: Long, sell: Long) = InventoryItemWithVariants(
        item = InventoryItemEntity(id = id, displayName = name, sortKey = name.uppercase()),
        allVariants = listOf(
            ItemVariantEntity(
                id = "v-$id", itemId = id, unit = "pcs",
                purchasePricePaise = cost, sellingPricePaise = sell, stockMilliUnits = 10_000L
            )
        )
    )

    private val inventory = listOf(
        item("1", "Butt Hinge 4\" SS", 4800, 6000),
        item("2", "Fevicol SH 1kg", 20000, 25000),
        item("3", "Drywall Screw 2\"", 100, 125)
    )

    private fun line(name: String, qty: Long = 1000, rate: Long? = null, consistent: Boolean = true) =
        ParsedStockLine(name, qty, null, rate, null, consistent, name)

    private fun match(vararg lines: ParsedStockLine) =
        StockLineMatcher.match(lines.toList(), inventory, ShopVertical.HARDWARE)

    @Test fun exactNameIsMatched() {
        val m = match(line("Fevicol SH 1kg", rate = 21000)).single()
        assertEquals(MatchStatus.MATCHED, m.status)
        assertEquals("2", m.itemId)
        assertEquals("v-2", m.variantId)
        assertEquals(20000L, m.currentCostPaise)
        assertEquals(25000L, m.currentSellPaise)
    }

    @Test fun weakNameIsCheckWithBestGuess() {
        val m = match(line("Scrw blk 2 extra")).single()
        assertEquals(MatchStatus.CHECK, m.status)
        assertEquals("3", m.itemId)
    }

    @Test fun unknownNameIsNewWithSuggestedSell() {
        val m = match(line("Door Handle Brass", rate = 8000)).single()
        assertEquals(MatchStatus.NEW, m.status)
        assertNull(m.itemId)
        assertNull(m.variantId)
        assertEquals(10000L, m.suggestedSellPaise)
    }

    @Test fun suggestedSellRoundsToWholeRupees() {
        // 25% markup median; 4750 * 1.25 = 5937.5 -> 5900 paise (Rs 59)
        val m = match(line("Door Handle Brass", rate = 4750)).single()
        assertEquals(5900L, m.suggestedSellPaise)
    }

    @Test fun medianMarkupOfEmptyInventoryIsDefault() {
        assertEquals(StockLineMatcher.DEFAULT_MARKUP, StockLineMatcher.medianMarkup(emptyList()), 1e-9)
        assertEquals(0.25, StockLineMatcher.medianMarkup(inventory), 1e-9)
    }

    @Test fun duplicateLinesMergeWithSameRate() {
        val r = match(line("Fevicol SH 1kg", 2000, 21000), line("Fevicol SH 1kg", 3000, 21000))
        assertEquals(1, r.size)
        assertEquals(5000L, r[0].line.qtyMilli)
        assertEquals(21000L, r[0].line.ratePaise)
        assertEquals(MatchStatus.MATCHED, r[0].status)
    }

    @Test fun duplicateLinesWithDifferentRatesBecomeCheck() {
        val r = match(line("Fevicol SH 1kg", 2000, 21000), line("Fevicol SH 1kg", 3000, 22000))
        assertEquals(1, r.size)
        assertEquals(MatchStatus.CHECK, r[0].status)
        assertEquals(22000L, r[0].line.ratePaise)
        assertEquals(5000L, r[0].line.qtyMilli)
    }

    @Test fun inconsistentLineIsNeverMatched() {
        val m = match(line("Fevicol SH 1kg", rate = 21000, consistent = false)).single()
        assertEquals(MatchStatus.CHECK, m.status)
        assertEquals("2", m.itemId)
    }
}
