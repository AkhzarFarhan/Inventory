package com.shopledger.pos.ui.screens.inventory

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shopledger.pos.R
import com.shopledger.pos.core.buyer.BuyerHistoryHelper
import com.shopledger.pos.core.buyer.BuyerOrderLineSnapshot
import com.shopledger.pos.core.buyer.KnownBuyerProfile
import com.shopledger.pos.core.cipher.CodedPurchasePriceBadge
import com.shopledger.pos.core.error.FloatingAppMessageHost
import com.shopledger.pos.core.format.IndianCurrencyFormatter
import com.shopledger.pos.data.local.entity.InventoryItemEntity
import com.shopledger.pos.data.local.entity.ItemVariantEntity
import com.shopledger.pos.data.model.CartLineItem
import com.shopledger.pos.ui.theme.HapticHelper
import com.shopledger.pos.ui.theme.PrimaryActionButton
import com.shopledger.pos.ui.theme.SecondaryActionButton
import com.shopledger.pos.ui.theme.SectionCard
import com.shopledger.pos.ui.theme.SegmentedChoice
import com.shopledger.pos.ui.theme.ShopLedgerTokens

/**
 * 1-Tap Quick Sell Bottom Sheet for a single hardware item variant.
 * Includes Smart Buyer Auto-Suggest & Single-Tap Order Fill, bargaining price override,
 *Estimate Photo sharing, and Sale Receipt Photo sharing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickSellSheet(
    item: InventoryItemEntity,
    variant: ItemVariantEntity,
    allVariants: List<ItemVariantEntity> = emptyList(),
    isSellerUnlocked: Boolean,
    knownBuyers: List<KnownBuyerProfile>,
    onConfirmQuickSell: (CartLineItem, buyerName: String, buyerPhone: String, paymentMode: String, discountPaise: Long, amountPaidPaise: Long?) -> Unit,
    onShareEstimatePhoto: (List<CartLineItem>, buyerName: String, buyerPhone: String) -> Unit,
    onFillCartWithBuyerOrderAndOpenCart: (List<BuyerOrderLineSnapshot>, buyerName: String, buyerPhone: String) -> Unit = { _, _, _ -> },
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    var activeVariant by remember(variant.id) { mutableStateOf(variant) }
    var qtyInput by remember(activeVariant.id) { mutableStateOf("1") }
    var sellingPriceInput by remember(activeVariant.id) {
        mutableStateOf(IndianCurrencyFormatter.paiseToEditableString(activeVariant.sellingPricePaise))
    }
    var buyerName by remember { mutableStateOf("") }
    var buyerPhone by remember { mutableStateOf("") }
    var paymentModeIndex by remember { mutableIntStateOf(0) } // 0 = CASH, 1 = UPI, 2 = CREDIT
    var discountInput by remember { mutableStateOf("") }
    var amountPaidInput by remember { mutableStateOf("0") }

    val parsedQty = (qtyInput.toDoubleOrNull() ?: 1.0).coerceAtLeast(0.01)
    val parsedSellingPaise = IndianCurrencyFormatter.parseRupeesToPaise(sellingPriceInput)
    val profitAnalysis = remember(activeVariant.purchasePricePaise, parsedSellingPaise, parsedQty) {
        IndianCurrencyFormatter.computeProfitAnalysis(
            unitPurchasePaise = activeVariant.purchasePricePaise,
            unitSellingPaise = parsedSellingPaise,
            quantity = parsedQty
        )
    }
    val parsedDiscountPaise = IndianCurrencyFormatter.parseRupeesToPaise(discountInput).coerceIn(0L, profitAnalysis.totalRevenuePaise)
    val netPayablePaise = (profitAnalysis.totalRevenuePaise - parsedDiscountPaise).coerceAtLeast(0L)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background
    ) {
        FloatingAppMessageHost()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ShopLedgerTokens.spacingL, vertical = ShopLedgerTokens.spacingS),
            verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingM)
        ) {
            Text(
                text = stringResource(R.string.sell_title),
                style = MaterialTheme.typography.titleLarge,
            )

            // Item details & pricing card
            SectionCard {
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.titleMedium,
                )

                if (allVariants.size > 1) {
                    Text(
                        text = stringResource(R.string.sell_choose_variant),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        allVariants.forEach { v ->
                            val isSelected = v.id == activeVariant.id
                            val summary = v.buildVariantSummary()
                            val priceLabel = IndianCurrencyFormatter.formatPaise(v.sellingPricePaise)
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    activeVariant = v
                                    sellingPriceInput = IndianCurrencyFormatter.paiseToEditableString(v.sellingPricePaise)
                                },
                                label = {
                                    Text("$summary • $priceLabel", style = MaterialTheme.typography.bodyMedium)
                                },
                            )
                        }
                    }
                }

                Text(
                    text = stringResource(
                        R.string.sell_available,
                        activeVariant.buildVariantSummary(),
                        IndianCurrencyFormatter.formatQuantity(activeVariant.stockQuantity),
                        activeVariant.unit
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Coded purchase price badge (hidden unless seller mode) + total bill
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = ShopLedgerTokens.spacingM, vertical = ShopLedgerTokens.spacingS),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CodedPurchasePriceBadge(
                            purchasePricePaise = activeVariant.purchasePricePaise,
                            isSellerUnlocked = isSellerUnlocked
                        )

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = stringResource(R.string.sell_total_bill),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = IndianCurrencyFormatter.formatPaise(profitAnalysis.totalRevenuePaise),
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                // Profit margin and gain % (hidden unless seller mode is unlocked)
                if (isSellerUnlocked) {
                    val profitable = profitAnalysis.totalProfitPaise >= 0
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        color = if (profitable) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                        contentColor = if (profitable) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = ShopLedgerTokens.spacingM, vertical = ShopLedgerTokens.spacingS)
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.sell_net_profit,
                                    IndianCurrencyFormatter.formatPaise(profitAnalysis.totalProfitPaise, showPlusSign = true)
                                ),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = stringResource(
                                    R.string.sell_gain,
                                    IndianCurrencyFormatter.formatPercentage(profitAnalysis.gainMarkupPercent),
                                    IndianCurrencyFormatter.formatPercentage(profitAnalysis.grossMarginPercent, showPlus = false)
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }

                // Quantity stepper
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS)
                ) {
                    IconButton(
                        onClick = {
                            val next = (parsedQty - 1.0).coerceAtLeast(1.0)
                            qtyInput = IndianCurrencyFormatter.formatQuantity(next)
                            HapticHelper.tick(context)
                        },
                        modifier = Modifier.size(ShopLedgerTokens.minTouch),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(),
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.cd_decrease_quantity))
                    }

                    OutlinedTextField(
                        value = qtyInput,
                        onValueChange = { qtyInput = it },
                        label = { Text(stringResource(R.string.sell_qty_label, variant.unit)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.weight(1f)
                    )

                    IconButton(
                        onClick = {
                            val next = parsedQty + 1.0
                            qtyInput = IndianCurrencyFormatter.formatQuantity(next)
                            HapticHelper.tick(context)
                        },
                        modifier = Modifier.size(ShopLedgerTokens.minTouch),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_increase_quantity))
                    }
                }

                // Bargaining: unit selling price override
                OutlinedTextField(
                    value = sellingPriceInput,
                    onValueChange = { sellingPriceInput = it },
                    label = { Text(stringResource(R.string.sell_rate_label, variant.unit)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Buyer details + existing-buyer auto-suggest (1-tap name and phone fill)
            BuyerSuggestAndHistoryCard(
                buyerName = buyerName,
                buyerPhone = buyerPhone,
                knownBuyers = knownBuyers,
                onBuyerNameChange = { buyerName = it },
                onBuyerPhoneChange = { buyerPhone = it },
                onSingleTapSelectBuyer = { selectedBuyer ->
                    HapticHelper.tick(context)
                    buyerName = selectedBuyer.buyerName
                    buyerPhone = selectedBuyer.buyerPhone
                    Toast.makeText(
                        context,
                        context.getString(R.string.msg_customer_selected, selectedBuyer.buyerName.ifBlank { selectedBuyer.buyerPhone }),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )

            PaymentModeAndDiscountCard(
                totalAmountPaise = profitAnalysis.totalRevenuePaise,
                paymentModeIndex = paymentModeIndex,
                onPaymentModeIndexChange = { paymentModeIndex = it },
                discountInput = discountInput,
                onDiscountInputChange = { discountInput = it },
                amountPaidInput = amountPaidInput,
                onAmountPaidInputChange = { amountPaidInput = it }
            )

            // Share estimate photo vs complete sale
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = ShopLedgerTokens.spacingXl),
                verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS)
            ) {
                PrimaryActionButton(
                    text = stringResource(R.string.sell_button, IndianCurrencyFormatter.formatPaise(netPayablePaise)),
                    icon = Icons.Default.CheckCircle,
                    onClick = {
                        if (parsedSellingPaise <= 0L) return@PrimaryActionButton
                        keyboardController?.hide()
                        focusManager.clearFocus()
                        HapticHelper.successPulse(context)

                        val lineItem = CartLineItem(
                            itemId = item.id,
                            variantId = activeVariant.id,
                            itemName = item.displayName,
                            variantSummary = activeVariant.buildVariantSummary(),
                            unit = activeVariant.unit,
                            availableStock = activeVariant.stockQuantity,
                            unitPurchasePricePaise = activeVariant.purchasePricePaise,
                            defaultSellingPricePaise = activeVariant.sellingPricePaise,
                            customSellingPricePaise = parsedSellingPaise,
                            quantity = parsedQty
                        )
                        val paymentMode = when (paymentModeIndex) {
                            1 -> "UPI"
                            2 -> "CREDIT"
                            else -> "CASH"
                        }
                        val paidPaise = if (paymentMode == "CREDIT") {
                            IndianCurrencyFormatter.parseRupeesToPaise(amountPaidInput).coerceIn(0L, netPayablePaise)
                        } else {
                            null
                        }
                        onConfirmQuickSell(lineItem, buyerName, buyerPhone, paymentMode, parsedDiscountPaise, paidPaise)
                        onDismiss()
                    },
                )

                SecondaryActionButton(
                    text = stringResource(R.string.sell_estimate_photo),
                    icon = Icons.Default.Share,
                    onClick = {
                        if (parsedSellingPaise <= 0L) return@SecondaryActionButton
                        keyboardController?.hide()
                        focusManager.clearFocus()
                        val lineItem = CartLineItem(
                            itemId = item.id,
                            variantId = activeVariant.id,
                            itemName = item.displayName,
                            variantSummary = activeVariant.buildVariantSummary(),
                            unit = activeVariant.unit,
                            availableStock = activeVariant.stockQuantity,
                            unitPurchasePricePaise = activeVariant.purchasePricePaise,
                            defaultSellingPricePaise = activeVariant.sellingPricePaise,
                            customSellingPricePaise = parsedSellingPaise,
                            quantity = parsedQty
                        )
                        onShareEstimatePhoto(listOf(lineItem), buyerName, buyerPhone)
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * Smart Buyer Auto-Suggest Card (Improvement #2 & #6).
 * Whenever the seller types a name or WhatsApp number (or views recent buyers), matching
 * customer profiles appear. Tapping a buyer fills their name and phone in 1 tap without
 * touching the current items.
 */
