package com.lpshardware.inventory.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lpshardware.inventory.core.buyer.BuyerHistoryHelper
import com.lpshardware.inventory.core.buyer.BuyerOrderLineSnapshot
import com.lpshardware.inventory.core.buyer.KnownBuyerProfile
import com.lpshardware.inventory.core.nlp.DuplicateCheckResult
import com.lpshardware.inventory.core.nlp.SmartItemMatcher
import com.lpshardware.inventory.core.share.ReceiptLineItem
import com.lpshardware.inventory.core.share.SaleReceiptData
import com.lpshardware.inventory.data.local.entity.InventoryItemEntity
import com.lpshardware.inventory.data.local.entity.ItemVariantEntity
import com.lpshardware.inventory.data.local.entity.SaleRecordEntity
import com.lpshardware.inventory.data.local.entity.SearchHistoryEntity
import com.lpshardware.inventory.data.model.CartLineItem
import com.lpshardware.inventory.data.model.InventoryItemWithVariants
import com.lpshardware.inventory.data.repository.LpsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class InventoryFilterMode {
    ALL_ALPHABETICAL,
    LOW_STOCK_ONLY
}

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

    private val _cartItems = MutableStateFlow<List<CartLineItem>>(emptyList())
    val cartItems: StateFlow<List<CartLineItem>> = _cartItems.asStateFlow()

    private val _activeReceiptForSharing = MutableStateFlow<SaleReceiptData?>(null)
    val activeReceiptForSharing: StateFlow<SaleReceiptData?> = _activeReceiptForSharing.asStateFlow()

    val allInventoryState: StateFlow<List<InventoryItemWithVariants>> =
        repository.activeInventoryFlow.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val filteredInventoryState: StateFlow<List<InventoryItemWithVariants>> = combine(
        allInventoryState,
        _searchQuery,
        _filterMode
    ) { allItems, query, mode ->
        val modeFiltered = when (mode) {
            InventoryFilterMode.ALL_ALPHABETICAL -> allItems
            InventoryFilterMode.LOW_STOCK_ONLY -> allItems.filter { it.hasLowStockVariant }
        }
        if (query.isBlank()) {
            modeFiltered
        } else {
            modeFiltered.filter { SmartItemMatcher.matchesSearchQuery(query, it) }
        }
    }.stateIn(
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

    init {
        viewModelScope.launch {
            repository.seedStarterCatalogIfEmpty()
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
        viewModelScope.launch {
            val matchCount = allInventoryState.value.count {
                SmartItemMatcher.matchesSearchQuery(trimmed, it)
            }
            repository.recordSearchQuery(trimmed, matchCount)
        }
    }

    fun deleteSearchHistoryItem(id: Long) {
        viewModelScope.launch {
            repository.deleteSearchHistoryItem(id)
        }
    }

    fun clearAllSearchHistory() {
        viewModelScope.launch {
            repository.clearAllSearchHistory()
        }
    }

    fun setFilterMode(mode: InventoryFilterMode) {
        _filterMode.value = mode
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
        return SmartItemMatcher.checkDuplicateOrVariant(
            candidateName = candidateName,
            candidateSize = candidateSize,
            candidateMaterial = candidateMaterial,
            candidateColorFinish = candidateColorFinish,
            existingInventory = allInventoryState.value,
            excludeItemId = excludeItemId
        )
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
        viewModelScope.launch {
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
        sizeLabel: String,
        materialLabel: String,
        colorFinishLabel: String,
        unit: String,
        purchasePricePaise: Long,
        sellingPricePaise: Long,
        stockQuantity: Double,
        lowStockThreshold: Double
    ) {
        viewModelScope.launch {
            repository.addVariantToExistingItem(
                itemId = itemId,
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
        newLowStockThreshold: Double
    ) {
        viewModelScope.launch {
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
                newLowStockThreshold = newLowStockThreshold
            )
        }
    }

    fun deleteItemOrVariant(itemId: String, variantId: String?, totalVariantsCount: Int) {
        viewModelScope.launch {
            repository.deleteItemOrVariant(itemId, variantId, totalVariantsCount)
        }
    }

    /**
     * Adds or updates an item variant inside the Multi-Item Counter Cart.
     */
    fun addOrUpdateCartItem(
        item: InventoryItemEntity,
        variant: ItemVariantEntity,
        quantityToAdd: Double = 1.0,
        customSellingPricePaise: Long = variant.sellingPricePaise
    ) {
        val current = _cartItems.value.toMutableList()
        val existingIdx = current.indexOfFirst { it.variantId == variant.id }
        if (existingIdx >= 0) {
            val existing = current[existingIdx]
            current[existingIdx] = existing.copy(
                quantity = existing.quantity + quantityToAdd,
                customSellingPricePaise = customSellingPricePaise
            )
        } else {
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
                    customSellingPricePaise = customSellingPricePaise,
                    quantity = quantityToAdd
                )
            )
        }
        _cartItems.value = current
    }

    fun updateCartLineQuantityAndPrice(
        variantId: String,
        newQuantity: Double,
        newSellingPricePaise: Long
    ) {
        val current = _cartItems.value.toMutableList()
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
            _cartItems.value = current
        }
    }

    fun removeCartLine(variantId: String) {
        _cartItems.value = _cartItems.value.filterNot { it.variantId == variantId }
    }

    fun clearCart() {
        _cartItems.value = emptyList()
    }

    /**
     * Single-tap fills the Counter Cart with all items from a known buyer's historical order.
     */
    fun fillCartFromBuyerHistory(snapshots: List<BuyerOrderLineSnapshot>) {
        val mapped = BuyerHistoryHelper.mapSnapshotsToCartLineItems(
            snapshots = snapshots,
            currentInventory = allInventoryState.value
        )
        if (mapped.isNotEmpty()) {
            _cartItems.value = mapped
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
        viewModelScope.launch {
            val estimateData = repository.prepareEstimateReceiptData(
                cartItems = itemsToEstimate,
                buyerName = buyerName,
                buyerPhone = buyerPhone
            )
            _activeReceiptForSharing.value = estimateData
        }
    }

    /**
     * Executes an instant 1-item Quick Sell or Multi-Item Cart Checkout, deducts inventory counts,
     * and opens the Sale Receipt Image & WhatsApp Share modal.
     */
    fun completeSaleAndPrepareReceipt(
        itemsToSell: List<CartLineItem>,
        buyerName: String,
        buyerPhone: String,
        clearCounterCart: Boolean,
        onComplete: (SaleReceiptData) -> Unit = {}
    ) {
        if (itemsToSell.isEmpty()) return
        viewModelScope.launch {
            val receipt = repository.executeCheckoutAndDeductStock(
                cartItems = itemsToSell,
                buyerName = buyerName,
                buyerPhone = buyerPhone
            )
            if (clearCounterCart) {
                _cartItems.value = emptyList()
            }
            _activeReceiptForSharing.value = receipt
            onComplete(receipt)
        }
    }

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
        val grandTotal = lineItems.sumOf { it.lineTotalPaise }

        _activeReceiptForSharing.value = SaleReceiptData(
            receiptNumber = clickedSale.receiptNumber,
            timestampMillis = clickedSale.soldAt,
            buyerName = clickedSale.buyerName,
            buyerPhone = clickedSale.buyerPhone,
            items = lineItems,
            grandTotalPaise = grandTotal
        )
    }

    fun dismissReceiptDialog() {
        _activeReceiptForSharing.value = null
    }

    fun returnSaleAndRestoreStock(sale: SaleRecordEntity) {
        viewModelScope.launch {
            repository.returnSaleAndRestoreStock(sale)
        }
    }

    suspend fun exportBackupJson(): String = repository.exportBackupJson()

    suspend fun importBackupJson(json: String): Boolean = repository.importBackupJson(json)

    class Factory(private val repository: LpsRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MainViewModel(repository) as T
        }
    }
}
