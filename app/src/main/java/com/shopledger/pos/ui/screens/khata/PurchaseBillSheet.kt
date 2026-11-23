package com.shopledger.pos.ui.screens.khata

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.shopledger.pos.R
import com.shopledger.pos.core.format.IndianCurrencyFormatter
import com.shopledger.pos.data.local.entity.PurchaseBillEntity
import com.shopledger.pos.data.local.entity.PurchaseBillLineEntity
import com.shopledger.pos.ui.text.variantLabel
import com.shopledger.pos.ui.theme.SecondaryActionButton
import com.shopledger.pos.ui.theme.SectionCard
import com.shopledger.pos.ui.theme.ShopLedgerTokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PurchaseBillSheet(
    bill: PurchaseBillEntity,
    lines: List<PurchaseBillLineEntity>,
    onDismiss: () -> Unit,
) {
    val dateFmt = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }
    val distributorName = bill.supplierNameSnapshot.ifBlank { stringResource(R.string.distributor_unknown) }
    val billNo = bill.billNumber.ifBlank { stringResource(R.string.distributor_bill_no_number) }
    val paidModeLabel = when (bill.paymentMode.trim().uppercase(Locale.ROOT)) {
        "UPI" -> stringResource(R.string.pay_upi)
        "CASH" -> stringResource(R.string.pay_nagad)
        else -> stringResource(R.string.pay_nagad)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ShopLedgerTokens.spacingL, vertical = ShopLedgerTokens.spacingS),
            verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingM),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingXs)) {
                Text(stringResource(R.string.purchase_bill_title), style = MaterialTheme.typography.titleLarge)
                Text(distributorName, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "$billNo · ${dateFmt.format(Date(bill.createdAt))}",
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

            if (lines.isNotEmpty()) {
                Column {
                    lines.forEach { line ->
                        val variantText = line.variantSummarySnapshot.variantLabel()
                        val qtyStr = IndianCurrencyFormatter.formatQuantity(line.qtyMilliUnits / 1000.0)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = ShopLedgerTokens.spacingS),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = ShopLedgerTokens.spacingS)) {
                                Text(
                                    text = if (line.variantSummarySnapshot.isBlank()) {
                                        line.itemNameSnapshot
                                    } else {
                                        "${line.itemNameSnapshot} · $variantText"
                                    },
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = if (bill.isLegacy) {
                                        "$qtyStr ${line.unitSnapshot} · ${stringResource(R.string.distributor_cost_unknown)}"
                                    } else {
                                        "$qtyStr ${line.unitSnapshot} × ${IndianCurrencyFormatter.formatPaise(line.unitCostPaise)}"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (!bill.isLegacy) {
                                Text(
                                    text = IndianCurrencyFormatter.formatPaise(line.lineTotalPaise),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }

            SectionCard {
                if (!bill.isLegacy) {
                    BillAmountRow(
                        label = stringResource(R.string.purchase_bill_lines_total),
                        amount = IndianCurrencyFormatter.formatPaise(bill.linesTotalPaise),
                    )
                }
                BillAmountRow(
                    label = stringResource(R.string.purchase_bill_total),
                    amount = IndianCurrencyFormatter.formatPaise(bill.totalPaise),
                )
                BillAmountRow(
                    label = stringResource(R.string.purchase_bill_paid_now, paidModeLabel),
                    amount = IndianCurrencyFormatter.formatPaise(bill.paidPaise),
                )
                BillAmountRow(
                    label = stringResource(R.string.purchase_bill_due),
                    amount = IndianCurrencyFormatter.formatPaise(bill.duePaise),
                    isError = bill.duePaise > 0L,
                )
            }

            SecondaryActionButton(
                text = stringResource(R.string.review_cd_close),
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun BillAmountRow(
    label: String,
    amount: String,
    isError: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = amount,
            style = MaterialTheme.typography.titleMedium,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}
