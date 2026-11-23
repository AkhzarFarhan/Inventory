package com.shopledger.pos.ui.screens.khata

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.shopledger.pos.R
import com.shopledger.pos.data.local.entity.PartyEntity
import com.shopledger.pos.data.local.entity.PartyTransactionEntity
import com.shopledger.pos.data.local.entity.PurchaseBillEntity
import com.shopledger.pos.ui.theme.EmptyState
import com.shopledger.pos.ui.theme.PrimaryActionButton
import com.shopledger.pos.ui.theme.SegmentedChoice
import com.shopledger.pos.ui.theme.ShopLedgerTokens

@Composable
fun KhataTab(
    parties: List<PartyEntity>,
    ledgerEntries: List<PartyTransactionEntity>,
    purchaseBills: List<PurchaseBillEntity>,
    isSellerUnlocked: Boolean,
    onRequestUnlock: () -> Unit,
    onAddParty: (name: String, phone: String, partyType: String, openingBalancePaise: Long) -> Unit,
    onRecordPaymentReceived: (partyId: String, amountPaise: Long, paymentMode: String, note: String) -> Unit,
    onAddDistributor: (name: String, phone: String, gstin: String, alreadyOwePaise: Long) -> Unit,
    onPayDistributor: (partyId: String, amountPaise: Long, mode: String, note: String) -> Unit,
    onOpenDistributor: (partyId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var side by rememberSaveable { mutableIntStateOf(0) } // 0 = Customers, 1 = Distributors
    val customers = remember(parties) {
        parties.filter { it.partyType == "CUSTOMER" || it.partyType == "BOTH" }
    }
    val distributors = remember(parties) {
        parties.filter { it.partyType == "SUPPLIER" || it.partyType == "BOTH" }
    }

    Column(modifier = modifier.fillMaxSize()) {
        SegmentedChoice(
            options = listOf(
                stringResource(R.string.khata_side_customers),
                stringResource(R.string.khata_side_distributors),
            ),
            selectedIndex = side,
            onSelect = { side = it },
            modifier = Modifier.padding(horizontal = ShopLedgerTokens.spacingL, vertical = ShopLedgerTokens.spacingS),
        )

        if (side == 0) {
            PartiesKhataScreen(
                parties = customers,
                onAddParty = onAddParty,
                onRecordPaymentReceived = onRecordPaymentReceived,
                ledgerEntries = ledgerEntries,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        } else if (!isSellerUnlocked) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = ShopLedgerTokens.spacingL),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingM),
                ) {
                    EmptyState(
                        title = stringResource(R.string.distributors_locked_title),
                        message = stringResource(R.string.distributors_locked_message),
                    )
                    PrimaryActionButton(
                        text = stringResource(R.string.distributors_unlock),
                        onClick = onRequestUnlock,
                        icon = Icons.Filled.LockOpen,
                    )
                }
            }
        } else {
            DistributorsScreen(
                distributors = distributors,
                purchaseBills = purchaseBills,
                onAddDistributor = onAddDistributor,
                onPayDistributor = onPayDistributor,
                onOpenDistributor = onOpenDistributor,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }
}
