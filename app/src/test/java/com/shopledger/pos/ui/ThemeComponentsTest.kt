package com.shopledger.pos.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shopledger.pos.ui.theme.ItemRow
import com.shopledger.pos.ui.theme.MoneyField
import com.shopledger.pos.ui.theme.PrimaryActionButton
import com.shopledger.pos.ui.theme.QtyStepper
import com.shopledger.pos.ui.theme.SectionCard
import com.shopledger.pos.ui.theme.ShopLedgerTheme
import com.shopledger.pos.ui.theme.StockStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeComponentsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun primaryActionButtonIsTallClickableAndFires() {
        var clicks = 0
        compose.setContent { ShopLedgerTheme { PrimaryActionButton(text = "Sell", onClick = { clicks++ }) } }
        compose.onNodeWithText("Sell").assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        compose.onNode(hasClickAction()).performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun itemRowShowsAllTexts() {
        compose.setContent {
            ShopLedgerTheme {
                ItemRow(
                    title = "Hinge", subtitle = "SKU 1", trailingPrimary = "Rs 50",
                    trailingSecondary = "-2 oversold", status = StockStatus.OVERSOLD, onClick = {},
                )
            }
        }
        compose.onNodeWithText("Hinge").assertIsDisplayed()
        compose.onNodeWithText("SKU 1").assertIsDisplayed()
        compose.onNodeWithText("Rs 50").assertIsDisplayed()
        compose.onNodeWithText("-2 oversold").assertIsDisplayed()
    }

    @Test
    fun qtyStepperButtonsFireAndAreTouchSized() {
        var plus = 0
        var minus = 0
        compose.setContent {
            ShopLedgerTheme {
                QtyStepper(valueText = "3", onMinus = { minus++ }, onPlus = { plus++ }, onValueClick = {})
            }
        }
        compose.onNodeWithContentDescription("Increase quantity").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithContentDescription("Decrease quantity").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, plus)
        assertEquals(1, minus)
    }

    @Test
    fun moneyFieldEmitsPaiseAndNullWhenCleared() {
        val value = mutableStateOf<Long?>(null)
        compose.setContent {
            ShopLedgerTheme { MoneyField(valuePaise = value.value, onValueChange = { value.value = it }, label = "Price") }
        }
        compose.onNodeWithText("Price").performTextInput("12.50")
        compose.waitForIdle()
        assertEquals(1250L, value.value)
        compose.onNodeWithText("12.50").performTextClearance()
        compose.waitForIdle()
        assertEquals(null, value.value)
    }

    @Test
    fun moneyFieldAcceptsCommaAsDecimalSeparator() {
        val value = mutableStateOf<Long?>(null)
        compose.setContent {
            ShopLedgerTheme { MoneyField(valuePaise = value.value, onValueChange = { value.value = it }, label = "Price") }
        }
        compose.onNodeWithText("Price").performTextInput("12,5")
        compose.waitForIdle()
        assertEquals(1250L, value.value)
    }

    @Test
    fun moneyFieldIgnoresNonAsciiDigits() {
        val value = mutableStateOf<Long?>(null)
        compose.setContent {
            ShopLedgerTheme { MoneyField(valuePaise = value.value, onValueChange = { value.value = it }, label = "Price") }
        }
        compose.onNodeWithText("Price").performTextInput("1٣" + "2")
        compose.waitForIdle()
        assertEquals(1200L, value.value)
    }

    @Test
    fun moneyFieldZeroIsZeroPaise() {
        val value = mutableStateOf<Long?>(null)
        compose.setContent {
            ShopLedgerTheme { MoneyField(valuePaise = value.value, onValueChange = { value.value = it }, label = "Price") }
        }
        compose.onNodeWithText("Price").performTextInput("0")
        compose.waitForIdle()
        assertEquals(0L, value.value)
    }

    @Test
    fun sectionCardRendersTitle() {
        compose.setContent { ShopLedgerTheme { SectionCard("Title") { Text("x") } } }
        compose.onNodeWithText("Title").assertIsDisplayed()
        compose.onNodeWithText("x").assertIsDisplayed()
    }

    @Test
    fun darkThemeHasDifferentBackground() {
        var light = Color.Unspecified
        var dark = Color.Unspecified
        compose.setContent {
            ShopLedgerTheme(darkTheme = false) { light = MaterialTheme.colorScheme.background }
            ShopLedgerTheme(darkTheme = true) { dark = MaterialTheme.colorScheme.background }
        }
        compose.waitForIdle()
        assertNotEquals(light, dark)
        assertNotEquals(Color.Unspecified, light)
    }
}
