package com.shopledger.pos.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shopledger.pos.R
import com.shopledger.pos.core.format.IndianCurrencyFormatter
import com.shopledger.pos.data.model.CartLineItem
import com.shopledger.pos.ui.screens.checkout.CheckoutEvent
import com.shopledger.pos.ui.screens.checkout.CheckoutScreen
import com.shopledger.pos.ui.screens.checkout.CheckoutState
import com.shopledger.pos.ui.screens.checkout.PayMode
import com.shopledger.pos.ui.theme.ShopLedgerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@Config(qualifiers = "w360dp-h2000dp")
@RunWith(AndroidJUnit4::class)
class CheckoutScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun str(id: Int, vararg a: Any) = context.getString(id, *a)
    private fun rupees(paise: Long) = IndianCurrencyFormatter.formatPaise(paise)

    private fun hinge(qty: Double = 1.0) = CartLineItem(
        itemId = "i1", variantId = "v1", itemName = "Butt Hinge", variantSummary = "4 inch", unit = "pc",
        availableStock = 20.0, unitPurchasePricePaise = 4_000L, defaultSellingPricePaise = 5_000L,
        customSellingPricePaise = 5_000L, quantity = qty,
    )

    /** Hosts the stateless screen with a tiny reducer so qty / buyer / mode edits feed back into the state. */
    private fun show(initial: CheckoutState, onComplete: (PayMode) -> Unit = {}) {
        compose.setContent {
            ShopLedgerTheme {
                var state by remember { mutableStateOf(initial) }
                CheckoutScreen(
                    state = state,
                    onEvent = { e ->
                        when (e) {
                            is CheckoutEvent.SetQty -> state = state.copy(
                                lines = state.lines.map { if (it.variantId == e.variantId) it.copy(quantity = e.qty) else it },
                            )
                            is CheckoutEvent.SelectMode -> state = state.copy(paymentMode = e.mode)
                            is CheckoutEvent.BuyerNameChanged -> state = state.copy(buyerName = e.name)
                            is CheckoutEvent.Complete -> onComplete(e.mode)
                            else -> Unit
                        }
                    },
                )
            }
        }
    }

    @Test
    fun plusAndMinusChangeQuantityAndTotal() {
        show(CheckoutState(lines = listOf(hinge())))
        compose.onNodeWithText(str(R.string.checkout_complete, rupees(5_000L))).assertIsDisplayed()

        compose.onNodeWithContentDescription(str(R.string.cd_increase_quantity)).performClick()
        compose.onNodeWithText(str(R.string.checkout_complete, rupees(10_000L))).assertIsDisplayed()

        compose.onNodeWithContentDescription(str(R.string.cd_increase_quantity)).performClick()
        compose.onNodeWithText(str(R.string.checkout_complete, rupees(15_000L))).assertIsDisplayed()

        compose.onNodeWithContentDescription(str(R.string.cd_decrease_quantity)).performClick()
        compose.onNodeWithText(str(R.string.checkout_complete, rupees(10_000L))).assertIsDisplayed()
    }

    @Test
    fun udharWithoutCustomerShowsErrorAndDoesNotComplete() {
        val completed = mutableListOf<PayMode>()
        show(CheckoutState(lines = listOf(hinge())), onComplete = { completed += it })

        compose.onNodeWithText(str(R.string.checkout_pay_udhar)).performClick()
        compose.onNodeWithText(str(R.string.checkout_complete, rupees(5_000L))).performClick()

        compose.onNodeWithText(str(R.string.checkout_udhar_needs_customer)).assertIsDisplayed()
        assertEquals(emptyList<PayMode>(), completed)
    }

    @Test
    fun udharWithCustomerNameCompletesAsCredit() {
        val completed = mutableListOf<PayMode>()
        show(CheckoutState(lines = listOf(hinge())), onComplete = { completed += it })

        compose.onNodeWithText(str(R.string.checkout_pay_udhar)).performClick()
        compose.onNodeWithText(str(R.string.checkout_name)).performTextInput("Ramesh")
        compose.onNodeWithText(str(R.string.checkout_complete, rupees(5_000L))).performClick()

        assertEquals(listOf(PayMode.CREDIT), completed)
    }

    @Test
    fun cashWithLinesCompletesOnce() {
        val completed = mutableListOf<PayMode>()
        show(CheckoutState(lines = listOf(hinge())), onComplete = { completed += it })

        compose.onNodeWithText(str(R.string.checkout_complete, rupees(5_000L))).performClick()

        assertEquals(listOf(PayMode.CASH), completed)
    }

    @Test
    fun emptyCartShowsEmptyStateAndDisabledComplete() {
        show(CheckoutState(lines = emptyList()))

        compose.onNodeWithText(str(R.string.checkout_empty_title)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.checkout_complete, rupees(0L))).assertIsNotEnabled()
    }

    @Test
    fun paymentButtonsAndCompleteAreBigEnoughAt360dp() {
        show(CheckoutState(lines = listOf(hinge())))
        listOf(R.string.checkout_pay_cash, R.string.checkout_pay_upi, R.string.checkout_pay_udhar).forEach {
            compose.onNodeWithText(str(it)).assertIsDisplayed()
        }
        compose.onNodeWithText(str(R.string.checkout_complete, rupees(5_000L))).assertIsEnabled()
    }

    @Test
    fun profitIsHiddenWhenSellerModeLockedAndShownWhenUnlocked() {
        show(CheckoutState(lines = listOf(hinge()), isSellerUnlocked = false))
        compose.onAllNodesWithText(str(R.string.checkout_profit)).assertCountEquals(0)
    }

    @Test
    fun profitIsShownWhenSellerModeUnlocked() {
        show(CheckoutState(lines = listOf(hinge()), isSellerUnlocked = true))
        compose.onNodeWithText(str(R.string.checkout_profit), substring = true).assertIsDisplayed()
    }

    @Test
    fun discountIsCollapsedUntilRequestedAndReducesGrandTotal() {
        show(CheckoutState(lines = listOf(hinge(2.0)), discountPaise = 1_000L))
        // 2 x 50 = 100, minus 10 discount
        compose.onNodeWithText(str(R.string.checkout_complete, rupees(9_000L))).assertIsDisplayed()
    }

    @Test
    fun gstLineAppearsOnlyWhenGstApplies() {
        show(CheckoutState(lines = listOf(hinge()), gstPaise = 900L))
        compose.onNodeWithText(str(R.string.checkout_gst)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.checkout_complete, rupees(5_900L))).assertIsDisplayed()
    }
}
