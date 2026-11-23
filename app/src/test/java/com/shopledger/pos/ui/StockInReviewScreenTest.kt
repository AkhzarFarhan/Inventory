package com.shopledger.pos.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shopledger.pos.core.scanner.MatchStatus
import com.shopledger.pos.core.scanner.ParsedStockLine
import com.shopledger.pos.core.scanner.StockLineMatch
import com.shopledger.pos.data.command.StockInRequest
import com.shopledger.pos.data.command.StockInResult
import com.shopledger.pos.data.local.entity.InventoryItemEntity
import com.shopledger.pos.data.local.entity.ItemVariantEntity
import com.shopledger.pos.data.model.InventoryItemWithVariants
import com.shopledger.pos.ui.screens.stockin.StockInEvent
import com.shopledger.pos.ui.screens.stockin.StockInReviewScreen
import com.shopledger.pos.ui.screens.stockin.StockInSaver
import com.shopledger.pos.ui.screens.stockin.StockInViewModel
import com.shopledger.pos.ui.theme.ShopLedgerTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

// A tall screen so the lazy table composes every row next to the header.
@Config(qualifiers = "w400dp-h2000dp")
@RunWith(AndroidJUnit4::class)
class StockInReviewScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val inventory = listOf(
        InventoryItemWithVariants(
            InventoryItemEntity(id = "1", displayName = "Butt Hinge", sortKey = "BUTT HINGE"),
            listOf(ItemVariantEntity(id = "v-1", itemId = "1", purchasePricePaise = 4800, sellingPricePaise = 6000))
        ),
        InventoryItemWithVariants(
            InventoryItemEntity(id = "2", displayName = "Fevicol", sortKey = "FEVICOL"),
            listOf(ItemVariantEntity(id = "v-2", itemId = "2", purchasePricePaise = 20000, sellingPricePaise = 25000))
        ),
    )

    private fun line(name: String, rate: Long?) = ParsedStockLine(name, 2000, null, rate, null, true, name)

    private val matches = listOf(
        StockLineMatch(line("Butt Hinge", 4800), MatchStatus.MATCHED, "1", "v-1", 4800, 6000, null),
        StockLineMatch(line("Fevcol", 21000), MatchStatus.CHECK, "2", "v-2", 20000, 25000, null),
        StockLineMatch(line("Door Stopper", 1000), MatchStatus.NEW, null, null, null, null, null),
    )

    private class SlowSaver : StockInSaver {
        val gate = CompletableDeferred<StockInResult>()
        var calls = 0
        override suspend fun receive(request: StockInRequest): StockInResult {
            calls++
            return gate.await()
        }
    }

    private fun show(saver: StockInSaver = StockInSaver { StockInResult("p", 0, 0, 0, 0, false) }, onEvent: (StockInEvent) -> Unit = {}): StockInViewModel {
        val vm = StockInViewModel(saver, newId = { "act" })
        vm.openMatches(matches, inventory, null, null)
        compose.setContent {
            ShopLedgerTheme {
                val s by vm.state.collectAsState()
                s?.let { st ->
                    StockInReviewScreen(st, onEvent = { e ->
                        onEvent(e)
                        vm.onEvent(e)
                    })
                }
            }
        }
        return vm
    }

    @Test fun rowsShowStatusIconsAndTabCounts() {
        show()
        compose.onNodeWithText("All (3)").assertIsDisplayed()
        compose.onNodeWithText("New (1)").assertIsDisplayed()
        compose.onNodeWithText("Check (1)").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Matched", useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithContentDescription("Needs checking", useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithContentDescription("New item", useUnmergedTree = true).assertCountEquals(1)
    }

    @Test fun newTabShowsOnlyNewRows() {
        show()
        compose.onNodeWithText("New (1)").performClick()
        compose.onNodeWithText("Door Stopper").assertIsDisplayed()
        compose.onAllNodesWithText("Butt Hinge").assertCountEquals(0)
    }

    @Test fun costThatDiffersShowsWasPrice() {
        show()
        compose.onNodeWithText("was ₹200").assertIsDisplayed()
    }

    @Test fun invalidRowsDisableSaveAndExplainWhy() {
        show()
        // CHECK row is unresolved first.
        compose.onNodeWithText("Add 3 items to stock").assertIsNotEnabled()
        compose.onNodeWithText("Choose which item \"Fevcol\" is.").assertIsDisplayed()
    }

    @Test fun enteringSellPriceEnablesSave() {
        val vm = show()
        // resolve the CHECK row by confirming it
        val checkKey = vm.state.value!!.rows[1].key
        val newKey = vm.state.value!!.rows[2].key
        compose.runOnIdle { vm.onEvent(StockInEvent.PickVariant(checkKey, "2", "v-2")) }
        compose.onNodeWithText("Add 3 items to stock").assertIsNotEnabled()
        compose.onNodeWithText("Add a selling price for \"Door Stopper\".").assertIsDisplayed()
        compose.onNodeWithTag("cell_sell_$newKey").performClick()
        compose.onNodeWithTag("cell_sell_$newKey").performTextInput("15")
        compose.onNodeWithText("Add 3 items to stock").assertIsEnabled()
    }

    @Test fun deletingARowUpdatesTheButtonCount() {
        val vm = show()
        compose.runOnIdle { vm.onEvent(StockInEvent.PickVariant(vm.state.value!!.rows[1].key, "2", "v-2")) }
        compose.onNodeWithText("Add 3 items to stock").assertIsDisplayed()
        val key = vm.state.value!!.rows[0].key
        // There is no delete column any more: press and hold the item to get the remove menu.
        compose.onNodeWithTag("cell_item_$key").performTouchInput { longClick() }
        compose.onNodeWithTag("remove_$key").performClick()
        compose.onNodeWithText("Add 2 items to stock").assertIsDisplayed()
    }

    @Test fun longPressingANewRowsNameAlsoOffersRemove() {
        val vm = show()
        compose.runOnIdle { vm.onEvent(StockInEvent.PickVariant(vm.state.value!!.rows[1].key, "2", "v-2")) }
        val newKey = vm.state.value!!.rows[2].key
        compose.onNodeWithTag("cell_name_$newKey").performTouchInput { longClick() }
        compose.onNodeWithTag("remove_$newKey").performClick()
        compose.onNodeWithText("Add 2 items to stock").assertIsDisplayed()
    }

    @Test fun headerIsOneLineUntilTappedAndColumnTitlesAreHeadings() {
        show()
        compose.onNodeWithTag("header_summary").assertIsDisplayed()
        compose.onNodeWithText("Bill total").assertDoesNotExist()
        compose.onNodeWithTag("header_summary").performClick()
        compose.onNodeWithText("Bill total").assertIsDisplayed()
        compose.onNodeWithTag("header_summary").performClick()
        compose.onNodeWithText("Bill total").assertDoesNotExist()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertExists()
    }

    @Test fun cellsAreAnnouncedWithColumnAndItem() {
        val vm = show()
        val key = vm.state.value!!.rows[0].key
        compose.onNodeWithTag("cell_cost_$key").assertContentDescriptionEquals("Cost for Butt Hinge: ₹48")
    }

    @Test fun moneyCellsTakeAtMostTwoDecimals() {
        val vm = show()
        val newKey = vm.state.value!!.rows[2].key
        compose.onNodeWithTag("cell_sell_$newKey").performClick()
        compose.onNodeWithTag("cell_sell_$newKey").performTextInput("12.345")
        // The third decimal is refused, so nothing was typed at all.
        assertEquals(null, vm.state.value!!.rows[2].sellPaise)
        compose.onNodeWithTag("cell_sell_$newKey").performTextInput("12.34")
        assertEquals(1234L, vm.state.value!!.rows[2].sellPaise)
    }

    // On a 360 dp phone the item column must still be readable.
    @Config(qualifiers = "w360dp-h2000dp")
    @Test fun itemColumnHasRoomAtThreeSixtyDp() {
        val vm = show()
        val matchedKey = vm.state.value!!.rows[0].key
        val newKey = vm.state.value!!.rows[2].key
        compose.onNodeWithTag("cell_item_$matchedKey").assertWidthIsAtLeast(140.dp)
        compose.onNodeWithTag("cell_name_$newKey").assertWidthIsAtLeast(140.dp)
        // Cost and sell stay visible next to it.
        compose.onNodeWithTag("cell_cost_$matchedKey").assertIsDisplayed()
        compose.onNodeWithTag("cell_sell_$matchedKey").assertIsDisplayed()
    }

    @Test fun saveFiresOnceEvenOnDoubleTap() {
        val saver = SlowSaver()
        val events = ArrayList<StockInEvent>()
        val vm = show(saver) { events.add(it) }
        compose.runOnIdle {
            vm.onEvent(StockInEvent.PickVariant(vm.state.value!!.rows[1].key, "2", "v-2"))
            vm.onEvent(StockInEvent.EditSell(vm.state.value!!.rows[2].key, 1500))
        }
        compose.onNodeWithText("Add 3 items to stock").assertIsEnabled()
        compose.onNodeWithText("Add 3 items to stock").performClick()
        compose.onNodeWithText("Saving…").performClick()
        compose.waitForIdle()
        assertEquals(1, events.count { it is StockInEvent.Save })
        assertEquals(1, saver.calls)
    }

    @Test fun emptyParseOffersTryAgainAndManual() {
        val vm = StockInViewModel(StockInSaver { StockInResult("p", 0, 0, 0, 0, false) })
        vm.openMatches(emptyList(), inventory, null, null)
        val events = ArrayList<StockInEvent>()
        compose.setContent {
            ShopLedgerTheme {
                val s by vm.state.collectAsState()
                StockInReviewScreen(s!!, onEvent = { events.add(it) })
            }
        }
        compose.onNodeWithText("Try again").performClick()
        compose.onNodeWithText("Add item manually").performClick()
        assertEquals(listOf(StockInEvent.Rescan, StockInEvent.AddManually), events)
    }

    @Test fun nagadUpiChoiceAppearsWhenPaidIsAboveZero() {
        val vm = show()
        compose.onNodeWithTag("header_summary").performClick()
        compose.onNodeWithText("Nagad").assertDoesNotExist()
        compose.runOnIdle { vm.onEvent(StockInEvent.SetPaid(5000L)) }
        compose.waitForIdle()
        compose.onNodeWithText("Nagad").assertIsDisplayed()
        compose.onNodeWithText("UPI").performClick()
        assertEquals("UPI", vm.state.value!!.paidMode)
    }

    @Config(qualifiers = "mdpi")
    @Test fun addItemButtonOpensPicker() {
        val vm = StockInViewModel(StockInSaver { StockInResult("p", 0, 0, 0, 0, false) })
        vm.setInventory(inventory)
        vm.openManual()
        compose.setContent {
            ShopLedgerTheme {
                val s by vm.state.collectAsState()
                s?.let { st -> StockInReviewScreen(st, onEvent = vm::onEvent) }
            }
        }
        compose.onNodeWithText("Add item").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText("Butt Hinge").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals("v-1", vm.state.value!!.rows.single().variantId)
    }

    @Test fun emptyReviewShowsEmptyState() {
        val vm = StockInViewModel(StockInSaver { StockInResult("p", 0, 0, 0, 0, false) })
        vm.openManual()
        compose.setContent {
            ShopLedgerTheme {
                val s by vm.state.collectAsState()
                s?.let { st -> StockInReviewScreen(st, onEvent = vm::onEvent) }
            }
        }
        compose.onNodeWithText("No items on this bill yet").assertIsDisplayed()
        compose.onNodeWithText("Tap Add item to add what you received.").assertIsDisplayed()
        compose.onNodeWithText("Add item").assertIsDisplayed()
    }
}
