package com.shopledger.pos.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shopledger.pos.data.local.entity.PartyEntity
import com.shopledger.pos.ui.screens.khata.DistributorsScreen
import com.shopledger.pos.ui.theme.ShopLedgerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@Config(qualifiers = "w360dp-h800dp")
@RunWith(AndroidJUnit4::class)
class DistributorsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val supplierS = PartyEntity(
        id = "S",
        partyType = "SUPPLIER",
        name = "Sharma Wholesalers",
        phone = "9876543210",
        currentBalancePaise = -90_000L,
    )

    @Test
    fun distributorCardShowsYouOwe() {
        compose.setContent {
            ShopLedgerTheme {
                DistributorsScreen(
                    distributors = listOf(supplierS),
                    purchaseBills = emptyList(),
                    onAddDistributor = { _, _, _, _ -> },
                    onPayDistributor = { _, _, _, _ -> },
                    onOpenDistributor = {},
                )
            }
        }
        compose.onNodeWithText("You owe ₹900").assertIsDisplayed()
    }

    @Config(qualifiers = "mdpi")
    @Test
    fun payDialogPrefillsOwedAndSendsUpi() {
        var paid: List<Any>? = null
        compose.setContent {
            ShopLedgerTheme {
                DistributorsScreen(
                    distributors = listOf(supplierS),
                    purchaseBills = emptyList(),
                    onAddDistributor = { _, _, _, _ -> },
                    onPayDistributor = { id, amt, mode, note -> paid = listOf(id, amt, mode, note) },
                    onOpenDistributor = {},
                )
            }
        }
        compose.onNode(hasText("Pay") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNode(hasText("900") and hasSetTextAction()).assertExists()
        compose.onNode(hasText("UPI") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNode(hasText("Save payment") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(listOf("S", 90_000L, "UPI", ""), paid)
    }

    @Config(qualifiers = "mdpi")
    @Test
    fun addDistributorSendsOweAmount() {
        var added: List<Any>? = null
        compose.setContent {
            ShopLedgerTheme {
                DistributorsScreen(
                    distributors = emptyList(),
                    purchaseBills = emptyList(),
                    onAddDistributor = { name, phone, gstin, owe -> added = listOf(name, phone, gstin, owe) },
                    onPayDistributor = { _, _, _, _ -> },
                    onOpenDistributor = {},
                )
            }
        }
        compose.onNode(hasText("Add distributor") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNode(hasText("Distributor name") and hasSetTextAction())
            .performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("Gupta Traders")) }
        compose.onNode(hasText("Amount you already owe (₹)") and hasSetTextAction())
            .performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("1500")) }
        compose.waitForIdle()
        compose.onNode(hasText("Save party") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(listOf("Gupta Traders", "", "", 150_000L), added)
    }

    @Test
    fun tappingCardOpensDistributor() {
        var opened: String? = null
        compose.setContent {
            ShopLedgerTheme {
                DistributorsScreen(
                    distributors = listOf(supplierS),
                    purchaseBills = emptyList(),
                    onAddDistributor = { _, _, _, _ -> },
                    onPayDistributor = { _, _, _, _ -> },
                    onOpenDistributor = { opened = it },
                )
            }
        }
        compose.onNodeWithText("Sharma Wholesalers").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("S", opened)
    }

    @Test
    fun emptyStateWhenNoDistributors() {
        compose.setContent {
            ShopLedgerTheme {
                DistributorsScreen(
                    distributors = emptyList(),
                    purchaseBills = emptyList(),
                    onAddDistributor = { _, _, _, _ -> },
                    onPayDistributor = { _, _, _, _ -> },
                    onOpenDistributor = {},
                )
            }
        }
        compose.onNodeWithText("No distributors yet").assertIsDisplayed()
    }
}
