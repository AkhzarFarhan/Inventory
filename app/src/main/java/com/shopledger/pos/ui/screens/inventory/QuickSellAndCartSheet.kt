package com.shopledger.pos.ui.screens.inventory

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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.mutableIntStateOf
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
import com.shopledger.pos.core.buyer.BuyerHistoryHelper
import com.shopledger.pos.core.buyer.BuyerOrderLineSnapshot
import com.shopledger.pos.core.buyer.KnownBuyerProfile
import com.shopledger.pos.core.cipher.CodedPurchasePriceBadge
import com.shopledger.pos.core.error.AppErrorManager
import com.shopledger.pos.core.error.FloatingAppMessageHost
import com.shopledger.pos.core.format.IndianCurrencyFormatter
import com.shopledger.pos.data.local.entity.InventoryItemEntity
import com.shopledger.pos.data.local.entity.ItemVariantEntity
import com.shopledger.pos.data.model.CartLineItem
import com.shopledger.pos.ui.CustomerCartSession
import com.shopledger.pos.ui.theme.CupertinoCard
import com.shopledger.pos.ui.theme.CupertinoColors
import com.shopledger.pos.ui.theme.CupertinoSegmentedControl
import com.shopledger.pos.ui.theme.CupertinoTextField
import com.shopledger.pos.ui.theme.HapticHelper

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
        containerColor = CupertinoColors.SystemGroupedBackground
    ) {
        FloatingAppMessageHost()
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

                if (allVariants.size > 1) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "CHOOSE VARIANT:",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = CupertinoColors.LabelMuted
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        allVariants.forEach { v ->
                            val isSelected = v.id == activeVariant.id
                            val summary = v.buildVariantSummary()
                            val priceLabel = IndianCurrencyFormatter.formatPaise(v.sellingPricePaise)
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) CupertinoColors.SystemBlue else CupertinoColors.SecondarySurface,
                                border = BorderStroke(
                                    width = 1.dp,
                                    color = if (isSelected) CupertinoColors.SystemBlue else CupertinoColors.Separator
                                ),
                                modifier = Modifier.clickable {
                                    activeVariant = v
                                    sellingPriceInput = IndianCurrencyFormatter.paiseToEditableString(v.sellingPricePaise)
                                }
                            ) {
                                Text(
                                    text = "$summary • $priceLabel",
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) Color.White else CupertinoColors.LabelPrimary,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }

                Text(
                    text = "${activeVariant.buildVariantSummary()} • Available: ${
                        IndianCurrencyFormatter.formatQuantity(activeVariant.stockQuantity)
                    } ${activeVariant.unit}",
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
                        purchasePricePaise = activeVariant.purchasePricePaise,
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

            PaymentModeAndDiscountCard(
                totalAmountPaise = profitAnalysis.totalRevenuePaise,
                paymentModeIndex = paymentModeIndex,
                onPaymentModeIndexChange = { paymentModeIndex = it },
                discountInput = discountInput,
                onDiscountInputChange = { discountInput = it },
                amountPaidInput = amountPaidInput,
                onAmountPaidInputChange = { amountPaidInput = it }
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
                        text = "Sell (${IndianCurrencyFormatter.formatPaise(netPayablePaise)})",
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
    carts: List<CustomerCartSession> = emptyList(),
    activeCartId: String = "cart-1",
    initialBuyerName: String = "",
    initialBuyerPhone: String = "",
    onSwitchCart: (String) -> Unit = {},
    onCreateNewCart: () -> Unit = {},
    onCloseCart: (String) -> Unit = {},
    onActiveCartBuyerChange: (buyerName: String, buyerPhone: String) -> Unit = { _, _ -> },
    onUpdateCartLine: (variantId: String, newQty: Double, newPricePaise: Long) -> Unit,
    onRemoveCartLine: (variantId: String) -> Unit,
    onRemoveMultipleCartLines: (Set<String>) -> Unit = {},
    onClearCart: () -> Unit,
    onFillCartFromBuyerOrder: (List<BuyerOrderLineSnapshot>) -> Unit = {},
    onOpenOrderScanner: () -> Unit = {},
    onShareEstimatePhoto: (List<CartLineItem>, buyerName: String, buyerPhone: String) -> Unit,
    onCheckoutCart: (buyerName: String, buyerPhone: String, paymentMode: String, discountPaise: Long, amountPaidPaise: Long?) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    var buyerName by remember(activeCartId, initialBuyerName) { mutableStateOf(initialBuyerName) }
    var buyerPhone by remember(activeCartId, initialBuyerPhone) { mutableStateOf(initialBuyerPhone) }
    var paymentModeIndex by remember(activeCartId) { mutableIntStateOf(0) } // 0 = CASH, 1 = UPI, 2 = CREDIT
    var discountInput by remember(activeCartId) { mutableStateOf("") }
    var amountPaidInput by remember(activeCartId) { mutableStateOf("0") }
    var isMultiSelectMode by remember(activeCartId) { mutableStateOf(false) }
    var selectedVariantIds by remember(activeCartId, cartItems) { mutableStateOf<Set<String>>(emptySet()) }

    val totalCostPaise = remember(cartItems) { cartItems.sumOf { it.lineCostPaise } }
    val totalRevenuePaise = remember(cartItems) { cartItems.sumOf { it.lineRevenuePaise } }
    val totalProfitPaise = totalRevenuePaise - totalCostPaise
    val parsedDiscountPaise = IndianCurrencyFormatter.parseRupeesToPaise(discountInput).coerceIn(0L, totalRevenuePaise)
    val netPayablePaise = (totalRevenuePaise - parsedDiscountPaise).coerceAtLeast(0L)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CupertinoColors.SystemGroupedBackground
    ) {
        FloatingAppMessageHost()
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
                    text = "Counter Cart (${cartItems.size})",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = CupertinoColors.LabelPrimary,
                    modifier = Modifier.weight(1f)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextButton(
                        onClick = onOpenOrderScanner,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DocumentScanner,
                            contentDescription = "Scan Order List",
                            tint = CupertinoColors.WhatsAppGreen,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Scan List",
                            color = CupertinoColors.WhatsAppGreen,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                    if (cartItems.isNotEmpty()) {
                        TextButton(
                            onClick = onClearCart,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "Clear",
                                color = CupertinoColors.DestructiveRed,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            // Active Cart Switcher Strip (+ New Cart / Close Cart / Instant Switch)
            if (carts.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    carts.forEach { cart ->
                        val isSelected = cart.cartId == activeCartId
                        Surface(
                            shape = RoundedCornerShape(9.dp),
                            color = if (isSelected) CupertinoColors.SystemBlue else CupertinoColors.CardSurface,
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) CupertinoColors.SystemBlue else CupertinoColors.Separator
                            ),
                            modifier = Modifier.clickable { onSwitchCart(cart.cartId) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "${cart.label} (${cart.items.size})",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) Color.White else CupertinoColors.LabelPrimary
                                )
                                if (carts.size > 1) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Close ${cart.label}",
                                        tint = if (isSelected) Color.White.copy(alpha = 0.85f) else CupertinoColors.LabelMuted,
                                        modifier = Modifier
                                            .size(14.dp)
                                            .clickable { onCloseCart(cart.cartId) }
                                    )
                                }
                            }
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(9.dp),
                        color = CupertinoColors.SystemBlueTint,
                        border = BorderStroke(1.dp, CupertinoColors.SystemBlue.copy(alpha = 0.35f)),
                        modifier = Modifier.clickable { onCreateNewCart() }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add New Cart",
                                tint = CupertinoColors.SystemBlue,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "New Cart",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = CupertinoColors.SystemBlue
                            )
                        }
                    }
                }
            }

            // Smart Buyer Search / Auto-Suggest (1-Tap Name & Phone Fill)
            BuyerSuggestAndHistoryCard(
                buyerName = buyerName,
                buyerPhone = buyerPhone,
                knownBuyers = knownBuyers,
                onBuyerNameChange = {
                    buyerName = it
                    onActiveCartBuyerChange(it, buyerPhone)
                },
                onBuyerPhoneChange = {
                    buyerPhone = it
                    onActiveCartBuyerChange(buyerName, it)
                },
                onSingleTapSelectBuyer = { selectedBuyer ->
                    HapticHelper.tick(context)
                    buyerName = selectedBuyer.buyerName
                    buyerPhone = selectedBuyer.buyerPhone
                    onActiveCartBuyerChange(selectedBuyer.buyerName, selectedBuyer.buyerPhone)
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
                        text = "This Cart is empty. Tap '+ Cart' on items in Inventory or use 'Scan List' to fill it automatically.",
                        fontSize = 13.sp,
                        color = CupertinoColors.LabelSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onOpenOrderScanner,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, CupertinoColors.WhatsAppGreen),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.DocumentScanner,
                            contentDescription = null,
                            tint = CupertinoColors.WhatsAppGreen,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Scan Order List / Receipt Photo",
                            color = CupertinoColors.WhatsAppGreen,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
            } else {
                // Multi-Select Batch Remove Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            isMultiSelectMode = !isMultiSelectMode
                            if (!isMultiSelectMode) selectedVariantIds = emptySet()
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SelectAll,
                            contentDescription = null,
                            tint = CupertinoColors.SystemBlue,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isMultiSelectMode) "Cancel Multi-Select" else "Select Multiple to Remove",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = CupertinoColors.SystemBlue
                        )
                    }

                    if (isMultiSelectMode) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(
                                onClick = {
                                    selectedVariantIds = if (selectedVariantIds.size == cartItems.size) {
                                        emptySet()
                                    } else {
                                        cartItems.map { it.variantId }.toSet()
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = if (selectedVariantIds.size == cartItems.size) "None" else "All",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            if (selectedVariantIds.isNotEmpty()) {
                                Button(
                                    onClick = {
                                        onRemoveMultipleCartLines(selectedVariantIds)
                                        selectedVariantIds = emptySet()
                                        isMultiSelectMode = false
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = CupertinoColors.DestructiveRed,
                                        contentColor = Color.White
                                    ),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "Remove (${selectedVariantIds.size})",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }

                cartItems.forEach { line ->
                    var qtyText by remember(line.variantId, line.quantity) {
                        mutableStateOf(IndianCurrencyFormatter.formatQuantity(line.quantity))
                    }
                    var priceText by remember(line.variantId, line.customSellingPricePaise) {
                        mutableStateOf(IndianCurrencyFormatter.paiseToEditableString(line.customSellingPricePaise))
                    }
                    val isChecked = line.variantId in selectedVariantIds

                    CupertinoCard(contentPadding = PaddingValues(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isMultiSelectMode) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { checked ->
                                        selectedVariantIds = if (checked) {
                                            selectedVariantIds + line.variantId
                                        } else {
                                            selectedVariantIds - line.variantId
                                        }
                                    }
                                )
                            }

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
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilledIconButton(
                                onClick = {
                                    HapticHelper.tick(context)
                                    val nextQty = line.quantity - 1.0
                                    if (nextQty <= 0.0) {
                                        onRemoveCartLine(line.variantId)
                                    } else {
                                        onUpdateCartLine(line.variantId, nextQty, line.customSellingPricePaise)
                                    }
                                },
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = CupertinoColors.SecondarySurface,
                                    contentColor = CupertinoColors.LabelPrimary
                                ),
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(Icons.Default.Remove, contentDescription = "Decrease qty", modifier = Modifier.size(15.dp))
                            }

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
                                modifier = Modifier.weight(0.75f)
                            )

                            FilledIconButton(
                                onClick = {
                                    HapticHelper.tick(context)
                                    onUpdateCartLine(line.variantId, line.quantity + 1.0, line.customSellingPricePaise)
                                },
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = CupertinoColors.SystemBlueTint,
                                    contentColor = CupertinoColors.SystemBlue
                                ),
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Increase qty", modifier = Modifier.size(15.dp))
                            }

                            CupertinoTextField(
                                value = priceText,
                                onValueChange = {
                                    priceText = it
                                    val p = IndianCurrencyFormatter.parseRupeesToPaise(it)
                                    if (p > 0) {
                                        onUpdateCartLine(line.variantId, line.quantity, p)
                                    }
                                },
                                label = "Rate (₹)",
                                keyboardType = KeyboardType.Decimal,
                                modifier = Modifier.weight(0.85f)
                            )

                            Column(
                                horizontalAlignment = Alignment.End,
                                modifier = Modifier.weight(0.8f)
                            ) {
                                Text(
                                    text = "Total",
                                    fontSize = 10.sp,
                                    color = CupertinoColors.LabelMuted
                                )
                                Text(
                                    text = IndianCurrencyFormatter.formatPaise(line.lineRevenuePaise),
                                    fontSize = 14.sp,
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
                                text = IndianCurrencyFormatter.formatPaise(netPayablePaise),
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

                PaymentModeAndDiscountCard(
                    totalAmountPaise = totalRevenuePaise,
                    paymentModeIndex = paymentModeIndex,
                    onPaymentModeIndexChange = { paymentModeIndex = it },
                    discountInput = discountInput,
                    onDiscountInputChange = { discountInput = it },
                    amountPaidInput = amountPaidInput,
                    onAmountPaidInputChange = { amountPaidInput = it }
                )

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
                            onCheckoutCart(buyerName, buyerPhone, paymentMode, parsedDiscountPaise, paidPaise)
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

    CupertinoCard {
        Text(
            text = "Payment Mode & Discount",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = CupertinoColors.LabelSecondary
        )
        Spacer(modifier = Modifier.height(8.dp))
        CupertinoSegmentedControl(
            options = listOf("Cash", "UPI", "Credit (Udhar)"),
            selectedIndex = paymentModeIndex,
            onSelect = onPaymentModeIndexChange
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CupertinoTextField(
                value = discountInput,
                onValueChange = onDiscountInputChange,
                label = "Discount (₹, Opt)",
                placeholder = "0",
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.weight(1f)
            )
            if (paymentModeIndex == 2) {
                CupertinoTextField(
                    value = amountPaidInput,
                    onValueChange = onAmountPaidInputChange,
                    label = "Paid Now (₹)",
                    placeholder = "0",
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        if (paymentModeIndex == 2 && duePaise > 0L) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Khata Balance Due (Baaki): ${IndianCurrencyFormatter.formatPaise(duePaise)}",
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
                color = CupertinoColors.DestructiveRed
            )
        }
    }
}
