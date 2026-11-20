package com.shopledger.pos.ui.screens.checkout

import com.shopledger.pos.ui.text.variantLabel
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shopledger.pos.R
import com.shopledger.pos.core.buyer.BuyerHistoryHelper
import com.shopledger.pos.core.buyer.KnownBuyerProfile
import com.shopledger.pos.core.cipher.CodedPurchasePriceBadge
import com.shopledger.pos.core.error.AppErrorManager
import com.shopledger.pos.core.format.IndianCurrencyFormatter
import com.shopledger.pos.core.scanner.BarcodeScannerHelper
import com.shopledger.pos.data.model.CartLineItem
import com.shopledger.pos.ui.MAX_OPEN_CARTS
import com.shopledger.pos.ui.screens.cart.CartChip
import com.shopledger.pos.ui.screens.cart.CartSwitcher
import com.shopledger.pos.ui.text.UiText
import com.shopledger.pos.ui.theme.ChipTone
import com.shopledger.pos.ui.theme.ConfirmDialog
import com.shopledger.pos.ui.theme.EmptyState
import com.shopledger.pos.ui.theme.HapticHelper
import com.shopledger.pos.ui.theme.MoneyField
import com.shopledger.pos.ui.theme.PrimaryActionButton
import com.shopledger.pos.ui.theme.QtyStepper
import com.shopledger.pos.ui.theme.SectionCard
import com.shopledger.pos.ui.theme.ShopLedgerTokens
import com.shopledger.pos.ui.theme.StatusChip

/** How the customer pays. [wire] is the value the checkout command stores. */
enum class PayMode(val wire: String) { CASH("CASH"), UPI("UPI"), CREDIT("CREDIT") }

data class CheckoutCartTab(
    val cartId: String,
    val label: String,
    val lineCount: Int,
    val subtotalPaise: Long = 0L,
)

/** One tappable search result: a single variant that can be added to the bill. */
data class CheckoutSearchHit(
    val itemId: String,
    val variantId: String,
    val itemName: String,
    val variantSummary: String,
    val unit: String,
    val sellingPricePaise: Long,
    val stockText: String,
)

data class CheckoutState(
    val cartLabel: String = "",
    val carts: List<CheckoutCartTab> = emptyList(),
    val activeCartId: String = "",
    val lines: List<CartLineItem> = emptyList(),
    val searchQuery: String = "",
    val searchHits: List<CheckoutSearchHit> = emptyList(),
    val buyerName: String = "",
    val buyerPhone: String = "",
    val knownBuyers: List<KnownBuyerProfile> = emptyList(),
    val discountPaise: Long = 0L,
    /** Tax added on top of the discounted subtotal; zero hides the GST line. */
    val gstPaise: Long = 0L,
    val paymentMode: PayMode = PayMode.CASH,
    val amountPaidPaise: Long? = null,
    /** Cost and profit stay hidden unless seller mode is unlocked. */
    val isSellerUnlocked: Boolean = false,
    val maxCarts: Int = MAX_OPEN_CARTS,
    val matchedPartyBalancePaise: Long? = null,
    val lastSoldPriceByVariant: Map<String, Long> = emptyMap(),
) {
    val subtotalPaise: Long get() = lines.sumOf { it.lineRevenuePaise }
    val appliedDiscountPaise: Long get() = discountPaise.coerceIn(0L, subtotalPaise)
    val totalPaise: Long get() = (subtotalPaise - appliedDiscountPaise + gstPaise).coerceAtLeast(0L)
    val profitPaise: Long get() = subtotalPaise - appliedDiscountPaise - lines.sumOf { it.lineCostPaise }
}

