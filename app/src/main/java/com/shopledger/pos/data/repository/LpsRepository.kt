package com.shopledger.pos.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.shopledger.pos.core.format.IndianCurrencyFormatter
import com.shopledger.pos.core.nlp.SmartItemMatcher
import com.shopledger.pos.core.share.ReceiptLineItem
import com.shopledger.pos.core.share.SaleReceiptData
import com.shopledger.pos.core.sync.SyncManager
import com.shopledger.pos.data.local.LpsDatabase
import com.shopledger.pos.data.local.entity.InventoryItemEntity
import com.shopledger.pos.data.local.entity.InvoiceHeaderEntity
import com.shopledger.pos.data.local.entity.InvoiceLineEntity
import com.shopledger.pos.data.local.entity.ItemVariantEntity
import com.shopledger.pos.data.local.entity.PartyEntity
import com.shopledger.pos.data.local.entity.PartyTransactionEntity
import com.shopledger.pos.data.local.entity.SaleRecordEntity
import com.shopledger.pos.data.local.entity.SearchHistoryEntity
import com.shopledger.pos.data.local.entity.StockMovementEntity
import com.shopledger.pos.data.local.entity.SyncState
import com.shopledger.pos.data.model.CartLineItem
import com.shopledger.pos.data.model.InventoryItemWithVariants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToLong

typealias ShopRepository = LpsRepository

