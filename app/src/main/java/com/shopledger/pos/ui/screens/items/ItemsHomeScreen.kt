package com.shopledger.pos.ui.screens.items

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddShoppingCart
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shopledger.pos.R
import com.shopledger.pos.core.cipher.CipherMode
import com.shopledger.pos.core.cipher.CostCipherEngine
import com.shopledger.pos.core.error.AppErrorManager
import com.shopledger.pos.core.format.IndianCurrencyFormatter
import com.shopledger.pos.core.scanner.BarcodeScannerHelper
import com.shopledger.pos.data.local.entity.InventoryItemEntity
import com.shopledger.pos.data.local.entity.ItemVariantEntity
import com.shopledger.pos.data.local.entity.SearchHistoryEntity
import com.shopledger.pos.data.model.InventoryItemWithVariants
import com.shopledger.pos.ui.screens.cart.CartChip
import com.shopledger.pos.ui.screens.cart.CartSwitcher
import com.shopledger.pos.ui.text.UiText
import com.shopledger.pos.ui.theme.EmptyState
import com.shopledger.pos.ui.theme.HapticHelper
import com.shopledger.pos.ui.theme.ItemRow
import com.shopledger.pos.ui.theme.PrimaryActionButton
import com.shopledger.pos.ui.theme.ShopLedgerTheme
import com.shopledger.pos.ui.theme.ShopLedgerTokens
import com.shopledger.pos.ui.theme.StockStatus

