package com.shopledger.pos.ui

import androidx.annotation.StringRes
import com.shopledger.pos.R
import com.shopledger.pos.ui.text.UiText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.shopledger.pos.core.buyer.BuyerHistoryHelper
import com.shopledger.pos.core.buyer.BuyerOrderLineSnapshot
import com.shopledger.pos.core.buyer.KnownBuyerProfile
import com.shopledger.pos.core.cart.CartDraftStore
import com.shopledger.pos.core.error.AppErrorManager
import com.shopledger.pos.core.nlp.DuplicateCheckResult
import com.shopledger.pos.core.sync.SyncManager
import com.shopledger.pos.core.nlp.SmartItemMatcher
import com.shopledger.pos.core.share.ReceiptLineItem
import com.shopledger.pos.core.share.SaleReceiptData
import com.shopledger.pos.data.local.dao.LastPurchaseInfo
import com.shopledger.pos.data.local.dao.SupplierItemSummary
import com.shopledger.pos.data.local.entity.InventoryItemEntity
import com.shopledger.pos.data.local.entity.InvoiceHeaderEntity
import com.shopledger.pos.data.local.entity.ItemVariantEntity
import com.shopledger.pos.data.local.entity.PartyEntity
import com.shopledger.pos.data.local.entity.PartyTransactionEntity
import com.shopledger.pos.data.local.entity.PurchaseBillEntity
import com.shopledger.pos.data.local.entity.PurchaseBillLineEntity
import com.shopledger.pos.data.local.entity.SaleRecordEntity
import com.shopledger.pos.data.local.entity.SearchHistoryEntity
import com.shopledger.pos.data.model.CartLineItem
import com.shopledger.pos.data.model.InventoryItemWithVariants
import com.shopledger.pos.data.repository.LpsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

const val MAX_OPEN_CARTS = 8

enum class InventoryFilterMode {
    ALL_ALPHABETICAL,
    LOW_STOCK_ONLY
}

data class CustomerCartSession(
    val cartId: String,
    /** A name typed by the user. Blank for default carts, which show "Cart N" in the current language. */
    val label: String = "",
    val cartNumber: Int = 1,
    val buyerName: String = "",
    val buyerPhone: String = "",
    val items: List<CartLineItem> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val checkoutActionId: String = UUID.randomUUID().toString()
)

/** The cart name to show: the user's own name, else the customer's name, else "Cart N" in the current language. */
fun CustomerCartSession.displayLabel(context: android.content.Context): String =
    label.trim().ifBlank { buyerName.trim() }.ifBlank { context.getString(R.string.cart_default_label, cartNumber) }

