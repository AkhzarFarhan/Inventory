package com.lpshardware.inventory.ui.screens.inventory

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lpshardware.inventory.core.buyer.BuyerHistoryHelper
import com.lpshardware.inventory.core.buyer.BuyerOrderLineSnapshot
import com.lpshardware.inventory.core.buyer.KnownBuyerProfile
import com.lpshardware.inventory.core.cipher.CodedPurchasePriceBadge
import com.lpshardware.inventory.core.format.IndianCurrencyFormatter
import com.lpshardware.inventory.data.local.entity.InventoryItemEntity
import com.lpshardware.inventory.data.local.entity.ItemVariantEntity
import com.lpshardware.inventory.data.model.CartLineItem
import com.lpshardware.inventory.ui.theme.CupertinoCard
import com.lpshardware.inventory.ui.theme.CupertinoColors
import com.lpshardware.inventory.ui.theme.CupertinoTextField
import com.lpshardware.inventory.ui.theme.HapticHelper

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
    isSellerUnlocked: Boolean,
    knownBuyers: List<KnownBuyerProfile>,
    onConfirmQuickSell: (CartLineItem, buyerName: String, buyerPhone: String) -> Unit,
    onShareEstimatePhoto: (List<CartLineItem>, buyerName: String, buyerPhone: String) -> Unit,
    onFillCartWithBuyerOrderAndOpenCart: (List<BuyerOrderLineSnapshot>, buyerName: String, buyerPhone: String) -> Unit = { _, _, _ -> },
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    var qtyInput by remember(variant.id) { mutableStateOf("1") }
    var sellingPriceInput by remember(variant.id) {
        mutableStateOf(IndianCurrencyFormatter.paiseToEditableString(variant.sellingPricePaise))
    }
    var buyerName by remember { mutableStateOf("") }
    var buyerPhone by remember { mutableStateOf("") }

    val parsedQty = (qtyInput.toDoubleOrNull() ?: 1.0).coerceAtLeast(0.01)
    val parsedSellingPaise = IndianCurrencyFormatter.parseRupeesToPaise(sellingPriceInput)
    val profitAnalysis = remember(variant.purchasePricePaise, parsedSellingPaise, parsedQty) {
        IndianCurrencyFormatter.computeProfitAnalysis(
            unitPurchasePaise = variant.purchasePricePaise,
            unitSellingPaise = parsedSellingPaise,
            quantity = parsedQty
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CupertinoColors.SystemGroupedBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Quick Sell Item",
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                color = CupertinoColors.LabelPrimary
            )

            // Item Details & Pricing Card
            CupertinoCard {
                Text(
                    text = item.displayName,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = CupertinoColors.LabelPrimary
                )
                Text(
                    text = "${variant.buildVariantSummary()} • Available: ${
                        IndianCurrencyFormatter.formatQuantity(variant.stockQuantity)
                    } ${variant.unit}",
                    fontSize = 12.sp,
                    color = CupertinoColors.LabelSecondary
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Coded Purchase Price Symbol Badge (No "PURCHASE CODE" label) + Total Bill
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(CupertinoColors.SecondarySurface)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CodedPurchasePriceBadge(
                        purchasePricePaise = variant.purchasePricePaise,
                        isSellerUnlocked = isSellerUnlocked
                    )

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "TOTAL BILL",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = CupertinoColors.LabelMuted
                        )
                        Text(
                            text = IndianCurrencyFormatter.formatPaise(profitAnalysis.totalRevenuePaise),
                            fontSize = 21.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = CupertinoColors.SystemBlue
                        )
                    }
                }

                // Profit Margin & Gain % (Hidden by default unless Seller Mode is explicitly unlocked)
                if (isSellerUnlocked) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (profitAnalysis.totalProfitPaise >= 0) CupertinoColors.EmeraldTint
                                else CupertinoColors.DestructiveTint
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Net Profit: ${
                                IndianCurrencyFormatter.formatPaise(
                                    profitAnalysis.totalProfitPaise,
                                    showPlusSign = true
                                )
                            }",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (profitAnalysis.totalProfitPaise >= 0) CupertinoColors.EmeraldGreen
                            else CupertinoColors.DestructiveRed
                        )
                        Text(
                            text = "Gain: ${IndianCurrencyFormatter.formatPercentage(profitAnalysis.gainMarkupPercent)} (${
                                IndianCurrencyFormatter.formatPercentage(
                                    profitAnalysis.grossMarginPercent,
                                    showPlus = false
                                )
                            } margin)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (profitAnalysis.totalProfitPaise >= 0) CupertinoColors.EmeraldGreen
                            else CupertinoColors.DestructiveRed
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Quantity Stepper + Bargaining Unit Selling Price Override
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1.1f)
                    ) {
                        FilledIconButton(
                            onClick = {
                                val next = (parsedQty - 1.0).coerceAtLeast(1.0)
                                qtyInput = IndianCurrencyFormatter.formatQuantity(next)
                                HapticHelper.tick(context)
                            },
                            shape = CircleShape,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = CupertinoColors.SecondarySurface,
                                contentColor = CupertinoColors.LabelPrimary
                            ),
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(Icons.Default.Remove, contentDescription = "Decrease Quantity")
                        }

                        CupertinoTextField(
                            value = qtyInput,
                            onValueChange = { qtyInput = it },
                            label = "Qty (${variant.unit})",
                            keyboardType = KeyboardType.Decimal,
                            modifier = Modifier.weight(1f)
                        )

                        FilledIconButton(
                            onClick = {
                                val next = parsedQty + 1.0
                                qtyInput = IndianCurrencyFormatter.formatQuantity(next)
                                HapticHelper.tick(context)
                            },
                            shape = CircleShape,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = CupertinoColors.SystemBlueTint,
                                contentColor = CupertinoColors.SystemBlue
                            ),
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Increase Quantity")
                        }
                    }

                    CupertinoTextField(
                        value = sellingPriceInput,
                        onValueChange = { sellingPriceInput = it },
                        label = "Sale Rate (₹/${variant.unit})",
                        keyboardType = KeyboardType.Decimal,
                        modifier = Modifier.weight(0.9f)
                    )
                }
            }

            // Smart Buyer Details + Existing Buyer Auto-Suggest (1-Tap Name & Phone Fill)
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
                        "Selected customer: ${selectedBuyer.buyerName.ifBlank { selectedBuyer.buyerPhone }}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )

            // Share Estimate Photo vs Complete Sale
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        if (parsedSellingPaise <= 0L) return@OutlinedButton
                        keyboardController?.hide()
                        focusManager.clearFocus()
                        val lineItem = CartLineItem(
                            itemId = item.id,
                            variantId = variant.id,
                            itemName = item.displayName,
                            variantSummary = variant.buildVariantSummary(),
                            unit = variant.unit,
                            availableStock = variant.stockQuantity,
                            unitPurchasePricePaise = variant.purchasePricePaise,
                            defaultSellingPricePaise = variant.sellingPricePaise,
                            customSellingPricePaise = parsedSellingPaise,
                            quantity = parsedQty
                        )
                        onShareEstimatePhoto(listOf(lineItem), buyerName, buyerPhone)
                        onDismiss()
                    },
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, CupertinoColors.WhatsAppGreen),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                        tint = CupertinoColors.WhatsAppGreen,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Estimate Photo",
                        color = CupertinoColors.WhatsAppGreen,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }

                Button(
                    onClick = {
                        if (parsedSellingPaise <= 0L) return@Button
                        keyboardController?.hide()
                        focusManager.clearFocus()
                        HapticHelper.successPulse(context)

                        val lineItem = CartLineItem(
                            itemId = item.id,
                            variantId = variant.id,
                            itemName = item.displayName,
                            variantSummary = variant.buildVariantSummary(),
                            unit = variant.unit,
                            availableStock = variant.stockQuantity,
                            unitPurchasePricePaise = variant.purchasePricePaise,
                            defaultSellingPricePaise = variant.sellingPricePaise,
                            customSellingPricePaise = parsedSellingPaise,
                            quantity = parsedQty
                        )
                        onConfirmQuickSell(lineItem, buyerName, buyerPhone)
                        onDismiss()
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CupertinoColors.SystemBlue,
                        contentColor = Color.White
                    ),
                    modifier = Modifier.weight(1.35f),
                    contentPadding = PaddingValues(vertical = 14.dp)
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Sell (${IndianCurrencyFormatter.formatPaise(profitAnalysis.totalRevenuePaise)})",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

/**
 * Multi-Item Counter Cart ("Quick Estimate / Kaccha Parcha") Bottom Sheet.
 * Supports Smart Buyer Auto-Suggest (single-tap fills all items from the buyer's past order!),
 * sharing an Estimate Photo over WhatsApp, or completing the sale with a Receipt Photo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CounterCartSheet(
    cartItems: List<CartLineItem>,
    isSellerUnlocked: Boolean,
    knownBuyers: List<KnownBuyerProfile>,
    initialBuyerName: String = "",
    initialBuyerPhone: String = "",
    onUpdateCartLine: (variantId: String, newQty: Double, newPricePaise: Long) -> Unit,
    onRemoveCartLine: (variantId: String) -> Unit,
    onClearCart: () -> Unit,
    onFillCartFromBuyerOrder: (List<BuyerOrderLineSnapshot>) -> Unit = {},
    onShareEstimatePhoto: (List<CartLineItem>, buyerName: String, buyerPhone: String) -> Unit,
    onCheckoutCart: (buyerName: String, buyerPhone: String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    var buyerName by remember(initialBuyerName) { mutableStateOf(initialBuyerName) }
    var buyerPhone by remember(initialBuyerPhone) { mutableStateOf(initialBuyerPhone) }

    val totalCostPaise = remember(cartItems) { cartItems.sumOf { it.lineCostPaise } }
    val totalRevenuePaise = remember(cartItems) { cartItems.sumOf { it.lineRevenuePaise } }
    val totalProfitPaise = totalRevenuePaise - totalCostPaise

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CupertinoColors.SystemGroupedBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Counter Cart / Estimate (${cartItems.size})",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = CupertinoColors.LabelPrimary
                )
                if (cartItems.isNotEmpty()) {
                    TextButton(onClick = onClearCart) {
                        Text(
                            text = "Clear Cart",
                            color = CupertinoColors.DestructiveRed,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Smart Buyer Search / Auto-Suggest at the top so seller can type a returning buyer's
            // Smart Buyer Search / Auto-Suggest (1-Tap Name & Phone Fill)
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
                        "Selected customer: ${selectedBuyer.buyerName.ifBlank { selectedBuyer.buyerPhone }}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )

            if (cartItems.isEmpty()) {
                CupertinoCard {
                    Text(
                        text = "Your Counter Cart is empty. Tap '+ Cart' on items in Inventory to add them to this sale.",
                        fontSize = 13.sp,
                        color = CupertinoColors.LabelSecondary
                    )
                }
            } else {
                cartItems.forEach { line ->
                    var qtyText by remember(line.variantId, line.quantity) {
                        mutableStateOf(IndianCurrencyFormatter.formatQuantity(line.quantity))
                    }
                    var priceText by remember(line.variantId, line.customSellingPricePaise) {
                        mutableStateOf(IndianCurrencyFormatter.paiseToEditableString(line.customSellingPricePaise))
                    }

                    CupertinoCard(contentPadding = PaddingValues(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = line.itemName,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = CupertinoColors.LabelPrimary
                                )
                                Text(
                                    text = line.variantSummary,
                                    fontSize = 11.sp,
                                    color = CupertinoColors.LabelSecondary
                                )
                            }

                            CodedPurchasePriceBadge(
                                purchasePricePaise = line.unitPurchasePricePaise,
                                isSellerUnlocked = isSellerUnlocked,
                                glyphSize = 15.dp
                            )

                            IconButton(
                                onClick = { onRemoveCartLine(line.variantId) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = "Remove item",
                                    tint = CupertinoColors.DestructiveRed
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CupertinoTextField(
                                value = qtyText,
                                onValueChange = {
                                    qtyText = it
                                    val q = it.toDoubleOrNull()
                                    if (q != null && q > 0) {
                                        onUpdateCartLine(line.variantId, q, line.customSellingPricePaise)
                                    }
                                },
                                label = "Qty (${line.unit})",
                                keyboardType = KeyboardType.Decimal,
                                modifier = Modifier.weight(0.9f)
                            )

                            CupertinoTextField(
                                value = priceText,
                                onValueChange = {
                                    priceText = it
                                    val p = IndianCurrencyFormatter.parseRupeesToPaise(it)
                                    if (p > 0) {
                                        onUpdateCartLine(line.variantId, line.quantity, p)
                                    }
                                },
                                label = "Unit Rate (₹)",
                                keyboardType = KeyboardType.Decimal,
                                modifier = Modifier.weight(1f)
                            )

                            Column(
                                horizontalAlignment = Alignment.End,
                                modifier = Modifier.weight(0.9f)
                            ) {
                                Text(
                                    text = "Line Total",
                                    fontSize = 10.sp,
                                    color = CupertinoColors.LabelMuted
                                )
                                Text(
                                    text = IndianCurrencyFormatter.formatPaise(line.lineRevenuePaise),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = CupertinoColors.LabelPrimary
                                )
                            }
                        }
                    }
                }

                // Cart Summary & Coded Total Cost (No "TOTAL COST CODE" label)
                CupertinoCard(
                    containerColor = CupertinoColors.SystemBlueTint,
                    borderColor = CupertinoColors.SystemBlue.copy(alpha = 0.35f)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CodedPurchasePriceBadge(
                            purchasePricePaise = totalCostPaise,
                            isSellerUnlocked = isSellerUnlocked
                        )

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "GRAND TOTAL",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = CupertinoColors.SystemBlue
                            )
                            Text(
                                text = IndianCurrencyFormatter.formatPaise(totalRevenuePaise),
                                fontSize = 22.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = CupertinoColors.SystemBlue
                            )
                        }
                    }

                    if (isSellerUnlocked) {
                        val analysis = IndianCurrencyFormatter.computeProfitAnalysis(
                            totalCostPaise,
                            totalRevenuePaise,
                            1.0
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Cart Net Margin: ${
                                IndianCurrencyFormatter.formatPaise(totalProfitPaise, showPlusSign = true)
                            } (${IndianCurrencyFormatter.formatPercentage(analysis.gainMarkupPercent)} gain • ${
                                IndianCurrencyFormatter.formatPercentage(analysis.grossMarginPercent, showPlus = false)
                            } margin)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = CupertinoColors.EmeraldGreen
                        )
                    }
                }

                // Share Estimate (Photo) vs Complete Sale (Deduct Stock + Receipt Photo)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            onShareEstimatePhoto(cartItems, buyerName, buyerPhone)
                            onDismiss()
                        },
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, CupertinoColors.WhatsAppGreen),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = null,
                            tint = CupertinoColors.WhatsAppGreen,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Share Estimate Photo",
                            color = CupertinoColors.WhatsAppGreen,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }

                    Button(
                        onClick = {
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            HapticHelper.successPulse(context)
                            onCheckoutCart(buyerName, buyerPhone)
                            onDismiss()
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CupertinoColors.SystemBlue,
                            contentColor = Color.White
                        ),
                        modifier = Modifier.weight(1.25f),
                        contentPadding = PaddingValues(vertical = 14.dp)
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Sell & Receipt",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
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

    CupertinoCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.PersonSearch,
                contentDescription = null,
                tint = CupertinoColors.SystemBlue,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = "Buyer WhatsApp & Name (Type to Auto-Suggest Returning Customers)",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = CupertinoColors.LabelSecondary
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CupertinoTextField(
                value = buyerPhone,
                onValueChange = onBuyerPhoneChange,
                label = "Buyer WhatsApp Phone",
                placeholder = "9876543210",
                keyboardType = KeyboardType.Phone,
                modifier = Modifier.weight(1.1f)
            )
            CupertinoTextField(
                value = buyerName,
                onValueChange = onBuyerNameChange,
                label = "Buyer Name",
                placeholder = "Customer Name",
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Done,
                onImeAction = {
                    keyboardController?.hide()
                    focusManager.clearFocus()
                },
                modifier = Modifier.weight(0.9f)
            )
        }

        // Auto-Suggest Matching Buyers when typing or when known buyers exist
        if (matchingBuyers.isNotEmpty() && exactSelectedBuyer == null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Tap a customer below to fill their Name & WhatsApp number:",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = CupertinoColors.SystemBlue
            )
            Spacer(modifier = Modifier.height(6.dp))

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                matchingBuyers.forEach { buyer ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(CupertinoColors.SystemBlueTint)
                            .border(1.dp, CupertinoColors.SystemBlue.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                            .clickable {
                                keyboardController?.hide()
                                focusManager.clearFocus()
                                onSingleTapSelectBuyer(buyer)
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = listOf(buyer.buyerName.ifBlank { "Customer" }, buyer.buyerPhone)
                                    .filter { it.isNotBlank() }
                                    .joinToString(" • "),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = CupertinoColors.LabelPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Returning Customer • ${buyer.totalDistinctOrders} past visits",
                                fontSize = 11.sp,
                                color = CupertinoColors.LabelSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = CupertinoColors.SystemBlue
                        ) {
                            Text(
                                text = "1-Tap Fill",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