/**
 * Home tab: shop header, search, the item list and two big fixed buttons (Sell / Stock In).
 * Driven by plain state and lambdas so it can be tested without the ViewModel; [items] is
 * already filtered by the caller.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemsHomeScreen(
    shopName: String,
    items: List<InventoryItemWithVariants>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    lowStockOnly: Boolean,
    onLowStockOnlyChange: (Boolean) -> Unit,
    isSellerUnlocked: Boolean,
    onToggleSeller: () -> Unit,
    carts: List<CartChip> = emptyList(),
    activeCartId: String = "",
    onSwitchCart: (String) -> Unit = {},
    onNewCart: () -> Unit = {},
    onSell: () -> Unit,
    onOpenItem: (InventoryItemEntity, ItemVariantEntity, List<ItemVariantEntity>) -> Unit,
    onEditItem: (InventoryItemWithVariants, ItemVariantEntity) -> Unit,
    onAddToCart: (InventoryItemEntity, ItemVariantEntity) -> Unit,
    modifier: Modifier = Modifier,
    onCommitSearch: (String) -> Unit = {},
    recentSearches: List<SearchHistoryEntity> = emptyList(),
    onDeleteRecentSearch: (Long) -> Unit = {},
    onClearRecentSearches: () -> Unit = {},
    draftName: String? = null,
    onResumeDraft: () -> Unit = {},
    onDiscardDraft: () -> Unit = {},
    onShareLowStock: (() -> Unit)? = null,
    /** Opens the Stock In sheet. The sheet itself lives in the app shell so it survives tab changes and rotation. */
    onStockIn: () -> Unit = {},
    /** Rows in a stock-in review that is waiting behind the seller lock (null = none). Tapping the card calls [onResumeStockIn]. */
    resumeStockInRows: Int? = null,
    onResumeStockIn: () -> Unit = {},
    activeCartQtyByVariant: Map<String, Double> = emptyMap(),
    onUpdateCartQty: (InventoryItemEntity, ItemVariantEntity, Double) -> Unit = { _, _, _ -> },
    onVoiceSearchResult: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var searchFocused by remember { mutableStateOf(false) }
    val cipherMode by CostCipherEngine.activeModeFlow.collectAsState()
    val digitMap by CostCipherEngine.activeCustomDigitMapFlow.collectAsState()
    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        if (spoken.isNotEmpty()) {
            onSearchQueryChange(spoken)
            onCommitSearch(spoken)
            onVoiceSearchResult?.invoke(spoken)
        }
    }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(shopName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        text = stringResource(R.string.status_saved_on_phone),
                        style = MaterialTheme.typography.labelMedium,
                        color = ShopLedgerTheme.extra.success,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            actions = {
                IconButton(onClick = onToggleSeller, modifier = Modifier.size(ShopLedgerTokens.minTouch)) {
                    Icon(
                        imageVector = if (isSellerUnlocked) Icons.Filled.LockOpen else Icons.Filled.Lock,
                        contentDescription = stringResource(
                            if (isSellerUnlocked) R.string.cd_seller_lock else R.string.cd_seller_unlock,
                        ),
                    )
                }
            },
        )

        Column(
            Modifier.fillMaxWidth().padding(horizontal = ShopLedgerTokens.spacingL, vertical = ShopLedgerTokens.spacingS),
            verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS),
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                label = { Text(stringResource(R.string.items_search_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearchQueryChange("") }) {
                                Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.cd_clear_search))
                            }
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
                                            context.getString(R.string.items_search_hint),
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
                        ) {
                            Icon(Icons.Filled.Mic, contentDescription = stringResource(R.string.cd_voice_search))
                        }
                        IconButton(
                            onClick = {
                                BarcodeScannerHelper.scanBarcode(
                                    context = context,
                                    onScanned = { scanned ->
                                        onSearchQueryChange(scanned)
                                        onCommitSearch(scanned)
                                    },
                                    onError = { err ->
                                        AppErrorManager.reportError(UiText.Res(R.string.error_barcode_scanner_failed), UiText.Res(R.string.err_scanner_detail), err)
                                    },
                                )
                            },
                        ) {
                            Icon(Icons.Filled.QrCodeScanner, contentDescription = stringResource(R.string.cd_scan_barcode))
                        }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        onCommitSearch(searchQuery)
                        focusManager.clearFocus()
                    },
                ),
                modifier = Modifier.fillMaxWidth().onFocusChanged { searchFocused = it.isFocused },
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = lowStockOnly,
                    onClick = { onLowStockOnlyChange(!lowStockOnly) },
                    label = { Text(stringResource(R.string.filter_low_stock), style = MaterialTheme.typography.labelLarge) },
                    modifier = Modifier.heightIn(min = ShopLedgerTokens.minTouch),
                )
                if (lowStockOnly && items.isNotEmpty() && onShareLowStock != null) {
                    TextButton(onClick = onShareLowStock, modifier = Modifier.heightIn(min = ShopLedgerTokens.minTouch)) {
                        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(
                            stringResource(R.string.share_low_stock),
                            modifier = Modifier.padding(start = ShopLedgerTokens.spacingS),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (resumeStockInRows != null) {
                ResumeStockInCard(resumeStockInRows, onResumeStockIn)
            }
            if (draftName != null) {
                DraftCard(draftName, onResumeDraft, onDiscardDraft)
            }
            if (searchFocused && searchQuery.isBlank() && recentSearches.isNotEmpty()) {
                RecentSearches(
                    recent = recentSearches,
                    onPick = { picked ->
                        onSearchQueryChange(picked)
                        onCommitSearch(picked)
                        focusManager.clearFocus()
                    },
                    onClear = onClearRecentSearches,
                    onDelete = onDeleteRecentSearch,
                )
            }
        }
        HorizontalDivider()

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (items.isEmpty()) {
                val (title, message) = when {
                    searchQuery.isNotBlank() ->
                        stringResource(R.string.items_no_match_title) to stringResource(R.string.items_no_match_message)
                    lowStockOnly ->
                        stringResource(R.string.items_low_empty_title) to stringResource(R.string.items_low_empty_message)
                    else ->
                        stringResource(R.string.items_empty_title) to stringResource(R.string.items_empty_message)
                }
                EmptyState(title = title, message = message, modifier = Modifier.align(Alignment.Center))
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = ShopLedgerTokens.spacingL)) {
                    items(items, key = { it.item.id }) { group ->
                        val variants = group.variants
                        if (variants.isNotEmpty()) {
                            GroupRow(
                                group = group,
                                variants = variants,
                                isSellerUnlocked = isSellerUnlocked,
                                cipherMode = cipherMode,
                                digitMap = digitMap,
                                activeCartQtyByVariant = activeCartQtyByVariant,
                                onOpen = {
                                    if (searchQuery.isNotBlank()) onCommitSearch(searchQuery)
                                    onOpenItem(group.item, variants.first(), variants)
                                },
                                onEdit = { onEditItem(group, variants.first()) },
                                onAddToCart = {
                                    if (variants.size > 1) {
                                        if (searchQuery.isNotBlank()) onCommitSearch(searchQuery)
                                        onOpenItem(group.item, variants.first(), variants)
                                    } else {
                                        onAddToCart(group.item, variants.first())
                                    }
                                },
                                onUpdateCartQty = onUpdateCartQty,
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }

        val active = carts.firstOrNull { it.cartId == activeCartId }
        val activeLines = active?.lineCount ?: 0
        if (carts.size > 1 || activeLines > 0) {
            Surface(tonalElevation = 1.dp) {
                CartSwitcher(
                    carts = carts,
                    activeCartId = activeCartId,
                    onSwitch = onSwitchCart,
                    onNewCart = onNewCart,
                )
            }
        }

        Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
            Row(
                Modifier.fillMaxWidth().padding(ShopLedgerTokens.spacingL),
                horizontalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingM),
            ) {
                Box(Modifier.weight(1f)) {
                    PrimaryActionButton(
                        text = if (active != null && (carts.size > 1 || activeLines > 0)) {
                            stringResource(R.string.action_sell_cart, active.label)
                        } else {
                            stringResource(R.string.action_sell)
                        },
                        onClick = onSell,
                        icon = Icons.Filled.ShoppingCart,
                    )
                    if (activeLines > 0) {
                        val cartDesc = stringResource(R.string.cd_cart_lines, activeLines)
                        Badge(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(ShopLedgerTokens.spacingXs)
                                .semantics { contentDescription = cartDesc },
                        ) {
                            Text(activeLines.toString())
                        }
                    }
                }
                PrimaryActionButton(
                    text = stringResource(R.string.action_stock_in),
                    onClick = onStockIn,
                    icon = Icons.Filled.Inventory2,
                    containerColor = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun GroupRow(
    group: InventoryItemWithVariants,
    variants: List<ItemVariantEntity>,
    isSellerUnlocked: Boolean,
    cipherMode: CipherMode,
    digitMap: Map<Char, String>,
    activeCartQtyByVariant: Map<String, Double>,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onAddToCart: () -> Unit,
    onUpdateCartQty: (InventoryItemEntity, ItemVariantEntity, Double) -> Unit,
) {
    val context = LocalContext.current
    val worst = variants.maxBy { statusOf(it).ordinal }
    val status = statusOf(worst)
    val stockText = when (status) {
        StockStatus.OVERSOLD -> stringResource(R.string.stock_oversold, qty(worst), worst.unit)
        StockStatus.OUT -> stringResource(R.string.stock_out)
        StockStatus.LOW -> stringResource(R.string.stock_low, qty(worst), worst.unit)
        StockStatus.OK -> {
            val first = variants.first()
            val shownQty = if (variants.size == 1) qty(first) else IndianCurrencyFormatter.formatQuantity(group.totalStockAcrossVariants)
            stringResource(R.string.stock_in_stock, shownQty, first.unit)
        }
    }
    val variantText = if (variants.size > 1) {
        stringResource(R.string.items_variant_count, variants.size)
    } else if (variants.first().hasAttributes()) {
        variants.first().buildVariantSummary()
    } else {
        null
    }
    val inCartQty = if (variants.size == 1) activeCartQtyByVariant[variants.first().id] ?: 0.0 else 0.0
    val singleInCart = variants.size == 1 && inCartQty > 0.0
    val inCartVariantCount = if (variants.size > 1) {
        variants.count { (activeCartQtyByVariant[it.id] ?: 0.0) > 0.0 }
    } else {
        0
    }
    val inCartBadge = if (inCartVariantCount > 0) {
        pluralStringResource(R.plurals.items_in_cart_badge, inCartVariantCount, inCartVariantCount)
    } else {
        null
    }
    val subtitle = listOfNotNull(variantText, stockText, inCartBadge).joinToString(" · ")

    val minPrice = variants.minOf { it.sellingPricePaise }
    val formattedMin = IndianCurrencyFormatter.formatPaise(minPrice)
    val priceText = if (variants.any { it.sellingPricePaise != minPrice }) {
        stringResource(R.string.items_price_from, formattedMin)
    } else {
        formattedMin
    }
    val costVariant = variants.minBy { it.sellingPricePaise }
    val cost = costVariant.purchasePricePaise
    val costText: String? = when {
        cipherMode == CipherMode.CUSTOM_CODE -> {
            val coded = CostCipherEngine.encodePaiseToCustomCode(cost, digitMap)
            if (isSellerUnlocked) "$coded (${IndianCurrencyFormatter.formatPaise(cost)})" else coded
        }
        isSellerUnlocked -> IndianCurrencyFormatter.formatPaise(cost)
        else -> null
    }
    val rowBg = if (singleInCart || inCartVariantCount > 0) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.20f)
    } else {
        Color.Transparent
    }

    Row(
        modifier = Modifier.fillMaxWidth().background(rowBg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ItemRow(
            title = group.item.displayName,
            subtitle = subtitle,
            trailingPrimary = priceText,
            trailingSecondary = costText,
            trailingSecondaryColor = MaterialTheme.colorScheme.onSurfaceVariant,
            status = status,
            onClick = onOpen,
            modifier = Modifier.weight(1f),
        )
        if (singleInCart) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        HapticHelper.tick(context)
                        onUpdateCartQty(group.item, variants.first(), (inCartQty - 1.0).coerceAtLeast(0.0))
                    },
                    modifier = Modifier.size(ShopLedgerTokens.minTouch),
                ) {
                    Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.cd_decrease_quantity))
                }
                Text(
                    text = IndianCurrencyFormatter.formatQuantity(inCartQty),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
                IconButton(
                    onClick = {
                        HapticHelper.tick(context)
                        onUpdateCartQty(group.item, variants.first(), inCartQty + 1.0)
                    },
                    modifier = Modifier.size(ShopLedgerTokens.minTouch),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.cd_increase_quantity))
                }
            }
        } else {
            IconButton(onClick = onAddToCart, modifier = Modifier.size(ShopLedgerTokens.minTouch)) {
                Icon(
                    Icons.Filled.AddShoppingCart,
                    contentDescription = stringResource(R.string.cd_add_to_cart),
                    tint = if (inCartVariantCount > 0) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                )
            }
        }
        IconButton(onClick = onEdit, modifier = Modifier.size(ShopLedgerTokens.minTouch)) {
            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.cd_edit_item))
        }
    }
}

