package com.shopledger.pos.ui

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shopledger.pos.core.nlp.DuplicateCheckResult
import com.shopledger.pos.core.nlp.DuplicateDecisionType
import com.shopledger.pos.core.nlp.SemanticItemSignature
import com.shopledger.pos.data.local.dao.LastPurchaseInfo
import com.shopledger.pos.data.local.entity.InventoryItemEntity
import com.shopledger.pos.data.local.entity.ItemVariantEntity
import com.shopledger.pos.data.model.InventoryItemWithVariants
import com.shopledger.pos.ui.screens.inventory.AddEditItemSheet
import com.shopledger.pos.ui.screens.inventory.ItemEditorMode
import com.shopledger.pos.ui.theme.ShopLedgerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@Config(qualifiers = "mdpi")
@RunWith(AndroidJUnit4::class)
class AddEditItemLastPurchaseTest {
    @get:Rule
    val compose = createComposeRule()

    private val itemGroup = InventoryItemWithVariants(
        item = InventoryItemEntity(id = "i1", displayName = "Butt Hinge", sortKey = "BUTT HINGE"),
        allVariants = listOf(
            ItemVariantEntity(
                id = "v1",
                itemId = "i1",
                sizeLabel = "4 inch",
                purchasePricePaise = 4_500L,
                sellingPricePaise = 6_000L,
                stockMilliUnits = 10_000L,
            )
        ),
    )

    private fun noDup() = DuplicateCheckResult(
        decision = DuplicateDecisionType.UNIQUE_NEW_ITEM,
        candidateSignature = SemanticItemSignature("", emptySet(), emptySet(), "", "", "", ""),
    )

    @Test
    fun manageModeShowsLastBoughtFromDistributorAndCost() {
        compose.setContent {
            ShopLedgerTheme {
                AddEditItemSheet(
                    mode = ItemEditorMode.ManageItem(itemGroup, initialVariantId = "v1"),
                    isSellerUnlocked = true,
                    onCheckDuplicate = { _, _, _, _, _ -> noDup() },
                    onSaveNewItemWithVariants = { _, _, _ -> },
                    onSaveVariantToParent = { _, _, _, _, _, _, _, _, _, _, _, _ -> },
                    onUpdateExisting = { _, _, _, _, _, _, _, _, _, _, _, _, _ -> },
                    onDeleteItemOrVariant = { _, _, _ -> },
                    onSwitchToManageItem = { _, _, _, _, _, _ -> },
                    onDismiss = {},
                    lastPurchase = LastPurchaseInfo(
                        supplierPartyId = "s",
                        supplierNameSnapshot = "Gupta Traders",
                        unitCostPaise = 4_500L,
                        createdAt = 1_763_683_200_000L,
                        isLegacy = false,
                    ),
                )
            }
        }
        compose.waitForIdle()
        compose.onNode(hasText("Gupta Traders", substring = true) and hasText("₹45", substring = true)).assertExists()
    }

    @Test
    fun legacyLastPurchaseOmitsCost() {
        compose.setContent {
            ShopLedgerTheme {
                AddEditItemSheet(
                    mode = ItemEditorMode.ManageItem(itemGroup, initialVariantId = "v1"),
                    isSellerUnlocked = true,
                    onCheckDuplicate = { _, _, _, _, _ -> noDup() },
                    onSaveNewItemWithVariants = { _, _, _ -> },
                    onSaveVariantToParent = { _, _, _, _, _, _, _, _, _, _, _, _ -> },
                    onUpdateExisting = { _, _, _, _, _, _, _, _, _, _, _, _, _ -> },
                    onDeleteItemOrVariant = { _, _, _ -> },
                    onSwitchToManageItem = { _, _, _, _, _, _ -> },
                    onDismiss = {},
                    lastPurchase = LastPurchaseInfo(
                        supplierPartyId = "s",
                        supplierNameSnapshot = "Gupta Traders",
                        unitCostPaise = 0L,
                        createdAt = 1_763_683_200_000L,
                        isLegacy = true,
                    ),
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Gupta Traders", substring = true).assertExists()
        compose.onNodeWithText("₹0", substring = true).assertDoesNotExist()
    }
}
