package com.lpshardware.inventory.ui.screens.inventory

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddShoppingCart
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lpshardware.inventory.core.cipher.CodedPurchasePriceBadge
import com.lpshardware.inventory.core.format.IndianCurrencyFormatter
import com.lpshardware.inventory.core.share.ReceiptShareManager
import com.lpshardware.inventory.data.local.entity.InventoryItemEntity
import com.lpshardware.inventory.data.local.entity.ItemVariantEntity
import com.lpshardware.inventory.data.local.entity.SearchHistoryEntity
import com.lpshardware.inventory.data.model.InventoryItemWithVariants
import com.lpshardware.inventory.ui.InventoryFilterMode
import com.lpshardware.inventory.ui.theme.AlphabetScrubberBar
import com.lpshardware.inventory.ui.theme.CupertinoCard
import com.lpshardware.inventory.ui.theme.CupertinoColors
import com.lpshardware.inventory.ui.theme.CupertinoSegmentedControl
import com.lpshardware.inventory.ui.theme.HapticHelper
import com.lpshardware.inventory.ui.theme.SemanticPillBadge
import kotlinx.coroutines.launch

@Composable
fun InventoryScreen(
    items: List<InventoryItemWithVariants>,
    searchQuery: String,
    filterMode: InventoryFilterMode,
    isSellerUnlocked: Boolean,
    recentSearches: List<SearchHistoryEntity>,
    frequentSearches: List<SearchHistoryEntity>,
    onSearchQueryChange: (String) -> Unit,
    onCommitSearch: (String) -> Unit,
    onDeleteSearchEntry: (Long) -> Unit,
    onClearAllSearches: () -> Unit,
    onFilterModeChange: (InventoryFilterMode) -> Unit,
    onAddNewItemClick: () -> Unit,
    onAddVariantClick: (InventoryItemWithVariants) -> Unit,
    onEditItemVariantClick: (InventoryItemEntity, ItemVariantEntity) -> Unit,
    onQuickSellClick: (InventoryItemEntity, ItemVariantEntity) -> Unit,
    onAddToCartClick: (InventoryItemEntity, ItemVariantEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    var isSearchFocused by remember { mutableStateOf(false) }

    // Precompute alphabetical index map for fast A-Z right-edge scrubbing
    val letterToFirstIndex = remember(items) {
        val map = LinkedHashMap<Char, Int>()
        items.forEachIndexed { index, item ->
            val ch = item.firstLetterHeader
            if (!map.containsKey(ch)) {
                map[ch] = index
            }
        }
        map
    }

    val lowStockCount = remember(items) {
        items.count { it.hasLowStockVariant }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CupertinoColors.SystemGroupedBackground)
    ) {
        // Top Search & Segmented Control Container
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(CupertinoColors.CardSurface)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Search Bar + Add Item Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = {
                        Text(
                            text = "Search items, sizes, Hinglish (e.g. 4\" kabza)...",
                            fontSize = 13.sp,
                            color = CupertinoColors.LabelMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = CupertinoColors.LabelSecondary
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty() || isSearchFocused) {
                            IconButton(
                                onClick = {
                                    onSearchQueryChange("")
                                    isSearchFocused = false
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Clear search",
                                    tint = CupertinoColors.LabelSecondary
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            onCommitSearch(searchQuery)
                            isSearchFocused = false
                            focusManager.clearFocus()
                            keyboardController?.hide()
                        }
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = CupertinoColors.SearchFieldFill,
                        unfocusedContainerColor = CupertinoColors.SearchFieldFill,
                        focusedBorderColor = CupertinoColors.SystemBlue,
                        unfocusedBorderColor = Color.Transparent
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { state ->
                            isSearchFocused = state.isFocused
                        }
                )

                // Primary + Item Button
                Button(
                    onClick = onAddNewItemClick,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CupertinoColors.SystemBlue,
                        contentColor = Color.White
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add New Item",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }

            // Segmented Control: All Items (A-Z) vs Low Stock Alert
            CupertinoSegmentedControl(
                options = listOf(
                    "All Items A–Z (${items.size})",
                    "Low Stock Alert ($lowStockCount)"
                ),
                selectedIndex = if (filterMode == InventoryFilterMode.ALL_ALPHABETICAL) 0 else 1,
                onSelect = { idx ->
                    onFilterModeChange(
                        if (idx == 0) InventoryFilterMode.ALL_ALPHABETICAL
                        else InventoryFilterMode.LOW_STOCK_ONLY
                    )
                }
            )

            // If viewing Low Stock, show 1-tap WhatsApp Supplier Reorder List button
            if (filterMode == InventoryFilterMode.LOW_STOCK_ONLY && items.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(CupertinoColors.WhatsAppTint)
                        .border(1.dp, CupertinoColors.WhatsAppGreen.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                        .clickable {
                            val orderText = buildString {
                                append("*LPS Hardware — Reorder / Mandi Stock List*\n\n")
                                var count = 1
                                items.forEach { itemWithVars ->
                                    itemWithVars.variants
                                        .filter { it.isLowStock || it.isOutOfStock }
                                        .forEach { v ->
                                            val varLabel = v.buildVariantSummary()
                                            append(
                                                "${count++}. *${itemWithVars.item.displayName}* ($varLabel) — Current Stock: ${
                                                    IndianCurrencyFormatter.formatQuantity(v.stockQuantity)
                                                } ${v.unit}\n"
                                            )
                                        }
                                }
                            }
                            ReceiptShareManager.shareTextToWhatsApp(context, orderText)
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Share Low-Stock Order List on WhatsApp",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = CupertinoColors.WhatsAppGreen
                    )
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Share on WhatsApp",
                        tint = CupertinoColors.WhatsAppGreen,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Divider
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(CupertinoColors.Separator)
        )

        // Search History Listing Panel (Requirement #5: Shown when search bar is focused and query is empty, or via History toggle)
        if (isSearchFocused && searchQuery.isBlank() && (recentSearches.isNotEmpty() || frequentSearches.isNotEmpty())) {
            SearchHistoryPanel(
                recentSearches = recentSearches,
                frequentSearches = frequentSearches,
                onSelectQuery = { selected ->
                    onSearchQueryChange(selected)
                    onCommitSearch(selected)
                    isSearchFocused = false
                    focusManager.clearFocus()
                    keyboardController?.hide()
                },
                onDeleteEntry = onDeleteSearchEntry,
                onClearAll = onClearAllSearches,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }

        // Main Inventory List + Right-Edge A–Z Scrubber
        Box(modifier = Modifier.fillMaxSize()) {
            if (items.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = if (searchQuery.isNotBlank()) "No matching hardware items found"
                        else "No items in this view",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = CupertinoColors.LabelPrimary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (searchQuery.isNotBlank()) {
                            "Try searching by Hinglish name (e.g. kabza, pech, chatkani), size (4 inch, 100mm), or material (SS, Brass)."
                        } else {
                            "Tap '+ Add' in the top bar to add your first hardware item."
                        },
                        fontSize = 13.sp,
                        color = CupertinoColors.LabelSecondary
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 36.dp, // Space for right-edge A-Z Scrubber
                        top = 12.dp,
                        bottom = 96.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(
                        items = items,
                        key = { it.item.id }
                    ) { itemWithVariants ->
                        InventoryItemCard(
                            itemWithVariants = itemWithVariants,
                            isSellerUnlocked = isSellerUnlocked,
                            onAddVariant = { onAddVariantClick(itemWithVariants) },
                            onEditVariant = { variant ->
                                onEditItemVariantClick(itemWithVariants.item, variant)
                            },
                            onQuickSell = { variant ->
                                if (searchQuery.isNotBlank()) {
                                    onCommitSearch(searchQuery)
                                }
                                onQuickSellClick(itemWithVariants.item, variant)
                            },
                            onAddToCart = { variant ->
                                if (searchQuery.isNotBlank()) {
                                    onCommitSearch(searchQuery)
                                }
                                HapticHelper.tick(context)
                                onAddToCartClick(itemWithVariants.item, variant)
                            }
                        )
                    }
                }

                // Right-edge iOS A-Z Alphabet Scrubber
                if (letterToFirstIndex.size > 1) {
                    AlphabetScrubberBar(
                        availableLetters = letterToFirstIndex.keys.toList(),
                        onLetterSelected = { letter ->
                            val targetIdx = letterToFirstIndex[letter]
                            if (targetIdx != null) {
                                HapticHelper.tick(context)
                                coroutineScope.launch {
                                    listState.scrollToItem(targetIdx)
                                }
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 6.dp, top = 16.dp, bottom = 16.dp)
                            .fillMaxHeight(0.78f)
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchHistoryPanel(
    recentSearches: List<SearchHistoryEntity>,
    frequentSearches: List<SearchHistoryEntity>,
    onSelectQuery: (String) -> Unit,
    onDeleteEntry: (Long) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    CupertinoCard(
        modifier = modifier,
        contentPadding = PaddingValues(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = "Search History",
                    tint = CupertinoColors.SystemBlue,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "Search History",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = CupertinoColors.LabelPrimary
                )
            }
            TextButton(
                onClick = onClearAll,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "Clear All",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = CupertinoColors.DestructiveRed
                )
            }
        }

        // Frequent Search Chips
        if (frequentSearches.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                frequentSearches.forEach { freq ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = CupertinoColors.SystemBlueTint,
                        modifier = Modifier.clickable { onSelectQuery(freq.rawQuery) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.TrendingUp,
                                contentDescription = null,
                                tint = CupertinoColors.SystemBlue,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = "${freq.rawQuery} (${freq.searchFrequency}×)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = CupertinoColors.SystemBlue
                            )
                        }
                    }
                }
            }
        }

        // Recent Search Rows
        recentSearches.take(6).forEach { entry ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectQuery(entry.rawQuery) }
                    .padding(vertical = 5.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        tint = CupertinoColors.LabelMuted,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = entry.rawQuery,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = CupertinoColors.LabelPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "• ${entry.resultCount} items",
                        fontSize = 11.sp,
                        color = CupertinoColors.LabelMuted
                    )
                }
                IconButton(
                    onClick = { onDeleteEntry(entry.id) },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Remove from history",
                        tint = CupertinoColors.LabelMuted,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun InventoryItemCard(
    itemWithVariants: InventoryItemWithVariants,
    isSellerUnlocked: Boolean,
    onAddVariant: () -> Unit,
    onEditVariant: (ItemVariantEntity) -> Unit,
    onQuickSell: (ItemVariantEntity) -> Unit,
    onAddToCart: (ItemVariantEntity) -> Unit
) {
    val variants = itemWithVariants.variants
    if (variants.isEmpty()) return

    var selectedVariantIndex by rememberSaveable(itemWithVariants.item.id) { mutableIntStateOf(0) }
    val safeIndex = selectedVariantIndex.coerceIn(0, variants.lastIndex)
    val activeVariant = variants[safeIndex]

    CupertinoCard(
        borderColor = if (activeVariant.isOutOfStock) {
            CupertinoColors.DestructiveRed.copy(alpha = 0.35f)
        } else if (activeVariant.isLowStock) {
            CupertinoColors.AmberWarning.copy(alpha = 0.35f)
        } else {
            CupertinoColors.LightBorder
        }
    ) {
        // Row 1: Alphabetical Letter Badge + Item Name + Category + Stock Status Pill
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(CupertinoColors.SystemBlueTint),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = itemWithVariants.firstLetterHeader.toString(),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = CupertinoColors.SystemBlue
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = itemWithVariants.item.displayName,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = CupertinoColors.LabelPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${itemWithVariants.item.category} • ${variants.size} variant${if (variants.size > 1) "s" else ""}",
                        fontSize = 11.sp,
                        color = CupertinoColors.LabelMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Stock Badge
            val stockText = "${IndianCurrencyFormatter.formatQuantity(activeVariant.stockQuantity)} ${activeVariant.unit}"
            when {
                activeVariant.isOutOfStock -> SemanticPillBadge(
                    text = "Out of Stock (0)",
                    containerColor = CupertinoColors.DestructiveTint,
                    contentColor = CupertinoColors.DestructiveRed
                )
                activeVariant.isLowStock -> SemanticPillBadge(
                    text = "Low: $stockText",
                    containerColor = CupertinoColors.AmberTint,
                    contentColor = CupertinoColors.AmberWarning
                )
                else -> SemanticPillBadge(
                    text = "In Stock: $stockText",
                    containerColor = CupertinoColors.EmeraldTint,
                    contentColor = CupertinoColors.EmeraldGreen
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Row 2: Variant Selector Chips (Size · Material · Color/Finish) + "+ Variant" Chip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            variants.forEachIndexed { idx, variant ->
                val isSelected = idx == safeIndex
                val summary = variant.buildVariantSummary()
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSelected) CupertinoColors.SystemBlue else CupertinoColors.SecondarySurface,
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (isSelected) CupertinoColors.SystemBlue else CupertinoColors.Separator
                    ),
                    modifier = Modifier.clickable { selectedVariantIndex = idx }
                ) {
                    Text(
                        text = summary,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) Color.White else CupertinoColors.LabelPrimary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }

            // "+ Variant" chip to add another Size / Color / Material of the same item
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = CupertinoColors.SystemBlueTint,
                border = BorderStroke(1.dp, CupertinoColors.SystemBlue.copy(alpha = 0.3f)),
                modifier = Modifier.clickable { onAddVariant() }
            ) {
                Text(
                    text = "+ Variant",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = CupertinoColors.SystemBlue,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Row 3: Selling Price (₹) + Coded Purchase Price Symbol Badge (IMG_20261031_222339.jpg)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(CupertinoColors.SecondarySurface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "SELLING PRICE",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = CupertinoColors.LabelMuted
                )
                Text(
                    text = "${IndianCurrencyFormatter.formatPaise(activeVariant.sellingPricePaise)} / ${activeVariant.unit}",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = CupertinoColors.LabelPrimary
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                CodedPurchasePriceBadge(
                    purchasePricePaise = activeVariant.purchasePricePaise,
                    isSellerUnlocked = isSellerUnlocked,
                    glyphSize = 17.dp
                )
            }
        }

        // Only shown when Seller explicitly unlocks Seller Mode: Unit Margin & Gain %
        if (isSellerUnlocked) {
            val profitAnalysis = remember(
                activeVariant.purchasePricePaise,
                activeVariant.sellingPricePaise
            ) {
                IndianCurrencyFormatter.computeProfitAnalysis(
                    unitPurchasePaise = activeVariant.purchasePricePaise,
                    unitSellingPaise = activeVariant.sellingPricePaise,
                    quantity = 1.0
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Unit Margin: ${IndianCurrencyFormatter.formatPaise(profitAnalysis.totalProfitPaise, showPlusSign = true)}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = CupertinoColors.EmeraldGreen
                )
                Text(
                    text = "Gain: ${IndianCurrencyFormatter.formatPercentage(profitAnalysis.gainMarkupPercent)} • Margin: ${IndianCurrencyFormatter.formatPercentage(profitAnalysis.grossMarginPercent, showPlus = false)}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = CupertinoColors.EmeraldGreen
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Row 4: Symmetrical Ergonomic Action Buttons (Edit | + Cart | Quick Sell)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = { onEditVariant(activeVariant) },
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, CupertinoColors.Separator),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                modifier = Modifier.weight(0.9f)
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Edit Item",
                    tint = CupertinoColors.LabelSecondary,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Edit",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = CupertinoColors.LabelPrimary
                )
            }

            OutlinedButton(
                onClick = { onAddToCart(activeVariant) },
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, CupertinoColors.SystemBlue.copy(alpha = 0.45f)),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.AddShoppingCart,
                    contentDescription = "Add to Counter Cart",
                    tint = CupertinoColors.SystemBlue,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "+ Cart",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = CupertinoColors.SystemBlue
                )
            }

            Button(
                onClick = { onQuickSell(activeVariant) },
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CupertinoColors.SystemBlue,
                    contentColor = Color.White
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier.weight(1.25f)
            ) {
                Icon(
                    imageVector = Icons.Default.ShoppingBag,
                    contentDescription = "Quick Sell",
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Sell Now",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