@Composable
private fun DraftCard(name: String, onResume: () -> Unit, onDiscard: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onResume),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.padding(start = ShopLedgerTokens.spacingL),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(vertical = ShopLedgerTokens.spacingS)) {
                Text(
                    stringResource(R.string.draft_saved, name.ifBlank { stringResource(R.string.draft_untitled) }),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(stringResource(R.string.draft_resume), style = MaterialTheme.typography.bodyMedium)
            }
            IconButton(onClick = onDiscard, modifier = Modifier.size(ShopLedgerTokens.minTouch)) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.draft_discard))
            }
        }
    }
}

@Composable
private fun ResumeStockInCard(rows: Int, onResume: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onResume),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = ShopLedgerTokens.spacingL, vertical = ShopLedgerTokens.spacingS)) {
            Text(
                pluralStringResource(R.plurals.stock_in_resume_title, rows, rows),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(stringResource(R.string.stock_in_resume_hint), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun RecentSearches(
    recent: List<SearchHistoryEntity>,
    onPick: (String) -> Unit,
    onClear: () -> Unit,
    onDelete: (Long) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.search_recent),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClear, modifier = Modifier.heightIn(min = ShopLedgerTokens.minTouch)) {
                Text(stringResource(R.string.search_clear_all))
            }
        }
        recent.take(6).forEach { entry ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.rawQuery,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = ShopLedgerTokens.minTouch)
                        .clickable { onPick(entry.rawQuery) }
                        .padding(vertical = ShopLedgerTokens.spacingS),
                )
                IconButton(onClick = { onDelete(entry.id) }, modifier = Modifier.size(ShopLedgerTokens.minTouch)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.cd_delete_search_entry, entry.rawQuery),
                    )
                }
            }
        }
    }
}

private fun ItemVariantEntity.hasAttributes(): Boolean =
    sizeLabel.isNotBlank() || materialLabel.isNotBlank() || colorFinishLabel.isNotBlank()
