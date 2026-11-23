package com.shopledger.pos.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shopledger.pos.data.local.entity.PartyEntity
import com.shopledger.pos.ui.screens.khata.KhataTab
import com.shopledger.pos.ui.theme.ShopLedgerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@Config(qualifiers = "w360dp-h800dp")
@RunWith(AndroidJUnit4::class)
class KhataTabTest {
    @get:Rule
    val compose = createComposeRule()

    private val customerA = PartyEntity(id = "A", partyType = "CUSTOMER", name = "Amit Customer", currentBalancePaise = 50_000L)
    private val supplierS = PartyEntity(id = "S", partyType = "SUPPLIER", name = "Sharma Wholesalers", currentBalancePaise = -90_000L)

    @Test
    fun customersSideHidesDistributors() {
        compose.setContent {
            ShopLedgerTheme {
                KhataTab(
                    parties = listOf(customerA, supplierS),
                    ledgerEntries = emptyList(),
                    purchaseBills = emptyList(),
                    isSellerUnlocked = true,
                    onRequestUnlock = {},
                    onAddParty = { _, _, _, _ -> },
                    onRecordPaymentReceived = { _, _, _, _ -> },
                    onAddDistributor = { _, _, _, _ -> },
                    onPayDistributor = { _, _, _, _ -> },
                    onOpenDistributor = {},
                )
            }
        }
        compose.onNodeWithText("Amit Customer").assertIsDisplayed()
        compose.onNodeWithText("Sharma Wholesalers").assertDoesNotExist()
    }

    @Test
    fun distributorsSideNeedsUnlock() {
        var unlockRequests = 0
        compose.setContent {
            ShopLedgerTheme {
                KhataTab(
                    parties = listOf(customerA, supplierS),
                    ledgerEntries = emptyList(),
                    purchaseBills = emptyList(),
                    isSellerUnlocked = false,
                    onRequestUnlock = { unlockRequests++ },
                    onAddParty = { _, _, _, _ -> },
                    onRecordPaymentReceived = { _, _, _, _ -> },
                    onAddDistributor = { _, _, _, _ -> },
                    onPayDistributor = { _, _, _, _ -> },
                    onOpenDistributor = {},
                )
            }
        }
        compose.onNode(hasText("Distributors") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText("Sharma Wholesalers").assertDoesNotExist()
        compose.onNodeWithText("Distributor accounts are locked").assertIsDisplayed()
        compose.onNode(hasText("Unlock") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, unlockRequests)
    }
}
