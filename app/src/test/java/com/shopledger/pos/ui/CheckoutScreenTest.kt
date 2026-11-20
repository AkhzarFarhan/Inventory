package com.shopledger.pos.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shopledger.pos.R
import com.shopledger.pos.core.format.IndianCurrencyFormatter
import com.shopledger.pos.data.model.CartLineItem
import com.shopledger.pos.ui.screens.checkout.CheckoutEvent
import com.shopledger.pos.ui.screens.checkout.CheckoutScreen
import com.shopledger.pos.ui.screens.checkout.CheckoutState
import com.shopledger.pos.ui.screens.checkout.PayMode
import com.shopledger.pos.ui.screens.checkout.mapCheckoutSale
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
                            is CheckoutEvent.DiscountChanged -> state = state.copy(discountPaise = e.paise)
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
            compose.onNodeWithText(str(it)).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        }
        compose.onNodeWithText(str(R.string.checkout_complete, rupees(5_000L)))
            .assertIsEnabled()
            .assertHeightIsAtLeast(56.dp)
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
    fun discountStartsCollapsedAndReducesGrandTotalOnceEntered() {
        show(CheckoutState(lines = listOf(hinge(2.0))))
        compose.onAllNodesWithText(str(R.string.checkout_discount)).assertCountEquals(0)
        compose.onNodeWithText(str(R.string.checkout_complete, rupees(10_000L))).assertIsDisplayed()

        compose.onNodeWithText(str(R.string.checkout_add_discount)).performClick()
        compose.onNodeWithText(str(R.string.checkout_discount)).performTextInput("10")

        // 2 x 50 = 100, minus 10 discount
        compose.onNodeWithText(str(R.string.checkout_complete, rupees(9_000L))).assertIsDisplayed()
    }

    @Test
    fun udharAndDiscountSurviveRotation() {
        val completed = mutableListOf<PayMode>()
        val tester = StateRestorationTester(compose)
        tester.setContent {
            ShopLedgerTheme {
                var mode by rememberSaveable { mutableStateOf(PayMode.CASH) }
                var discount by rememberSaveable { mutableStateOf(0L) }
                CheckoutScreen(
                    state = CheckoutState(
                        lines = listOf(hinge(2.0)),
                        paymentMode = mode,
                        discountPaise = discount,
                        buyerName = "Ramesh",
                    ),
                    onEvent = { e ->
                        when (e) {
                            is CheckoutEvent.SelectMode -> mode = e.mode
                            is CheckoutEvent.DiscountChanged -> discount = e.paise
                            is CheckoutEvent.Complete -> completed += e.mode
                            else -> Unit
                        }
                    },
                )
            }
        }
        compose.onNodeWithText(str(R.string.checkout_pay_udhar)).performClick()
        compose.onNodeWithText(str(R.string.checkout_add_discount)).performClick()
        compose.onNodeWithText(str(R.string.checkout_discount)).performTextInput("10")

        tester.emulateSavedInstanceStateRestore()

        compose.onNodeWithText(str(R.string.checkout_paid_now)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.checkout_discount)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.checkout_complete, rupees(9_000L))).performClick()
        assertEquals(listOf(PayMode.CREDIT), completed)
    }

    @Test
    fun saleMappingClampsDiscountAndPaidNowAndOnlyUdharKeepsPaid() {
        // Udhar: discount cannot exceed subtotal, paid-now cannot exceed the net.
        val credit = mapCheckoutSale(subtotalPaise = 10_000L, mode = PayMode.CREDIT, discountPaise = 99_999L, paidNowPaise = 500L)
        assertEquals(PayMode.CREDIT.wire, credit.paymentMode)
        assertEquals(10_000L, credit.discountPaise)
        assertEquals(0L, credit.amountPaidPaise)

        val overpaid = mapCheckoutSale(10_000L, PayMode.CREDIT, 1_000L, 50_000L)
        assertEquals(1_000L, overpaid.discountPaise)
        assertEquals(9_000L, overpaid.amountPaidPaise)

        val noPaid = mapCheckoutSale(10_000L, PayMode.CREDIT, 0L, null)
        assertEquals(0L, noPaid.amountPaidPaise)

        // Cash / UPI never send a paid-now amount; negative discount clamps to zero.
        val cash = mapCheckoutSale(10_000L, PayMode.CASH, -5L, 4_000L)
        assertEquals("CASH", cash.paymentMode)
        assertEquals(0L, cash.discountPaise)
        assertEquals(null, cash.amountPaidPaise)
        assertEquals(null, mapCheckoutSale(10_000L, PayMode.UPI, 0L, 4_000L).amountPaidPaise)
    }

    @Test
    fun gstLineAppearsOnlyWhenGstApplies() {
        show(CheckoutState(lines = listOf(hinge()), gstPaise = 900L))
        compose.onNodeWithText(str(R.string.checkout_gst)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.checkout_complete, rupees(5_900L))).assertIsDisplayed()
    }
}
