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
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shopledger.pos.data.local.entity.InventoryItemEntity
import com.shopledger.pos.data.local.entity.ItemVariantEntity
import com.shopledger.pos.data.local.entity.SearchHistoryEntity
import com.shopledger.pos.core.cipher.CipherMode
import com.shopledger.pos.core.cipher.CostCipherEngine
import org.junit.After
import com.shopledger.pos.data.model.InventoryItemWithVariants
import com.shopledger.pos.ui.screens.cart.CartChip
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
        onStockIn: () -> Unit = {},
        unlocked: Boolean = false,
        onOpen: (String) -> Unit = {},
        onCart: (String) -> Unit = {},
        recent: List<SearchHistoryEntity> = emptyList(),
        onDeleteRecent: (Long) -> Unit = {},
        carts: List<CartChip> = emptyList(),
        activeCartId: String = "",
        onSwitchCart: (String) -> Unit = {},
        onNewCart: () -> Unit = {},
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
                    isSellerUnlocked = unlocked,
                    onToggleSeller = {},
                    carts = carts,
                    activeCartId = activeCartId,
                    onSwitchCart = onSwitchCart,
                    onNewCart = onNewCart,
                    onSell = onSell,
                    onStockIn = onStockIn,
                    onOpenItem = { _, v, _ -> onOpen(v.id) },
                    onEditItem = { _, _ -> },
                    onAddToCart = { _, v -> onCart(v.id) },
                    recentSearches = recent,
                    onDeleteRecentSearch = onDeleteRecent,
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
    fun stockInButtonAsksTheHostToOpenTheSheet() {
        var opened = 0
        show(onStockIn = { opened++ })
        compose.onNodeWithText("Stock In").performClick()
        compose.waitForIdle()
        assertEquals(1, opened)
        // The sheet lives in the app shell, not in this screen.
        compose.onNodeWithText("Add item manually").assertDoesNotExist()
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

    @After
    fun restoreCipher() {
        CostCipherEngine.setActiveConfig(CipherMode.CUSTOM_CODE)
    }

    private fun costItem(): InventoryItemWithVariants = InventoryItemWithVariants(
        item = InventoryItemEntity(id = "c1", displayName = "Cost Item", sortKey = "COST ITEM"),
        allVariants = listOf(
            ItemVariantEntity(
                id = "c1-v", itemId = "c1", purchasePricePaise = 4_321L, sellingPricePaise = 5_000L,
                stockMilliUnits = 20_000L, lowStockMilliUnits = 5_000L,
            ),
        ),
    )

    @Test
    fun numericCostHiddenWhenLockedAndShownWhenUnlocked() {
        CostCipherEngine.setActiveConfig(CipherMode.HIDDEN_PIN)
        show(items = listOf(costItem()), unlocked = false)
        compose.onNodeWithText("43.21", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Cost Item").assertIsDisplayed()
    }

    @Test
    fun numericCostShownWhenUnlocked() {
        CostCipherEngine.setActiveConfig(CipherMode.HIDDEN_PIN)
        show(items = listOf(costItem()), unlocked = true)
        compose.onNodeWithText("43.21", substring = true).assertIsDisplayed()
    }

    @Test
    fun customCodeShownWhenLockedAndWhenUnlocked() {
        CostCipherEngine.setActiveConfig(CipherMode.CUSTOM_CODE)
        val coded = CostCipherEngine.encodePaiseToCustomCode(4_321L)
        show(items = listOf(costItem()), unlocked = false)
        compose.onNodeWithText(coded, substring = true).assertIsDisplayed()
        compose.onNodeWithText("43.21", substring = true).assertDoesNotExist()
    }

    @Test
    fun customCodeShownAlongsideNumericWhenUnlocked() {
        CostCipherEngine.setActiveConfig(CipherMode.CUSTOM_CODE)
        val coded = CostCipherEngine.encodePaiseToCustomCode(4_321L)
        show(items = listOf(costItem()), unlocked = true)
        compose.onNodeWithText(coded, substring = true).assertIsDisplayed()
        compose.onNodeWithText("43.21", substring = true).assertIsDisplayed()
    }

    private fun multiVariantItem(): InventoryItemWithVariants = InventoryItemWithVariants(
        item = InventoryItemEntity(id = "m1", displayName = "Multi Item", sortKey = "MULTI ITEM"),
        allVariants = listOf(
            ItemVariantEntity(
                id = "m1-a", itemId = "m1", sizeLabel = "A", purchasePricePaise = 4_000L, sellingPricePaise = 5_000L,
                stockMilliUnits = 20_000L, lowStockMilliUnits = 5_000L,
            ),
            ItemVariantEntity(
                id = "m1-b", itemId = "m1", sizeLabel = "B", purchasePricePaise = 6_000L, sellingPricePaise = 7_000L,
                stockMilliUnits = 20_000L, lowStockMilliUnits = 5_000L,
            ),
        ),
    )

    @Test
    fun cartTapOnMultiVariantOpensItemSheetInsteadOfAdding() {
        val opened = mutableListOf<String>()
        val added = mutableListOf<String>()
        show(items = listOf(multiVariantItem()), onOpen = { opened += it }, onCart = { added += it })
        compose.onNodeWithContentDescription("Add to cart").performClick()
        assertEquals(1, opened.size)
        assertEquals(0, added.size)
    }

    @Test
    fun cartTapOnSingleVariantAddsDirectly() {
        val opened = mutableListOf<String>()
        val added = mutableListOf<String>()
        show(items = listOf(item("s1", "Single", 20_000L)), onOpen = { opened += it }, onCart = { added += it })
        compose.onNodeWithContentDescription("Add to cart").performClick()
        assertEquals(listOf("s1-v"), added)
        assertEquals(0, opened.size)
    }

    @Test
    fun deletingARecentSearchInvokesCallbackWithItsId() {
        val deleted = mutableListOf<Long>()
        val recent = listOf(
            SearchHistoryEntity(id = 7L, rawQuery = "Nuts", normalizedQuery = "nuts"),
            SearchHistoryEntity(id = 9L, rawQuery = "Screws", normalizedQuery = "screws"),
        )
        show(recent = recent, onDeleteRecent = { deleted += it })
        compose.onNodeWithText("Search items or scan a barcode").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Remove \"Screws\" from recent searches").performClick()
        assertEquals(listOf(9L), deleted)
    }

    @Test
    fun cartSwitcherHiddenWithOneEmptyCart() {
        show(
            carts = listOf(CartChip("c1", "Cart 1", 0)),
            activeCartId = "c1",
        )
        compose.onNodeWithText("New cart").assertDoesNotExist()
        compose.onNodeWithText("Sell").assertIsDisplayed()
    }

    @Test
    fun cartSwitcherShownWhenActiveCartHasItems() {
        show(
            carts = listOf(CartChip("c1", "Cart 1", 2)),
            activeCartId = "c1",
        )
        compose.onNodeWithText("Cart 1 · 2 items").assertIsDisplayed()
        compose.onNodeWithText("New cart").assertIsDisplayed()
    }

    @Test
    fun cartSwitcherShownWithTwoCarts() {
        show(
            carts = listOf(CartChip("c1", "Cart 1", 0), CartChip("c2", "Cart 2", 0)),
            activeCartId = "c1",
        )
        compose.onNodeWithText("Cart 1 · 0 items").assertIsDisplayed()
        compose.onNodeWithText("Cart 2 · 0 items").assertIsDisplayed()
    }

    @Test
    fun sellButtonNamesActiveCartAndCountsOnlyIt() {
        show(
            carts = listOf(CartChip("c1", "Cart 1", 3), CartChip("c2", "Cart 2", 1)),
            activeCartId = "c2",
        )
        compose.onNodeWithText("Sell · Cart 2").assertIsDisplayed()
        compose.onNodeWithContentDescription("1 items in cart").assertIsDisplayed()
        compose.onNodeWithContentDescription("4 items in cart").assertDoesNotExist()
    }
}
