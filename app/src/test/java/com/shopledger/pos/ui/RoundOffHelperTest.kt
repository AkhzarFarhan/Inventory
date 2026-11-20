package com.shopledger.pos.ui

import com.shopledger.pos.ui.screens.checkout.RoundOffOption
import com.shopledger.pos.ui.screens.checkout.roundOffSuggestions
import org.junit.Assert.assertEquals
import org.junit.Test

class RoundOffHelperTest {
    @Test
    fun exactHundredSubtotalReturnsEmpty() {
        assertEquals(emptyList<RoundOffOption>(), roundOffSuggestions(10_000L, 0L))
    }

    @Test
    fun smallSubtotalAtOrBelowTenRupeesReturnsEmpty() {
        assertEquals(emptyList<RoundOffOption>(), roundOffSuggestions(1_000L, 0L))
        assertEquals(emptyList<RoundOffOption>(), roundOffSuggestions(800L, 0L))
    }

    @Test
    fun oddSubtotalReturnsNearestTenAndFiftyRoundOffs() {
        assertEquals(
            listOf(
                RoundOffOption(targetTotalPaise = 41_000L, discountPaise = 300L),
                RoundOffOption(targetTotalPaise = 40_000L, discountPaise = 1_300L),
            ),
            roundOffSuggestions(41_300L, 0L),
        )
    }

    @Test
    fun fiveTwentyReturnsOnlyFiveHundredDueToDeduplication() {
        assertEquals(
            listOf(RoundOffOption(targetTotalPaise = 50_000L, discountPaise = 2_000L)),
            roundOffSuggestions(52_000L, 0L),
        )
    }

    @Test
    fun alreadyAppliedDiscountIsFilteredOut() {
        assertEquals(
            listOf(RoundOffOption(targetTotalPaise = 40_000L, discountPaise = 1_300L)),
            roundOffSuggestions(41_300L, 300L),
        )
    }
}