@OptIn(FlowPreview::class)
class MainViewModel(
    private val repository: LpsRepository
) : ViewModel() {

    /**
     * Requirement 3.1: By default, seller profit/margin & numeric purchase price are HIDDEN (`false`).
     * Only shown when seller explicitly toggles Seller Mode.
     * Note: Coded Purchase Price symbols (`IMG_20261031_222339.jpg`) remain visible on inventory cards
     * because buyers cannot decode the symbols!
     */
    private val _isSellerUnlocked = MutableStateFlow(false)
    val isSellerUnlocked: StateFlow<Boolean> = _isSellerUnlocked.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _filterMode = MutableStateFlow(InventoryFilterMode.ALL_ALPHABETICAL)
    val filterMode: StateFlow<InventoryFilterMode> = _filterMode.asStateFlow()

    private val _selectedCategory = MutableStateFlow<String?>(null)
    val selectedCategory: StateFlow<String?> = _selectedCategory.asStateFlow()

    private val _topSoldOnly = MutableStateFlow(false)
    val topSoldOnly: StateFlow<Boolean> = _topSoldOnly.asStateFlow()

    private val _carts = MutableStateFlow(
        listOf(CustomerCartSession(cartId = "cart-1", cartNumber = 1))
    )
    val cartsState: StateFlow<List<CustomerCartSession>> = _carts.asStateFlow()

    private val _activeCartId = MutableStateFlow("cart-1")
    val activeCartIdState: StateFlow<String> = _activeCartId.asStateFlow()

    val activeCartSessionState: StateFlow<CustomerCartSession> = combine(
        _carts,
        _activeCartId
    ) { carts, activeId ->
        carts.firstOrNull { it.cartId == activeId } ?: carts.first()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = _carts.value.first()
    )

    val cartItems: StateFlow<List<CartLineItem>> = activeCartSessionState
        .map { it.items }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _activeReceiptForSharing = MutableStateFlow<SaleReceiptData?>(null)
    val activeReceiptForSharing: StateFlow<SaleReceiptData?> = _activeReceiptForSharing.asStateFlow()

    val allInventoryState: StateFlow<List<InventoryItemWithVariants>> =
        repository.activeInventoryFlow.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val salesHistoryState: StateFlow<List<SaleRecordEntity>> =
        repository.salesHistoryFlow.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val topSoldItemIdsState: StateFlow<List<String>> =
        salesHistoryState.map { sales ->
            val cutoff = System.currentTimeMillis() - 30L * 86_400_000L
            sales
                .asSequence()
                .filter { !it.isReturned && it.soldAt >= cutoff }
                .groupBy { it.itemId }
                .entries
                .sortedWith(
                    compareByDescending<Map.Entry<String, List<SaleRecordEntity>>> { entry ->
                        entry.value.map { it.receiptNumber }.distinct().size
                    }.thenByDescending { entry ->
                        entry.value.sumOf { it.quantitySold }
                    },
                )
                .map { it.key }
                .take(12)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList(),
        )

    val availableCategoriesState: StateFlow<List<String>> =
        allInventoryState.map { groups ->
            groups
                .asSequence()
                .map { it.item.category.trim() }
                .filter { it.isNotEmpty() && !it.equals("Uncategorized", ignoreCase = true) }
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .map { it.key }
                .take(6)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList(),
        )

    private data class HomeFilterCriteria(
        val query: String,
        val mode: InventoryFilterMode,
        val category: String?,
        val topSoldOnly: Boolean,
    )

    val filteredInventoryState: StateFlow<List<InventoryItemWithVariants>> = combine(
        allInventoryState,
        combine(_searchQuery, _filterMode, _selectedCategory, _topSoldOnly) { q, mode, cat, top ->
            HomeFilterCriteria(q, mode, cat, top)
        },
        topSoldItemIdsState,
    ) { allItems, criteria, topIds ->
        val modeFiltered = when (criteria.mode) {
            InventoryFilterMode.ALL_ALPHABETICAL -> allItems
            InventoryFilterMode.LOW_STOCK_ONLY -> allItems.filter { it.hasLowStockVariant }
        }
        val categoryFiltered = criteria.category?.takeIf { it.isNotBlank() }?.let { selected ->
            modeFiltered.filter { it.item.category.trim().equals(selected.trim(), ignoreCase = true) }
        } ?: modeFiltered
        val topFiltered = if (criteria.topSoldOnly) {
            val rank = topIds.withIndex().associate { it.value to it.index }
            categoryFiltered
                .filter { it.item.id in rank }
                .sortedBy { rank[it.item.id] ?: Int.MAX_VALUE }
        } else {
            categoryFiltered
        }
        val trimmed = criteria.query.trim()
        if (trimmed.isBlank()) {
            topFiltered
        } else {
            topFiltered
                .mapNotNull { item ->
                    val score = SmartItemMatcher.calculateSearchRelevanceScore(trimmed, item)
                    if (score > 0.0) item to score else null
                }
                .sortedWith(
                    compareByDescending<Pair<InventoryItemWithVariants, Double>> { it.second }
                        .thenBy { it.first.item.displayName.lowercase() }
                )
                .map { it.first }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    /**
     * Reactive list of known buyers with their phone numbers and past order histories
     * for instant auto-suggest and 1-tap order item filling during sales.
     */
    val knownBuyersState: StateFlow<List<KnownBuyerProfile>> =
        salesHistoryState.map { sales ->
            BuyerHistoryHelper.extractBuyerProfiles(sales)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val recentSearchesState: StateFlow<List<SearchHistoryEntity>> =
        repository.recentSearchesFlow.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val frequentSearchesState: StateFlow<List<SearchHistoryEntity>> =
        repository.frequentSearchesFlow.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val pendingSyncCountState: StateFlow<Int> =
        repository.totalPendingSyncCountFlow.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0
        )

    val partiesState: StateFlow<List<PartyEntity>> =
        repository.partiesFlow.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val invoiceHeadersState: StateFlow<List<InvoiceHeaderEntity>> =
        repository.invoiceHeadersFlow.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val partyLedgerEntriesState: StateFlow<List<PartyTransactionEntity>> =
        repository.allLedgerEntriesFlow.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val purchaseBillsState: StateFlow<List<PurchaseBillEntity>> =
        repository.purchaseBillsFlow.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private fun launchSafe(
        @StringRes titleRes: Int,
        @StringRes fallbackDetail: Int? = null,
        block: suspend CoroutineScope.() -> Unit
    ) {
        val title = UiText.Res(titleRes)
        val fallback = UiText.Res(fallbackDetail ?: R.string.err_generic_detail)
        viewModelScope.launch(AppErrorManager.exceptionHandler(title)) {
            try {
                block()
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                AppErrorManager.reportError(title, t, fallback)
            }
        }
    }

    init {
        CartDraftStore.load(repository.appContext)?.let { snap ->
            if (snap.carts.isNotEmpty()) {
                _carts.value = snap.carts.take(MAX_OPEN_CARTS)
                _activeCartId.value = snap.activeCartId.takeIf { id -> snap.carts.any { it.cartId == id } } ?: snap.carts.first().cartId
            }
        }
        viewModelScope.launch {
            combine(_carts, _activeCartId) { c, a -> c to a }
                .drop(1)
                .debounce(300)
                .collect { (c, a) -> withContext(Dispatchers.IO) { CartDraftStore.save(repository.appContext, c, a) } }
        }
        // Existing installs keep today's behaviour. A fresh install seeds nothing until the setup wizard's choice.
        if (SyncManager.isSetupCompleted()) {
            launchSafe(R.string.err_catalog_init_title) {
                repository.seedStarterCatalogIfEmpty()
            }
        }
    }

    private val _setupSaving = MutableStateFlow(false)
    /** True while [finishSetup] runs, so the wizard can disable its buttons (no double submit). */
    val setupSaving: StateFlow<Boolean> = _setupSaving.asStateFlow()

    /**
     * Wizard finish (first run). Crash-safe order: the sample-items / empty choice is applied first (both are
     * idempotent), then [applySettings] saves the shop details and flips "setup completed" LAST. If the process dies
     * anywhere in between, the stored flag is still false, so the next launch shows the wizard again and nothing is
     * seeded twice. Failures are reported through the app message banner and re-enable the buttons.
     */
    fun finishSetup(sampleData: Boolean, applySettings: () -> Unit) {
        if (_setupSaving.value) return
        _setupSaving.value = true
        viewModelScope.launch(AppErrorManager.exceptionHandler(UiText.Res(R.string.err_setup_title))) {
            try {
                if (sampleData) repository.seedStarterCatalogIfEmpty() else repository.markStarterCatalogDone()
                applySettings()
            } catch (t: Throwable) {
                AppErrorManager.reportError(UiText.Res(R.string.err_setup_title), t)
            } finally {
                _setupSaving.value = false
            }
        }
    }

    fun toggleSellerUnlocked() {
        _isSellerUnlocked.value = !_isSellerUnlocked.value
    }

    fun setSellerUnlocked(unlocked: Boolean) {
        _isSellerUnlocked.value = unlocked
    }

    fun updateSearchQuery(newQuery: String) {
        _searchQuery.value = newQuery
    }

    fun commitSearchToHistory(query: String) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return
        launchSafe(R.string.err_search_history_title) {
            val matchCount = allInventoryState.value.count {
                SmartItemMatcher.matchesSearchQuery(trimmed, it)
            }
            repository.recordSearchQuery(trimmed, matchCount)
        }
    }

    fun deleteSearchHistoryItem(id: Long) {
        launchSafe(R.string.err_search_history_title) {
            repository.deleteSearchHistoryItem(id)
        }
    }

    fun clearAllSearchHistory() {
        launchSafe(R.string.err_search_history_title) {
            repository.clearAllSearchHistory()
        }
    }

    fun setFilterMode(mode: InventoryFilterMode) {
        _filterMode.value = mode
    }

    fun setSelectedCategory(category: String?) {
        _selectedCategory.value = category
    }

    fun setTopSoldOnly(enabled: Boolean) {
        _topSoldOnly.value = enabled
    }

    /**
     * Runs live smart semantic duplicate/variant analysis against existing inventory.
     */
    fun checkCandidateDuplicate(
        candidateName: String,
        candidateSize: String,
        candidateMaterial: String,
        candidateColorFinish: String,
        excludeItemId: String? = null
    ): DuplicateCheckResult {
        return runCatching {
            SmartItemMatcher.checkDuplicateOrVariant(
                candidateName = candidateName,
                candidateSize = candidateSize,
                candidateMaterial = candidateMaterial,
                candidateColorFinish = candidateColorFinish,
                existingInventory = allInventoryState.value,
                excludeItemId = excludeItemId
            )
        }.getOrElse { err ->
            AppErrorManager.reportError(UiText.Res(R.string.err_duplicate_check_title), err)
            DuplicateCheckResult(
                decision = com.shopledger.pos.core.nlp.DuplicateDecisionType.UNIQUE_NEW_ITEM,
                matchedItem = null,
                matchedVariantId = null,
                confidenceScore = 0.0,
                candidateSignature = SmartItemMatcher.parseSignature(candidateName),
                explanationMessage = ""
            )
        }
    }

    fun addNewItemWithVariants(
        displayName: String,
        category: String,
        variants: List<LpsRepository.NewVariantSpec>
    ) {
        launchSafe(R.string.err_item_not_saved_title) {
            repository.addNewItemWithVariants(
                displayName = displayName,
                category = category,
                variants = variants
            )
        }
    }

    fun addNewItemWithVariant(
        displayName: String,
        category: String,
        sizeLabel: String,
        materialLabel: String,
        colorFinishLabel: String,
        unit: String,
        purchasePricePaise: Long,
        sellingPricePaise: Long,
        stockQuantity: Double,
        lowStockThreshold: Double
    ) {
        launchSafe(R.string.err_item_not_saved_title) {
            repository.addNewItemWithVariant(
                displayName = displayName,
                category = category,
                sizeLabel = sizeLabel,
                materialLabel = materialLabel,
                colorFinishLabel = colorFinishLabel,
                unit = unit,
                purchasePricePaise = purchasePricePaise,
                sellingPricePaise = sellingPricePaise,
                stockQuantity = stockQuantity,
                lowStockThreshold = lowStockThreshold
            )
        }
    }

    fun addVariantToExistingItem(
        itemId: String,
        newDisplayName: String? = null,
        newCategory: String? = null,
        sizeLabel: String,
        materialLabel: String,
        colorFinishLabel: String,
        unit: String,
        purchasePricePaise: Long,
        sellingPricePaise: Long,
        stockQuantity: Double,
        lowStockThreshold: Double,
        barcode: String = ""
    ) {
        launchSafe(R.string.err_variant_not_added_title) {
            repository.addVariantToExistingItem(
                itemId = itemId,
                newDisplayName = newDisplayName,
                newCategory = newCategory,
                sizeLabel = sizeLabel,
                materialLabel = materialLabel,
                colorFinishLabel = colorFinishLabel,
                unit = unit,
                purchasePricePaise = purchasePricePaise,
                sellingPricePaise = sellingPricePaise,
                stockQuantity = stockQuantity,
                lowStockThreshold = lowStockThreshold,
                barcode = barcode
            )
        }
    }

    fun addVariantToExistingItem(
        item: InventoryItemEntity,
        newDisplayName: String,
        newCategory: String,
        sizeLabel: String,
        materialLabel: String,
        colorFinishLabel: String,
        unit: String,
        purchasePricePaise: Long,
        sellingPricePaise: Long,
        stockQuantity: Double,
        lowStockThreshold: Double,
        barcode: String = ""
    ) {
        addVariantToExistingItem(
            itemId = item.id,
            newDisplayName = newDisplayName,
            newCategory = newCategory,
            sizeLabel = sizeLabel,
            materialLabel = materialLabel,
            colorFinishLabel = colorFinishLabel,
            unit = unit,
            purchasePricePaise = purchasePricePaise,
            sellingPricePaise = sellingPricePaise,
            stockQuantity = stockQuantity,
            lowStockThreshold = lowStockThreshold,
            barcode = barcode
        )
    }

    fun updateExistingItemAndVariant(
        item: InventoryItemEntity,
        newDisplayName: String,
        newCategory: String,
        variant: ItemVariantEntity,
        newSizeLabel: String,
        newMaterialLabel: String,
        newColorFinishLabel: String,
        newUnit: String,
        newPurchasePricePaise: Long,
        newSellingPricePaise: Long,
        newStockQuantity: Double,
        newLowStockThreshold: Double,
        newBarcode: String = variant.barcode
    ) {
        launchSafe(R.string.err_item_not_updated_title) {
            repository.updateItemAndVariant(
                item = item,
                newDisplayName = newDisplayName,
                newCategory = newCategory,
                variant = variant,
                newSizeLabel = newSizeLabel,
                newMaterialLabel = newMaterialLabel,
                newColorFinishLabel = newColorFinishLabel,
                newUnit = newUnit,
                newPurchasePricePaise = newPurchasePricePaise,
                newSellingPricePaise = newSellingPricePaise,
                newStockQuantity = newStockQuantity,
                newLowStockThreshold = newLowStockThreshold,
                newBarcode = newBarcode
            )
        }
    }

    fun deleteItemOrVariant(itemId: String, variantId: String?, totalVariantsCount: Int) {
        launchSafe(R.string.err_item_not_deleted_title) {
            repository.deleteItemOrVariant(itemId, variantId, totalVariantsCount)
        }
    }

    private inline fun updateActiveCart(transform: (CustomerCartSession) -> CustomerCartSession) {
        val activeId = _activeCartId.value
        val currentCarts = _carts.value.toMutableList()
        val idx = currentCarts.indexOfFirst { it.cartId == activeId }
        if (idx >= 0) {
            currentCarts[idx] = transform(currentCarts[idx])
            _carts.value = currentCarts
        } else if (currentCarts.isNotEmpty()) {
            _activeCartId.value = currentCarts.first().cartId
            currentCarts[0] = transform(currentCarts[0])
            _carts.value = currentCarts
        }
    }

    private fun nextFreeCartNumber(carts: List<CustomerCartSession>): Int {
        val used = carts.map { it.cartNumber }.toSet()
        var n = 1
        while (n in used) n++
        return n
    }

    fun switchActiveCart(cartId: String) {
        if (_carts.value.any { it.cartId == cartId }) {
            _activeCartId.value = cartId
        }
    }

    fun createNewCart(
        customLabel: String? = null,
        initialBuyerName: String = "",
        initialBuyerPhone: String = ""
    ): String? {
        val current = _carts.value
        if (current.size >= MAX_OPEN_CARTS) return null
        val created = CustomerCartSession(
            cartId = "cart-${UUID.randomUUID()}",
            label = customLabel?.trim().orEmpty(),
            cartNumber = nextFreeCartNumber(current),
            buyerName = initialBuyerName,
            buyerPhone = initialBuyerPhone,
        )
        _carts.value = current + created
        _activeCartId.value = created.cartId
        return created.cartId
    }

    fun closeCart(cartId: String) {
        val remaining = _carts.value.filterNot { it.cartId == cartId }
        if (remaining.isEmpty()) {
            val fresh = CustomerCartSession(cartId = "cart-${UUID.randomUUID()}", cartNumber = 1)
            _carts.value = listOf(fresh)
            _activeCartId.value = fresh.cartId
        } else {
            _carts.value = remaining
            if (_activeCartId.value == cartId) {
                _activeCartId.value = remaining.last().cartId
            }
        }
    }

    fun renameCart(cartId: String, newLabel: String) {
        _carts.value = _carts.value.map { if (it.cartId == cartId) it.copy(label = newLabel.trim().take(24)) else it }
    }

    fun updateActiveCartBuyer(buyerName: String, buyerPhone: String) {
        updateActiveCart { cart ->
            cart.copy(buyerName = buyerName, buyerPhone = buyerPhone)
        }
    }

    /**
     * Adds, increments/decrements, or updates an item variant inside the currently active Cart.
     * Supports simultaneous multi-item additions and delta removals directly from Inventory cards.
     */
    fun addOrUpdateCartItem(
        item: InventoryItemEntity,
        variant: ItemVariantEntity,
        quantityToAdd: Double = 1.0,
        customSellingPricePaise: Long? = null
    ) {
        updateActiveCart { cart ->
            val current = cart.items.toMutableList()
            val existingIdx = current.indexOfFirst { it.variantId == variant.id }
            if (existingIdx >= 0) {
                val existing = current[existingIdx]
                val nextQty = existing.quantity + quantityToAdd
                if (nextQty <= 0.0) {
                    current.removeAt(existingIdx)
                } else {
                    current[existingIdx] = existing.copy(
                        quantity = nextQty,
                        customSellingPricePaise = customSellingPricePaise ?: existing.customSellingPricePaise
                    )
                }
            } else if (quantityToAdd > 0.0) {
                current.add(
                    CartLineItem(
                        itemId = item.id,
                        variantId = variant.id,
                        itemName = item.displayName,
                        variantSummary = variant.buildVariantSummary(),
                        unit = variant.unit,
                        availableStock = variant.stockQuantity,
                        unitPurchasePricePaise = variant.purchasePricePaise,
                        defaultSellingPricePaise = variant.sellingPricePaise,
                        customSellingPricePaise = customSellingPricePaise ?: variant.sellingPricePaise,
                        skuCode = variant.skuCode,
                        barcode = variant.barcode,
                        gstRateBps = variant.gstRateBps,
                        quantity = quantityToAdd
                    )
                )
            }
            cart.copy(items = current)
        }
    }

    fun updateCartLineQuantityAndPrice(
        variantId: String,
        newQuantity: Double,
        newSellingPricePaise: Long
    ) {
        updateActiveCart { cart ->
            val current = cart.items.toMutableList()
            val idx = current.indexOfFirst { it.variantId == variantId }
            if (idx >= 0) {
                if (newQuantity <= 0.0) {
                    current.removeAt(idx)
                } else {
                    current[idx] = current[idx].copy(
                        quantity = newQuantity,
                        customSellingPricePaise = newSellingPricePaise
                    )
                }
                cart.copy(items = current)
            } else {
                cart
            }
        }
    }

    fun removeCartLine(variantId: String) {
        updateActiveCart { cart ->
            cart.copy(items = cart.items.filterNot { it.variantId == variantId })
        }
    }

    fun removeMultipleCartLines(variantIds: Set<String>) {
        if (variantIds.isEmpty()) return
        updateActiveCart { cart ->
            cart.copy(items = cart.items.filterNot { it.variantId in variantIds })
        }
    }

    fun clearCart() {
        updateActiveCart { cart ->
            cart.copy(items = emptyList(), checkoutActionId = UUID.randomUUID().toString())
        }
    }

    fun batchAddItemsToActiveCart(
        lines: List<CartLineItem>,
        replaceExisting: Boolean = false
    ) {
        if (lines.isEmpty()) return
        updateActiveCart { cart ->
            if (replaceExisting) {
                cart.copy(items = lines)
            } else {
                val merged = cart.items.toMutableList()
                for (line in lines) {
                    val existingIdx = merged.indexOfFirst { it.variantId == line.variantId }
                    if (existingIdx >= 0) {
                        val existing = merged[existingIdx]
                        merged[existingIdx] = existing.copy(quantity = existing.quantity + line.quantity)
                    } else {
                        merged.add(line)
                    }
                }
                cart.copy(items = merged)
            }
        }
    }

    /**
     * Single-tap fills the Active Cart with all items from a known buyer's historical order.
     */
    fun fillCartFromBuyerHistory(snapshots: List<BuyerOrderLineSnapshot>) {
        val mapped = BuyerHistoryHelper.mapSnapshotsToCartLineItems(
            snapshots = snapshots,
            currentInventory = allInventoryState.value
        )
        if (mapped.isNotEmpty()) {
            batchAddItemsToActiveCart(mapped, replaceExisting = true)
        }
    }

    /**
     * Prepares an Estimate (Quotation) photo receipt and opens the Receipt/Estimate Image Share modal
     * so the seller can share the Estimate as a photo over WhatsApp just like a real sale.
     */
    fun prepareEstimateImageForSharing(
        itemsToEstimate: List<CartLineItem>,
        buyerName: String,
        buyerPhone: String
    ) {
        if (itemsToEstimate.isEmpty()) return
        launchSafe(R.string.err_estimate_title) {
            val estimateData = repository.prepareEstimateReceiptData(
                cartItems = itemsToEstimate,
                buyerName = buyerName,
                buyerPhone = buyerPhone
            )
            _activeReceiptForSharing.value = estimateData
        }
    }

    /**
     * Executes Multi-Item Cart Checkout, deducts inventory counts,
     * and opens the Sale Receipt Image & WhatsApp Share modal.
     */
    fun completeSaleAndPrepareReceipt(
        itemsToSell: List<CartLineItem>,
        buyerName: String,
        buyerPhone: String,
        paymentMode: String = "CASH",
        discountPaise: Long = 0L,
        amountPaidPaise: Long? = null,
        onComplete: (SaleReceiptData) -> Unit = {}
    ) {
        if (itemsToSell.isEmpty()) return
        // Captured before launching: a double tap reuses the same id and the second call is a no-op replay.
        val checkedOutCartId = _activeCartId.value
        val actionId = _carts.value.firstOrNull { it.cartId == checkedOutCartId }?.checkoutActionId ?: UUID.randomUUID().toString()
        launchSafe(R.string.err_sale_not_saved, R.string.err_sale_not_saved_detail) {
            val outcome = repository.executeCheckoutAndDeductStock(
                actionId = actionId,
                cartItems = itemsToSell,
                buyerName = buyerName,
                buyerPhone = buyerPhone,
                paymentMode = paymentMode,
                discountPaise = discountPaise,
                amountPaidPaise = amountPaidPaise
            )
            // On replay (double tap, or a retry after a post-commit failure) still clean up, but only if
            // the cart id still belongs to this checkout; a normal double tap finds it rotated.
            val stillThere = _carts.value.firstOrNull { it.cartId == checkedOutCartId }
            if (stillThere != null && stillThere.checkoutActionId == actionId) {
                if (_carts.value.size > 1) {
                    closeCart(checkedOutCartId)
                } else {
                    clearCart()
                    updateActiveCartBuyer("", "")
                }
            }
            if (!outcome.replayed && outcome.oversoldItemNames.isNotEmpty()) {
                AppErrorManager.reportWarning(
                    UiText.Res(R.string.msg_stock_below_zero_title),
                    UiText.Res(R.string.msg_stock_below_zero, listOf(outcome.oversoldItemNames.distinct().joinToString())),
                )
            }
            _activeReceiptForSharing.value = outcome.receipt
            onComplete(outcome.receipt)
        }
    }

    fun addOrUpdateParty(
        name: String,
        phone: String,
        partyType: String = "CUSTOMER",
        openingBalancePaise: Long = 0L
    ) {
        launchSafe(R.string.err_party_not_saved_title) {
            repository.addOrUpdateParty(
                name = name,
                phone = phone,
                partyType = partyType,
                openingBalancePaise = openingBalancePaise
            )
        }
    }

    fun recordPartyPaymentReceived(
        partyId: String,
        amountPaise: Long,
        paymentMode: String = "CASH",
        note: String = ""
    ) {
        launchSafe(R.string.err_payment_not_saved_title) {
            repository.recordPartyPaymentReceived(
                partyId = partyId,
                amountPaise = amountPaise,
                paymentMode = paymentMode,
                note = note
            )
        }
    }

    fun recordPaymentToDistributor(
        partyId: String,
        amountPaise: Long,
        mode: String = "CASH",
        note: String = ""
    ) {
        launchSafe(R.string.err_payment_not_saved_title) {
            repository.recordPaymentToDistributor(
                partyId = partyId,
                amountPaise = amountPaise,
                paymentMode = mode,
                note = note
            )
        }
    }

    fun addDistributor(
        name: String,
        phone: String,
        gstin: String,
        alreadyOwePaise: Long
    ) {
        launchSafe(R.string.err_party_not_saved_title) {
            repository.addOrUpdateParty(
                name = name,
                phone = phone,
                partyType = "SUPPLIER",
                gstin = gstin,
                openingBalancePaise = -alreadyOwePaise
            )
        }
    }

    fun billsForSupplier(partyId: String): Flow<List<PurchaseBillEntity>> =
        repository.billsForSupplier(partyId)

    fun itemsForSupplier(partyId: String): Flow<List<SupplierItemSummary>> =
        repository.itemsForSupplier(partyId)

    fun ledgerForParty(partyId: String): Flow<List<PartyTransactionEntity>> =
        repository.ledgerForParty(partyId)

    suspend fun purchaseLines(purchaseId: String): List<PurchaseBillLineEntity> =
        repository.purchaseLines(purchaseId)

    suspend fun lastPurchaseForSku(skuId: String): LastPurchaseInfo? =
        repository.lastPurchaseForSku(skuId)

    /**
     * Opens the Sale Receipt Image & WhatsApp Share modal for any historical sale record(s) sharing
     * the same receipt number.
     */
    fun openReceiptForHistoricalSale(clickedSale: SaleRecordEntity) {
        val matchingReceiptSales = salesHistoryState.value
            .filter { it.receiptNumber == clickedSale.receiptNumber && !it.isReturned }
            .ifEmpty { listOf(clickedSale) }

        val lineItems = matchingReceiptSales.map { s ->
            ReceiptLineItem(
                itemName = s.itemNameSnapshot,
                variantSummary = s.variantSummarySnapshot,
                quantity = s.quantitySold,
                unit = s.unitSnapshot,
                unitSellingPricePaise = s.unitSellingPricePaise,
                lineTotalPaise = s.totalRevenuePaise
            )
        }
        val subtotal = lineItems.sumOf { it.lineTotalPaise }
        val grandTotal = (subtotal - clickedSale.discountPaise + clickedSale.gstPaise).coerceAtLeast(0L)

        _activeReceiptForSharing.value = SaleReceiptData(
            receiptNumber = clickedSale.receiptNumber,
            timestampMillis = clickedSale.soldAt,
            buyerName = clickedSale.buyerName,
            buyerPhone = clickedSale.buyerPhone,
            items = lineItems,
            grandTotalPaise = grandTotal,
            paymentMode = clickedSale.paymentMode,
            subtotalPaise = subtotal,
            discountPaise = clickedSale.discountPaise,
            taxPaise = clickedSale.gstPaise,
            amountPaidPaise = clickedSale.amountPaidPaise,
            balanceDuePaise = clickedSale.balanceDuePaise
        )
    }

    fun dismissReceiptDialog() {
        _activeReceiptForSharing.value = null
    }

    fun returnSaleLine(sale: SaleRecordEntity, qtyMilli: Long, actionId: String) {
        launchSafe(R.string.err_return_not_saved_title) {
            repository.returnSaleLine(actionId, sale, qtyMilli)
            AppErrorManager.reportSuccess(UiText.Res(R.string.msg_return_done_title), UiText.Res(R.string.msg_return_done, listOf(sale.receiptNumber.toString())))
        }
    }

    suspend fun importBackupJson(json: String): Boolean {
        return runCatching { repository.importBackupJson(json) }
            .onFailure { AppErrorManager.reportError(UiText.Res(R.string.err_json_import_title), it) }
            .getOrDefault(false)
    }

    class Factory(private val repository: LpsRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MainViewModel(repository) as T
        }
    }
}