@Composable
private fun BuyerSuggestAndHistoryCard(
    buyerName: String,
    buyerPhone: String,
    knownBuyers: List<KnownBuyerProfile>,
    onBuyerNameChange: (String) -> Unit,
    onBuyerPhoneChange: (String) -> Unit,
    onSingleTapSelectBuyer: (KnownBuyerProfile) -> Unit
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val matchingBuyers = remember(buyerName, buyerPhone, knownBuyers) {
        BuyerHistoryHelper.findMatchingBuyers(buyerName, buyerPhone, knownBuyers)
    }

    val exactSelectedBuyer = remember(buyerName, buyerPhone, knownBuyers) {
        val phoneDigits = buyerPhone.filter { it.isDigit() }
        knownBuyers.firstOrNull { profile ->
            (phoneDigits.length >= 6 && profile.buyerPhone.filter { it.isDigit() } == phoneDigits) ||
                (buyerName.length >= 3 && profile.buyerName.equals(buyerName.trim(), ignoreCase = true))
        }
    }

    SectionCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS)
        ) {
            Icon(
                imageVector = Icons.Default.PersonSearch,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Column {
                Text(
                    text = stringResource(R.string.sell_buyer_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.sell_buyer_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        OutlinedTextField(
            value = buyerPhone,
            onValueChange = onBuyerPhoneChange,
            label = { Text(stringResource(R.string.sell_buyer_phone)) },
            placeholder = { Text(stringResource(R.string.sell_buyer_phone_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = buyerName,
            onValueChange = onBuyerNameChange,
            label = { Text(stringResource(R.string.sell_buyer_name)) },
            placeholder = { Text(stringResource(R.string.sell_buyer_name_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    keyboardController?.hide()
                    focusManager.clearFocus()
                }
            ),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()
        )

        // Matching buyers when typing or when known buyers exist
        if (matchingBuyers.isNotEmpty() && exactSelectedBuyer == null) {
            Text(
                text = stringResource(R.string.sell_tap_customer),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Column(verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS)) {
                val fallbackName = stringResource(R.string.sell_customer_fallback)
                matchingBuyers.forEach { buyer ->
                    Surface(
                        onClick = {
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            onSingleTapSelectBuyer(buyer)
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = ShopLedgerTokens.minTouch),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = ShopLedgerTokens.spacingM, vertical = ShopLedgerTokens.spacingS),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = listOf(buyer.buyerName.ifBlank { fallbackName }, buyer.buyerPhone)
                                        .filter { it.isNotBlank() }
                                        .joinToString(" • "),
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = pluralStringResource(
                                        R.plurals.sell_past_visits,
                                        buyer.totalDistinctOrders,
                                        buyer.totalDistinctOrders
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.width(ShopLedgerTokens.spacingS))
                            Text(
                                text = stringResource(R.string.sell_tap_fill),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PaymentModeAndDiscountCard(
    totalAmountPaise: Long,
    paymentModeIndex: Int,
    onPaymentModeIndexChange: (Int) -> Unit,
    discountInput: String,
    onDiscountInputChange: (String) -> Unit,
    amountPaidInput: String,
    onAmountPaidInputChange: (String) -> Unit
) {
    val discountPaise = IndianCurrencyFormatter.parseRupeesToPaise(discountInput).coerceIn(0L, totalAmountPaise)
    val netTotalPaise = (totalAmountPaise - discountPaise).coerceAtLeast(0L)
    val paidPaise = if (paymentModeIndex == 2) {
        IndianCurrencyFormatter.parseRupeesToPaise(amountPaidInput).coerceIn(0L, netTotalPaise)
    } else {
        netTotalPaise
    }
    val duePaise = (netTotalPaise - paidPaise).coerceAtLeast(0L)

    SectionCard(title = stringResource(R.string.sell_pay_title)) {
        SegmentedChoice(
            options = listOf(
                stringResource(R.string.checkout_pay_cash),
                stringResource(R.string.checkout_pay_upi),
                stringResource(R.string.checkout_pay_udhar),
            ),
            selectedIndex = paymentModeIndex,
            onSelect = onPaymentModeIndexChange
        )
        OutlinedTextField(
            value = discountInput,
            onValueChange = onDiscountInputChange,
            label = { Text(stringResource(R.string.sell_discount_label)) },
            placeholder = { Text(stringResource(R.string.sell_zero_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()
        )
        if (paymentModeIndex == 2) {
            OutlinedTextField(
                value = amountPaidInput,
                onValueChange = onAmountPaidInputChange,
                label = { Text(stringResource(R.string.sell_paid_now)) },
                placeholder = { Text(stringResource(R.string.sell_zero_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (paymentModeIndex == 2 && duePaise > 0L) {
            Text(
                text = stringResource(R.string.checkout_due, IndianCurrencyFormatter.formatPaise(duePaise)),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
