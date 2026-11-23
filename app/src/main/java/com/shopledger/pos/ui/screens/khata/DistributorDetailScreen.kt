package com.shopledger.pos.ui.screens.khata

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shopledger.pos.R
import com.shopledger.pos.core.format.IndianCurrencyFormatter
import com.shopledger.pos.data.command.LedgerEntryType
import com.shopledger.pos.data.local.dao.SupplierItemSummary
import com.shopledger.pos.data.local.entity.PartyEntity
import com.shopledger.pos.data.local.entity.PartyTransactionEntity
import com.shopledger.pos.data.local.entity.PurchaseBillEntity
import com.shopledger.pos.ui.text.variantLabel
import com.shopledger.pos.ui.theme.ChipTone
import com.shopledger.pos.ui.theme.EmptyState
import com.shopledger.pos.ui.theme.PrimaryActionButton
import com.shopledger.pos.ui.theme.SectionCard
import com.shopledger.pos.ui.theme.SegmentedChoice
import com.shopledger.pos.ui.theme.ShopLedgerTokens
import com.shopledger.pos.ui.theme.StatusChip
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

data class DistributorDetailState(
    val party: PartyEntity,
    val ledger: List<PartyTransactionEntity>, // newest first
    val bills: List<PurchaseBillEntity>,      // newest first
    val items: List<SupplierItemSummary>,
)

sealed interface DistributorEvent {
    data object Close : DistributorEvent
    data object Pay : DistributorEvent
    data class OpenBill(val purchaseId: String) : DistributorEvent
    data object Call : DistributorEvent
    data object WhatsApp : DistributorEvent
}