sealed interface CheckoutEvent {
    data object Close : CheckoutEvent
    data class SearchChanged(val query: String) : CheckoutEvent
    data class CommitSearch(val query: String) : CheckoutEvent
    data class AddVariant(val itemId: String, val variantId: String) : CheckoutEvent
    data class SetQty(val variantId: String, val qty: Double) : CheckoutEvent
    data class SetPrice(val variantId: String, val pricePaise: Long) : CheckoutEvent
    data class Remove(val variantId: String) : CheckoutEvent
    data class BuyerNameChanged(val name: String) : CheckoutEvent
    data class BuyerPhoneChanged(val phone: String) : CheckoutEvent
    data class PickBuyer(val buyer: KnownBuyerProfile) : CheckoutEvent
    data class DiscountChanged(val paise: Long) : CheckoutEvent
    data class SelectMode(val mode: PayMode) : CheckoutEvent
    data class PaidNowChanged(val paise: Long?) : CheckoutEvent
    data class Complete(val mode: PayMode) : CheckoutEvent
    data object ShareEstimate : CheckoutEvent
    data object ClearCart : CheckoutEvent
    data object NewCart : CheckoutEvent
    data object CloseCart : CheckoutEvent
    data class RenameCart(val name: String) : CheckoutEvent
    data class SwitchCart(val cartId: String) : CheckoutEvent
    data object OpenOrderScanner : CheckoutEvent
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckoutScreen(
    state: CheckoutState,
    onEvent: (CheckoutEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var attemptedComplete by rememberSaveable(state.paymentMode) { mutableStateOf(false) }
    var showClearConfirm by rememberSaveable { mutableStateOf(false) }
    var showCloseConfirm by rememberSaveable { mutableStateOf(false) }
    var showRename by rememberSaveable { mutableStateOf(false) }
    var renameText by rememberSaveable(state.activeCartId, state.cartLabel) { mutableStateOf(state.cartLabel) }
    val customerMissing = state.paymentMode == PayMode.CREDIT &&
        state.buyerName.isBlank() && state.buyerPhone.isBlank()
    val canComplete = state.lines.isNotEmpty()

    Column(modifier.fillMaxSize().imePadding()) {
        CheckoutTopBar(
            state = state,
            onEvent = onEvent,
            onClear = { showClearConfirm = true },
            onRename = {
                renameText = state.cartLabel
                showRename = true
            },
            onCloseCart = {
                if (state.lines.isNotEmpty()) {
                    showCloseConfirm = true
                } else {
                    onEvent(CheckoutEvent.CloseCart)
                }
            },
        )
        CartSwitcher(
            carts = state.carts.map { CartChip(it.cartId, it.label, it.lineCount) },
            activeCartId = state.activeCartId,
            onSwitch = { onEvent(CheckoutEvent.SwitchCart(it)) },
            onNewCart = { onEvent(CheckoutEvent.NewCart) },
            maxCarts = state.maxCarts,
        )
        HorizontalDivider()

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(ShopLedgerTokens.spacingL),
            verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingM),
        ) {
            item(key = "search") { SearchBlock(state, onEvent) }

            if (state.lines.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        title = stringResource(R.string.checkout_empty_title),
                        message = stringResource(R.string.checkout_empty_message),
                    )
                }
            } else {
                items(state.lines, key = { it.variantId }) { line ->
                    CartLineCard(
                        line = line,
                        isSellerUnlocked = state.isSellerUnlocked,
                        lastSoldPricePaise = state.lastSoldPriceByVariant[line.variantId],
                        onEvent = onEvent,
                    )
                }
            }

