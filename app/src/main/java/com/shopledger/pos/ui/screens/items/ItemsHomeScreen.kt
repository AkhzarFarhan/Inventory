package com.shopledger.pos.ui.screens.items

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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddShoppingCart
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.QrCodeScanner
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
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
import com.shopledger.pos.ui.screens.stockin.StockInEntrySheet
import com.shopledger.pos.ui.theme.ChipTone
import com.shopledger.pos.ui.theme.EmptyState
import com.shopledger.pos.ui.theme.ItemRow
import com.shopledger.pos.ui.theme.PrimaryActionButton
import com.shopledger.pos.ui.theme.ShopLedgerTokens
import com.shopledger.pos.ui.theme.StatusChip
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
    cartLineCount: Int,
    onSell: () -> Unit,
    onAddItemManually: () -> Unit,
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
    /** Called with the OCR text (and photo) of a scanned supplier bill or stock list. */
    onStockScanned: (text: String, imageUri: android.net.Uri?) -> Unit = { _, _ -> },
    /** Bump this number to re-open the Stock In sheet (e.g. "Try again" from the review screen). */
    stockInRequest: Int = 0,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var searchFocused by remember { mutableStateOf(false) }
    var showStockIn by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(stockInRequest) { if (stockInRequest > 0) showStockIn = true }
    val cipherMode by CostCipherEngine.activeModeFlow.collectAsState()
    val digitMap by CostCipherEngine.activeCustomDigitMapFlow.collectAsState()
    val scannerFailedTitle = stringResource(R.string.error_barcode_scanner_failed)

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(shopName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
        Row(
            Modifier.fillMaxWidth().padding(horizontal = ShopLedgerTokens.spacingL),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusChip(text = stringResource(R.string.status_saved_on_phone), tone = ChipTone.SUCCESS)
        }

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
                                BarcodeScannerHelper.scanBarcode(
                                    context = context,
                                    onScanned = { scanned ->
                                        onSearchQueryChange(scanned)
                                        onCommitSearch(scanned)
                                    },
                                    onError = { err ->
                                        AppErrorManager.reportError(title = scannerFailedTitle, customDetail = err)
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
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }

        Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
            Row(
                Modifier.fillMaxWidth().padding(ShopLedgerTokens.spacingL),
                horizontalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingM),
            ) {
                Box(Modifier.weight(1f)) {
                    PrimaryActionButton(
                        text = stringResource(R.string.action_sell),
                        onClick = onSell,
                        icon = Icons.Filled.ShoppingCart,
                    )
                    if (cartLineCount > 0) {
                        val cartDesc = stringResource(R.string.cd_cart_lines, cartLineCount)
                        Badge(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(ShopLedgerTokens.spacingXs)
                                .semantics { contentDescription = cartDesc },
                        ) {
                            Text(cartLineCount.toString())
                        }
                    }
                }
                PrimaryActionButton(
                    text = stringResource(R.string.action_stock_in),
                    onClick = { showStockIn = true },
                    icon = Icons.Filled.Inventory2,
                    containerColor = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    if (showStockIn) {
        StockInEntrySheet(
            onAddManually = {
                showStockIn = false
                onAddItemManually()
            },
            onDismiss = { showStockIn = false },
            onScanned = { text, uri ->
                showStockIn = false
                onStockScanned(text, uri)
            },
        )
    }
}

@Composable
private fun GroupRow(
    group: InventoryItemWithVariants,
    variants: List<ItemVariantEntity>,
    isSellerUnlocked: Boolean,
    cipherMode: CipherMode,
    digitMap: Map<Char, String>,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onAddToCart: () -> Unit,
) {
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
    val subtitle = listOfNotNull(variantText, stockText).joinToString(" · ")

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

    Row(verticalAlignment = Alignment.CenterVertically) {
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
        IconButton(onClick = onAddToCart, modifier = Modifier.size(ShopLedgerTokens.minTouch)) {
            Icon(Icons.Filled.AddShoppingCart, contentDescription = stringResource(R.string.cd_add_to_cart))
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

private fun statusOf(v: ItemVariantEntity): StockStatus = when {
    v.isOversold -> StockStatus.OVERSOLD
    v.isOutOfStock -> StockStatus.OUT
    v.isLowStock -> StockStatus.LOW
    else -> StockStatus.OK
}

private fun qty(v: ItemVariantEntity): String = IndianCurrencyFormatter.formatQuantity(v.stockQuantity)

private fun ItemVariantEntity.hasAttributes(): Boolean =
    sizeLabel.isNotBlank() || materialLabel.isNotBlank() || colorFinishLabel.isNotBlank()
