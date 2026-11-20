package com.shopledger.pos.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shopledger.pos.core.share.ReceiptLineItem
import com.shopledger.pos.core.share.SaleReceiptData
import com.shopledger.pos.ui.screens.sales.ReceiptShareDialog
import com.shopledger.pos.ui.theme.ShopLedgerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@Config(qualifiers = "mdpi")
@RunWith(AndroidJUnit4::class)
class ReceiptShareDialogQrTest {
    @get:Rule
    val compose = createComposeRule()

    private fun click(text: String) {
        compose.waitForIdle()
        compose.onNode(hasText(text) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun sampleReceipt(
        paymentMode: String = "UPI",
        isEstimate: Boolean = false,
        upiId: String = "shop@upi",
        grandTotalPaise: Long = 40_000L,
    ) = SaleReceiptData(
        receiptNumber = "R-1001",
        timestampMillis = 1_700_000_000_000L,
        buyerName = "Ramesh",
        sellerName = "Shakti Traders",
        isEstimate = isEstimate,
        items = listOf(
            ReceiptLineItem(
                itemName = "White Cement",
                variantSummary = "25 kg",
                quantity = 1.0,
                unit = "bag",
                unitSellingPricePaise = grandTotalPaise,
                lineTotalPaise = grandTotalPaise,
            ),
        ),
        grandTotalPaise = grandTotalPaise,
        paymentMode = paymentMode,
        amountPaidPaise = grandTotalPaise,
        upiId = upiId,
    )

    @Test
    fun upiReceiptAutoExpandsCounterQrAndOpensFullScreen() {
        compose.setContent {
            ShopLedgerTheme {
                ReceiptShareDialog(
                    receipt = sampleReceipt(paymentMode = "UPI"),
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("Scan to pay ₹400").assertExists()
        compose.onNodeWithText("Full-screen QR").assertExists()
        click("Full-screen QR")
        compose.onNodeWithText("Ask customer to scan with any UPI app").assertExists()
    }

    @Test
    fun cashReceiptStartsQrCollapsedUntilToggled() {
        compose.setContent {
            ShopLedgerTheme {
                ReceiptShareDialog(
                    receipt = sampleReceipt(paymentMode = "CASH"),
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("Show UPI QR").assertExists()
        compose.onAllNodesWithText("Full-screen QR").assertCountEquals(0)

        click("Show UPI QR")
        compose.onNodeWithText("Full-screen QR").assertExists()
    }

    @Test
    fun estimateNeverShowsCounterUpiQr() {
        compose.setContent {
            ShopLedgerTheme {
                ReceiptShareDialog(
                    receipt = sampleReceipt(isEstimate = true),
                    onDismiss = {},
                )
            }
        }
        compose.onAllNodesWithText("Full-screen QR").assertCountEquals(0)
        compose.onAllNodesWithText("Show UPI QR").assertCountEquals(0)
    }
}
