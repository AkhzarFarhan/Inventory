package com.shopledger.pos.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shopledger.pos.data.command.LedgerEntryType
import com.shopledger.pos.data.local.entity.PartyEntity
import com.shopledger.pos.data.local.entity.PartyTransactionEntity
import com.shopledger.pos.ui.screens.khata.PartiesKhataScreen
import com.shopledger.pos.ui.screens.khata.computeDaysOverdue
import com.shopledger.pos.ui.theme.ShopLedgerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@Config(qualifiers = "mdpi")
@RunWith(AndroidJUnit4::class)
class KhataInsightsTest {
    @get:Rule
    val compose = createComposeRule()

    private val dayMs = 86_400_000L

    @Test
    fun computeDaysOverdueTracksCrossingSettlingAndFallback() {
        val now = 1_700_000_000_000L + 30 * dayMs
        val settled = PartyEntity(id = "p0", name = "Settled", currentBalancePaise = 0L, updatedAt = now - 5 * dayMs)
        assertNull(computeDaysOverdue(settled, emptyList(), now))

        val due12 = PartyEntity(id = "p1", name = "A", currentBalancePaise = 25_000L, updatedAt = now - dayMs)
        val e1 = listOf(
            PartyTransactionEntity(
                entryId = "1", partyId = "p1", entryType = LedgerEntryType.CREDIT_BILL,
                amountDeltaPaise = 25_000L, createdAt = now - 12 * dayMs,
            ),
        )
        assertEquals(12, computeDaysOverdue(due12, e1, now))

        val reborrowed = PartyEntity(id = "p2", name = "B", currentBalancePaise = 5_000L, updatedAt = now - dayMs)
        val e2 = listOf(
            PartyTransactionEntity(
                entryId = "a", partyId = "p2", entryType = LedgerEntryType.CREDIT_BILL,
                amountDeltaPaise = 10_000L, createdAt = now - 20 * dayMs,
            ),
            PartyTransactionEntity(
                entryId = "b", partyId = "p2", entryType = LedgerEntryType.PAYMENT_IN,
                amountDeltaPaise = -10_000L, createdAt = now - 10 * dayMs,
            ),
            PartyTransactionEntity(
                entryId = "c", partyId = "p2", entryType = LedgerEntryType.CREDIT_BILL,
                amountDeltaPaise = 5_000L, createdAt = now - 3 * dayMs,
            ),
        )
        assertEquals(3, computeDaysOverdue(reborrowed, e2, now))

        val fallback = PartyEntity(id = "p3", name = "C", currentBalancePaise = 9_000L, updatedAt = now - 7 * dayMs)
        assertEquals(7, computeDaysOverdue(fallback, emptyList(), now))
    }

    @Test
    fun partiesKhataScreenShowsOverdueBadgeSortToggleAndStatementDialog() {
        val now = System.currentTimeMillis()
        val oldDue = PartyEntity(
            id = "p1",
            name = "Ramesh Traders",
            phone = "9876543210",
            currentBalancePaise = 15_000L,
            updatedAt = now - 18 * dayMs,
        )
        val biggerNewDue = PartyEntity(
            id = "p2",
            name = "Suresh Store",
            phone = "9876543211",
            currentBalancePaise = 50_000L,
            updatedAt = now - 2 * dayMs,
        )
        val entries = listOf(
            PartyTransactionEntity(
                entryId = "e1",
                partyId = "p1",
                entryType = LedgerEntryType.CREDIT_BILL,
                amountDeltaPaise = 15_000L,
                note = "Bill R-1",
                createdAt = now - 18 * dayMs,
            ),
            PartyTransactionEntity(
                entryId = "e2",
                partyId = "p2",
                entryType = LedgerEntryType.CREDIT_BILL,
                amountDeltaPaise = 50_000L,
                createdAt = now - 2 * dayMs,
            ),
        )

        compose.setContent {
            ShopLedgerTheme {
                PartiesKhataScreen(
                    parties = listOf(oldDue, biggerNewDue),
                    onAddParty = { _, _, _, _ -> },
                    onRecordPaymentReceived = { _, _, _, _ -> },
                    ledgerEntries = entries,
                )
            }
        }

        compose.onNodeWithText("Sort: Highest due").assertIsDisplayed().performClick()
        compose.onNodeWithText("Sort: Oldest due").assertIsDisplayed()
        compose.onNodeWithText("Due · 18 days").assertIsDisplayed()

        compose.onAllNodesWithContentDescription("Statement")[0]
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Khata statement · Ramesh Traders").assertIsDisplayed()
        compose.onNodeWithText("Send statement picture on WhatsApp").assertIsDisplayed()
    }
}
