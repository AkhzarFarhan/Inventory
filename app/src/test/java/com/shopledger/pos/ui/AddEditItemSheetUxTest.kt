package com.shopledger.pos.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shopledger.pos.core.nlp.DuplicateCheckResult
import com.shopledger.pos.core.nlp.DuplicateDecisionType
import com.shopledger.pos.core.nlp.SemanticItemSignature
import com.shopledger.pos.ui.screens.inventory.AddEditItemSheet
import com.shopledger.pos.ui.screens.inventory.ItemEditorMode
import com.shopledger.pos.ui.theme.ShopLedgerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@Config(qualifiers = "mdpi")
@RunWith(AndroidJUnit4::class)
class AddEditItemSheetUxTest {
    @get:Rule
    val compose = createComposeRule()

    private fun noDup() = DuplicateCheckResult(
        decision = DuplicateDecisionType.UNIQUE_NEW_ITEM,
        candidateSignature = SemanticItemSignature("", emptySet(), emptySet(), "", "", "", ""),
    )

    private fun launchAddSheet(isSellerUnlocked: Boolean) {
        compose.setContent {
            ShopLedgerTheme {
                AddEditItemSheet(
                    mode = ItemEditorMode.AddNewItem,
                    isSellerUnlocked = isSellerUnlocked,
                    onCheckDuplicate = { _, _, _, _, _ -> noDup() },
                    onSaveNewItemWithVariants = { _, _, _ -> },
                    onSaveVariantToParent = { _, _, _, _, _, _, _, _, _, _, _, _ -> },
                    onUpdateExisting = { _, _, _, _, _, _, _, _, _, _, _, _, _ -> },
                    onDeleteItemOrVariant = { _, _, _ -> },
                    onSwitchToManageItem = { _, _, _, _, _, _ -> },
                    onDismiss = {},
                )
            }
        }
    }

    @Test
    fun addNewItemStartsInSimpleModeAndExpandsExtraDetails() {
        launchAddSheet(isSellerUnlocked = false)
        compose.onNodeWithText("Add sizes, unit or barcode").assertExists()
        compose.onNodeWithText("Selling price (₹)").assertExists()
        compose.onAllNodesWithText("Barcode or SKU code (optional)").assertCountEquals(0)

        compose.onNode(hasText("Add sizes, unit or barcode") and hasClickAction())
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()

        compose.onNodeWithText("Barcode or SKU code (optional)").assertExists()
        compose.onNodeWithText("Hide extra details").assertExists()
    }

    @Test
    fun liveCounterPreviewRespectsSellerLock() {
        launchAddSheet(isSellerUnlocked = false)
        compose.onAllNodesWithText("Counter preview").assertCountEquals(0)

        compose.onAllNodes(hasSetTextAction())[0]
            .performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("White Cement")) }
        compose.onNodeWithText("Cost price (₹)")
            .performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("140")) }
        compose.onNodeWithText("Selling price (₹)")
            .performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("210")) }
        compose.waitForIdle()

        compose.onNodeWithText("Counter preview").assertExists()
        compose.onNodeWithText("₹210").assertExists()
        compose.onAllNodesWithText("₹140", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Cost:", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("profit", substring = true, ignoreCase = true).assertCountEquals(0)
    }
}
