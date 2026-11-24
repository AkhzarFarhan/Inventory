package com.shopledger.pos.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shopledger.pos.ui.screens.items.AddToCartContent
import com.shopledger.pos.ui.screens.items.AddToCartEvent
import com.shopledger.pos.ui.screens.items.AddToCartState
import com.shopledger.pos.ui.screens.items.AddToCartVariantRow
import com.shopledger.pos.ui.theme.ShopLedgerTheme
import com.shopledger.pos.ui.theme.StockStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@Config(qualifiers = "w360dp-h800dp")
@RunWith(AndroidJUnit4::class)
class AddToCartSheetTest {
    @get:Rule
    val compose = createComposeRule()

    private fun row(
        id: String = "v1",
        label: String = "4 inch",
        pricePaise: Long = 5_000L,
        purchasePricePaise: Long = 4_000L,
        stockText: String = "12 pcs in stock",
        status: StockStatus = StockStatus.OK,
        qtyInCart: Double = 0.0,
    ) = AddToCartVariantRow(
        variantId = id,
        label = label,
        pricePaise = pricePaise,
        purchasePricePaise = purchasePricePaise,
        stockText = stockText,
        status = status,
        qtyInCart = qtyInCart,
    )

    private fun show(
        state: AddToCartState,
        onEvent: (AddToCartEvent) -> Unit = {},
    ) {
        compose.setContent {
            ShopLedgerTheme {
                AddToCartContent(
                    state = state,
                    onEvent = onEvent,
                )
            }
        }
    }

