package com.shopledger.pos.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shopledger.pos.core.nlp.DuplicateCheckResult
import com.shopledger.pos.core.nlp.DuplicateDecisionType
import com.shopledger.pos.core.nlp.SemanticItemSignature
import com.shopledger.pos.core.share.ReceiptLineItem
import com.shopledger.pos.core.share.SaleReceiptData
import com.shopledger.pos.data.local.entity.InventoryItemEntity
import com.shopledger.pos.data.local.entity.ItemVariantEntity
import com.shopledger.pos.data.local.entity.PartyEntity
import com.shopledger.pos.data.local.entity.SaleRecordEntity
import com.shopledger.pos.data.model.InventoryItemWithVariants
import com.shopledger.pos.ui.screens.inventory.AddEditItemSheet
import com.shopledger.pos.ui.screens.inventory.ItemEditorMode
import com.shopledger.pos.ui.screens.inventory.OrderScanSheet
import com.shopledger.pos.ui.screens.khata.PartiesKhataScreen
import com.shopledger.pos.ui.screens.sales.ReceiptShareDialog
import com.shopledger.pos.ui.screens.sales.SaleHistoryScreen
import com.shopledger.pos.ui.theme.ShopLedgerTheme
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Smoke tests for the screens restyled to Material 3 in Task 10 (part A). */
@RunWith(AndroidJUnit4::class)
class RestyledScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private fun click(text: String, substring: Boolean = false) {
        compose.waitForIdle()
        compose.onNode(hasText(text, substring = substring) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    // ---- Khata ----

    private val dueParty = PartyEntity(id = "p1", name = "Ramesh Traders", phone = "9876543210", currentBalancePaise = 25_000L)
    private val settledParty = PartyEntity(id = "p2", name = "Sita Store", currentBalancePaise = 0L)

    @Test fun khataEmptyShowsEmptyStateAndAddPartyDialog() {
        compose.setContent {
            ShopLedgerTheme {
                PartiesKhataScreen(parties = emptyList(), onAddParty = { _, _, _, _ -> }, onRecordPaymentReceived = { _, _, _, _ -> })
            }
        }
        compose.onNodeWithText("No Khata parties yet").assertIsDisplayed()
        compose.onNodeWithContentDescription("Add party").assertIsDisplayed()
        click("Add party")
        compose.onNodeWithText("Add customer or distributor").assertIsDisplayed()
        compose.onNodeWithText("Save party").assertIsDisplayed()
    }

    @Test fun khataListShowsRemindAndReceiveForDuePartyAndRecordsPayment() {
        var recorded: Triple<String, Long, String>? = null
        compose.setContent {
            ShopLedgerTheme {
                PartiesKhataScreen(
                    parties = listOf(dueParty, settledParty),
                    onAddParty = { _, _, _, _ -> },
                    onRecordPaymentReceived = { id, paise, mode, _ -> recorded = Triple(id, paise, mode) },
                )
            }
        }
        compose.onNodeWithText("Ramesh Traders").assertIsDisplayed()
        compose.onNodeWithText("Sita Store").assertExists()
        compose.onNodeWithText("Remind").assertIsDisplayed()
        compose.onNodeWithText("Due (1)").assertIsDisplayed()
        click("Receive")
        compose.onNodeWithText("Receive payment (Jama)").assertIsDisplayed()
        click("Save payment")
        assertEquals(Triple("p1", 25_000L, "CASH"), recorded)
    }

    // ---- Bills ----

    private fun sale(returned: Boolean = false): SaleRecordEntity {
        val cal = Calendar.getInstance()
        return SaleRecordEntity(
            id = "s1", receiptNumber = "R-1", itemId = "i1", variantId = "v1",
            itemNameSnapshot = "Hinge", variantSummarySnapshot = "4 inch",
            quantitySold = 2.0, unitSnapshot = "pcs",
            unitPurchasePricePaise = 4_000L, unitSellingPricePaise = 5_000L,
            totalCostPaise = 8_000L, totalRevenuePaise = 10_000L, totalProfitPaise = 2_000L,
            gainMarkupPercent = 25.0, grossMarginPercent = 20.0,
            soldAt = cal.timeInMillis, yearInt = cal.get(Calendar.YEAR),
            monthInt = cal.get(Calendar.MONTH) + 1, dayInt = cal.get(Calendar.DAY_OF_MONTH),
            isReturned = returned,
        )
    }

    @Test fun billsEmptyShowsEmptyStateAndLockedHint() {
        compose.setContent {
            ShopLedgerTheme {
                SaleHistoryScreen(
                    sales = emptyList(), isSellerUnlocked = false, onToggleSellerUnlocked = {},
                    onOpenReceiptShare = {}, onReturnSale = { _, _, _ -> },
                )
            }
        }
        compose.onNodeWithText("No bills in this period").assertIsDisplayed()
        compose.onNodeWithText("Tap Sell on the Items tab and the bill will show here.").assertIsDisplayed()
        compose.onNodeWithText("Cost and profit are hidden. Tap to show.").assertIsDisplayed()
    }

    @Test fun billsListToggleReceiptAndReturnFlow() {
        var toggled = 0
        var shared: String? = null
        var returned: Pair<Long, String>? = null
        compose.setContent {
            ShopLedgerTheme {
                SaleHistoryScreen(
                    sales = listOf(sale()), isSellerUnlocked = false,
                    onToggleSellerUnlocked = { toggled++ },
                    onOpenReceiptShare = { shared = it.id },
                    onReturnSale = { _, qty, id -> returned = qty to id },
                )
            }
        }
        compose.onNodeWithText("Hinge").assertIsDisplayed()
        compose.onNodeWithText("Locked").assertIsDisplayed()
        click("Cost and profit are hidden. Tap to show.")
        assertEquals(1, toggled)
        click("Receipt")
        assertEquals("s1", shared)
        click("Return")
        compose.onNodeWithText("Return this sale?").assertIsDisplayed()
        click("Return and add stock back")
        assertEquals(2_000L, returned?.first)
    }

    @Test fun billsSellerUnlockedShowsProfit() {
        compose.setContent {
            ShopLedgerTheme {
                SaleHistoryScreen(
                    sales = listOf(sale()), isSellerUnlocked = true, onToggleSellerUnlocked = {},
                    onOpenReceiptShare = {}, onReturnSale = { _, _, _ -> },
                )
            }
        }
        compose.onNodeWithText("Cost and profit are showing. Tap to hide.").assertIsDisplayed()
        compose.onNodeWithText("Markup").assertIsDisplayed()
    }

    // ---- Receipt ----

    private fun receipt(estimate: Boolean) = SaleReceiptData(
        receiptNumber = "R-9", timestampMillis = 1_700_000_000_000L, isEstimate = estimate,
        items = listOf(ReceiptLineItem("Hinge", "4 inch", 2.0, "pcs", 5_000L, 10_000L)),
        grandTotalPaise = 10_000L,
    )

    @Test fun receiptDialogShowsShareAndPrintButtons() {
        var closed = 0
        compose.setContent { ShopLedgerTheme { ReceiptShareDialog(receipt = receipt(false), onDismiss = { closed++ }) } }
        compose.onNodeWithText("Share receipt picture on WhatsApp").assertExists()
        compose.onNodeWithText("Print receipt (58 mm Bluetooth)").assertExists()
        compose.onNodeWithText("Hinge", substring = true).assertExists()
        click("Done")
        assertEquals(1, closed)
    }

    @Test fun estimateDialogUsesEstimateWording() {
        compose.setContent { ShopLedgerTheme { ReceiptShareDialog(receipt = receipt(true), onDismiss = {}) } }
        compose.onNodeWithText("Share estimate picture on WhatsApp").assertExists()
        compose.onNodeWithText("Estimate total").assertExists()
    }

    private val sellVariant = ItemVariantEntity(
        id = "v1", itemId = "i1", sizeLabel = "4 inch", purchasePricePaise = 4_000L, sellingPricePaise = 5_000L,
        stockMilliUnits = 20_000L,
    )
    private val sellItem = InventoryItemEntity(id = "i1", displayName = "Hinge", sortKey = "HINGE")

    // ---- Order scan ----

    @Test fun orderScanSheetShowsScanActionsAndFillsCart() {
        compose.setContent {
            ShopLedgerTheme {
                OrderScanSheet(
                    inventory = emptyList(), activeCartLabel = "Cart 1",
                    onAddScannedItemsAndOpenCart = {}, onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("Scan an order list or bill").assertIsDisplayed()
        compose.onNodeWithText("Scan with camera").assertExists()
        compose.onNodeWithText("Pick a photo").assertExists()
        compose.onNodeWithText("Read text and fill Cart 1").assertExists()
        compose.onNodeWithText("No AI file").assertExists()
    }

    // ---- Add / edit item ----

    private fun addSheet(
        mode: ItemEditorMode,
        duplicate: DuplicateCheckResult? = null,
        onSwitch: () -> Unit = {},
        onSave: () -> Unit = {},
        unlocked: Boolean = false,
    ) {
        compose.setContent {
            ShopLedgerTheme {
                AddEditItemSheet(
                    mode = mode,
                    isSellerUnlocked = unlocked,
                    onCheckDuplicate = { _, _, _, _, _ -> duplicate ?: noDuplicate() },
                    onSaveNewItemWithVariants = { _, _, _ -> onSave() },
                    onSaveVariantToParent = { _, _, _, _, _, _, _, _, _, _, _, _ -> },
                    onUpdateExisting = { _, _, _, _, _, _, _, _, _, _, _, _, _ -> },
                    onDeleteItemOrVariant = { _, _, _ -> },
                    onSwitchToManageItem = { _, _, _, _, _, _ -> onSwitch() },
                    onDismiss = {},
                )
            }
        }
    }

    private fun signature(name: String) = SemanticItemSignature(name, emptySet(), emptySet(), "", "", "", name)

    private fun noDuplicate() = DuplicateCheckResult(DuplicateDecisionType.UNIQUE_NEW_ITEM, candidateSignature = signature(""))

    @Test fun addItemSheetShowsFormAndRejectsBlankName() {
        var saved = 0
        addSheet(ItemEditorMode.AddNewItem, onSave = { saved++ })
        compose.onNodeWithText("Add item").assertIsDisplayed()
        compose.onNodeWithText("Item name").assertIsDisplayed()
        compose.onNodeWithText("Save draft and close").assertExists()
        click("Save item")
        assertEquals(0, saved)
        compose.onAllNodesWithText("Enter the item name.")[0].assertExists()
    }

    @Test fun addItemSheetOffersEditExistingForExactDuplicate() {
        var switched = 0
        val existing = InventoryItemWithVariants(sellItem, listOf(sellVariant))
        val dup = DuplicateCheckResult(
            DuplicateDecisionType.EXACT_DUPLICATE, matchedItem = existing, matchedVariantId = "v1",
            candidateSignature = signature("Hinge"),
        )
        addSheet(ItemEditorMode.AddNewItem, duplicate = dup, onSwitch = { switched++ })
        compose.onAllNodes(androidx.compose.ui.test.hasSetTextAction())[0]
            .performSemanticsAction(SemanticsActions.SetText) { it(androidx.compose.ui.text.AnnotatedString("Hinge")) }
        compose.waitForIdle()
        compose.onNodeWithText("This item already exists").assertIsDisplayed()
        click("Edit existing item instead")
        assertTrue(switched == 1)
    }

    @Test fun editItemSheetShowsDeleteAndVariantChips() {
        val existing = InventoryItemWithVariants(sellItem, listOf(sellVariant))
        addSheet(ItemEditorMode.ManageItem(existing))
        compose.onNodeWithText("Edit item and its sizes").assertIsDisplayed()
        compose.onNodeWithText("Delete").assertIsDisplayed()
        compose.onNodeWithText("Save changes").assertExists()
        click("Delete")
        compose.onNodeWithText("Delete this item?").assertIsDisplayed()
    }

    @Test fun editorHidesCostAndProfitWhileSellerModeIsLocked() {
        val existing = InventoryItemWithVariants(sellItem, listOf(sellVariant))
        addSheet(ItemEditorMode.ManageItem(existing))
        compose.onAllNodesWithText("Cost:", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("profit", substring = true, ignoreCase = true).assertCountEquals(0)
    }

    @Test fun editorShowsCostWhileSellerModeIsUnlocked() {
        val existing = InventoryItemWithVariants(sellItem, listOf(sellVariant))
        addSheet(ItemEditorMode.ManageItem(existing), unlocked = true)
        compose.onNode(hasText("Cost:", substring = true)).assertExists()
    }
}
