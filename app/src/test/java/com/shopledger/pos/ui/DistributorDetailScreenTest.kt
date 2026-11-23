package com.shopledger.pos.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shopledger.pos.data.command.LedgerEntryType
import com.shopledger.pos.data.local.dao.SupplierItemSummary
import com.shopledger.pos.data.local.entity.PartyEntity
import com.shopledger.pos.data.local.entity.PartyTransactionEntity
import com.shopledger.pos.data.local.entity.PurchaseBillEntity
import com.shopledger.pos.ui.screens.khata.DistributorDetailScreen
import com.shopledger.pos.ui.screens.khata.DistributorDetailState
import com.shopledger.pos.ui.screens.khata.DistributorEvent
import com.shopledger.pos.ui.theme.ShopLedgerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@Config(qualifiers = "w360dp-h800dp")
@RunWith(AndroidJUnit4::class)
class DistributorDetailScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val party = PartyEntity(
        id = "s1",
        partyType = "SUPPLIER",
        name = "Gupta Traders",
        phone = "9876543210",
        currentBalancePaise = -40_000L,
    )

    private val day1 = 1_700_000_000_000L
    private val day2 = day1 + 86_400_000L
    private val day3 = day2 + 86_400_000L

    private fun bill(
        id: String = "b1",
        billNumber: String = "GT-17",
        total: Long = 50_000L,
        paid: Long = 20_000L,
        due: Long = 30_000L,
        isLegacy: Boolean = false,
    ) = PurchaseBillEntity(
        id = id,
        supplierPartyId = "s1",
        supplierNameSnapshot = "Gupta Traders",
        billNumber = billNumber,
        totalPaise = total,
        linesTotalPaise = total,
        paidPaise = paid,
        duePaise = due,
        paymentMode = if (paid > 0L) "UPI" else "CREDIT",
        lineCount = 2,
        createdAt = day2,
        yearInt = 2026,
        monthInt = 11,
        dayInt = 20,
        isLegacy = isLegacy,
    )

    @Test
    fun accountShowsBillAndPaymentWithRunningBalance() {
        val ledger = listOf(
            PartyTransactionEntity(
                entryId = "e3", partyId = "s1", refDocId = "b1",
                entryType = LedgerEntryType.PAYMENT_OUT, amountDeltaPaise = 20_000L, paymentMode = "UPI", createdAt = day3,
            ),
            PartyTransactionEntity(
                entryId = "e2", partyId = "s1", refDocId = "b1",
                entryType = LedgerEntryType.CREDIT_BILL, amountDeltaPaise = -50_000L, paymentMode = "CREDIT", createdAt = day2,
            ),
            PartyTransactionEntity(
                entryId = "e1", partyId = "s1", refDocId = "s1",
                entryType = LedgerEntryType.OPENING, amountDeltaPaise = -10_000L, paymentMode = "CASH", createdAt = day1,
            ),
        )
        compose.setContent {
            ShopLedgerTheme {
                DistributorDetailScreen(
                    state = DistributorDetailState(party = party, ledger = ledger, bills = listOf(bill()), items = emptyList()),
                    onEvent = {},
                )
            }
        }
        compose.onNodeWithText("Udhaar ₹500").assertIsDisplayed()
        compose.onNodeWithText("Paid ₹200 · UPI").assertIsDisplayed()
        compose.onNodeWithText("Owe ₹400").assertIsDisplayed()
        compose.onNodeWithText("Owe ₹600").assertIsDisplayed()
        compose.onNodeWithText("Owe ₹100").assertIsDisplayed()
    }

    @Test
    fun billsTabShowsDueChip() {
        compose.setContent {
            ShopLedgerTheme {
                DistributorDetailScreen(
                    state = DistributorDetailState(party = party, ledger = emptyList(), bills = listOf(bill(due = 30_000L)), items = emptyList()),
                    onEvent = {},
                )
            }
        }
        compose.onNode(hasText("Bills") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText("GT-17").assertIsDisplayed()
        compose.onNodeWithText("Due ₹300").assertIsDisplayed()
    }

    @Test
    fun itemsTabListsSuppliedItems() {
        val items = listOf(
            SupplierItemSummary("v1", "i1", "Butt Hinge", "4 inch", "pcs", 10_000L, 4_800L, day2, 2),
            SupplierItemSummary("v2", "i2", "Tower Bolt", "", "pcs", 5_000L, 6_000L, day3, 1),
        )
        compose.setContent {
            ShopLedgerTheme {
                DistributorDetailScreen(
                    state = DistributorDetailState(party = party, ledger = emptyList(), bills = emptyList(), items = items),
                    onEvent = {},
                )
            }
        }
        compose.onNode(hasText("Items") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText("Butt Hinge · 4 inch").assertIsDisplayed()
        compose.onNodeWithText("Bought 10 pcs in 2 bills").assertIsDisplayed()
        compose.onNodeWithText("Tower Bolt").assertIsDisplayed()
        compose.onNodeWithText("Bought 5 pcs in 1 bills").assertIsDisplayed()
    }

    @Test
    fun legacyBillShowsCostUnknown() {
        val legacyBill = bill(id = "old1", billNumber = "", total = 15_000L, paid = 0L, due = 15_000L, isLegacy = true)
        compose.setContent {
            ShopLedgerTheme {
                DistributorDetailScreen(
                    state = DistributorDetailState(party = party, ledger = emptyList(), bills = listOf(legacyBill), items = emptyList()),
                    onEvent = {},
                )
            }
        }
        compose.onNode(hasText("Bills") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText("Older entry — cost not recorded").assertIsDisplayed()
    }

    @Test
    fun payButtonSendsPay() {
        val events = mutableListOf<DistributorEvent>()
        compose.setContent {
            ShopLedgerTheme {
                DistributorDetailScreen(
                    state = DistributorDetailState(party = party, ledger = emptyList(), bills = emptyList(), items = emptyList()),
                    onEvent = { events += it },
                )
            }
        }
        compose.onNode(hasText("Pay") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(DistributorEvent.Pay), events)
    }

    @Test
    fun tappingBillRowOpensBill() {
        val events = mutableListOf<DistributorEvent>()
        val ledger = listOf(
            PartyTransactionEntity(
                entryId = "e2", partyId = "s1", refDocId = "b1",
                entryType = LedgerEntryType.CREDIT_BILL, amountDeltaPaise = -50_000L, paymentMode = "CREDIT", createdAt = day2,
            ),
        )
        compose.setContent {
            ShopLedgerTheme {
                DistributorDetailScreen(
                    state = DistributorDetailState(party = party, ledger = ledger, bills = listOf(bill(id = "b1")), items = emptyList()),
                    onEvent = { events += it },
                )
            }
        }
        compose.onNodeWithText("Purchase bill · GT-17").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(DistributorEvent.OpenBill("b1")), events)
    }
}
