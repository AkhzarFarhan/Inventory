package com.shopledger.pos.ui.screens.checkout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.shopledger.pos.R
import com.shopledger.pos.core.format.IndianCurrencyFormatter
import com.shopledger.pos.core.nlp.SmartItemMatcher
import com.shopledger.pos.ui.MainViewModel

/**
 * Connects [CheckoutScreen] to the existing cart + idempotent checkout in [MainViewModel].
 * Search text, payment mode, discount and paid-now are per-cart drafts held here; the sale itself still goes
 * through `completeSaleAndPrepareReceipt` (one checkout action id per cart).
 */
@Composable
fun CheckoutRoute(
    viewModel: MainViewModel,
    onClose: () -> Unit,
    onOpenOrderScanner: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val allInventory by viewModel.allInventoryState.collectAsState()
    val isSellerUnlocked by viewModel.isSellerUnlocked.collectAsState()
    val knownBuyers by viewModel.knownBuyersState.collectAsState()
    val carts by viewModel.cartsState.collectAsState()
    val session by viewModel.activeCartSessionState.collectAsState()
    val cartItems by viewModel.cartItems.collectAsState()

    val cartId = session.cartId
    var query by rememberSaveable { mutableStateOf("") }
    var buyerName by remember(cartId) { mutableStateOf(session.buyerName) }
    var buyerPhone by remember(cartId) { mutableStateOf(session.buyerPhone) }
    var mode by remember(cartId) { mutableStateOf(PayMode.CASH) }
    var discountPaise by remember(cartId) { mutableStateOf(0L) }
    var paidNowPaise by remember(cartId) { mutableStateOf<Long?>(null) }

    val inStockFormat = stringResource(R.string.stock_in_stock)
    val hits = remember(query, allInventory, inStockFormat) {
        val q = query.trim()
        if (q.isEmpty()) {
            emptyList()
        } else {
            allInventory
                .mapNotNull { group ->
                    val score = SmartItemMatcher.calculateSearchRelevanceScore(q, group)
                    if (score > 0.0) group to score else null
                }
                .sortedByDescending { it.second }
                .flatMap { (group, _) ->
                    group.variants.map { v ->
                        CheckoutSearchHit(
                            itemId = group.item.id,
                            variantId = v.id,
                            itemName = group.item.displayName,
                            variantSummary = v.buildVariantSummary(),
                            unit = v.unit,
                            sellingPricePaise = v.sellingPricePaise,
                            stockText = String.format(
                                inStockFormat,
                                IndianCurrencyFormatter.formatQuantity(v.stockQuantity),
                                v.unit,
                            ),
                        )
                    }
                }
                .take(MAX_HITS)
        }
    }

    val state = CheckoutState(
        cartLabel = session.label,
        carts = carts.map { CheckoutCartTab(it.cartId, it.label, it.items.size) },
        activeCartId = cartId,
        lines = cartItems,
        searchQuery = query,
        searchHits = hits,
        buyerName = buyerName,
        buyerPhone = buyerPhone,
        knownBuyers = knownBuyers,
        discountPaise = discountPaise,
        // GST is applied (or not) inside the checkout command from the shop settings; nothing to preview here.
        gstPaise = 0L,
        paymentMode = mode,
        amountPaidPaise = paidNowPaise,
        isSellerUnlocked = isSellerUnlocked,
    )

    CheckoutScreen(
        state = state,
        modifier = modifier,
        onEvent = { event ->
            when (event) {
                CheckoutEvent.Close -> onClose()
                is CheckoutEvent.SearchChanged -> query = event.query
                is CheckoutEvent.CommitSearch -> viewModel.commitSearchToHistory(event.query)
                is CheckoutEvent.AddVariant -> {
                    allInventory.firstOrNull { it.item.id == event.itemId }?.let { group ->
                        group.variants.firstOrNull { it.id == event.variantId }?.let { variant ->
                            viewModel.addOrUpdateCartItem(group.item, variant, 1.0)
                        }
                    }
                    query = ""
                }
                is CheckoutEvent.SetQty -> {
                    cartItems.firstOrNull { it.variantId == event.variantId }?.let {
                        viewModel.updateCartLineQuantityAndPrice(event.variantId, event.qty, it.customSellingPricePaise)
                    }
                }
                is CheckoutEvent.SetPrice -> {
                    cartItems.firstOrNull { it.variantId == event.variantId }?.let {
                        viewModel.updateCartLineQuantityAndPrice(event.variantId, it.quantity, event.pricePaise)
                    }
                }
                is CheckoutEvent.Remove -> viewModel.removeCartLine(event.variantId)
                is CheckoutEvent.BuyerNameChanged -> {
                    buyerName = event.name
                    viewModel.updateActiveCartBuyer(event.name, buyerPhone)
                }
                is CheckoutEvent.BuyerPhoneChanged -> {
                    buyerPhone = event.phone
                    viewModel.updateActiveCartBuyer(buyerName, event.phone)
                }
                is CheckoutEvent.PickBuyer -> {
                    buyerName = event.buyer.buyerName
                    buyerPhone = event.buyer.buyerPhone
                    viewModel.updateActiveCartBuyer(buyerName, buyerPhone)
                }
                is CheckoutEvent.DiscountChanged -> discountPaise = event.paise
                is CheckoutEvent.SelectMode -> mode = event.mode
                is CheckoutEvent.PaidNowChanged -> paidNowPaise = event.paise
                is CheckoutEvent.Complete -> {
                    val subtotal = cartItems.sumOf { it.lineRevenuePaise }
                    val discount = discountPaise.coerceIn(0L, subtotal)
                    val net = (subtotal - discount).coerceAtLeast(0L)
                    val paid = if (event.mode == PayMode.CREDIT) (paidNowPaise ?: 0L).coerceIn(0L, net) else null
                    viewModel.completeSaleAndPrepareReceipt(
                        itemsToSell = cartItems,
                        buyerName = buyerName,
                        buyerPhone = buyerPhone,
                        clearCounterCart = true,
                        paymentMode = event.mode.wire,
                        discountPaise = discount,
                        amountPaidPaise = paid,
                        // Only leave once the sale is saved; a failure keeps the bill open with its error message.
                        onComplete = { onClose() },
                    )
                }
                CheckoutEvent.ShareEstimate ->
                    viewModel.prepareEstimateImageForSharing(cartItems, buyerName, buyerPhone)
                CheckoutEvent.ClearCart -> viewModel.clearCart()
                CheckoutEvent.NewCart -> viewModel.createNewCart()
                CheckoutEvent.CloseCart -> viewModel.closeCart(cartId)
                is CheckoutEvent.SwitchCart -> viewModel.switchActiveCart(event.cartId)
                CheckoutEvent.OpenOrderScanner -> onOpenOrderScanner()
            }
        },
    )
}

private const val MAX_HITS = 12