    @Test
    fun variantsAreAVerticalListNotSideways() {
        val variants = (1..6).map { i ->
            row(id = "v$i", label = "Size $i inch")
        }
        show(
            AddToCartState(
                itemName = "Butt Hinge",
                cartLabel = "Cart 1",
                variants = variants,
                cartLineCount = 0,
                cartTotalPaise = 0L,
                isSellerUnlocked = false,
            ),
        )
        val leftEdges = mutableSetOf<Float>()
        variants.forEach { v ->
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(v.label))
            val node = compose.onNodeWithText(v.label)
            node.assertIsDisplayed()
            leftEdges += node.fetchSemanticsNode().boundsInRoot.left
        }
        assertEquals(1, leftEdges.size)
    }

    @Test
    fun addButtonAddsOneToCart() {
        val events = mutableListOf<AddToCartEvent>()
        show(
            AddToCartState(
                itemName = "Butt Hinge",
                cartLabel = "Cart 1",
                variants = listOf(row(id = "v1", qtyInCart = 0.0)),
                cartLineCount = 0,
                cartTotalPaise = 0L,
                isSellerUnlocked = false,
            ),
            onEvent = { events += it },
        )
        compose.onNodeWithText("Add").performClick()
        assertEquals(listOf(AddToCartEvent.Add("v1")), events)
    }

    @Test
    fun variantAlreadyInCartShowsStepper() {
        val events = mutableListOf<AddToCartEvent>()
        show(
            AddToCartState(
                itemName = "Butt Hinge",
                cartLabel = "Cart 1",
                variants = listOf(
                    row(id = "v1", label = "3 inch", qtyInCart = 0.0),
                    row(id = "v2", label = "4 inch", qtyInCart = 2.0),
                ),
                cartLineCount = 1,
                cartTotalPaise = 10_000L,
                isSellerUnlocked = false,
            ),
            onEvent = { events += it },
        )
        compose.onNodeWithText("2").assertIsDisplayed()
        compose.onNodeWithContentDescription("Increase quantity").performClick()
        compose.onNodeWithContentDescription("Decrease quantity").performClick()
        assertEquals(
            listOf(
                AddToCartEvent.SetQty("v2", 3.0),
                AddToCartEvent.SetQty("v2", 1.0),
            ),
            events,
        )
    }

    @Test
    fun minusAtOneRemoves() {
        val events = mutableListOf<AddToCartEvent>()
        show(
            AddToCartState(
                itemName = "Butt Hinge",
                cartLabel = "Cart 1",
                variants = listOf(row(id = "v1", qtyInCart = 1.0)),
                cartLineCount = 1,
                cartTotalPaise = 5_000L,
                isSellerUnlocked = false,
            ),
            onEvent = { events += it },
        )
        compose.onNodeWithContentDescription("Decrease quantity").performClick()
        assertEquals(listOf(AddToCartEvent.SetQty("v1", 0.0)), events)
    }

    @Test
    fun showsWhichCartAndGoToCart() {
        val events = mutableListOf<AddToCartEvent>()
        show(
            AddToCartState(
                itemName = "Butt Hinge",
                cartLabel = "Cart 2",
                variants = listOf(row(id = "v1", qtyInCart = 3.0)),
                cartLineCount = 3,
                cartTotalPaise = 15_000L,
                isSellerUnlocked = false,
            ),
            onEvent = { events += it },
        )
        compose.onNodeWithText("Adding to Cart 2").assertIsDisplayed()
        compose.onNodeWithText("Go to cart · 3 items", substring = true).performClick()
        compose.onNodeWithText("Done").performClick()
        assertEquals(listOf(AddToCartEvent.GoToCart, AddToCartEvent.Done), events)
    }

    @Test
    fun noSaleControlsOnThisSheet() {
        show(
            AddToCartState(
                itemName = "Butt Hinge",
                cartLabel = "Cart 1",
                variants = listOf(row()),
                cartLineCount = 1,
                cartTotalPaise = 5_000L,
                isSellerUnlocked = true,
            ),
        )
        listOf("Buyer", "Cash", "UPI", "Udhar", "Discount", "Share estimate picture", "Profit").forEach { label ->
            compose.onNodeWithText(label, substring = true).assertDoesNotExist()
        }
    }

    @Test
    fun costHiddenWhenLocked() {
        show(
            AddToCartState(
                itemName = "Butt Hinge",
                cartLabel = "Cart 1",
                variants = listOf(row(pricePaise = 5_000L, purchasePricePaise = 4_000L)),
                cartLineCount = 0,
                cartTotalPaise = 0L,
                isSellerUnlocked = false,
            ),
        )
        compose.onNodeWithText("₹40", substring = true).assertDoesNotExist()
        compose.onNodeWithText("₹50").assertIsDisplayed()
    }

    @Test
    fun outOfStockStillAddable() {
        show(
            AddToCartState(
                itemName = "Butt Hinge",
                cartLabel = "Cart 1",
                variants = listOf(
                    row(
                        id = "v1",
                        stockText = "Out of stock",
                        status = StockStatus.OUT,
                        qtyInCart = 0.0,
                    ),
                ),
                cartLineCount = 0,
                cartTotalPaise = 0L,
                isSellerUnlocked = false,
            ),
        )
        compose.onNodeWithText("Out of stock").assertIsDisplayed()
        compose.onNodeWithText("Add").assertIsEnabled()
    }

    @Test
    fun presetChipsShownWhenQtyInCartPositiveAndTapEmitsSetQty() {
        val events = mutableListOf<AddToCartEvent>()
        show(
            AddToCartState(
                itemName = "Screw",
                cartLabel = "Cart 1",
                variants = listOf(row(id = "v1", qtyInCart = 2.0)),
                cartLineCount = 1,
                cartTotalPaise = 10_000L,
                isSellerUnlocked = false,
            ),
            onEvent = { events += it },
        )
        compose.onNodeWithText("+12").assertIsDisplayed().performClick()
        assertEquals(listOf(AddToCartEvent.SetQty("v1", 14.0)), events)
    }

    @Test
    fun kgUnitShowsGramAndKgPresetChips() {
        show(
            AddToCartState(
                itemName = "Sugar",
                cartLabel = "Cart 1",
                variants = listOf(row(id = "v1", qtyInCart = 1.0).copy(unit = "kg")),
                cartLineCount = 1,
                cartTotalPaise = 4_500L,
                isSellerUnlocked = false,
            ),
        )
        compose.onNodeWithText("+250 g").assertIsDisplayed()
        compose.onNodeWithText("+500 g").assertIsDisplayed()
        compose.onNodeWithText("+1 kg").assertIsDisplayed()
    }

    @Test
    fun variantFilterFieldAppearsAboveSixVariantsAndFiltersRows() {
        val variants = (1..7).map { i -> row(id = "v$i", label = "Size $i inch") }
        show(
            AddToCartState(
                itemName = "Bolt",
                cartLabel = "Cart 1",
                variants = variants,
                cartLineCount = 0,
                cartTotalPaise = 0L,
                isSellerUnlocked = false,
            ),
        )
        compose.onNodeWithText("Filter sizes or types").assertIsDisplayed().performTextInput("7")
        compose.onNodeWithText("Size 7 inch").assertIsDisplayed()
        compose.onNodeWithText("Size 1 inch").assertDoesNotExist()
    }

    @Test
    fun inCartVariantShowsEditableSaleRateAndResetButton() {
        val events = mutableListOf<AddToCartEvent>()
        compose.setContent {
            ShopLedgerTheme {
                var rowState by remember { mutableStateOf(row(id = "v1", pricePaise = 5_000L, qtyInCart = 2.0)) }
                AddToCartContent(
                    state = AddToCartState(
                        itemName = "Butt Hinge",
                        cartLabel = "Cart 1",
                        variants = listOf(rowState),
                        cartLineCount = 1,
                        cartTotalPaise = kotlin.math.round(rowState.pricePaise * rowState.qtyInCart).toLong(),
                        isSellerUnlocked = false,
                    ),
                    onEvent = { e ->
                        events += e
                        if (e is AddToCartEvent.SetPrice && e.variantId == "v1") {
                            rowState = rowState.copy(pricePaise = e.pricePaise)
                        }
                    },
                )
            }
        }
        compose.onNodeWithText("Sale rate (per pcs)").assertIsDisplayed().performTextClearance()
        compose.onNodeWithText("Enter a sale rate above ₹0").assertIsDisplayed()
        compose.onNodeWithText("Go to cart", substring = true).assertIsNotEnabled()

        compose.onNodeWithText("Sale rate (per pcs)").performTextInput("45")
        compose.onNodeWithText("List ₹50").assertIsDisplayed()
        compose.onNodeWithText("Go to cart · 1 item · ₹90").assertIsEnabled()

        compose.onNodeWithText("List ₹50").performClick()
        compose.onNodeWithText("Go to cart · 1 item · ₹100").assertIsEnabled()
        assertEquals(AddToCartEvent.SetPrice("v1", 5_000L), events.last())
    }

    @Config(qualifiers = "mdpi")
    @Test
    fun tappingRatePillBeforeAddingOpensRateDialogAndEmitsSetPrice() {
        val events = mutableListOf<AddToCartEvent>()
        show(
            AddToCartState(
                itemName = "Butt Hinge",
                cartLabel = "Cart 1",
                variants = listOf(row(id = "v1", pricePaise = 5_000L, qtyInCart = 0.0)),
                cartLineCount = 0,
                cartTotalPaise = 0L,
                isSellerUnlocked = false,
            ),
            onEvent = { events += it },
        )
        compose.onNodeWithContentDescription("Change sale rate").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Price per pcs").assertIsDisplayed()
        compose.onNodeWithText("Price").performSemanticsAction(SemanticsActions.SetText) {
            it(AnnotatedString("48"))
        }
        compose.onNode(hasAnyAncestor(isDialog()) and hasText("Done"))
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(AddToCartEvent.SetPrice("v1", 4_800L)), events)
    }
}
