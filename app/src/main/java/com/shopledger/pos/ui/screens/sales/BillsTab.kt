package com.shopledger.pos.ui.screens.sales

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.shopledger.pos.R
import com.shopledger.pos.data.local.entity.InvoiceHeaderEntity
import com.shopledger.pos.data.local.entity.PartyTransactionEntity
import com.shopledger.pos.data.local.entity.PurchaseBillEntity
import com.shopledger.pos.data.local.entity.SaleRecordEntity
import com.shopledger.pos.ui.screens.sales.calendar.BusinessCalendarScreen
import com.shopledger.pos.ui.theme.SegmentedChoice
import com.shopledger.pos.ui.theme.ShopLedgerTokens

@Composable
fun BillsTab(
    sales: List<SaleRecordEntity>,
    isSellerUnlocked: Boolean,
    onToggleSellerUnlocked: () -> Unit,
    onOpenReceiptShare: (SaleRecordEntity) -> Unit,
    onReturnSale: (sale: SaleRecordEntity, qtyMilli: Long, actionId: String) -> Unit,
    purchaseBills: List<PurchaseBillEntity>,
    onOpenPurchaseBill: (String) -> Unit,
    modifier: Modifier = Modifier,
    invoiceHeaders: List<InvoiceHeaderEntity> = emptyList(),
    ledgerEntries: List<PartyTransactionEntity> = emptyList(),
) {
    var view by rememberSaveable { mutableIntStateOf(0) } // 0 = List, 1 = Calendar

    Column(modifier = modifier.fillMaxSize()) {
        SegmentedChoice(
            options = listOf(
                stringResource(R.string.bills_view_list),
                stringResource(R.string.bills_view_calendar),
            ),
            selectedIndex = view,
            onSelect = { view = it },
            modifier = Modifier.padding(horizontal = ShopLedgerTokens.spacingL, vertical = ShopLedgerTokens.spacingS),
        )

        if (view == 0) {
            SaleHistoryScreen(
                sales = sales,
                isSellerUnlocked = isSellerUnlocked,
                onToggleSellerUnlocked = onToggleSellerUnlocked,
                onOpenReceiptShare = onOpenReceiptShare,
                onReturnSale = onReturnSale,
                invoiceHeaders = invoiceHeaders,
                ledgerEntries = ledgerEntries,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        } else {
            BusinessCalendarScreen(
                invoices = invoiceHeaders,
                ledgerEntries = ledgerEntries,
                purchaseBills = purchaseBills,
                isSellerUnlocked = isSellerUnlocked,
                onOpenPurchaseBill = onOpenPurchaseBill,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }
}
