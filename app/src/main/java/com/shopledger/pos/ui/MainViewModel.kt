package com.shopledger.pos.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.shopledger.pos.core.buyer.BuyerHistoryHelper
import com.shopledger.pos.core.buyer.BuyerOrderLineSnapshot
import com.shopledger.pos.core.buyer.KnownBuyerProfile
import com.shopledger.pos.core.error.AppErrorManager
import com.shopledger.pos.core.nlp.DuplicateCheckResult
import com.shopledger.pos.core.nlp.SmartItemMatcher
import com.shopledger.pos.core.share.ReceiptLineItem
import com.shopledger.pos.core.share.SaleReceiptData
import com.shopledger.pos.data.local.entity.InventoryItemEntity
import com.shopledger.pos.data.local.entity.ItemVariantEntity
import com.shopledger.pos.data.local.entity.PartyEntity
import com.shopledger.pos.data.local.entity.SaleRecordEntity
import com.shopledger.pos.data.local.entity.SearchHistoryEntity
import com.shopledger.pos.data.model.CartLineItem
import com.shopledger.pos.data.model.InventoryItemWithVariants
import com.shopledger.pos.data.repository.LpsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

enum class InventoryFilterMode {
    ALL_ALPHABETICAL,
    LOW_STOCK_ONLY
}

data class CustomerCartSession(
    val cartId: String,
    val label: String,
    val buyerName: String = "",
    val buyerPhone: String = "",
    val items: List<CartLineItem> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val checkoutActionId: String = UUID.randomUUID().toString()
)

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

    private var nextCartSequence = 2
    private var quickSellActionId: String = UUID.randomUUID().toString()
    private val _carts = MutableStateFlow(
        listOf(CustomerCartSession(cartId = "cart-1", label = "Cart 1"))
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

    val filteredInventoryState: StateFlow<List<InventoryItemWithVariants>> = combine(
        allInventoryState,
        _searchQuery,
        _filterMode
    ) { allItems, query, mode ->
        val modeFiltered = when (mode) {
            InventoryFilterMode.ALL_ALPHABETICAL -> allItems
            InventoryFilterMode.LOW_STOCK_ONLY -> allItems.filter { it.hasLowStockVariant }
        }
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            modeFiltered
        } else {
            modeFiltered
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

    val partiesState: StateFlow<List<PartyEntity>> =
        repository.partiesFlow.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private fun launchSafe(
        operationName: String,
        block: suspend CoroutineScope.() -> Unit
    ) {
        viewModelScope.launch(AppErrorManager.exceptionHandler(operationName)) {
            try {
                block()
            } catch (t: Throwable) {
                AppErrorManager.reportError(operationName, t)
            }
        }
    }

    init {
        launchSafe("Catalog Initialization") {
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
        launchSafe("Save Search History") {
            val matchCount = allInventoryState.value.count {
                SmartItemMatcher.matchesSearchQuery(trimmed, it)
            }
            repository.recordSearchQuery(trimmed, matchCount)
        }
    }

    fun deleteSearchHistoryItem(id: Long) {
        launchSafe("Delete Search Entry") {
            repository.deleteSearchHistoryItem(id)
        }
    }

    fun clearAllSearchHistory() {
        launchSafe("Clear Search History") {
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
            AppErrorManager.reportError("Duplicate Check Failed", err)
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
        launchSafe("Failed to Save New Product") {
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
        launchSafe("Failed to Save Product") {
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
        launchSafe("Failed to Add Variant") {
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
        launchSafe("Failed to Update Item") {
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
        launchSafe("Failed to Delete Item") {
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

    fun switchActiveCart(cartId: String) {
        if (_carts.value.any { it.cartId == cartId }) {
            _activeCartId.value = cartId
        }
    }

    fun createNewCart(
        customLabel: String? = null,
        initialBuyerName: String = "",
        initialBuyerPhone: String = ""
    ): String {
        val newId = "cart-${UUID.randomUUID()}"
        val label = customLabel?.trim().takeUnless { it.isNullOrBlank() } ?: "Cart ${nextCartSequence++}"
        val created = CustomerCartSession(
            cartId = newId,
            label = label,
            buyerName = initialBuyerName,
            buyerPhone = initialBuyerPhone
        )
        _carts.value = _carts.value + created
        _activeCartId.value = newId
        return newId
    }

    fun closeCart(cartId: String) {
        val remaining = _carts.value.filterNot { it.cartId == cartId }
        if (remaining.isEmpty()) {
            val fresh = CustomerCartSession(cartId = "cart-${UUID.randomUUID()}", label = "Cart 1")
            nextCartSequence = 2
            _carts.value = listOf(fresh)
            _activeCartId.value = fresh.cartId
        } else {
            _carts.value = remaining
            if (_activeCartId.value == cartId) {
                _activeCartId.value = remaining.last().cartId
            }
        }
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
        customSellingPricePaise: Long = variant.sellingPricePaise
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
                        customSellingPricePaise = customSellingPricePaise
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
                        customSellingPricePaise = customSellingPricePaise,
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
        launchSafe("Failed to Prepare Estimate") {
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
        paymentMode: String = "CASH",
        discountPaise: Long = 0L,
        amountPaidPaise: Long? = null,
        onComplete: (SaleReceiptData) -> Unit = {}
    ) {
        if (itemsToSell.isEmpty()) return
        // Captured before launching: a double tap reuses the same id and the second call is a no-op replay.
        val checkedOutCartId = _activeCartId.value
        val actionId = if (clearCounterCart) {
            _carts.value.firstOrNull { it.cartId == checkedOutCartId }?.checkoutActionId ?: UUID.randomUUID().toString()
        } else {
            quickSellActionId
        }
        launchSafe("Checkout Failed") {
            val outcome = repository.executeCheckoutAndDeductStock(
                actionId = actionId,
                cartItems = itemsToSell,
                buyerName = buyerName,
                buyerPhone = buyerPhone,
                paymentMode = paymentMode,
                discountPaise = discountPaise,
                amountPaidPaise = amountPaidPaise
            )
            if (outcome.replayed) return@launchSafe
            if (!clearCounterCart) quickSellActionId = UUID.randomUUID().toString()
            if (clearCounterCart) {
                if (_carts.value.size > 1) {
                    closeCart(checkedOutCartId)
                } else {
                    clearCart()
                    updateActiveCartBuyer("", "")
                }
            }
            if (outcome.oversoldItemNames.isNotEmpty()) {
                AppErrorManager.reportWarning(
                    "Stock below zero",
                    "Sold more than recorded stock: ${outcome.oversoldItemNames.distinct().joinToString()}. Please recount."
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
        launchSafe("Failed to Save Khata Party") {
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
        launchSafe("Failed to Record Payment") {
            repository.recordPartyPaymentReceived(
                partyId = partyId,
                amountPaise = amountPaise,
                paymentMode = paymentMode,
                note = note
            )
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
        launchSafe("Failed to Return Sale") {
            repository.returnSaleLine(actionId, sale, qtyMilli)
            AppErrorManager.reportSuccess("Return Recorded", "Stock restored for #${sale.receiptNumber}.")
        }
    }

    suspend fun exportBackupJson(): String {
        return runCatching { repository.exportBackupJson() }
            .onFailure { AppErrorManager.reportError("Backup Export Failed", it) }
            .getOrDefault("")
    }

    suspend fun importBackupJson(json: String): Boolean {
        return runCatching { repository.importBackupJson(json) }
            .onFailure { AppErrorManager.reportError("Backup Restore Failed", it) }
            .getOrDefault(false)
    }

    class Factory(private val repository: LpsRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MainViewModel(repository) as T
        }
    }
}