private data class LedgerRowWithBalance(
    val entry: PartyTransactionEntity,
    val balanceAfterPaise: Long,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DistributorDetailScreen(
    state: DistributorDetailState,
    onEvent: (DistributorEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var tabIndex by rememberSaveable { mutableIntStateOf(0) } // 0 Account, 1 Bills, 2 Items
    val dateFmt = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }
    val billsById = remember(state.bills) { state.bills.associateBy { it.id } }

    val accountRows = remember(state.ledger) {
        val chronological = state.ledger.sortedWith(
            compareBy<PartyTransactionEntity>(
                { it.createdAt },
                {
                    when (it.entryType) {
                        LedgerEntryType.OPENING -> 0
                        LedgerEntryType.CREDIT_BILL -> 1
                        else -> 2
                    }
                },
                { it.entryId },
            )
        )
        var running = 0L
        chronological.map { entry ->
            running += entry.amountDeltaPaise
            LedgerRowWithBalance(entry, running)
        }.asReversed()
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(state.party.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                IconButton(
                    onClick = { onEvent(DistributorEvent.Close) },
                    modifier = Modifier.size(ShopLedgerTokens.minTouch),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.review_cd_close))
                }
            },
            actions = {
                if (state.party.phone.isNotBlank()) {
                    IconButton(
                        onClick = { onEvent(DistributorEvent.Call) },
                        modifier = Modifier.size(ShopLedgerTokens.minTouch),
                    ) {
                        Icon(Icons.Filled.Call, contentDescription = stringResource(R.string.distributor_cd_call))
                    }
                }
                IconButton(
                    onClick = { onEvent(DistributorEvent.WhatsApp) },
                    modifier = Modifier.size(ShopLedgerTokens.minTouch),
                ) {
                    Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.distributor_cd_share))
                }
            },
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ShopLedgerTokens.spacingL, vertical = ShopLedgerTokens.spacingS),
            verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS),
        ) {
            SectionCard {
                val balance = state.party.currentBalancePaise
                when {
                    balance < 0L -> Text(
                        text = stringResource(R.string.distributor_you_owe, IndianCurrencyFormatter.formatPaise(-balance)),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    balance > 0L -> Text(
                        text = stringResource(R.string.distributor_they_owe, IndianCurrencyFormatter.formatPaise(balance)),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    else -> Text(
                        text = stringResource(R.string.khata_badge_settled),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (state.party.gstin.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.receipt_gstin, state.party.gstin),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PrimaryActionButton(
                    text = stringResource(R.string.distributor_pay),
                    onClick = { onEvent(DistributorEvent.Pay) },
                    icon = Icons.Filled.Payments,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SegmentedChoice(
                options = listOf(
                    stringResource(R.string.distributor_tab_account),
                    stringResource(R.string.distributor_tab_bills),
                    stringResource(R.string.distributor_tab_items),
                ),
                selectedIndex = tabIndex,
                onSelect = { tabIndex = it },
            )
        }

        when (tabIndex) {
            0 -> AccountTab(
                rows = accountRows,
                billsById = billsById,
                dateFmt = dateFmt,
                onOpenBill = { onEvent(DistributorEvent.OpenBill(it)) },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            1 -> BillsListTab(
                bills = state.bills,
                dateFmt = dateFmt,
                onOpenBill = { onEvent(DistributorEvent.OpenBill(it)) },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            else -> SuppliedItemsTab(
                items = state.items,
                dateFmt = dateFmt,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun AccountTab(
    rows: List<LedgerRowWithBalance>,
    billsById: Map<String, PurchaseBillEntity>,
    dateFmt: SimpleDateFormat,
    onOpenBill: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (rows.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            EmptyState(
                title = stringResource(R.string.distributor_no_bills),
                message = stringResource(R.string.distributor_items_empty_message),
            )
        }
        return
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = ShopLedgerTokens.spacingL, vertical = ShopLedgerTokens.spacingS),
    ) {
        items(items = rows, key = { it.entry.entryId }) { row ->
            val entry = row.entry
            val linkedBill = entry.refDocId.takeIf { it.isNotBlank() }?.let { billsById[it] }
            val clickableBillId = linkedBill?.id
                ?: entry.refDocId.takeIf { entry.entryType == LedgerEntryType.CREDIT_BILL && it.isNotBlank() }

            val modeLabel = if (entry.paymentMode.equals("UPI", ignoreCase = true)) {
                stringResource(R.string.pay_upi)
            } else {
                stringResource(R.string.pay_nagad)
            }
            val label = when (entry.entryType) {
                LedgerEntryType.CREDIT_BILL -> {
                    val base = stringResource(R.string.distributor_entry_bill)
                    val billNo = linkedBill?.billNumber?.trim().orEmpty()
                    if (billNo.isNotEmpty()) "$base · $billNo" else base
                }
                LedgerEntryType.PAYMENT_OUT -> stringResource(R.string.distributor_entry_paid) + " · " + modeLabel
                LedgerEntryType.PAYMENT_IN -> stringResource(R.string.distributor_entry_refund)
                LedgerEntryType.OPENING -> stringResource(R.string.khata_entry_opening)
                else -> entry.entryType
            }

            val absAmt = IndianCurrencyFormatter.formatPaise(abs(entry.amountDeltaPaise))
            val isBillOrOwe = entry.amountDeltaPaise < 0L
            val amountText = when {
                entry.entryType == LedgerEntryType.PAYMENT_OUT ->
                    stringResource(R.string.distributor_paid_amount, absAmt) + " · " + modeLabel
                isBillOrOwe -> stringResource(R.string.distributor_udhaar_amount, absAmt)
                else -> stringResource(R.string.distributor_paid_amount, absAmt)
            }
            val balanceAfter = row.balanceAfterPaise
            val balanceText = when {
                balanceAfter < 0L -> stringResource(
                    R.string.distributor_balance_owe,
                    IndianCurrencyFormatter.formatPaise(-balanceAfter),
                )
                balanceAfter > 0L -> stringResource(
                    R.string.distributor_balance_advance,
                    IndianCurrencyFormatter.formatPaise(balanceAfter),
                )
                else -> IndianCurrencyFormatter.formatPaise(0L)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .then(
                        if (clickableBillId != null) Modifier.clickable { onOpenBill(clickableBillId) } else Modifier
                    )
                    .padding(vertical = ShopLedgerTokens.spacingS),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = ShopLedgerTokens.spacingS)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = dateFmt.format(Date(entry.createdAt)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = amountText,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (isBillOrOwe) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = balanceText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun BillsListTab(
    bills: List<PurchaseBillEntity>,
    dateFmt: SimpleDateFormat,
    onOpenBill: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (bills.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            EmptyState(
                title = stringResource(R.string.distributor_no_bills),
                message = stringResource(R.string.distributor_items_empty_message),
            )
        }
        return
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = ShopLedgerTokens.spacingL, vertical = ShopLedgerTokens.spacingS),
        verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS),
    ) {
        items(items = bills, key = { it.id }) { bill ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .clickable { onOpenBill(bill.id) },
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ShopLedgerTokens.spacingL, vertical = ShopLedgerTokens.spacingM),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(
                        modifier = Modifier.weight(1f).padding(end = ShopLedgerTokens.spacingS),
                        verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingXs),
                    ) {
                        Text(
                            text = bill.billNumber.ifBlank { stringResource(R.string.distributor_bill_no_number) },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = dateFmt.format(Date(bill.createdAt)) + " · " +
                                pluralStringResource(R.plurals.distributor_bill_items, bill.lineCount, bill.lineCount),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (bill.isLegacy) {
                            Text(
                                text = stringResource(R.string.distributor_bill_legacy),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingXs),
                    ) {
                        Text(
                            text = IndianCurrencyFormatter.formatPaise(bill.totalPaise),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (bill.duePaise > 0L) {
                            StatusChip(
                                text = stringResource(
                                    R.string.distributor_bill_due,
                                    IndianCurrencyFormatter.formatPaise(bill.duePaise),
                                ),
                                tone = ChipTone.WARNING,
                            )
                        } else {
                            StatusChip(
                                text = stringResource(R.string.distributor_bill_paid),
                                tone = ChipTone.SUCCESS,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SuppliedItemsTab(
    items: List<SupplierItemSummary>,
    dateFmt: SimpleDateFormat,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            EmptyState(
                title = stringResource(R.string.distributor_items_empty_title),
                message = stringResource(R.string.distributor_items_empty_message),
            )
        }
        return
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = ShopLedgerTokens.spacingL, vertical = ShopLedgerTokens.spacingS),
    ) {
        items(items = items, key = { it.skuId }) { item ->
            val variantText = item.variantSummarySnapshot.variantLabel()
            val qtyStr = IndianCurrencyFormatter.formatQuantity(item.totalQtyMilli / 1000.0)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = ShopLedgerTokens.spacingS),
                verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingXs),
            ) {
                Text(
                    text = if (item.variantSummarySnapshot.isBlank()) {
                        item.itemNameSnapshot
                    } else {
                        "${item.itemNameSnapshot} · $variantText"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.distributor_items_bought, qtyStr, item.unitSnapshot, item.billCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (item.lastUnitCostPaise > 0L) {
                        stringResource(
                            R.string.distributor_last_cost,
                            IndianCurrencyFormatter.formatPaise(item.lastUnitCostPaise),
                            dateFmt.format(Date(item.lastBoughtAt)),
                        )
                    } else {
                        stringResource(R.string.distributor_cost_unknown)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
        }
    }
}
