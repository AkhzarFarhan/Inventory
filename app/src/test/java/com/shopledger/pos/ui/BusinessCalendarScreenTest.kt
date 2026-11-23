package com.shopledger.pos.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shopledger.pos.data.command.DocType
import com.shopledger.pos.data.local.entity.InvoiceHeaderEntity
import com.shopledger.pos.data.local.entity.PurchaseBillEntity
import com.shopledger.pos.ui.screens.sales.BillsTab
import com.shopledger.pos.ui.screens.sales.calendar.BusinessCalendarScreen
import com.shopledger.pos.ui.theme.ShopLedgerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Calendar

@Config(qualifiers = "w360dp-h800dp")
@RunWith(AndroidJUnit4::class)
class BusinessCalendarScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun epoch(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, 11, 0, 0)
        }.timeInMillis

    private fun saleInvoice(
        id: String,
        day: Int,
        mode: String,
        grand: Long,
        paid: Long = grand,
        due: Long = grand - paid,
    ) = InvoiceHeaderEntity(
        id = id,
        invoiceNumber = "INV-$id",
        docType = DocType.SALE,
        paymentMode = mode,
        subtotalPaise = grand,
        grandTotalPaise = grand,
        amountPaidPaise = paid,
        balanceDuePaise = due,
        issuedAt = epoch(2026, 11, day),
        yearInt = 2026,
        monthInt = 11,
        dayInt = day,
    )

    private fun purchaseBill(id: String, day: Int, total: Long) = PurchaseBillEntity(
        id = id,
        supplierPartyId = "s1",
        supplierNameSnapshot = "Gupta Traders",
        billNumber = "GT-9",
        totalPaise = total,
        linesTotalPaise = total,
        paidPaise = 0L,
        duePaise = total,
        paymentMode = "CREDIT",
        lineCount = 2,
        createdAt = epoch(2026, 11, day),
        yearInt = 2026,
        monthInt = 11,
        dayInt = day,
    )

    @Test
    fun gridShowsSaleAmountOnDay() {
        val invoices = listOf(saleInvoice("1", day = 10, mode = "CASH", grand = 1_240_000L))
        compose.setContent {
            ShopLedgerTheme {
                BusinessCalendarScreen(
                    invoices = invoices,
                    ledgerEntries = emptyList(),
                    purchaseBills = emptyList(),
                    isSellerUnlocked = false,
                    onOpenPurchaseBill = {},
                    initialYear = 2026,
                    initialMonth = 11,
                )
            }
        }
        compose.onNodeWithText("₹12.4k").assertIsDisplayed()
    }

    @Test
    fun tappingDayOpensDetailsWithBreakdown() {
        val invoices = listOf(
            saleInvoice("1", day = 10, mode = "CASH", grand = 50_000L),
            saleInvoice("2", day = 10, mode = "UPI", grand = 30_000L),
            saleInvoice("3", day = 10, mode = "CREDIT", grand = 20_000L, paid = 0L, due = 20_000L),
        )
        compose.setContent {
            ShopLedgerTheme {
                BusinessCalendarScreen(
                    invoices = invoices,
                    ledgerEntries = emptyList(),
                    purchaseBills = emptyList(),
                    isSellerUnlocked = false,
                    onOpenPurchaseBill = {},
                    initialYear = 2026,
                    initialMonth = 11,
                )
            }
        }
        compose.onNodeWithTag("calendar_day_10").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText("Sales").assertIsDisplayed()
        compose.onNodeWithText("3 bills").assertIsDisplayed()
        compose.onAllNodesWithText("₹500").onFirst().assertExists()
        compose.onAllNodesWithText("₹300").onFirst().assertExists()
        compose.onAllNodesWithText("₹200").onFirst().assertExists()
    }

    @Test
    fun stockInHiddenWhenLocked() {
        val bills = listOf(purchaseBill("b1", day = 10, total = 80_000L))
        compose.setContent {
            ShopLedgerTheme {
                BusinessCalendarScreen(
                    invoices = emptyList(),
                    ledgerEntries = emptyList(),
                    purchaseBills = bills,
                    isSellerUnlocked = false,
                    onOpenPurchaseBill = {},
                    initialYear = 2026,
                    initialMonth = 11,
                )
            }
        }
        compose.onNodeWithText("Stock-in").assertDoesNotExist()
        compose.onNodeWithText("Unlock seller mode to see stock-in and distributor payments.").assertIsDisplayed()
    }

    @Test
    fun stockInShownWhenUnlocked() {
        val bills = listOf(purchaseBill("b1", day = 10, total = 80_000L))
        compose.setContent {
            ShopLedgerTheme {
                BusinessCalendarScreen(
                    invoices = emptyList(),
                    ledgerEntries = emptyList(),
                    purchaseBills = bills,
                    isSellerUnlocked = true,
                    onOpenPurchaseBill = {},
                    initialYear = 2026,
                    initialMonth = 11,
                )
            }
        }
        compose.onAllNodesWithText("Stock-in").onFirst().assertExists()
        compose.onAllNodesWithText("₹800").onFirst().assertExists()
    }

    @Test
    fun prevMonthButtonChangesTitle() {
        compose.setContent {
            ShopLedgerTheme {
                BusinessCalendarScreen(
                    invoices = emptyList(),
                    ledgerEntries = emptyList(),
                    purchaseBills = emptyList(),
                    isSellerUnlocked = false,
                    onOpenPurchaseBill = {},
                    initialYear = 2026,
                    initialMonth = 11,
                )
            }
        }
        compose.onNodeWithText("November 2026").assertIsDisplayed()
        compose.onNodeWithContentDescription("Previous month").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText("October 2026").assertIsDisplayed()
    }

    @Test
    fun emptyDayShowsEmptyState() {
        compose.setContent {
            ShopLedgerTheme {
                BusinessCalendarScreen(
                    invoices = emptyList(),
                    ledgerEntries = emptyList(),
                    purchaseBills = emptyList(),
                    isSellerUnlocked = false,
                    onOpenPurchaseBill = {},
                    initialYear = 2026,
                    initialMonth = 11,
                )
            }
        }
        compose.onNodeWithTag("calendar_day_15").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText("Nothing on this day").assertIsDisplayed()
    }

    @Test
    fun cellsAreAtLeast48dpTall() {
        compose.setContent {
            ShopLedgerTheme {
                BusinessCalendarScreen(
                    invoices = emptyList(),
                    ledgerEntries = emptyList(),
                    purchaseBills = emptyList(),
                    isSellerUnlocked = false,
                    onOpenPurchaseBill = {},
                    initialYear = 2026,
                    initialMonth = 11,
                )
            }
        }
        compose.onNodeWithTag("calendar_day_10").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun billsTabSwitchesBetweenListAndCalendar() {
        compose.setContent {
            ShopLedgerTheme {
                BillsTab(
                    sales = emptyList(),
                    isSellerUnlocked = false,
                    onToggleSellerUnlocked = {},
                    onOpenReceiptShare = {},
                    onReturnSale = { _, _, _ -> },
                    purchaseBills = emptyList(),
                    onOpenPurchaseBill = {},
                )
            }
        }
        compose.onNodeWithText("No bills in this period").assertIsDisplayed()
        compose.onNode(hasText("Calendar") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText("This month").assertIsDisplayed()
        compose.onNode(hasText("List") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText("No bills in this period").assertIsDisplayed()
    }
}
