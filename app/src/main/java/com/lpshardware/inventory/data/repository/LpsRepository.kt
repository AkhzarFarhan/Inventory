package com.lpshardware.inventory.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.lpshardware.inventory.core.format.IndianCurrencyFormatter
import com.lpshardware.inventory.core.nlp.SmartItemMatcher
import com.lpshardware.inventory.core.share.ReceiptLineItem
import com.lpshardware.inventory.core.share.SaleReceiptData
import com.lpshardware.inventory.core.sync.SyncManager
import com.lpshardware.inventory.data.local.LpsDatabase
import com.lpshardware.inventory.data.local.entity.InventoryItemEntity
import com.lpshardware.inventory.data.local.entity.ItemVariantEntity
import com.lpshardware.inventory.data.local.entity.SaleRecordEntity
import com.lpshardware.inventory.data.local.entity.SearchHistoryEntity
import com.lpshardware.inventory.data.local.entity.SyncState
import com.lpshardware.inventory.data.model.CartLineItem
import com.lpshardware.inventory.data.model.InventoryItemWithVariants
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

class LpsRepository(
    private val context: Context,
    private val database: LpsDatabase
) {
    private val inventoryDao = database.inventoryDao()
    private val saleDao = database.saleDao()
    private val searchHistoryDao = database.searchHistoryDao()

    val activeInventoryFlow: Flow<List<InventoryItemWithVariants>> =
        inventoryDao.observeAllActiveItemsAlphabetically()

    val salesHistoryFlow: Flow<List<SaleRecordEntity>> =
        saleDao.observeAllSalesChronological()

    val recentSearchesFlow: Flow<List<SearchHistoryEntity>> =
        searchHistoryDao.observeRecentSearches()

    val frequentSearchesFlow: Flow<List<SearchHistoryEntity>> =
        searchHistoryDao.observeFrequentSearches()

    val totalPendingSyncCountFlow: Flow<Int> = combine(
        inventoryDao.observePendingItemCount(),
        inventoryDao.observePendingVariantCount(),
        saleDao.observePendingSaleCount()
    ) { items, variants, sales -> items + variants + sales }

    /**
     * Adds a brand-new parent hardware item along with its initial variant.
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
        lowStockThreshold: Double
    ): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cleanName = displayName.trim()
        val itemId = UUID.randomUUID().toString()
        val variantId = UUID.randomUUID().toString()

        val itemEntity = InventoryItemEntity(
            id = itemId,
            displayName = cleanName,
            sortKey = cleanName.uppercase(),
            category = category.trim().ifEmpty { "General Hardware" },
            createdAt = now,
            updatedAt = now,
            isDeleted = false,
            syncStatus = SyncState.PENDING_UPLOAD
        )

        val variantEntity = ItemVariantEntity(
            id = variantId,
            itemId = itemId,
            sizeLabel = SmartItemMatcher.normalizeSizeString(sizeLabel),
            materialLabel = SmartItemMatcher.normalizeMaterialString(materialLabel),
            colorFinishLabel = SmartItemMatcher.normalizeColorString(colorFinishLabel),
            unit = unit.trim().ifEmpty { "pcs" },
            purchasePricePaise = purchasePricePaise,
            sellingPricePaise = sellingPricePaise,
            stockQuantity = stockQuantity,
            lowStockThreshold = lowStockThreshold,
            updatedAt = now,
            isDeleted = false,
            syncStatus = SyncState.PENDING_UPLOAD
        )

        database.withTransaction {
            inventoryDao.upsertItem(itemEntity)
            inventoryDao.upsertVariant(variantEntity)
        }
        SyncManager.scheduleDeltaSync(context)
        itemId
    }

    /**
     * Adds a new variant (size, material, or color/finish) under an existing parent hardware item,
     * optionally updating the parent item's display name or category in the same unified operation.
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
        lowStockThreshold: Double
    ): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!newDisplayName.isNullOrBlank() || !newCategory.isNullOrBlank()) {
            val existingItem = inventoryDao.getAllRawItems().firstOrNull { it.id == itemId }
            if (existingItem != null) {
                val cleanName = newDisplayName?.trim()?.ifEmpty { existingItem.displayName } ?: existingItem.displayName
                val cleanCategory = newCategory?.trim()?.ifEmpty { existingItem.category } ?: existingItem.category
                val updatedItem = existingItem.copy(
                    displayName = cleanName,
                    sortKey = cleanName.uppercase(),
                    category = cleanCategory,
                    updatedAt = now,
                    syncStatus = SyncState.PENDING_UPLOAD
                )
                inventoryDao.upsertItem(updatedItem)
            }
        }
        val variantId = UUID.randomUUID().toString()
        val variantEntity = ItemVariantEntity(
            id = variantId,
            itemId = itemId,
            sizeLabel = SmartItemMatcher.normalizeSizeString(sizeLabel),
            materialLabel = SmartItemMatcher.normalizeMaterialString(materialLabel),
            colorFinishLabel = SmartItemMatcher.normalizeColorString(colorFinishLabel),
            unit = unit.trim().ifEmpty { "pcs" },
            purchasePricePaise = purchasePricePaise,
            sellingPricePaise = sellingPricePaise,
            stockQuantity = stockQuantity,
            lowStockThreshold = lowStockThreshold,
            updatedAt = now,
            isDeleted = false,
            syncStatus = SyncState.PENDING_UPLOAD
        )
        inventoryDao.upsertVariant(variantEntity)
        SyncManager.scheduleDeltaSync(context)
        variantId
    }

    /**
     * Updates an existing item's name/category and a specific variant's prices, attributes, and stock count.
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
        newLowStockThreshold: Double
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cleanName = newDisplayName.trim()

        val updatedItem = item.copy(
            displayName = cleanName,
            sortKey = cleanName.uppercase(),
            category = newCategory.trim().ifEmpty { "General Hardware" },
            updatedAt = now,
            syncStatus = SyncState.PENDING_UPLOAD
        )

        val updatedVariant = variant.copy(
            sizeLabel = SmartItemMatcher.normalizeSizeString(newSizeLabel),
            materialLabel = SmartItemMatcher.normalizeMaterialString(newMaterialLabel),
            colorFinishLabel = SmartItemMatcher.normalizeColorString(newColorFinishLabel),
            unit = newUnit.trim().ifEmpty { "pcs" },
            purchasePricePaise = newPurchasePricePaise,
            sellingPricePaise = newSellingPricePaise,
            stockQuantity = newStockQuantity,
            lowStockThreshold = newLowStockThreshold,
            updatedAt = now,
            syncStatus = SyncState.PENDING_UPLOAD
        )

        database.withTransaction {
            inventoryDao.upsertItem(updatedItem)
            inventoryDao.upsertVariant(updatedVariant)
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
     * Records a single-item or multi-item Counter Cart sale, automatically deducting variant stock
     * counts in an atomic SQLite transaction and returning [SaleReceiptData] for WhatsApp receipt image sharing.
     */
    suspend fun executeCheckoutAndDeductStock(
        cartItems: List<CartLineItem>,
        buyerName: String = "",
        buyerPhone: String = ""
    ): SaleReceiptData = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val yearInt = cal.get(Calendar.YEAR)
        val monthInt = cal.get(Calendar.MONTH) + 1
        val dayInt = cal.get(Calendar.DAY_OF_MONTH)

        val receiptNo = generateNextSmartReceiptNumber(yearInt, monthInt, dayInt)

        val saleEntities = ArrayList<SaleRecordEntity>(cartItems.size)
        val receiptLineItems = ArrayList<ReceiptLineItem>(cartItems.size)
        var grandTotalPaise = 0L

        for (line in cartItems) {
            val profitAnalysis = IndianCurrencyFormatter.computeProfitAnalysis(
                unitPurchasePaise = line.unitPurchasePricePaise,
                unitSellingPaise = line.customSellingPricePaise,
                quantity = line.quantity
            )

            saleEntities.add(
                SaleRecordEntity(
                    id = UUID.randomUUID().toString(),
                    receiptNumber = receiptNo,
                    buyerName = buyerName.trim(),
                    buyerPhone = buyerPhone.trim(),
                    itemId = line.itemId,
                    variantId = line.variantId,
                    itemNameSnapshot = line.itemName,
                    variantSummarySnapshot = line.variantSummary,
                    quantitySold = line.quantity,
                    unitSnapshot = line.unit,
                    unitPurchasePricePaise = line.unitPurchasePricePaise,
                    unitSellingPricePaise = line.customSellingPricePaise,
                    totalCostPaise = profitAnalysis.totalCostPaise,
                    totalRevenuePaise = profitAnalysis.totalRevenuePaise,
                    totalProfitPaise = profitAnalysis.totalProfitPaise,
                    gainMarkupPercent = profitAnalysis.gainMarkupPercent,
                    grossMarginPercent = profitAnalysis.grossMarginPercent,
                    soldAt = now,
                    yearInt = yearInt,
                    monthInt = monthInt,
                    dayInt = dayInt,
                    isReturned = false,
                    updatedAt = now,
                    syncStatus = SyncState.PENDING_UPLOAD
                )
            )

            receiptLineItems.add(
                ReceiptLineItem(
                    itemName = line.itemName,
                    variantSummary = line.variantSummary,
                    quantity = line.quantity,
                    unit = line.unit,
                    unitSellingPricePaise = line.customSellingPricePaise,
                    lineTotalPaise = profitAnalysis.totalRevenuePaise
                )
            )
            grandTotalPaise += profitAnalysis.totalRevenuePaise
        }

        database.withTransaction {
            for (line in cartItems) {
                inventoryDao.deductVariantStock(
                    variantId = line.variantId,
                    qtySold = line.quantity,
                    now = now
                )
            }
            saleDao.insertSales(saleEntities)
        }

        SyncManager.scheduleDeltaSync(context)

        SaleReceiptData(
            receiptNumber = receiptNo,
            timestampMillis = now,
            buyerName = buyerName.trim(),
            buyerPhone = buyerPhone.trim(),
            sellerName = SyncManager.getSellerName(context),
            sellerWhatsAppPhone = SyncManager.getSellerWhatsAppNumber(context),
            isEstimate = false,
            items = receiptLineItems,
            grandTotalPaise = grandTotalPaise
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
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val yearInt = cal.get(Calendar.YEAR)
        val monthInt = cal.get(Calendar.MONTH) + 1
        val dayInt = cal.get(Calendar.DAY_OF_MONTH)

        val receiptNo = generateNextSmartReceiptNumber(yearInt, monthInt, dayInt)
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
            grandTotalPaise = grandTotal
        )
    }

    /**
     * Generates a smart 12-digit receipt number in `YYYYMMDDCCCC` format where `CCCC` is the
     * 1-based sequential sale count of the day (e.g. `202611010001`).
     */
    private suspend fun generateNextSmartReceiptNumber(
        yearInt: Int,
        monthInt: Int,
        dayInt: Int
    ): String {
        val existingReceiptsToday = saleDao.getDistinctReceiptNumbersForDay(yearInt, monthInt, dayInt)
        val datePrefix = String.format(Locale.US, "%04d%02d%02d", yearInt, monthInt, dayInt)
        var maxSeqToday = existingReceiptsToday.size
        for (r in existingReceiptsToday) {
            if (r.startsWith(datePrefix) && r.length == 12) {
                val seq = r.substring(8).toIntOrNull() ?: 0
                maxSeqToday = max(maxSeqToday, seq)
            }
        }
        val nextCount = maxSeqToday + 1
        return formatSmartReceiptId(yearInt, monthInt, dayInt, nextCount)
    }

    companion object {
        fun formatSmartReceiptId(yearInt: Int, monthInt: Int, dayInt: Int, dailyCount: Int): String {
            return String.format(
                Locale.US,
                "%04d%02d%02d%04d",
                yearInt,
                monthInt,
                dayInt,
                dailyCount.coerceAtLeast(1)
            )
        }
    }

    /**
     * Reverses a sale ("Wapsi / Exchange"), restoring the sold quantity back to inventory stock.
     */
    suspend fun returnSaleAndRestoreStock(sale: SaleRecordEntity) = withContext(Dispatchers.IO) {
        if (sale.isReturned) return@withContext
        val now = System.currentTimeMillis()
        database.withTransaction {
            saleDao.markSaleReturned(sale.id, now)
            inventoryDao.restoreVariantStock(sale.variantId, sale.quantitySold, now)
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
     * Exports the entire database (Items, Variants, Sales) as a portable JSON backup string.
     */
    suspend fun exportBackupJson(): String = withContext(Dispatchers.IO) {
        val items = inventoryDao.getAllRawItems()
        val variants = inventoryDao.getAllRawVariants()
        val sales = saleDao.getAllRawSales()

        val root = JSONObject()
        root.put("shopName", "LPS Hardware")
        root.put("exportedAt", System.currentTimeMillis())

        val itemsArr = JSONArray()
        for (i in items) {
            itemsArr.put(
                JSONObject().apply {
                    put("id", i.id)
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
                    put("itemId", v.itemId)
                    put("sizeLabel", v.sizeLabel)
                    put("materialLabel", v.materialLabel)
                    put("colorFinishLabel", v.colorFinishLabel)
                    put("unit", v.unit)
                    put("purchasePricePaise", v.purchasePricePaise)
                    put("sellingPricePaise", v.sellingPricePaise)
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

        root.toString(2)
    }

    /**
     * Restores items, variants, and sales from a previously exported JSON backup.
     */
    suspend fun importBackupJson(jsonString: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val root = JSONObject(jsonString)
            val itemsArr = root.optJSONArray("items") ?: JSONArray()
            val variantsArr = root.optJSONArray("variants") ?: JSONArray()
            val salesArr = root.optJSONArray("sales") ?: JSONArray()

            val items = ArrayList<InventoryItemEntity>(itemsArr.length())
            for (idx in 0 until itemsArr.length()) {
                val o = itemsArr.getJSONObject(idx)
                items.add(
                    InventoryItemEntity(
                        id = o.getString("id"),
                        displayName = o.getString("displayName"),
                        sortKey = o.optString("sortKey", o.getString("displayName").uppercase()),
                        category = o.optString("category", "General Hardware"),
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
                variants.add(
                    ItemVariantEntity(
                        id = o.getString("id"),
                        itemId = o.getString("itemId"),
                        sizeLabel = o.optString("sizeLabel", ""),
                        materialLabel = o.optString("materialLabel", ""),
                        colorFinishLabel = o.optString("colorFinishLabel", ""),
                        unit = o.optString("unit", "pcs"),
                        purchasePricePaise = o.getLong("purchasePricePaise"),
                        sellingPricePaise = o.getLong("sellingPricePaise"),
                        stockQuantity = o.getDouble("stockQuantity"),
                        lowStockThreshold = o.optDouble("lowStockThreshold", 5.0),
                        updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                        isDeleted = o.optBoolean("isDeleted", false),
                        syncStatus = SyncState.PENDING_UPLOAD
                    )
                )
            }

            val sales = ArrayList<SaleRecordEntity>(salesArr.length())
            for (idx in 0 until salesArr.length()) {
                val o = salesArr.getJSONObject(idx)
                sales.add(
                    SaleRecordEntity(
                        id = o.getString("id"),
                        receiptNumber = o.optString("receiptNumber", "LPS-0000"),
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
                        totalRevenuePaise = o.getLong("totalRevenuePaise"),
                        totalProfitPaise = o.getLong("totalProfitPaise"),
                        gainMarkupPercent = o.optDouble("gainMarkupPercent", 0.0),
                        grossMarginPercent = o.optDouble("grossMarginPercent", 0.0),
                        soldAt = o.getLong("soldAt"),
                        yearInt = o.optInt("yearInt", 2026),
                        monthInt = o.optInt("monthInt", 10),
                        dayInt = o.optInt("dayInt", 31),
                        isReturned = o.optBoolean("isReturned", false),
                        updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                        syncStatus = SyncState.PENDING_UPLOAD
                    )
                )
            }

            database.withTransaction {
                inventoryDao.upsertItems(items)
                inventoryDao.upsertVariants(variants)
                saleDao.insertSales(sales)
            }
            SyncManager.scheduleDeltaSync(context)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Seeds realistic initial LPS Hardware inventory items on first launch so the shop owner
     * can immediately see alphabetical sorting, multi-variants (Size, Material, Color), and the
     * coded purchase price symbols in action.
     */
    suspend fun seedStarterCatalogIfEmpty() = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("lps_seed_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("catalog_seeded_v1", false)) return@withContext

        val existing = inventoryDao.getAllActiveItemsSnapshot()
        if (existing.isNotEmpty()) {
            prefs.edit().putBoolean("catalog_seeded_v1", true).apply()
            return@withContext
        }

        // 1. Butt Hinge (Kabza) - Multiple variants (4 inch & 6 inch, SS 304 & Brass, Matt Black & Silver)
        val hingeId = addSeedItemWithVariant(
            displayName = "Butt Hinge (Kabza)",
            category = "Door & Window Fittings",
            sizeLabel = "4 inch (100mm)",
            materialLabel = "SS 304",
            colorFinishLabel = "Silver / Chrome",
            unit = "pcs",
            purchasePricePaise = 4800L, // ₹48 -> coded as [□][House+Stem]
            sellingPricePaise = 7500L,  // ₹75
            stockQuantity = 40.0,
            lowStockThreshold = 10.0
        )
        addSeedVariantToItem(
            itemId = hingeId,
            sizeLabel = "4 inch (100mm)",
            materialLabel = "SS 304",
            colorFinishLabel = "Matt Black",
            unit = "pcs",
            purchasePricePaise = 6500L, // ₹65 -> [☒][Square+Diag]
            sellingPricePaise = 9500L,  // ₹95
            stockQuantity = 18.0,
            lowStockThreshold = 6.0
        )
        addSeedVariantToItem(
            itemId = hingeId,
            sizeLabel = "6 inch (150mm)",
            materialLabel = "Brass",
            colorFinishLabel = "Antique Brass",
            unit = "pcs",
            purchasePricePaise = 14500L, // ₹145
            sellingPricePaise = 21000L,  // ₹210
            stockQuantity = 12.0,
            lowStockThreshold = 4.0
        )

        // 2. Drywall Screw (Black)
        val screwId = addSeedItemWithVariant(
            displayName = "Drywall Screw (Black)",
            category = "Fasteners & Screws",
            sizeLabel = "1.5 inch (38mm)",
            materialLabel = "MS (Iron)",
            colorFinishLabel = "Black",
            unit = "box",
            purchasePricePaise = 13500L, // ₹135
            sellingPricePaise = 19000L,  // ₹190
            stockQuantity = 15.0,
            lowStockThreshold = 5.0
        )
        addSeedVariantToItem(
            itemId = screwId,
            sizeLabel = "2 inch (50mm)",
            materialLabel = "MS (Iron)",
            colorFinishLabel = "Black",
            unit = "box",
            purchasePricePaise = 16000L, // ₹160
            sellingPricePaise = 22500L,  // ₹225
            stockQuantity = 4.0, // Low stock demo!
            lowStockThreshold = 5.0
        )

        // 3. Tower Bolt (Chatkani)
        val boltId = addSeedItemWithVariant(
            displayName = "Tower Bolt (Chatkani)",
            category = "Door & Window Fittings",
            sizeLabel = "6 inch (150mm)",
            materialLabel = "SS",
            colorFinishLabel = "Silver / Chrome",
            unit = "pcs",
            purchasePricePaise = 8200L, // ₹82
            sellingPricePaise = 12500L, // ₹125
            stockQuantity = 24.0,
            lowStockThreshold = 6.0
        )
        addSeedVariantToItem(
            itemId = boltId,
            sizeLabel = "8 inch (200mm)",
            materialLabel = "Aluminium",
            colorFinishLabel = "Rose Gold",
            unit = "pcs",
            purchasePricePaise = 11000L, // ₹110
            sellingPricePaise = 16500L, // ₹165
            stockQuantity = 3.0, // Low stock demo!
            lowStockThreshold = 5.0
        )

        // 4. Wood Adhesive (Fevicol SH)
        addSeedItemWithVariant(
            displayName = "Wood Adhesive (Fevicol SH)",
            category = "Paints & Adhesives",
            sizeLabel = "1 kg",
            materialLabel = "",
            colorFinishLabel = "White",
            unit = "pkt",
            purchasePricePaise = 23000L, // ₹230
            sellingPricePaise = 29000L,  // ₹290
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
