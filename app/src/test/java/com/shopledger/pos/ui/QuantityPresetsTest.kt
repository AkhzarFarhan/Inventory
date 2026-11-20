package com.shopledger.pos.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shopledger.pos.R
import com.shopledger.pos.ui.screens.items.formatLabel
import com.shopledger.pos.ui.screens.items.quantityPresetsForUnit
import com.shopledger.pos.ui.theme.ShopLedgerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuantityPresetsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun kgReturnsExpectedMassPresets() {
        val presets = quantityPresetsForUnit("KG")
        assertEquals(listOf(0.25, 0.5, 1.0, 2.0, 5.0), presets.map { it.deltaQty })
        assertEquals(R.string.preset_qty_250g, presets[0].labelRes)
        assertEquals(R.string.preset_qty_500g, presets[1].labelRes)
        compose.setContent {
            ShopLedgerTheme {
                Row { presets.forEach { Text(it.formatLabel()) } }
            }
        }
        listOf("+250 g", "+500 g", "+1 kg", "+2 kg", "+5 kg").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
    }

    @Test
    fun ltrReturnsExpectedVolumePresets() {
        val presets = quantityPresetsForUnit("ltr")
        assertEquals(listOf(0.25, 0.5, 1.0, 5.0), presets.map { it.deltaQty })
        compose.setContent {
            ShopLedgerTheme {
                Row { presets.forEach { Text(it.formatLabel()) } }
            }
        }
        listOf("+250 ml", "+500 ml", "+1 ltr", "+5 ltr").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
    }

    @Test
    fun countUnitReturnsPlus5To50Presets() {
        val presets = quantityPresetsForUnit("pcs")
        assertEquals(listOf(5.0, 10.0, 12.0, 50.0), presets.map { it.deltaQty })
        compose.setContent {
            ShopLedgerTheme {
                Row { presets.forEach { Text(it.formatLabel()) } }
            }
        }
        listOf("+5", "+10", "+12", "+50").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
    }
}
