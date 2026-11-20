package com.shopledger.pos.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shopledger.pos.data.command.DocType
import com.shopledger.pos.data.command.LedgerEntryType
import com.shopledger.pos.data.local.entity.InvoiceHeaderEntity
import com.shopledger.pos.data.local.entity.PartyTransactionEntity
import com.shopledger.pos.data.local.entity.SaleRecordEntity
import com.shopledger.pos.ui.screens.sales.SaleHistoryScreen
import com.shopledger.pos.ui.screens.sales.computeGallaBreakdown
import com.shopledger.pos.ui.theme.ShopLedgerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Calendar

@RunWith(AndroidJUnit4::class)
class GallaSummaryTest {
    @get:Rule
    val compose = createComposeRule()

    private fun calAt(year: Int, month: Int, day: Int): Long = Calendar.getInstance().apply {
        set(year, month - 1, day, 14, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun computesCashUpiCreditReturnsAndJamaAcrossPeriods() {
        val todayTs = calAt(2026, 11, 20)
        val prevDaySameMonthTs = calAt(2026, 11, 5)
        val prevMonthTs = calAt(2026, 10, 15)

        val invoices = listOf(
            InvoiceHeaderEntity(
                id = "inv1", invoiceNumber = "R-1", docType = DocType.SALE, paymentMode = "CASH",
                subtotalPaise = 10_000L, grandTotalPaise = 10_000L, amountPaidPaise = 10_000L, balanceDuePaise = 0L,
                issuedAt = todayTs, yearInt = 2026, monthInt = 11, dayInt = 20,
            ),
            InvoiceHeaderEntity(
                id = "inv2", invoiceNumber = "R-2", docType = DocType.SALE, paymentMode = "UPI",
                subtotalPaise = 20_000L, grandTotalPaise = 20_000L, amountPaidPaise = 20_000L, balanceDuePaise = 0L,
                issuedAt = todayTs, yearInt = 2026, monthInt = 11, dayInt = 20,
            ),
            InvoiceHeaderEntity(
                id = "inv3", invoiceNumber = "R-3", docType = DocType.SALE, paymentMode = "CREDIT",
                subtotalPaise = 50_000L, grandTotalPaise = 50_000L, amountPaidPaise = 10_000L, balanceDuePaise = 40_000L,
                issuedAt = todayTs, yearInt = 2026, monthInt = 11, dayInt = 20,
            ),
            InvoiceHeaderEntity(
                id = "cn1", invoiceNumber = "CN-1", docType = DocType.CREDIT_NOTE, originalInvoiceId = "inv1",
                paymentMode = "CASH", subtotalPaise = -2_000L, grandTotalPaise = -2_000L,
                amountPaidPaise = -2_000L, balanceDuePaise = 0L,
                issuedAt = todayTs, yearInt = 2026, monthInt = 11, dayInt = 20,
            ),
            InvoiceHeaderEntity(
                id = "inv_m", invoiceNumber = "R-4", docType = DocType.SALE, paymentMode = "CASH",
                subtotalPaise = 30_000L, grandTotalPaise = 30_000L, amountPaidPaise = 30_000L, balanceDuePaise = 0L,
                issuedAt = prevDaySameMonthTs, yearInt = 2026, monthInt = 11, dayInt = 5,
            ),
            InvoiceHeaderEntity(
                id = "inv_old", invoiceNumber = "R-5", docType = DocType.SALE, paymentMode = "UPI",
                subtotalPaise = 60_000L, grandTotalPaise = 60_000L, amountPaidPaise = 60_000L, balanceDuePaise = 0L,
                issuedAt = prevMonthTs, yearInt = 2026, monthInt = 10, dayInt = 15,
            ),
        )
        val ledger = listOf(
            PartyTransactionEntity(
                entryId = "l1", partyId = "p1", entryType = LedgerEntryType.PAYMENT_IN,
                amountDeltaPaise = -5_000L, paymentMode = "CASH", createdAt = todayTs,
            ),
            PartyTransactionEntity(
                entryId = "l2", partyId = "p2", entryType = LedgerEntryType.PAYMENT_IN,
                amountDeltaPaise = -7_000L, paymentMode = "UPI", createdAt = prevDaySameMonthTs,
            ),
        )

        val today = computeGallaBreakdown(invoices, emptyList(), ledger, 2026, 11, 20, 0)
        assertEquals(3, today.totalSalesCount)
        assertEquals(23_000L, today.cashInDrawerPaise) // 10000 + 10000 (credit paid) - 2000 (CN) + 5000 (Jama)
        assertEquals(20_000L, today.totalUpiPaise)
        assertEquals(40_000L, today.udharGivenPaise)
        assertEquals(5_000L, today.totalJamaPaise)

        val month = computeGallaBreakdown(invoices, emptyList(), ledger, 2026, 11, 20, 1)
        assertEquals(4, month.totalSalesCount)
        assertEquals(53_000L, month.cashInDrawerPaise)
        assertEquals(27_000L, month.totalUpiPaise)
        assertEquals(12_000L, month.totalJamaPaise)

        val all = computeGallaBreakdown(invoices, emptyList(), ledger, 2026, 11, 20, 2)
        assertEquals(5, all.totalSalesCount)
        assertEquals(87_000L, all.totalUpiPaise)
    }

    @Test
    fun saleHistoryScreenRendersGallaBreakdownAndJamaNote() {
        val now = Calendar.getInstance()
        val y = now.get(Calendar.YEAR)
        val m = now.get(Calendar.MONTH) + 1
        val d = now.get(Calendar.DAY_OF_MONTH)
        val saleCash = SaleRecordEntity(
            id = "s0", invoiceId = "i0", receiptNumber = "R-0", itemId = "item0", variantId = "v0",
            itemNameSnapshot = "Nut", variantSummarySnapshot = "10 mm",
            quantitySold = 1.0, unitSnapshot = "pcs",
            unitPurchasePricePaise = 7_000L, unitSellingPricePaise = 10_000L,
            totalCostPaise = 7_000L, totalRevenuePaise = 10_000L, totalProfitPaise = 3_000L,
            gainMarkupPercent = 42.8, grossMarginPercent = 30.0,
            paymentMode = "CASH", amountPaidPaise = 10_000L, balanceDuePaise = 0L,
            soldAt = now.timeInMillis, yearInt = y, monthInt = m, dayInt = d,
        )
        val saleUpi = SaleRecordEntity(
            id = "s1", invoiceId = "i1", receiptNumber = "R-1", itemId = "item1", variantId = "v1",
            itemNameSnapshot = "White Cement", variantSummarySnapshot = "25 kg",
            quantitySold = 1.0, unitSnapshot = "bag",
            unitPurchasePricePaise = 30_000L, unitSellingPricePaise = 40_000L,
            totalCostPaise = 30_000L, totalRevenuePaise = 40_000L, totalProfitPaise = 10_000L,
            gainMarkupPercent = 33.3, grossMarginPercent = 25.0,
            paymentMode = "UPI", amountPaidPaise = 40_000L, balanceDuePaise = 0L,
            soldAt = now.timeInMillis, yearInt = y, monthInt = m, dayInt = d,
        )
        val headers = listOf(
            InvoiceHeaderEntity(
                id = "i0", invoiceNumber = "R-0", docType = DocType.SALE, paymentMode = "CASH",
                subtotalPaise = 10_000L, grandTotalPaise = 10_000L, amountPaidPaise = 10_000L, balanceDuePaise = 0L,
                issuedAt = now.timeInMillis, yearInt = y, monthInt = m, dayInt = d,
            ),
            InvoiceHeaderEntity(
                id = "i1", invoiceNumber = "R-1", docType = DocType.SALE, paymentMode = "UPI",
                subtotalPaise = 40_000L, grandTotalPaise = 40_000L, amountPaidPaise = 40_000L, balanceDuePaise = 0L,
                issuedAt = now.timeInMillis, yearInt = y, monthInt = m, dayInt = d,
            ),
        )
        val ledger = listOf(
            PartyTransactionEntity(
                entryId = "j1", partyId = "p1", entryType = LedgerEntryType.PAYMENT_IN,
                amountDeltaPaise = -15_000L, paymentMode = "CASH", createdAt = now.timeInMillis,
            ),
        )

        compose.setContent {
            ShopLedgerTheme {
                SaleHistoryScreen(
                    sales = listOf(saleCash, saleUpi),
                    isSellerUnlocked = false,
                    onToggleSellerUnlocked = {},
                    onOpenReceiptShare = {},
                    onReturnSale = { _, _, _ -> },
                    invoiceHeaders = headers,
                    ledgerEntries = ledger,
                )
            }
        }
        compose.onNodeWithText("Galla breakdown").assertIsDisplayed()
        compose.onNodeWithText("Share Galla summary").assertIsDisplayed()
        compose.onNodeWithText("₹250").assertIsDisplayed()
        compose.onNodeWithText("₹400").assertIsDisplayed()
        compose.onNodeWithText("Includes Khata Jama: ₹150").assertIsDisplayed()
    }
}