class LpsRepository(
    private val context: Context,
    private val database: LpsDatabase
) {
    private val inventoryDao = database.inventoryDao()
    private val saleDao = database.saleDao()
    private val searchHistoryDao = database.searchHistoryDao()
    private val shopDao = database.shopPartyAndLedgerDao()

    val activeInventoryFlow: Flow<List<InventoryItemWithVariants>> =
        inventoryDao.observeAllActiveItemsAlphabetically()

    val salesHistoryFlow: Flow<List<SaleRecordEntity>> =
        saleDao.observeAllSalesChronological()

    val recentSearchesFlow: Flow<List<SearchHistoryEntity>> =
        searchHistoryDao.observeRecentSearches()

    val frequentSearchesFlow: Flow<List<SearchHistoryEntity>> =
        searchHistoryDao.observeFrequentSearches()

    val partiesFlow: Flow<List<PartyEntity>> =
        shopDao.observeAllParties()

    val allLedgerEntriesFlow: Flow<List<PartyTransactionEntity>> =
        shopDao.observeAllLedgerEntries()

    val totalPendingSyncCountFlow: Flow<Int> = combine(
        inventoryDao.observePendingItemCount(),
        inventoryDao.observePendingVariantCount(),
        saleDao.observePendingSaleCount(),
        shopDao.observePendingLedgerAndMovementCount()
    ) { items, variants, sales, ledgerAndMoves -> items + variants + sales + ledgerAndMoves }

    data class NewVariantSpec(
        val sizeLabel: String = "",
        val materialLabel: String = "",
        val colorFinishLabel: String = "",
        val unit: String = "pcs",
        val purchasePricePaise: Long,
        val sellingPricePaise: Long,
        val stockQuantity: Double = 10.0,
        val lowStockThreshold: Double = 5.0,
        val barcode: String = "",
        val skuCode: String = "",
        val gstRateBps: Int = 0
    )

    private fun activeShopId(): String = SyncManager.getConfig(context).shopCollectionId

    /**
     * Adds a brand-new parent product along with multiple variants/SKUs (each with its own distinct pricing).
     */
    suspend fun addNewItemWithVariants(
        displayName: String,
        category: String,
        variants: List<NewVariantSpec>
    ): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val shopId = activeShopId()
        val deviceCode = SyncManager.getDeviceShortCode(context)
        val cleanName = displayName.trim()
        val itemId = UUID.randomUUID().toString()

        val itemEntity = InventoryItemEntity(
            id = itemId,
            shopId = shopId,
            displayName = cleanName,
            sortKey = cleanName.uppercase(),
            category = category.trim().ifEmpty { "Uncategorized" },
            createdAt = now,
            updatedAt = now,
            isDeleted = false,
            syncStatus = SyncState.PENDING_UPLOAD
        )

        val variantEntities = variants.map { spec ->
            ItemVariantEntity(
                id = UUID.randomUUID().toString(),
                shopId = shopId,
                itemId = itemId,
                skuCode = spec.skuCode.trim(),
                barcode = spec.barcode.trim(),
                sizeLabel = SmartItemMatcher.normalizeSizeString(spec.sizeLabel),
                materialLabel = SmartItemMatcher.normalizeMaterialString(spec.materialLabel),
                colorFinishLabel = SmartItemMatcher.normalizeColorString(spec.colorFinishLabel),
                unit = spec.unit.trim().ifEmpty { "pcs" },
                purchasePricePaise = spec.purchasePricePaise,
                sellingPricePaise = spec.sellingPricePaise,
                gstRateBps = spec.gstRateBps,
                stockQuantity = spec.stockQuantity,
                lowStockThreshold = spec.lowStockThreshold,
                stockMilliUnits = IndianCurrencyFormatter.doubleToMilliUnits(spec.stockQuantity),
                lowStockMilliUnits = IndianCurrencyFormatter.doubleToMilliUnits(spec.lowStockThreshold),
                updatedAt = now,
                isDeleted = false,
                syncStatus = SyncState.PENDING_UPLOAD
            )
        }

        val openingMovements = variantEntities.filter { it.stockMilliUnits != 0L }.map { v ->
            StockMovementEntity(
                movementId = UUID.randomUUID().toString(),
                shopId = shopId,
                skuId = v.id,
                deviceShortCode = deviceCode,
                deltaMilliUnits = v.stockMilliUnits,
                movementType = "OPENING",
                refDocId = itemId,
                note = "Opening stock for $cleanName",
                createdAt = now,
                syncStatus = SyncState.PENDING_UPLOAD
            )
        }

        database.withTransaction {
            inventoryDao.upsertItem(itemEntity)
            inventoryDao.upsertVariants(variantEntities)
            if (openingMovements.isNotEmpty()) {
                shopDao.insertStockMovements(openingMovements)
            }
        }
        SyncManager.scheduleDeltaSync(context)
        itemId
    }

    /**
     * Adds a brand-new parent product along with its initial variant.
     */
    suspend fun addNewItemWithVariant(
        displayName: String,
        category: String,
        sizeLabel: String,
        materialLabel: String,
        colorFinishLabel: String,
        unit: String,
        purchasePricePaise: Long,
        sellingPricePaise: Long,
        stockQuantity: Double,
        lowStockThreshold: Double,
        barcode: String = "",
        skuCode: String = "",
        gstRateBps: Int = 0
    ): String = addNewItemWithVariants(
        displayName = displayName,
        category = category,
        variants = listOf(
            NewVariantSpec(
                sizeLabel = sizeLabel,
                materialLabel = materialLabel,
                colorFinishLabel = colorFinishLabel,
                unit = unit,
                purchasePricePaise = purchasePricePaise,
                sellingPricePaise = sellingPricePaise,
                stockQuantity = stockQuantity,
                lowStockThreshold = lowStockThreshold,
                barcode = barcode,
                skuCode = skuCode,
                gstRateBps = gstRateBps
            )
        )
    )

    /**
     * Adds a new variant/SKU under an existing parent product,
     * optionally updating the parent product's display name or category in the same unified operation.
     */
    suspend fun addVariantToExistingItem(
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
        barcode: String = "",
        skuCode: String = "",
        gstRateBps: Int = 0
    ): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val shopId = activeShopId()
        val deviceCode = SyncManager.getDeviceShortCode(context)

        val variantId = UUID.randomUUID().toString()
        val stockMilli = IndianCurrencyFormatter.doubleToMilliUnits(stockQuantity)
        val variantEntity = ItemVariantEntity(
            id = variantId,
            shopId = shopId,
            itemId = itemId,
            skuCode = skuCode.trim(),
            barcode = barcode.trim(),
            sizeLabel = SmartItemMatcher.normalizeSizeString(sizeLabel),
            materialLabel = SmartItemMatcher.normalizeMaterialString(materialLabel),
            colorFinishLabel = SmartItemMatcher.normalizeColorString(colorFinishLabel),
            unit = unit.trim().ifEmpty { "pcs" },
            purchasePricePaise = purchasePricePaise,
            sellingPricePaise = sellingPricePaise,
            gstRateBps = gstRateBps,
            stockQuantity = stockQuantity,
            lowStockThreshold = lowStockThreshold,
            stockMilliUnits = stockMilli,
            lowStockMilliUnits = IndianCurrencyFormatter.doubleToMilliUnits(lowStockThreshold),
            updatedAt = now,
            isDeleted = false,
            syncStatus = SyncState.PENDING_UPLOAD
        )

        database.withTransaction {
            if (!newDisplayName.isNullOrBlank() || !newCategory.isNullOrBlank()) {
                val existingItem = inventoryDao.getAllRawItems().firstOrNull { it.id == itemId }
                if (existingItem != null) {
                    val cleanName = newDisplayName?.trim()?.ifEmpty { existingItem.displayName } ?: existingItem.displayName
                    val cleanCategory = newCategory?.trim()?.ifEmpty { existingItem.category } ?: existingItem.category
                    inventoryDao.upsertItem(
                        existingItem.copy(
                            displayName = cleanName,
                            sortKey = cleanName.uppercase(),
                            category = cleanCategory,
                            updatedAt = now,
                            syncStatus = SyncState.PENDING_UPLOAD
                        )
                    )
                }
            }
            inventoryDao.upsertVariant(variantEntity)
            if (stockMilli != 0L) {
                shopDao.insertStockMovement(
                    StockMovementEntity(
                        movementId = UUID.randomUUID().toString(),
                        shopId = shopId,
                        skuId = variantId,
                        deviceShortCode = deviceCode,
                        deltaMilliUnits = stockMilli,
                        movementType = "OPENING",
                        refDocId = itemId,
                        note = "Opening stock for new variant",
                        createdAt = now,
                        syncStatus = SyncState.PENDING_UPLOAD
                    )
                )
            }
        }
        SyncManager.scheduleDeltaSync(context)
        variantId
    }

    /**
     * Updates an existing item's name/category and a specific variant's prices, barcode, attributes, and stock count.
     */
    suspend fun updateItemAndVariant(
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
        newBarcode: String = variant.barcode,
        newSkuCode: String = variant.skuCode,
        newGstRateBps: Int = variant.gstRateBps
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val shopId = item.shopId.ifBlank { activeShopId() }
        val deviceCode = SyncManager.getDeviceShortCode(context)
        val cleanName = newDisplayName.trim()

        val newStockMilli = IndianCurrencyFormatter.doubleToMilliUnits(newStockQuantity)
        val oldStockMilli = variant.stockMilliUnits
        val deltaMilli = newStockMilli - oldStockMilli

        val updatedItem = item.copy(
            shopId = shopId,
            displayName = cleanName,
            sortKey = cleanName.uppercase(),
            category = newCategory.trim().ifEmpty { "Uncategorized" },
            updatedAt = now,
            syncStatus = SyncState.PENDING_UPLOAD
        )

        val updatedVariant = variant.copy(
            shopId = shopId,
            skuCode = newSkuCode.trim(),
            barcode = newBarcode.trim(),
            sizeLabel = SmartItemMatcher.normalizeSizeString(newSizeLabel),
            materialLabel = SmartItemMatcher.normalizeMaterialString(newMaterialLabel),
            colorFinishLabel = SmartItemMatcher.normalizeColorString(newColorFinishLabel),
            unit = newUnit.trim().ifEmpty { "pcs" },
            purchasePricePaise = newPurchasePricePaise,
            sellingPricePaise = newSellingPricePaise,
            gstRateBps = newGstRateBps,
            stockQuantity = newStockQuantity,
            lowStockThreshold = newLowStockThreshold,
            stockMilliUnits = newStockMilli,
            lowStockMilliUnits = IndianCurrencyFormatter.doubleToMilliUnits(newLowStockThreshold),
            updatedAt = now,
            syncStatus = SyncState.PENDING_UPLOAD
        )

        database.withTransaction {
            inventoryDao.upsertItem(updatedItem)
            inventoryDao.upsertVariant(updatedVariant)
            if (deltaMilli != 0L) {
                shopDao.insertStockMovement(
                    StockMovementEntity(
                        movementId = UUID.randomUUID().toString(),
                        shopId = shopId,
                        skuId = variant.id,
                        deviceShortCode = deviceCode,
                        deltaMilliUnits = deltaMilli,
                        movementType = "ADJUSTMENT",
                        refDocId = item.id,
                        note = "Manual stock adjustment",
                        createdAt = now,
                        syncStatus = SyncState.PENDING_UPLOAD
                    )
                )
            }
        }
        SyncManager.scheduleDeltaSync(context)
    }

    /**
     * Deletes an entire parent item or a single variant.
     */
    suspend fun deleteItemOrVariant(itemId: String, variantId: String?, totalVariantsCount: Int) =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            database.withTransaction {
                if (variantId == null || totalVariantsCount <= 1) {
                    inventoryDao.softDeleteItem(itemId, now)
                    inventoryDao.softDeleteVariantsForItem(itemId, now)
                } else {
                    inventoryDao.softDeleteSingleVariant(variantId, now)
                }
            }
            SyncManager.scheduleDeltaSync(context)
        }

    /**
     * Records a single-item or multi-item POS sale (`CASH`, `UPI`, or `CREDIT` Udhar),
     * deducts stock counts atomically, inserts Invoice Header/Lines, logs additive StockMovements,
     * and updates Customer Khata balance when payment is on credit or partially paid.
     */
    suspend fun executeCheckoutAndDeductStock(
        cartItems: List<CartLineItem>,
        buyerName: String = "",
        buyerPhone: String = "",
        paymentMode: String = "CASH",
        discountPaise: Long = 0L,
        amountPaidPaise: Long? = null,
        gstRateBps: Int = 0
    ): SaleReceiptData = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val shopId = activeShopId()
        val deviceCode = SyncManager.getDeviceShortCode(context)
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val yearInt = cal.get(Calendar.YEAR)
        val monthInt = cal.get(Calendar.MONTH) + 1
        val dayInt = cal.get(Calendar.DAY_OF_MONTH)

        val receiptNo = generateNextSmartReceiptNumber(yearInt, monthInt, dayInt, deviceCode)
        val invoiceId = UUID.randomUUID().toString()
        val cleanBuyerName = buyerName.trim()
        val cleanBuyerPhone = buyerPhone.trim()

        val saleEntities = ArrayList<SaleRecordEntity>(cartItems.size)
        val invoiceLines = ArrayList<InvoiceLineEntity>(cartItems.size)
        val stockMovements = ArrayList<StockMovementEntity>(cartItems.size)
        val receiptLineItems = ArrayList<ReceiptLineItem>(cartItems.size)

        var subtotalPaise = 0L
        for (line in cartItems) {
            val analysis = IndianCurrencyFormatter.computeProfitAnalysis(
                unitPurchasePaise = line.unitPurchasePricePaise,
                unitSellingPaise = line.customSellingPricePaise,
                quantity = line.quantity
            )
            subtotalPaise += analysis.totalRevenuePaise
            receiptLineItems.add(
                ReceiptLineItem(
                    itemName = line.itemName,
                    variantSummary = line.variantSummary,
                    quantity = line.quantity,
                    unit = line.unit,
                    unitSellingPricePaise = line.customSellingPricePaise,
                    lineTotalPaise = analysis.totalRevenuePaise
                )
            )
        }

        val cleanDiscount = discountPaise.coerceIn(0L, subtotalPaise)
        val taxableAmount = (subtotalPaise - cleanDiscount).coerceAtLeast(0L)
        val gstEnabled = SyncManager.gstEnabledFlow.value
        val effectiveGstBps = if (gstEnabled && gstRateBps > 0) gstRateBps else 0
        val taxBreakdown = IndianCurrencyFormatter.computeGstBreakdown(taxableAmount, effectiveGstBps)
        val grandTotalPaise = taxBreakdown.grandTotalPaise

        val normalizedPaymentMode = paymentMode.uppercase(Locale.ROOT).ifBlank { "CASH" }
        val resolvedPaidPaise = when {
            amountPaidPaise != null -> amountPaidPaise.coerceIn(0L, grandTotalPaise)
            normalizedPaymentMode == "CREDIT" -> 0L
            else -> grandTotalPaise
        }
        val balanceDuePaise = (grandTotalPaise - resolvedPaidPaise).coerceAtLeast(0L)

        // Resolve or create PartyEntity if this transaction involves a customer with balance due or phone
        var resolvedPartyId: String? = null
        var partyToSave: PartyEntity? = null
        var partyLedgerToInsert: PartyTransactionEntity? = null

        if (balanceDuePaise > 0L || cleanBuyerPhone.isNotBlank()) {
            val phoneDigits = cleanBuyerPhone.filter { it.isDigit() }.takeLast(10)
            val existingParties = shopDao.getAllActivePartiesSnapshot()
            val matchedParty = existingParties.firstOrNull { p ->
                (phoneDigits.length >= 6 && p.phone.filter { it.isDigit() }.takeLast(10) == phoneDigits) ||
                    (cleanBuyerName.isNotBlank() && p.name.equals(cleanBuyerName, ignoreCase = true))
            }

            if (matchedParty != null) {
                resolvedPartyId = matchedParty.id
                if (balanceDuePaise > 0L) {
                    partyToSave = matchedParty.copy(
                        currentBalancePaise = matchedParty.currentBalancePaise + balanceDuePaise,
                        updatedAt = now,
                        syncStatus = SyncState.PENDING_UPLOAD
                    )
                }
            } else if (balanceDuePaise > 0L || cleanBuyerName.isNotBlank() || phoneDigits.length >= 6) {
                val newPartyId = UUID.randomUUID().toString()
                resolvedPartyId = newPartyId
                partyToSave = PartyEntity(
                    id = newPartyId,
                    shopId = shopId,
                    partyType = "CUSTOMER",
                    name = cleanBuyerName.ifBlank { cleanBuyerPhone.ifBlank { "Credit Customer" } },
                    phone = if (phoneDigits.length >= 10) phoneDigits else cleanBuyerPhone,
                    openingBalancePaise = 0L,
                    currentBalancePaise = balanceDuePaise,
                    updatedAt = now,
                    isDeleted = false,
                    syncStatus = SyncState.PENDING_UPLOAD
                )
            }

            if (resolvedPartyId != null && balanceDuePaise > 0L) {
                partyLedgerToInsert = PartyTransactionEntity(
                    entryId = UUID.randomUUID().toString(),
                    shopId = shopId,
                    partyId = resolvedPartyId,
                    refDocId = receiptNo,
                    entryType = "CREDIT_BILL",
                    amountDeltaPaise = balanceDuePaise,
                    paymentMode = normalizedPaymentMode,
                    note = "Invoice #$receiptNo (${receiptLineItems.size} items)",
                    createdAt = now,
                    syncStatus = SyncState.PENDING_UPLOAD
                )
            }
        }

        for (line in cartItems) {
            val analysis = IndianCurrencyFormatter.computeProfitAnalysis(
                unitPurchasePaise = line.unitPurchasePricePaise,
                unitSellingPaise = line.customSellingPricePaise,
                quantity = line.quantity
            )
            val lineId = UUID.randomUUID().toString()
            val qtyMilli = IndianCurrencyFormatter.doubleToMilliUnits(line.quantity)

            saleEntities.add(
                SaleRecordEntity(
                    id = lineId,
                    shopId = shopId,
                    receiptNumber = receiptNo,
                    buyerName = cleanBuyerName,
                    buyerPhone = cleanBuyerPhone,
                    partyId = resolvedPartyId,
                    itemId = line.itemId,
                    variantId = line.variantId,
                    itemNameSnapshot = line.itemName,
                    variantSummarySnapshot = line.variantSummary,
                    quantitySold = line.quantity,
                    unitSnapshot = line.unit,
                    unitPurchasePricePaise = line.unitPurchasePricePaise,
                    unitSellingPricePaise = line.customSellingPricePaise,
                    totalCostPaise = analysis.totalCostPaise,
                    totalRevenuePaise = analysis.totalRevenuePaise,
                    totalProfitPaise = analysis.totalProfitPaise,
                    gainMarkupPercent = analysis.gainMarkupPercent,
                    grossMarginPercent = analysis.grossMarginPercent,
                    paymentMode = normalizedPaymentMode,
                    amountPaidPaise = resolvedPaidPaise,
                    balanceDuePaise = balanceDuePaise,
                    discountPaise = cleanDiscount,
                    gstPaise = taxBreakdown.totalTaxPaise,
                    soldAt = now,
                    yearInt = yearInt,
                    monthInt = monthInt,
                    dayInt = dayInt,
                    isReturned = false,
                    updatedAt = now,
                    syncStatus = SyncState.PENDING_UPLOAD
                )
            )

            invoiceLines.add(
                InvoiceLineEntity(
                    id = lineId,
                    shopId = shopId,
                    invoiceId = invoiceId,
                    productId = line.itemId,
                    skuId = line.variantId,
                    itemNameSnapshot = line.itemName,
                    variantSummarySnapshot = line.variantSummary,
                    qtyMilliUnits = qtyMilli,
                    unitSnapshot = line.unit,
                    unitCostPaise = line.unitPurchasePricePaise,
                    unitSellPaise = line.customSellingPricePaise,
                    gstRateBps = effectiveGstBps,
                    lineTaxPaise = 0L,
                    lineTotalPaise = analysis.totalRevenuePaise,
                    lineProfitPaise = analysis.totalProfitPaise,
                    isReturned = false
                )
            )

            stockMovements.add(
                StockMovementEntity(
                    movementId = UUID.randomUUID().toString(),
                    shopId = shopId,
                    skuId = line.variantId,
                    deviceShortCode = deviceCode,
                    deltaMilliUnits = -qtyMilli,
                    movementType = "SALE",
                    refDocId = receiptNo,
                    note = "Sale #$receiptNo",
                    createdAt = now,
                    syncStatus = SyncState.PENDING_UPLOAD
                )
            )
        }

        val invoiceHeader = InvoiceHeaderEntity(
            id = invoiceId,
            shopId = shopId,
            deviceShortCode = deviceCode,
            invoiceNumber = receiptNo,
            docType = "SALE",
            partyId = resolvedPartyId,
            buyerName = cleanBuyerName,
            buyerPhone = cleanBuyerPhone,
            paymentMode = normalizedPaymentMode,
            subtotalPaise = subtotalPaise,
            discountPaise = cleanDiscount,
            cgstPaise = taxBreakdown.cgstPaise,
            sgstPaise = taxBreakdown.sgstPaise,
            igstPaise = taxBreakdown.igstPaise,
            grandTotalPaise = grandTotalPaise,
            amountPaidPaise = resolvedPaidPaise,
            balanceDuePaise = balanceDuePaise,
            issuedAt = now,
            yearInt = yearInt,
            monthInt = monthInt,
            dayInt = dayInt,
            isReturned = false,
            updatedAt = now,
            syncStatus = SyncState.PENDING_UPLOAD
        )

        database.withTransaction {
            for (line in cartItems) {
                inventoryDao.deductVariantStock(
                    variantId = line.variantId,
                    qtySold = line.quantity,
                    now = now
                )
            }
            saleDao.insertSales(saleEntities)
            shopDao.upsertInvoiceHeader(invoiceHeader)
            shopDao.upsertInvoiceLines(invoiceLines)
            shopDao.insertStockMovements(stockMovements)
            if (partyToSave != null) {
                shopDao.upsertParty(partyToSave)
            }
            if (partyLedgerToInsert != null) {
                shopDao.insertLedgerEntry(partyLedgerToInsert)
            }
        }

        SyncManager.scheduleDeltaSync(context)

        SaleReceiptData(
            receiptNumber = receiptNo,
            timestampMillis = now,
            buyerName = cleanBuyerName,
            buyerPhone = cleanBuyerPhone,
            sellerName = SyncManager.getSellerName(context),
            sellerWhatsAppPhone = SyncManager.getSellerWhatsAppNumber(context),
            isEstimate = false,
            items = receiptLineItems,
            grandTotalPaise = grandTotalPaise,
            paymentMode = normalizedPaymentMode,
            subtotalPaise = subtotalPaise,
            discountPaise = cleanDiscount,
            taxPaise = taxBreakdown.totalTaxPaise,
            amountPaidPaise = resolvedPaidPaise,
            balanceDuePaise = balanceDuePaise,
            upiId = SyncManager.getUpiId(context),
            gstin = SyncManager.getGstin(context)
        )
    }

    /**
     * Prepares an Estimate (Quotation / Kaccha Parcha) photo receipt without deducting stock.
     */
    suspend fun prepareEstimateReceiptData(
        cartItems: List<CartLineItem>,
        buyerName: String = "",
        buyerPhone: String = ""
    ): SaleReceiptData = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val deviceCode = SyncManager.getDeviceShortCode(context)
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val yearInt = cal.get(Calendar.YEAR)
        val monthInt = cal.get(Calendar.MONTH) + 1
        val dayInt = cal.get(Calendar.DAY_OF_MONTH)

        val receiptNo = generateNextSmartReceiptNumber(yearInt, monthInt, dayInt, deviceCode)
        val receiptLineItems = cartItems.map { line ->
            ReceiptLineItem(
                itemName = line.itemName,
                variantSummary = line.variantSummary,
                quantity = line.quantity,
                unit = line.unit,
                unitSellingPricePaise = line.customSellingPricePaise,
                lineTotalPaise = line.lineRevenuePaise
            )
        }
        val grandTotal = receiptLineItems.sumOf { it.lineTotalPaise }

        SaleReceiptData(
            receiptNumber = receiptNo,
            timestampMillis = now,
            buyerName = buyerName.trim(),
            buyerPhone = buyerPhone.trim(),
            sellerName = SyncManager.getSellerName(context),
            sellerWhatsAppPhone = SyncManager.getSellerWhatsAppNumber(context),
            isEstimate = true,
            items = receiptLineItems,
            grandTotalPaise = grandTotal,
            subtotalPaise = grandTotal,
            upiId = SyncManager.getUpiId(context),
            gstin = SyncManager.getGstin(context)
        )
    }

    /**
     * Generates a smart receipt number (`YYYYMMDDCCCC` or `D1-YYYYMMDD-CCCC` when multi-device code is set).
     */
    private suspend fun generateNextSmartReceiptNumber(
        yearInt: Int,
        monthInt: Int,
        dayInt: Int,
        deviceShortCode: String = ""
    ): String {
        val existingReceiptsToday = saleDao.getDistinctReceiptNumbersForDay(yearInt, monthInt, dayInt)
        val datePrefix = String.format(Locale.US, "%04d%02d%02d", yearInt, monthInt, dayInt)
        var maxSeqToday = existingReceiptsToday.size
        for (r in existingReceiptsToday) {
            if (r.contains(datePrefix)) {
                val tail = r.takeLast(4).toIntOrNull() ?: 0
                maxSeqToday = max(maxSeqToday, tail)
            }
        }
        val nextCount = maxSeqToday + 1
        return formatSmartReceiptId(yearInt, monthInt, dayInt, nextCount, deviceShortCode)
    }

    companion object {
        fun formatSmartReceiptId(
            yearInt: Int,
            monthInt: Int,
            dayInt: Int,
            dailyCount: Int,
            deviceShortCode: String = ""
        ): String {
            val dateStr = String.format(Locale.US, "%04d%02d%02d", yearInt, monthInt, dayInt)
            val seqStr = String.format(Locale.US, "%04d", dailyCount.coerceAtLeast(1))
            val cleanCode = deviceShortCode.trim().uppercase(Locale.US)
            return if (cleanCode.isEmpty()) {
                "$dateStr$seqStr"
            } else {
                "$cleanCode-$dateStr-$seqStr"
            }
        }
    }

    /**
     * Reverses a sale ("Wapsi / Exchange"), restoring the sold quantity back to inventory stock
     * and recording an additive `SALE_RETURN` movement.
     */
    suspend fun returnSaleAndRestoreStock(sale: SaleRecordEntity) = withContext(Dispatchers.IO) {
        if (sale.isReturned) return@withContext
        val now = System.currentTimeMillis()
        val deviceCode = SyncManager.getDeviceShortCode(context)
        val qtyMilli = IndianCurrencyFormatter.doubleToMilliUnits(sale.quantitySold)

        database.withTransaction {
            saleDao.markSaleReturned(sale.id, now)
            inventoryDao.restoreVariantStock(sale.variantId, sale.quantitySold, now)
            shopDao.insertStockMovement(
                StockMovementEntity(
                    movementId = UUID.randomUUID().toString(),
                    shopId = sale.shopId.ifBlank { activeShopId() },
                    skuId = sale.variantId,
                    deviceShortCode = deviceCode,
                    deltaMilliUnits = qtyMilli,
                    movementType = "SALE_RETURN",
                    refDocId = sale.receiptNumber,
                    note = "Return of #${sale.receiptNumber}",
                    createdAt = now,
                    syncStatus = SyncState.PENDING_UPLOAD
                )
            )
        }
        SyncManager.scheduleDeltaSync(context)
    }

    // -------------------------------------------------------------------------
    // Udhar Khata (Customer & Supplier Ledger)
    // -------------------------------------------------------------------------
    suspend fun addOrUpdateParty(
        id: String? = null,
        name: String,
        phone: String,
        partyType: String = "CUSTOMER",
        gstin: String = "",
        openingBalancePaise: Long = 0L
    ): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val shopId = activeShopId()
        val existing = if (!id.isNullOrBlank()) shopDao.getPartyById(id) else null
        val partyId = existing?.id ?: UUID.randomUUID().toString()

        val entity = PartyEntity(
            id = partyId,
            shopId = shopId,
            partyType = partyType,
            name = name.trim(),
            phone = phone.trim(),
            gstin = gstin.trim().uppercase(Locale.ROOT),
            openingBalancePaise = existing?.openingBalancePaise ?: openingBalancePaise,
            currentBalancePaise = existing?.currentBalancePaise ?: openingBalancePaise,
            updatedAt = now,
            isDeleted = false,
            syncStatus = SyncState.PENDING_UPLOAD
        )
        shopDao.upsertParty(entity)
        SyncManager.scheduleDeltaSync(context)
        partyId
    }

    /**
     * Records a *Jama* (payment received from customer or paid to supplier), updating balance atomically.
     */
    suspend fun recordPartyPaymentReceived(
        partyId: String,
        amountPaise: Long,
        paymentMode: String = "CASH",
        note: String = ""
    ) = withContext(Dispatchers.IO) {
        if (amountPaise <= 0L) return@withContext
        val party = shopDao.getPartyById(partyId) ?: return@withContext
        val now = System.currentTimeMillis()
        val entryType = if (paymentMode.equals("UPI", ignoreCase = true)) "JAMA_UPI" else "JAMA_CASH"

        val updatedParty = party.copy(
            currentBalancePaise = (party.currentBalancePaise - amountPaise).coerceAtLeast(0L),
            updatedAt = now,
            syncStatus = SyncState.PENDING_UPLOAD
        )
        val entry = PartyTransactionEntity(
            entryId = UUID.randomUUID().toString(),
            shopId = party.shopId.ifBlank { activeShopId() },
            partyId = partyId,
            refDocId = "",
            entryType = entryType,
            amountDeltaPaise = -amountPaise,
            paymentMode = paymentMode.uppercase(Locale.ROOT),
            note = note.trim().ifBlank { "Payment received ($paymentMode)" },
            createdAt = now,
            syncStatus = SyncState.PENDING_UPLOAD
        )

        database.withTransaction {
            shopDao.upsertParty(updatedParty)
            shopDao.insertLedgerEntry(entry)
        }
        SyncManager.scheduleDeltaSync(context)
    }

    /**
     * Records a search query in Search History (incrementing frequency if already searched before).
     */
    suspend fun recordSearchQuery(rawQuery: String, resultCount: Int) = withContext(Dispatchers.IO) {
        val trimmed = rawQuery.trim()
        if (trimmed.length < 2) return@withContext
        val normalized = trimmed.lowercase()
        val existing = searchHistoryDao.findByNormalizedQuery(normalized)
        val now = System.currentTimeMillis()
        if (existing != null) {
            searchHistoryDao.upsertSearchEntry(
                existing.copy(
                    rawQuery = trimmed,
                    resultCount = resultCount,
                    searchFrequency = existing.searchFrequency + 1,
                    lastSearchedAt = now
                )
            )
        } else {
            searchHistoryDao.upsertSearchEntry(
                SearchHistoryEntity(
                    shopId = activeShopId(),
                    rawQuery = trimmed,
                    normalizedQuery = normalized,
                    resultCount = resultCount,
                    searchFrequency = 1,
                    lastSearchedAt = now
                )
            )
        }
    }

    suspend fun deleteSearchHistoryItem(id: Long) = withContext(Dispatchers.IO) {
        searchHistoryDao.deleteSearchById(id)
    }

    suspend fun clearAllSearchHistory() = withContext(Dispatchers.IO) {
        searchHistoryDao.clearAllSearchHistory()
    }

    /**
     * Exports the entire database (Items, Variants, Sales, Parties) as a portable JSON backup string.
     */
    suspend fun exportBackupJson(): String = withContext(Dispatchers.IO) {
        val items = inventoryDao.getAllRawItems()
        val variants = inventoryDao.getAllRawVariants()
        val sales = saleDao.getAllRawSales()
        val parties = shopDao.getAllRawParties()

        val root = JSONObject()
        root.put("shopName", SyncManager.getSellerName(context))
        root.put("schemaVersion", 2)
        root.put("exportedAt", System.currentTimeMillis())

        val itemsArr = JSONArray()
        for (i in items) {
            itemsArr.put(
                JSONObject().apply {
                    put("id", i.id)
                    put("shopId", i.shopId)
                    put("displayName", i.displayName)
                    put("sortKey", i.sortKey)
                    put("category", i.category)
                    put("createdAt", i.createdAt)
                    put("updatedAt", i.updatedAt)
                    put("isDeleted", i.isDeleted)
                }
            )
        }
        root.put("items", itemsArr)

        val variantsArr = JSONArray()
        for (v in variants) {
            variantsArr.put(
                JSONObject().apply {
                    put("id", v.id)
                    put("shopId", v.shopId)
                    put("itemId", v.itemId)
                    put("skuCode", v.skuCode)
                    put("barcode", v.barcode)
                    put("sizeLabel", v.sizeLabel)
                    put("materialLabel", v.materialLabel)
                    put("colorFinishLabel", v.colorFinishLabel)
                    put("unit", v.unit)
                    put("purchasePricePaise", v.purchasePricePaise)
                    put("sellingPricePaise", v.sellingPricePaise)
                    put("gstRateBps", v.gstRateBps)
                    put("stockQuantity", v.stockQuantity)
                    put("lowStockThreshold", v.lowStockThreshold)
                    put("updatedAt", v.updatedAt)
                    put("isDeleted", v.isDeleted)
                }
            )
        }
        root.put("variants", variantsArr)

        val salesArr = JSONArray()
        for (s in sales) {
            salesArr.put(
                JSONObject().apply {
                    put("id", s.id)
                    put("shopId", s.shopId)
                    put("receiptNumber", s.receiptNumber)
                    put("buyerName", s.buyerName)
                    put("buyerPhone", s.buyerPhone)
                    put("itemId", s.itemId)
                    put("variantId", s.variantId)
                    put("itemNameSnapshot", s.itemNameSnapshot)
                    put("variantSummarySnapshot", s.variantSummarySnapshot)
                    put("quantitySold", s.quantitySold)
                    put("unitSnapshot", s.unitSnapshot)
                    put("unitPurchasePricePaise", s.unitPurchasePricePaise)
                    put("unitSellingPricePaise", s.unitSellingPricePaise)
                    put("totalCostPaise", s.totalCostPaise)
                    put("totalRevenuePaise", s.totalRevenuePaise)
                    put("totalProfitPaise", s.totalProfitPaise)
                    put("gainMarkupPercent", s.gainMarkupPercent)
                    put("grossMarginPercent", s.grossMarginPercent)
                    put("paymentMode", s.paymentMode)
                    put("amountPaidPaise", s.amountPaidPaise)
                    put("balanceDuePaise", s.balanceDuePaise)
                    put("discountPaise", s.discountPaise)
                    put("gstPaise", s.gstPaise)
                    put("soldAt", s.soldAt)
                    put("yearInt", s.yearInt)
                    put("monthInt", s.monthInt)
                    put("dayInt", s.dayInt)
                    put("isReturned", s.isReturned)
                    put("updatedAt", s.updatedAt)
                }
            )
        }
        root.put("sales", salesArr)

        val partiesArr = JSONArray()
        for (p in parties) {
            partiesArr.put(
                JSONObject().apply {
                    put("id", p.id)
                    put("shopId", p.shopId)
                    put("partyType", p.partyType)
                    put("name", p.name)
                    put("phone", p.phone)
                    put("gstin", p.gstin)
                    put("currentBalancePaise", p.currentBalancePaise)
                    put("updatedAt", p.updatedAt)
                    put("isDeleted", p.isDeleted)
                }
            )
        }
        root.put("parties", partiesArr)

        root.toString(2)
    }

    /**
     * Restores items, variants, sales, and parties from either a v1 LPS Hardware or v2 ShopLedger JSON backup.
     */
    suspend fun importBackupJson(jsonString: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val root = JSONObject(jsonString)
            val shopId = activeShopId()
            val itemsArr = root.optJSONArray("items") ?: JSONArray()
            val variantsArr = root.optJSONArray("variants") ?: JSONArray()
            val salesArr = root.optJSONArray("sales") ?: JSONArray()
            val partiesArr = root.optJSONArray("parties") ?: JSONArray()

            val items = ArrayList<InventoryItemEntity>(itemsArr.length())
            for (idx in 0 until itemsArr.length()) {
                val o = itemsArr.getJSONObject(idx)
                items.add(
                    InventoryItemEntity(
                        id = o.getString("id"),
                        shopId = o.optString("shopId", shopId),
                        displayName = o.getString("displayName"),
                        sortKey = o.optString("sortKey", o.getString("displayName").uppercase()),
                        category = o.optString("category", "Uncategorized"),
                        createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                        updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                        isDeleted = o.optBoolean("isDeleted", false),
                        syncStatus = SyncState.PENDING_UPLOAD
                    )
                )
            }

            val variants = ArrayList<ItemVariantEntity>(variantsArr.length())
            for (idx in 0 until variantsArr.length()) {
                val o = variantsArr.getJSONObject(idx)
                val qty = o.getDouble("stockQuantity")
                val lowAlert = o.optDouble("lowStockThreshold", 5.0)
                variants.add(
                    ItemVariantEntity(
                        id = o.getString("id"),
                        shopId = o.optString("shopId", shopId),
                        itemId = o.getString("itemId"),
                        skuCode = o.optString("skuCode", ""),
                        barcode = o.optString("barcode", ""),
                        sizeLabel = o.optString("sizeLabel", ""),
                        materialLabel = o.optString("materialLabel", ""),
                        colorFinishLabel = o.optString("colorFinishLabel", ""),
                        unit = o.optString("unit", "pcs"),
                        purchasePricePaise = o.getLong("purchasePricePaise"),
                        sellingPricePaise = o.getLong("sellingPricePaise"),
                        gstRateBps = o.optInt("gstRateBps", 0),
                        stockQuantity = qty,
                        lowStockThreshold = lowAlert,
                        stockMilliUnits = (qty * 1000.0).roundToLong(),
                        lowStockMilliUnits = (lowAlert * 1000.0).roundToLong(),
                        updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                        isDeleted = o.optBoolean("isDeleted", false),
                        syncStatus = SyncState.PENDING_UPLOAD
                    )
                )
            }

            val sales = ArrayList<SaleRecordEntity>(salesArr.length())
            for (idx in 0 until salesArr.length()) {
                val o = salesArr.getJSONObject(idx)
                val rev = o.getLong("totalRevenuePaise")
                sales.add(
                    SaleRecordEntity(
                        id = o.getString("id"),
                        shopId = o.optString("shopId", shopId),
                        receiptNumber = o.optString("receiptNumber", "INV-0000"),
                        buyerName = o.optString("buyerName", ""),
                        buyerPhone = o.optString("buyerPhone", ""),
                        itemId = o.getString("itemId"),
                        variantId = o.getString("variantId"),
                        itemNameSnapshot = o.getString("itemNameSnapshot"),
                        variantSummarySnapshot = o.optString("variantSummarySnapshot", "Standard"),
                        quantitySold = o.getDouble("quantitySold"),
                        unitSnapshot = o.optString("unitSnapshot", "pcs"),
                        unitPurchasePricePaise = o.getLong("unitPurchasePricePaise"),
                        unitSellingPricePaise = o.getLong("unitSellingPricePaise"),
                        totalCostPaise = o.getLong("totalCostPaise"),
                        totalRevenuePaise = rev,
                        totalProfitPaise = o.getLong("totalProfitPaise"),
                        gainMarkupPercent = o.optDouble("gainMarkupPercent", 0.0),
                        grossMarginPercent = o.optDouble("grossMarginPercent", 0.0),
                        paymentMode = o.optString("paymentMode", "CASH"),
                        amountPaidPaise = o.optLong("amountPaidPaise", rev),
                        balanceDuePaise = o.optLong("balanceDuePaise", 0L),
                        discountPaise = o.optLong("discountPaise", 0L),
                        gstPaise = o.optLong("gstPaise", 0L),
                        soldAt = o.optLong("soldAt", System.currentTimeMillis()),
                        yearInt = o.optInt("yearInt", 2026),
                        monthInt = o.optInt("monthInt", 1),
                        dayInt = o.optInt("dayInt", 1),
                        isReturned = o.optBoolean("isReturned", false),
                        updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                        syncStatus = SyncState.PENDING_UPLOAD
                    )
                )
            }

            val parties = ArrayList<PartyEntity>(partiesArr.length())
            for (idx in 0 until partiesArr.length()) {
                val o = partiesArr.getJSONObject(idx)
                parties.add(
                    PartyEntity(
                        id = o.getString("id"),
                        shopId = o.optString("shopId", shopId),
                        partyType = o.optString("partyType", "CUSTOMER"),
                        name = o.getString("name"),
                        phone = o.optString("phone", ""),
                        gstin = o.optString("gstin", ""),
                        currentBalancePaise = o.optLong("currentBalancePaise", 0L),
                        updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                        isDeleted = o.optBoolean("isDeleted", false),
                        syncStatus = SyncState.PENDING_UPLOAD
                    )
                )
            }

            database.withTransaction {
                inventoryDao.upsertItems(items)
                inventoryDao.upsertVariants(variants)
                saleDao.insertSales(sales)
                if (parties.isNotEmpty()) {
                    shopDao.upsertParties(parties)
                }
            }
            SyncManager.scheduleDeltaSync(context)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun seedStarterCatalogIfEmpty() = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("lps_seed_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("catalog_seeded_v1", false)) return@withContext

        val existing = inventoryDao.getAllActiveItemsSnapshot()
        if (existing.isNotEmpty()) {
            prefs.edit().putBoolean("catalog_seeded_v1", true).apply()
            return@withContext
        }

        val hingeId = addSeedItemWithVariant(
            displayName = "Butt Hinge (Kabza)",
            category = "Door & Window Fittings",
            sizeLabel = "4 inch (100mm)",
            materialLabel = "SS 304",
            colorFinishLabel = "Silver / Chrome",
            unit = "pcs",
            purchasePricePaise = 4800L,
            sellingPricePaise = 7500L,
            stockQuantity = 40.0,
            lowStockThreshold = 10.0
        )
        addSeedVariantToItem(
            itemId = hingeId,
            sizeLabel = "4 inch (100mm)",
            materialLabel = "SS 304",
            colorFinishLabel = "Matt Black",
            unit = "pcs",
            purchasePricePaise = 6500L,
            sellingPricePaise = 9500L,
            stockQuantity = 18.0,
            lowStockThreshold = 6.0
        )
        addSeedVariantToItem(
            itemId = hingeId,
            sizeLabel = "6 inch (150mm)",
            materialLabel = "Brass",
            colorFinishLabel = "Antique Brass",
            unit = "pcs",
            purchasePricePaise = 14500L,
            sellingPricePaise = 21000L,
            stockQuantity = 12.0,
            lowStockThreshold = 4.0
        )

        val screwId = addSeedItemWithVariant(
            displayName = "Drywall Screw (Black)",
            category = "Fasteners & Screws",
            sizeLabel = "1.5 inch (38mm)",
            materialLabel = "MS (Iron)",
            colorFinishLabel = "Black",
            unit = "box",
            purchasePricePaise = 13500L,
            sellingPricePaise = 19000L,
            stockQuantity = 15.0,
            lowStockThreshold = 5.0
        )
        addSeedVariantToItem(
            itemId = screwId,
            sizeLabel = "2 inch (50mm)",
            materialLabel = "MS (Iron)",
            colorFinishLabel = "Black",
            unit = "box",
            purchasePricePaise = 16000L,
            sellingPricePaise = 22500L,
            stockQuantity = 4.0,
            lowStockThreshold = 5.0
        )

        val boltId = addSeedItemWithVariant(
            displayName = "Tower Bolt (Chatkani)",
            category = "Door & Window Fittings",
            sizeLabel = "6 inch (150mm)",
            materialLabel = "SS",
            colorFinishLabel = "Silver / Chrome",
            unit = "pcs",
            purchasePricePaise = 8200L,
            sellingPricePaise = 12500L,
            stockQuantity = 24.0,
            lowStockThreshold = 6.0
        )
        addSeedVariantToItem(
            itemId = boltId,
            sizeLabel = "8 inch (200mm)",
            materialLabel = "Aluminium",
            colorFinishLabel = "Rose Gold",
            unit = "pcs",
            purchasePricePaise = 11000L,
            sellingPricePaise = 16500L,
            stockQuantity = 3.0,
            lowStockThreshold = 5.0
        )

        addSeedItemWithVariant(
            displayName = "Wood Adhesive (Fevicol SH)",
            category = "Paints & Adhesives",
            sizeLabel = "1 kg",
            materialLabel = "",
            colorFinishLabel = "White",
            unit = "pkt",
            purchasePricePaise = 23000L,
            sellingPricePaise = 29000L,
            stockQuantity = 10.0,
            lowStockThreshold = 3.0
        )

        prefs.edit().putBoolean("catalog_seeded_v1", true).apply()
    }

    private suspend fun addSeedItemWithVariant(
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
    ): String {
        val now = System.currentTimeMillis()
        val itemId = UUID.randomUUID().toString()
        val variantId = UUID.randomUUID().toString()
        inventoryDao.upsertItem(
            InventoryItemEntity(
                id = itemId,
                displayName = displayName,
                sortKey = displayName.uppercase(),
                category = category,
                createdAt = now,
                updatedAt = now,
                isDeleted = false,
                syncStatus = SyncState.DEMO_SEED
            )
        )
        inventoryDao.upsertVariant(
            ItemVariantEntity(
                id = variantId,
                itemId = itemId,
                sizeLabel = sizeLabel,
                materialLabel = materialLabel,
                colorFinishLabel = colorFinishLabel,
                unit = unit,
                purchasePricePaise = purchasePricePaise,
                sellingPricePaise = sellingPricePaise,
                stockQuantity = stockQuantity,
                lowStockThreshold = lowStockThreshold,
                updatedAt = now,
                isDeleted = false,
                syncStatus = SyncState.DEMO_SEED
            )
        )
        return itemId
    }

    private suspend fun addSeedVariantToItem(
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
        val now = System.currentTimeMillis()
        inventoryDao.upsertVariant(
            ItemVariantEntity(
                id = UUID.randomUUID().toString(),
                itemId = itemId,
                sizeLabel = sizeLabel,
                materialLabel = materialLabel,
                colorFinishLabel = colorFinishLabel,
                unit = unit,
                purchasePricePaise = purchasePricePaise,
                sellingPricePaise = sellingPricePaise,
                stockQuantity = stockQuantity,
                lowStockThreshold = lowStockThreshold,
                updatedAt = now,
                isDeleted = false,
                syncStatus = SyncState.DEMO_SEED
            )
        )
    }
}
