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
import com.shopledger.pos.core.error.AppErrorManager
import com.shopledger.pos.core.format.IndianCurrencyFormatter
import com.shopledger.pos.core.nlp.SmartItemMatcher
import com.shopledger.pos.ui.MAX_OPEN_CARTS
import com.shopledger.pos.ui.MainViewModel
import com.shopledger.pos.ui.displayLabel
import com.shopledger.pos.ui.text.UiText
import androidx.compose.ui.platform.LocalContext

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

    val appContext = LocalContext.current
    val cartId = session.cartId
    var query by rememberSaveable { mutableStateOf("") }
    // Re-read whenever the session's stored buyer changes (scanned order, picked buyer, cart switch).
    var buyerName by remember(cartId, session.buyerName, session.buyerPhone) { mutableStateOf(session.buyerName) }
    var buyerPhone by remember(cartId, session.buyerName, session.buyerPhone) { mutableStateOf(session.buyerPhone) }
    // Payment choices survive rotation / process recreation so an Udhar sale cannot silently become Cash.
    var mode by rememberSaveable(cartId) { mutableStateOf(PayMode.CASH) }
    var discountPaise by rememberSaveable(cartId) { mutableStateOf(0L) }
    var paidNowPaise by rememberSaveable(cartId) { mutableStateOf<Long?>(null) }

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
                        v.barcode to CheckoutSearchHit(
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
                // An exact barcode hit goes first (stable sort keeps the relevance order for the rest).
                .sortedBy { (barcode, _) -> if (barcode.isNotBlank() && barcode.equals(q, ignoreCase = true)) 0 else 1 }
                .map { it.second }
                .take(MAX_HITS)
        }
    }

    val state = CheckoutState(
        cartLabel = session.displayLabel(appContext),
        carts = carts.map { CheckoutCartTab(it.cartId, it.displayLabel(appContext), it.items.size) },
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
        maxCarts = MAX_OPEN_CARTS,
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
                    val args = mapCheckoutSale(
                        subtotalPaise = cartItems.sumOf { it.lineRevenuePaise },
                        mode = event.mode,
                        discountPaise = discountPaise,
                        paidNowPaise = paidNowPaise,
                    )
                    viewModel.completeSaleAndPrepareReceipt(
                        itemsToSell = cartItems,
                        buyerName = buyerName,
                        buyerPhone = buyerPhone,
                        paymentMode = event.mode.wire,
                        discountPaise = args.discountPaise,
                        amountPaidPaise = args.amountPaidPaise,
                        // Only leave once the sale is saved; a failure keeps the bill open with its error message.
                        onComplete = { onClose() },
                    )
                }
                CheckoutEvent.ShareEstimate ->
                    viewModel.prepareEstimateImageForSharing(cartItems, buyerName, buyerPhone)
                CheckoutEvent.ClearCart -> viewModel.clearCart()
                CheckoutEvent.NewCart -> {
                    if (viewModel.createNewCart() == null) {
                        AppErrorManager.reportInfo(
                            UiText.Res(R.string.cart_limit_title),
                            UiText.Res(R.string.cart_limit_message, listOf(MAX_OPEN_CARTS)),
                        )
                    }
                }
                CheckoutEvent.CloseCart -> viewModel.closeCart(cartId)
                is CheckoutEvent.RenameCart -> viewModel.renameCart(cartId, event.name)
                is CheckoutEvent.SwitchCart -> viewModel.switchActiveCart(event.cartId)
                CheckoutEvent.OpenOrderScanner -> onOpenOrderScanner()
            }
        },
    )
}

/** What the checkout command receives: discount clamped to the subtotal, paid-now only for Udhar and clamped to the net. */
internal data class CheckoutSaleArgs(val paymentMode: String, val discountPaise: Long, val amountPaidPaise: Long?)

internal fun mapCheckoutSale(
    subtotalPaise: Long,
    mode: PayMode,
    discountPaise: Long,
    paidNowPaise: Long?,
): CheckoutSaleArgs {
    val discount = discountPaise.coerceIn(0L, subtotalPaise.coerceAtLeast(0L))
    val net = (subtotalPaise - discount).coerceAtLeast(0L)
    val paid = if (mode == PayMode.CREDIT) (paidNowPaise ?: 0L).coerceIn(0L, net) else null
    return CheckoutSaleArgs(mode.wire, discount, paid)
}

private const val MAX_HITS = 12
