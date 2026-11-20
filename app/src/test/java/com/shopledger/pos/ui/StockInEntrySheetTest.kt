package com.shopledger.pos.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shopledger.pos.ui.screens.items.ItemsHomeScreen
import com.shopledger.pos.ui.screens.stockin.StockInEntrySheet
import com.shopledger.pos.ui.theme.ShopLedgerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StockInEntrySheetTest {
    @get:Rule
    val compose = createComposeRule()

    private class Counts {
        var unlock = 0
        var manual = 0
        var dismissed = 0
        var scanned = 0
    }

    private fun show(unlocked: Boolean, visible: Boolean = true, counts: Counts = Counts()): Counts {
        compose.setContent {
            ShopLedgerTheme {
                StockInEntrySheet(
                    visible = visible,
                    isSellerUnlocked = unlocked,
                    onRequestUnlock = { counts.unlock++ },
                    onAddManually = { counts.manual++ },
                    onDismiss = { counts.dismissed++ },
                    onScanned = { _, _, _ -> counts.scanned++ },
                )
            }
        }
        return counts
    }

    private fun click(text: String) {
        compose.waitForIdle()
        compose.onNode(hasText(text) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    @Test fun lockedSellerModeAsksToUnlockInsteadOfScanning() {
        val c = show(unlocked = false)
        compose.onNodeWithText("Unlock seller mode to scan a bill or list.").assertIsDisplayed()
        click("Scan bill or list")
        assertEquals(1, c.unlock)
        click("Pick a photo")
        assertEquals(2, c.unlock)
        assertEquals(0, c.scanned)
    }

    @Test fun addingManuallyDoesNotNeedSellerMode() {
        val c = show(unlocked = false)
        click("Add item manually")
        assertEquals(1, c.manual)
        assertEquals(0, c.unlock)
    }

    @Test fun nothingIsShownWhileHidden() {
        show(unlocked = true, visible = false)
        compose.onNodeWithText("Scan bill or list").assertDoesNotExist()
    }

    @Test fun theSheetIsNotReopenedByTheItemsScreenComingBack() {
        // The Items screen only reports the tap; it never owns the sheet, so recomposing it cannot reopen the sheet.
        var tab by mutableStateOf(true)
        var sheetVisible by mutableStateOf(false)
        compose.setContent {
            ShopLedgerTheme {
                if (tab) {
                    ItemsHomeScreen(
                        shopName = "Shop",
                        items = emptyList(),
                        searchQuery = "",
                        onSearchQueryChange = {},
                        lowStockOnly = false,
                        onLowStockOnlyChange = {},
                        isSellerUnlocked = true,
                        onToggleSeller = {},
                        cartLineCount = 0,
                        onSell = {},
                        onOpenItem = { _, _, _ -> },
                        onEditItem = { _, _ -> },
                        onAddToCart = { _, _ -> },
                        onStockIn = { sheetVisible = true },
                    )
                }
                StockInEntrySheet(
                    visible = sheetVisible,
                    isSellerUnlocked = true,
                    onRequestUnlock = {},
                    onAddManually = { sheetVisible = false },
                    onDismiss = { sheetVisible = false },
                )
            }
        }
        compose.onNodeWithText("Stock In").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText("Scan bill or list").assertIsDisplayed()
        click("Add item manually")
        compose.onNodeWithText("Scan bill or list").assertDoesNotExist()
        // Leave the Items tab and come back.
        tab = false
        compose.waitForIdle()
        tab = true
        compose.waitForIdle()
        compose.onNodeWithText("Scan bill or list").assertDoesNotExist()
    }
}
