package com.shopledger.pos.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shopledger.pos.ui.screens.cart.CartChip
import com.shopledger.pos.ui.screens.cart.CartSwitcher
import com.shopledger.pos.ui.theme.ShopLedgerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@Config(qualifiers = "w360dp-h800dp")
@RunWith(AndroidJUnit4::class)
class CartSwitcherTest {
    @get:Rule
    val compose = createComposeRule()

    private val threeCarts = listOf(
        CartChip("c1", "Cart 1", 2),
        CartChip("c2", "Ramesh", 0),
        CartChip("c3", "Cart 3", 1),
    )

    @Test
    fun showsEveryCartWithItsCount() {
        compose.setContent {
            ShopLedgerTheme {
                CartSwitcher(
                    carts = threeCarts,
                    activeCartId = "c1",
                    onSwitch = {},
                    onNewCart = {},
                )
            }
        }
        compose.onNodeWithText("Cart 1 · 2 items").assertIsDisplayed()
        compose.onNodeWithText("Ramesh · 0 items").assertIsDisplayed()
        compose.onNodeWithText("Cart 3 · 1 item").assertIsDisplayed()
    }

    @Test
    fun activeCartIsSelected() {
        compose.setContent {
            ShopLedgerTheme {
                CartSwitcher(
                    carts = threeCarts,
                    activeCartId = "c2",
                    onSwitch = {},
                    onNewCart = {},
                )
            }
        }
        compose.onNodeWithText("Cart 1 · 2 items").assertIsNotSelected()
        compose.onNodeWithText("Ramesh · 0 items").assertIsSelected()
        compose.onNodeWithText("Cart 3 · 1 item").assertIsNotSelected()
    }

    @Test
    fun tapSwitchesCart() {
        var switched: String? = null
        compose.setContent {
            ShopLedgerTheme {
                CartSwitcher(
                    carts = threeCarts,
                    activeCartId = "c1",
                    onSwitch = { switched = it },
                    onNewCart = {},
                )
            }
        }
        compose.onNodeWithText("Ramesh · 0 items").performClick()
        assertEquals("c2", switched)
    }

    @Test
    fun newCartChipCallsNewAndHidesAtMax() {
        var called = false
        val eightCarts = (1..8).map { CartChip("c$it", "Cart $it", it) }
        var shownCarts by mutableStateOf(threeCarts)
        compose.setContent {
            ShopLedgerTheme {
                CartSwitcher(
                    carts = shownCarts,
                    activeCartId = "c1",
                    onSwitch = {},
                    onNewCart = { called = true },
                )
            }
        }
        compose.onNodeWithText("New cart").assertIsDisplayed().performClick()
        assertTrue(called)

        shownCarts = eightCarts
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithText("New cart").fetchSemanticsNodes().size)
    }

    @Test
    fun wrapsInsteadOfScrollingAt360dp() {
        val sixCarts = (1..6).map { CartChip("c$it", "Cart $it", it) }
        compose.setContent {
            ShopLedgerTheme {
                CartSwitcher(
                    carts = sixCarts,
                    activeCartId = "c1",
                    onSwitch = {},
                    onNewCart = {},
                )
            }
        }
        sixCarts.forEach { c ->
            val expected = if (c.lineCount == 1) "${c.label} · 1 item" else "${c.label} · ${c.lineCount} items"
            compose.onNodeWithText(expected).assertIsDisplayed()
        }
        compose.onNodeWithText("New cart").assertIsDisplayed()
    }

    @Test
    fun chipsAreAtLeast48dpTall() {
        compose.setContent {
            ShopLedgerTheme {
                CartSwitcher(
                    carts = threeCarts,
                    activeCartId = "c1",
                    onSwitch = {},
                    onNewCart = {},
                )
            }
        }
        compose.onNodeWithText("Cart 1 · 2 items").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("New cart").assertHeightIsAtLeast(48.dp)
    }
}