            item(key = "customer") { CustomerBlock(state, onEvent) }
            item(key = "payment") {
                PaymentBlock(
                    state = state,
                    showCustomerError = attemptedComplete && customerMissing,
                    onEvent = onEvent,
                )
            }
            item(key = "totals") { TotalsBlock(state, onEvent) }
        }

        HorizontalDivider()
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = ShopLedgerTokens.spacingL, vertical = ShopLedgerTokens.spacingM),
            verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingXs),
        ) {
            if (state.lines.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.checkout_bar_summary,
                            state.lines.size,
                            state.lines.size,
                            checkoutPayModeLabel(state.paymentMode),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = IndianCurrencyFormatter.formatPaise(state.totalPaise),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            PrimaryActionButton(
                text = stringResource(
                    R.string.checkout_complete,
                    IndianCurrencyFormatter.formatPaise(state.totalPaise),
                ),
                enabled = canComplete,
                onClick = {
                    if (customerMissing) {
                        attemptedComplete = true
                    } else {
                        onEvent(CheckoutEvent.Complete(state.paymentMode))
                    }
                },
            )
        }
    }

    if (showClearConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.checkout_clear_title),
            message = stringResource(R.string.checkout_clear_message),
            confirmText = stringResource(R.string.checkout_clear_confirm),
            destructive = true,
            onConfirm = {
                showClearConfirm = false
                onEvent(CheckoutEvent.ClearCart)
            },
            onDismiss = { showClearConfirm = false },
        )
    }

    if (showCloseConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.cart_close_title),
            message = stringResource(R.string.cart_close_message),
            confirmText = stringResource(R.string.cart_close_confirm),
            destructive = true,
            onConfirm = {
                showCloseConfirm = false
                onEvent(CheckoutEvent.CloseCart)
            },
            onDismiss = { showCloseConfirm = false },
        )
    }

    if (showRename) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text(stringResource(R.string.cart_rename)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it.take(24) },
                    label = { Text(stringResource(R.string.cart_rename_hint)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRename = false
                        onEvent(CheckoutEvent.RenameCart(renameText.trim()))
                    },
                    modifier = Modifier.heightIn(min = ShopLedgerTokens.minTouch),
                ) {
                    Text(stringResource(R.string.cart_rename_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showRename = false },
                    modifier = Modifier.heightIn(min = ShopLedgerTokens.minTouch),
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CheckoutTopBar(
    state: CheckoutState,
    onEvent: (CheckoutEvent) -> Unit,
    onClear: () -> Unit,
    onRename: () -> Unit,
    onCloseCart: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            Text(
                text = if (state.cartLabel.isNotBlank()) {
                    stringResource(R.string.checkout_title_with_cart, state.cartLabel)
                } else {
                    stringResource(R.string.checkout_title)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            IconButton(onClick = { onEvent(CheckoutEvent.Close) }, modifier = Modifier.size(ShopLedgerTokens.minTouch)) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.checkout_cd_close))
            }
        },
        actions = {
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(ShopLedgerTokens.minTouch)) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.checkout_cd_more))
                }
                if (menuOpen) {
                    DropdownMenu(expanded = true, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.cart_rename)) },
                            onClick = {
                                menuOpen = false
                                onRename()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.checkout_menu_close_cart)) },
                            onClick = {
                                menuOpen = false
                                onCloseCart()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.checkout_menu_estimate)) },
                            enabled = state.lines.isNotEmpty(),
                            onClick = {
                                menuOpen = false
                                onEvent(CheckoutEvent.ShareEstimate)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.checkout_menu_scan)) },
                            onClick = {
                                menuOpen = false
                                onEvent(CheckoutEvent.OpenOrderScanner)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.checkout_menu_clear)) },
                            enabled = state.lines.isNotEmpty(),
                            onClick = {
                                menuOpen = false
                                onClear()
                            },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun SearchBlock(state: CheckoutState, onEvent: (CheckoutEvent) -> Unit) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        if (spoken.isNotEmpty()) {
            onEvent(CheckoutEvent.SearchChanged(spoken))
            onEvent(CheckoutEvent.CommitSearch(spoken))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS)) {
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = { onEvent(CheckoutEvent.SearchChanged(it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.checkout_search_hint)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { onEvent(CheckoutEvent.SearchChanged("")) },
                            modifier = Modifier.size(ShopLedgerTokens.minTouch),
                        ) { Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.cd_clear_search)) }
                    }
                    IconButton(
                        onClick = {
                            runCatching {
                                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                    putExtra(
                                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                                    )
                                    putExtra(
                                        RecognizerIntent.EXTRA_PROMPT,
                                        context.getString(R.string.checkout_search_hint),
                                    )
                                }
                                voiceLauncher.launch(intent)
                            }.onFailure {
                                AppErrorManager.reportInfo(
                                    UiText.Res(R.string.voice_unavailable_title),
                                    UiText.Res(R.string.voice_unavailable_msg),
                                )
                            }
                        },
                        modifier = Modifier.size(ShopLedgerTokens.minTouch),
                    ) { Icon(Icons.Filled.Mic, contentDescription = stringResource(R.string.cd_voice_search)) }
                    IconButton(
                        onClick = {
                            BarcodeScannerHelper.scanBarcode(
                                context = context,
                                onScanned = { scanned ->
                                    onEvent(CheckoutEvent.SearchChanged(scanned))
                                    onEvent(CheckoutEvent.CommitSearch(scanned))
                                },
                                onError = { err ->
                                    AppErrorManager.reportError(UiText.Res(R.string.error_barcode_scanner_failed), UiText.Res(R.string.err_scanner_detail), err)
                                },
                            )
                        },
                        modifier = Modifier.size(ShopLedgerTokens.minTouch),
                    ) { Icon(Icons.Filled.QrCodeScanner, contentDescription = stringResource(R.string.cd_scan_barcode)) }
                }
            },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    onEvent(CheckoutEvent.CommitSearch(state.searchQuery))
                    focusManager.clearFocus()
                },
            ),
        )
        if (state.searchQuery.isNotBlank()) {
            if (state.searchHits.isEmpty()) {
                Text(
                    stringResource(R.string.checkout_no_match, state.searchQuery.trim()),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                SectionCard {
                    state.searchHits.forEach { hit ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = ShopLedgerTokens.minTouch)
                                .clickable { onEvent(CheckoutEvent.AddVariant(hit.itemId, hit.variantId)) },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(hit.itemName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOf(hit.variantSummary.variantLabel(), hit.stockText).filter { it.isNotBlank() }.joinToString(" • "),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                IndianCurrencyFormatter.formatPaise(hit.sellingPricePaise),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Icon(
                                Icons.Filled.AddCircleOutline,
                                contentDescription = stringResource(R.string.cd_add_to_cart),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CartLineCard(
    line: CartLineItem,
    isSellerUnlocked: Boolean,
    lastSoldPricePaise: Long?,
    onEvent: (CheckoutEvent) -> Unit,
) {
    val context = LocalContext.current
    var editQty by remember { mutableStateOf(false) }
    var editPrice by remember { mutableStateOf(false) }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(line.itemName, style = MaterialTheme.typography.titleMedium)
                if (line.variantSummary.isNotBlank()) {
                    Text(
                        line.variantSummary.variantLabel(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Coded cost: same visibility rules as the old cart sheet (hidden / masked unless seller mode allows).
            CodedPurchasePriceBadge(
                purchasePricePaise = line.unitPurchasePricePaise,
                isSellerUnlocked = isSellerUnlocked,
            )
            IconButton(
                onClick = { onEvent(CheckoutEvent.Remove(line.variantId)) },
                modifier = Modifier.size(ShopLedgerTokens.minTouch),
            ) {
                Icon(
                    Icons.Filled.DeleteOutline,
                    contentDescription = stringResource(R.string.checkout_cd_remove_line, line.itemName),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            QtyStepper(
                valueText = IndianCurrencyFormatter.formatQuantity(line.quantity),
                onMinus = {
                    val next = line.quantity - 1.0
                    if (next <= 0.0) onEvent(CheckoutEvent.Remove(line.variantId))
                    else onEvent(CheckoutEvent.SetQty(line.variantId, next))
                },
                onPlus = { onEvent(CheckoutEvent.SetQty(line.variantId, line.quantity + 1.0)) },
                onValueClick = { editQty = true },
            )
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                TextButton(
                    onClick = { editPrice = true },
                    modifier = Modifier.heightIn(min = ShopLedgerTokens.minTouch),
                ) {
                    Text(
                        stringResource(
                            R.string.checkout_price_per,
                            IndianCurrencyFormatter.formatPaise(line.customSellingPricePaise),
                            line.unit,
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                Text(
                    IndianCurrencyFormatter.formatPaise(line.lineRevenuePaise),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        if (lastSoldPricePaise != null && lastSoldPricePaise > 0L) {
            val formattedLast = IndianCurrencyFormatter.formatPaise(lastSoldPricePaise)
            if (lastSoldPricePaise != line.customSellingPricePaise) {
                TextButton(
                    onClick = {
                        HapticHelper.tick(context)
                        onEvent(CheckoutEvent.SetPrice(line.variantId, lastSoldPricePaise))
                    },
                    modifier = Modifier.heightIn(min = ShopLedgerTokens.minTouch),
                ) {
                    Text(
                        text = stringResource(R.string.checkout_last_sold_at, formattedLast),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.checkout_last_sold_same, formattedLast),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (editQty) {
        QuantityDialog(
            unit = line.unit,
            initial = line.quantity,
            onConfirm = {
                editQty = false
                onEvent(CheckoutEvent.SetQty(line.variantId, it))
            },
            onDismiss = { editQty = false },
        )
    }
    if (editPrice) {
        PriceDialog(
            unit = line.unit,
            initialPaise = line.customSellingPricePaise,
            onConfirm = {
                editPrice = false
                onEvent(CheckoutEvent.SetPrice(line.variantId, it))
            },
            onDismiss = { editPrice = false },
        )
    }
}

/** Allows digits and one decimal point with up to three decimals (loose units sell by 0.25 kg etc.). */
internal fun sanitiseQuantityInput(input: String): String? {
    val cleaned = input.replace(',', '.').filter { it in '0'..'9' || it == '.' }
    if (cleaned.count { it == '.' } > 1) return null
    if (cleaned.substringBefore('.').length > 7) return null
    if (cleaned.substringAfter('.', "").length > 3) return null
    return cleaned
}

@Composable
private fun QuantityDialog(unit: String, initial: Double, onConfirm: (Double) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(IndianCurrencyFormatter.formatQuantity(initial)) }
    val parsed = text.toBigDecimalOrNull()?.toDouble()
    val valid = parsed != null && parsed > 0.0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.checkout_qty_dialog_title, unit)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { sanitiseQuantityInput(it)?.let { ok -> text = ok } },
                label = { Text(stringResource(R.string.checkout_qty_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { parsed?.let(onConfirm) },
                enabled = valid,
                modifier = Modifier.heightIn(min = ShopLedgerTokens.minTouch),
            ) { Text(stringResource(R.string.action_done), style = MaterialTheme.typography.labelLarge) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = ShopLedgerTokens.minTouch)) {
                Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelLarge)
            }
        },
    )
}

@Composable
private fun PriceDialog(unit: String, initialPaise: Long, onConfirm: (Long) -> Unit, onDismiss: () -> Unit) {
    var paise by remember { mutableStateOf<Long?>(initialPaise) }
    val valid = (paise ?: 0L) > 0L
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.checkout_price_dialog_title, unit)) },
        text = {
            MoneyField(
                valuePaise = paise,
                onValueChange = { paise = it },
                label = stringResource(R.string.checkout_price_label),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { paise?.let(onConfirm) },
                enabled = valid,
                modifier = Modifier.heightIn(min = ShopLedgerTokens.minTouch),
            ) { Text(stringResource(R.string.action_done), style = MaterialTheme.typography.labelLarge) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = ShopLedgerTokens.minTouch)) {
                Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelLarge)
            }
        },
    )
}

@Composable
private fun CustomerBlock(state: CheckoutState, onEvent: (CheckoutEvent) -> Unit) {
    val focusManager = LocalFocusManager.current
    val suggestions = remember(state.buyerName, state.buyerPhone, state.knownBuyers) {
        if (state.buyerName.isBlank() && state.buyerPhone.isBlank()) {
            emptyList()
        } else {
            BuyerHistoryHelper.findMatchingBuyers(state.buyerName, state.buyerPhone, state.knownBuyers)
                .filterNot {
                    it.buyerName.equals(state.buyerName.trim(), ignoreCase = true) &&
                        it.buyerPhone == state.buyerPhone.trim()
                }
                .take(3)
        }
    }
    SectionCard(title = stringResource(R.string.checkout_customer_title)) {
        OutlinedTextField(
            value = state.buyerName,
            onValueChange = { onEvent(CheckoutEvent.BuyerNameChanged(it)) },
            label = { Text(stringResource(R.string.checkout_name)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
        )
        OutlinedTextField(
            value = state.buyerPhone,
            onValueChange = { onEvent(CheckoutEvent.BuyerPhoneChanged(it)) },
            label = { Text(stringResource(R.string.checkout_phone)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
        )
        if (state.matchedPartyBalancePaise != null && state.matchedPartyBalancePaise > 0L) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(ShopLedgerTokens.spacingM),
                    verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingXs),
                ) {
                    Text(
                        text = stringResource(
                            R.string.checkout_old_due,
                            IndianCurrencyFormatter.formatPaise(state.matchedPartyBalancePaise),
                        ),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (state.paymentMode == PayMode.CREDIT) {
                        val billDue = (state.totalPaise - (state.amountPaidPaise ?: 0L).coerceIn(0L, state.totalPaise))
                            .coerceAtLeast(0L)
                        Text(
                            text = stringResource(
                                R.string.checkout_new_total_due,
                                IndianCurrencyFormatter.formatPaise(state.matchedPartyBalancePaise + billDue),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
        suggestions.forEach { buyer ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = ShopLedgerTokens.minTouch)
                    .clickable {
                        focusManager.clearFocus()
                        onEvent(CheckoutEvent.PickBuyer(buyer))
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        listOf(buyer.buyerName, buyer.buyerPhone).filter { it.isNotBlank() }.joinToString(" • "),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(R.string.checkout_regular_customer),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun PaymentBlock(state: CheckoutState, showCustomerError: Boolean, onEvent: (CheckoutEvent) -> Unit) {
    SectionCard(title = stringResource(R.string.checkout_pay_title)) {
        Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS)) {
            PayModeButton(stringResource(R.string.checkout_pay_cash), state.paymentMode == PayMode.CASH, Modifier.weight(1f)) {
                onEvent(CheckoutEvent.SelectMode(PayMode.CASH))
            }
            PayModeButton(stringResource(R.string.checkout_pay_upi), state.paymentMode == PayMode.UPI, Modifier.weight(1f)) {
                onEvent(CheckoutEvent.SelectMode(PayMode.UPI))
            }
            PayModeButton(stringResource(R.string.checkout_pay_udhar), state.paymentMode == PayMode.CREDIT, Modifier.weight(1f)) {
                onEvent(CheckoutEvent.SelectMode(PayMode.CREDIT))
            }
        }
        when (state.paymentMode) {
            PayMode.UPI -> Text(
                stringResource(R.string.checkout_upi_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PayMode.CREDIT -> {
                MoneyField(
                    valuePaise = state.amountPaidPaise,
                    onValueChange = { onEvent(CheckoutEvent.PaidNowChanged(it)) },
                    label = stringResource(R.string.checkout_paid_now),
                )
                val due = (state.totalPaise - (state.amountPaidPaise ?: 0L).coerceIn(0L, state.totalPaise)).coerceAtLeast(0L)
                if (due > 0L) {
                    Text(
                        stringResource(R.string.checkout_due, IndianCurrencyFormatter.formatPaise(due)),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (showCustomerError) {
                    Text(
                        stringResource(R.string.checkout_udhar_needs_customer),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            PayMode.CASH -> Unit
        }
    }
}

@Composable
private fun PayModeButton(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val sized = modifier
        .heightIn(min = ShopLedgerTokens.primaryActionHeight)
        .semantics { this.selected = selected; role = Role.RadioButton }
    if (selected) {
        Button(onClick = onClick, modifier = sized, shape = MaterialTheme.shapes.medium) {
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = sized, shape = MaterialTheme.shapes.medium) {
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

@Composable
private fun checkoutPayModeLabel(mode: PayMode): String = when (mode) {
    PayMode.CASH -> stringResource(R.string.checkout_pay_cash)
    PayMode.UPI -> stringResource(R.string.checkout_pay_upi)
    PayMode.CREDIT -> stringResource(R.string.checkout_pay_udhar)
}

@Composable
private fun TotalsBlock(state: CheckoutState, onEvent: (CheckoutEvent) -> Unit) {
    val context = LocalContext.current
    var discountOpen by rememberSaveable { mutableStateOf(state.discountPaise > 0L) }
    var showTargetTotalDialog by rememberSaveable { mutableStateOf(false) }
    val roundOptions = remember(state.subtotalPaise, state.discountPaise) {
        roundOffSuggestions(state.subtotalPaise, state.discountPaise)
    }
    SectionCard {
        AmountRow(stringResource(R.string.checkout_subtotal), IndianCurrencyFormatter.formatPaise(state.subtotalPaise))
        if (roundOptions.isNotEmpty() && state.gstPaise == 0L) {
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS),
                verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingXs),
            ) {
                roundOptions.forEach { opt ->
                    AssistChip(
                        onClick = {
                            HapticHelper.tick(context)
                            onEvent(CheckoutEvent.DiscountChanged(opt.discountPaise))
                        },
                        label = {
                            Text(
                                text = stringResource(
                                    R.string.checkout_round_chip,
                                    IndianCurrencyFormatter.formatPaise(opt.targetTotalPaise),
                                    IndianCurrencyFormatter.formatPaise(opt.discountPaise),
                                ),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        },
                        modifier = Modifier.heightIn(min = ShopLedgerTokens.minTouch),
                    )
                }
            }
        }
        if (discountOpen) {
            MoneyField(
                valuePaise = state.discountPaise.takeIf { it > 0L },
                onValueChange = { onEvent(CheckoutEvent.DiscountChanged(it ?: 0L)) },
                label = stringResource(R.string.checkout_discount),
            )
        } else {
            TextButton(
                onClick = { discountOpen = true },
                modifier = Modifier.heightIn(min = ShopLedgerTokens.minTouch),
            ) { Text(stringResource(R.string.checkout_add_discount), style = MaterialTheme.typography.labelLarge) }
        }
        if (state.gstPaise > 0L) {
            AmountRow(stringResource(R.string.checkout_gst), IndianCurrencyFormatter.formatPaise(state.gstPaise))
        }
        HorizontalDivider()
        val canEditTotal = state.subtotalPaise > 0L && state.gstPaise == 0L
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = ShopLedgerTokens.minTouch)
                .then(if (canEditTotal) Modifier.clickable { showTargetTotalDialog = true } else Modifier),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.checkout_total), style = MaterialTheme.typography.titleLarge)
            Text(
                IndianCurrencyFormatter.formatPaise(state.totalPaise),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (state.isSellerUnlocked) {
            AmountRow(stringResource(R.string.checkout_profit), IndianCurrencyFormatter.formatPaise(state.profitPaise))
            if (state.lines.isNotEmpty() && state.profitPaise < 0L) {
                StatusChip(
                    text = stringResource(
                        R.string.checkout_below_cost,
                        IndianCurrencyFormatter.formatPaise(-state.profitPaise),
                    ),
                    tone = ChipTone.DANGER,
                )
            } else if (state.lines.isNotEmpty() && state.totalPaise > 0L && (state.profitPaise * 100L) / state.totalPaise < 5L) {
                StatusChip(
                    text = stringResource(R.string.checkout_low_margin),
                    tone = ChipTone.WARNING,
                )
            }
        }
    }
    if (showTargetTotalDialog) {
        TargetTotalDialog(
            initialTotalPaise = state.totalPaise,
            subtotalPaise = state.subtotalPaise,
            onConfirm = { targetPaise ->
                showTargetTotalDialog = false
                val discount = (state.subtotalPaise - targetPaise).coerceIn(0L, state.subtotalPaise)
                onEvent(CheckoutEvent.DiscountChanged(discount))
            },
            onDismiss = { showTargetTotalDialog = false },
        )
    }
}

@Composable
private fun TargetTotalDialog(
    initialTotalPaise: Long,
    subtotalPaise: Long,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var paise by remember { mutableStateOf<Long?>(initialTotalPaise) }
    val valid = paise != null && paise in 0L..subtotalPaise
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.checkout_round_dialog_title)) },
        text = {
            MoneyField(
                valuePaise = paise,
                onValueChange = { paise = it },
                label = stringResource(R.string.checkout_round_dialog_label),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { paise?.let(onConfirm) },
                enabled = valid,
                modifier = Modifier.heightIn(min = ShopLedgerTokens.minTouch),
            ) { Text(stringResource(R.string.action_done), style = MaterialTheme.typography.labelLarge) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = ShopLedgerTokens.minTouch)) {
                Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelLarge)
            }
        },
    )
}

@Composable
private fun AmountRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
