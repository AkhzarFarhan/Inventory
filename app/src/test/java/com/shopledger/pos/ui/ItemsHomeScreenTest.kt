package com.shopledger.pos.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shopledger.pos.data.local.entity.InventoryItemEntity
import com.shopledger.pos.data.local.entity.ItemVariantEntity
import com.shopledger.pos.data.model.InventoryItemWithVariants
import com.shopledger.pos.ui.screens.items.ItemsHomeScreen
import com.shopledger.pos.ui.theme.ShopLedgerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ItemsHomeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun item(id: String, name: String, stockMilli: Long): InventoryItemWithVariants =
        InventoryItemWithVariants(
            item = InventoryItemEntity(id = id, displayName = name, sortKey = name.uppercase()),
            allVariants = listOf(
                ItemVariantEntity(
                    id = "$id-v", itemId = id, purchasePricePaise = 4_000L, sellingPricePaise = 5_000L,
                    stockMilliUnits = stockMilli, lowStockMilliUnits = 5_000L,
                ),
            ),
        )

    private val all = listOf(item("i1", "Hinge", 20_000L), item("i2", "Bolt", -2_000L))

    private fun show(
        items: List<InventoryItemWithVariants> = all,
        onSell: () -> Unit = {},
        onManual: () -> Unit = {},
    ) {
        compose.setContent {
            ShopLedgerTheme {
                var query by remember { mutableStateOf("") }
                var low by remember { mutableStateOf(false) }
                val shown = items.filter { it.item.displayName.contains(query, ignoreCase = true) }
                ItemsHomeScreen(
                    shopName = "Test Shop",
                    items = shown,
                    searchQuery = query,
                    onSearchQueryChange = { query = it },
                    lowStockOnly = low,
                    onLowStockOnlyChange = { low = it },
                    isSellerUnlocked = false,
                    onToggleSeller = {},
                    cartLineCount = 0,
                    onSell = onSell,
                    onAddItemManually = onManual,
                    onOpenItem = { _, _, _ -> },
                    onEditItem = { _, _ -> },
                    onAddToCart = { _, _ -> },
                )
            }
        }
    }

    @Test
    fun showsItemsOversoldStatusAndBothActionButtons() {
        show()
        compose.onNodeWithText("Hinge").assertIsDisplayed()
        compose.onNodeWithText("Bolt").assertIsDisplayed()
        compose.onNodeWithText("Oversold", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Sell").assertIsDisplayed()
        compose.onNodeWithText("Stock In").assertIsDisplayed()
    }

    @Test
    fun typingInSearchFiltersTheList() {
        show()
        compose.onNodeWithText("Search items or scan a barcode").performTextInput("Hin")
        compose.onNodeWithText("Hinge").assertIsDisplayed()
        compose.onNodeWithText("Bolt").assertDoesNotExist()
    }

    @Test
    fun stockInOffersAddItemManually() {
        var manual = 0
        show(onManual = { manual++ })
        compose.onNodeWithText("Stock In").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Add item manually").assertIsDisplayed()
        compose.onNode(hasText("Add item manually") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(1, manual)
    }

    @Test
    fun sellButtonFires() {
        var sells = 0
        show(onSell = { sells++ })
        compose.onNodeWithText("Sell").performClick()
        assertEquals(1, sells)
    }

    @Test
    fun emptyListShowsFirstRunPrompt() {
        show(items = emptyList())
        compose.onNodeWithText("No items yet").assertIsDisplayed()
        compose.onNodeWithText("Tap Stock In to add your first items").assertIsDisplayed()
    }
}
