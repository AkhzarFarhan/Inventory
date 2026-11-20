package com.shopledger.pos.data.repository

import android.content.Context
import com.shopledger.pos.core.format.IndianCurrencyFormatter
import com.shopledger.pos.core.nlp.SmartItemMatcher
import com.shopledger.pos.core.share.ReceiptLineItem
import com.shopledger.pos.core.share.SaleReceiptData
import com.shopledger.pos.core.sync.SyncManager
import com.shopledger.pos.data.command.AppCommandEnvironment
import com.shopledger.pos.data.command.CatalogCommands
import com.shopledger.pos.data.command.DeviceCommands
import com.shopledger.pos.data.command.CheckoutLine
import com.shopledger.pos.data.command.CheckoutRequest
import com.shopledger.pos.data.command.CommandEnvironment
import com.shopledger.pos.data.command.CommandException
import com.shopledger.pos.data.command.InvoiceResult
import com.shopledger.pos.data.command.KhataCommands
import com.shopledger.pos.data.command.LegacyJsonImporter
import com.shopledger.pos.data.command.PaymentDirection
import com.shopledger.pos.data.command.ReturnLine
import com.shopledger.pos.data.command.SalesCommands
import com.shopledger.pos.data.command.StockInCommands
import com.shopledger.pos.data.command.StockInRequest
import com.shopledger.pos.data.command.StockInResult
import com.shopledger.pos.data.command.VariantEdit
import com.shopledger.pos.data.command.VariantSpec
import com.shopledger.pos.data.local.LpsDatabase
import com.shopledger.pos.data.local.entity.InventoryItemEntity
import com.shopledger.pos.data.local.entity.ItemVariantEntity
import com.shopledger.pos.data.local.entity.PartyEntity
import com.shopledger.pos.data.local.entity.PartyTransactionEntity
import com.shopledger.pos.data.local.entity.SaleRecordEntity
import com.shopledger.pos.data.local.entity.SearchHistoryEntity
import com.shopledger.pos.data.model.CartLineItem
import com.shopledger.pos.data.model.InventoryItemWithVariants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID

typealias ShopRepository = LpsRepository

class LpsRepository(
    private val context: Context,
    private val database: LpsDatabase,
    private val env: CommandEnvironment = AppCommandEnvironment(context)
) {
    private val inventoryDao = database.inventoryDao()
    private val saleDao = database.saleDao()
    private val searchHistoryDao = database.searchHistoryDao()
    private val shopDao = database.shopPartyAndLedgerDao()
    private val catalog = CatalogCommands(database, env)
    private val khata = KhataCommands(database, env)
    private val sales = SalesCommands(database, env)
    private val stockIn = StockInCommands(database, env)
    private val deviceCommands = DeviceCommands(database, env)


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

    val totalPendingSyncCountFlow: Flow<Int> = database.syncOutboxDao().observePendingCount()

    data class CheckoutOutcome(
        val receipt: SaleReceiptData,
        val oversoldItemNames: List<String>,
        val replayed: Boolean
    )

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
    ) {
        fun toVariantSpec() = VariantSpec(
            sizeLabel = sizeLabel,
            materialLabel = materialLabel,
            colorFinishLabel = colorFinishLabel,
            unit = unit,
            purchasePricePaise = purchasePricePaise,
            sellingPricePaise = sellingPricePaise,
            openingStockMilli = IndianCurrencyFormatter.doubleToMilliUnits(stockQuantity),
            lowStockMilli = IndianCurrencyFormatter.doubleToMilliUnits(lowStockThreshold),
            barcode = barcode,
            skuCode = skuCode,
            gstRateBps = gstRateBps
        )
    }

    private fun newActionId(): String = UUID.randomUUID().toString()

    suspend fun localDeviceCode(): String = deviceCommands.localDeviceCode()

    /** Saves a whole scanned stock list (items, costs, movements, supplier bill) atomically. */
    suspend fun receiveScannedStock(request: StockInRequest): StockInResult = stockIn.receiveScannedStock(request)

    /** Role of whoever is using this device right now (OWNER until staff accounts exist). */
    fun currentRole(): com.shopledger.pos.data.command.Role = env.actor().role

    suspend fun setDeviceCode(code: String): String = deviceCommands.setDeviceCode(newActionId(), code)

    /**
     * Adds a brand-new parent product along with multiple variants/SKUs (each with its own distinct pricing).
     */
    suspend fun addNewItemWithVariants(
        displayName: String,
        category: String,
        variants: List<NewVariantSpec>
    ): String = catalog.createItem(newActionId(), displayName, category, variants.map { it.toVariantSpec() })

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
        displayName, category,
        listOf(NewVariantSpec(sizeLabel, materialLabel, colorFinishLabel, unit, purchasePricePaise, sellingPricePaise, stockQuantity, lowStockThreshold, barcode, skuCode, gstRateBps))
    )

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
    ): String = catalog.addVariant(
        newActionId(), itemId,
        NewVariantSpec(sizeLabel, materialLabel, colorFinishLabel, unit, purchasePricePaise, sellingPricePaise, stockQuantity, lowStockThreshold, barcode, skuCode, gstRateBps).toVariantSpec(),
        renameTo = newDisplayName,
        recategorizeTo = newCategory
    )

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
    ) {
        val newStockMilli = IndianCurrencyFormatter.doubleToMilliUnits(newStockQuantity)
        catalog.editItem(
            actionId = newActionId(),
            itemId = item.id,
            expectedItemUpdatedAt = item.updatedAt,
            newDisplayName = newDisplayName,
            newCategory = newCategory,
            variantId = variant.id,
            expectedVariantUpdatedAt = variant.updatedAt,
            edit = VariantEdit(
                sizeLabel = newSizeLabel,
                materialLabel = newMaterialLabel,
                colorFinishLabel = newColorFinishLabel,
                unit = newUnit,
                purchasePricePaise = newPurchasePricePaise,
                sellingPricePaise = newSellingPricePaise,
                lowStockMilli = IndianCurrencyFormatter.doubleToMilliUnits(newLowStockThreshold),
                barcode = newBarcode,
                skuCode = newSkuCode,
                gstRateBps = newGstRateBps,
                // Only when the user actually changed the count in the form; otherwise leave stock alone.
                targetStockMilli = newStockMilli.takeIf { it != variant.stockMilliUnits }
            )
        )
    }

    /**
     * Deletes an entire parent item or a single variant.
     */
    suspend fun deleteItemOrVariant(itemId: String, variantId: String?, totalVariantsCount: Int) =
        catalog.deleteItem(newActionId(), itemId, variantId.takeIf { totalVariantsCount > 1 })

    suspend fun executeCheckoutAndDeductStock(
        actionId: String,
        cartItems: List<CartLineItem>,
        buyerName: String = "",
        buyerPhone: String = "",
        paymentMode: String = "CASH",
        discountPaise: Long = 0L,
        amountPaidPaise: Long? = null,
        gstRateBps: Int = 0
    ): CheckoutOutcome = withContext(Dispatchers.IO) {
        val result = sales.checkout(
            CheckoutRequest(
                actionId = actionId,
                lines = cartItems.map {
                    CheckoutLine(it.variantId, IndianCurrencyFormatter.doubleToMilliUnits(it.quantity), it.customSellingPricePaise)
                },
                buyerName = buyerName,
                buyerPhone = buyerPhone,
                paymentMode = paymentMode,
                discountPaise = discountPaise,
                amountPaidPaise = amountPaidPaise,
                gstRateBps = gstRateBps
            )
        )
        val oversoldNames = result.lines.filter { it.skuId in result.oversoldVariantIds }.map { it.itemNameSnapshot }
        CheckoutOutcome(result.toReceipt(), oversoldNames, result.replayed)
    }

    private fun InvoiceResult.toReceipt(): SaleReceiptData = SaleReceiptData(
        receiptNumber = header.invoiceNumber,
        timestampMillis = header.issuedAt,
        buyerName = header.buyerName,
        buyerPhone = header.buyerPhone,
        sellerName = SyncManager.getSellerName(context),
        sellerWhatsAppPhone = SyncManager.getSellerWhatsAppNumber(context),
        isEstimate = false,
        items = lines.map {
            ReceiptLineItem(
                itemName = it.itemNameSnapshot,
                variantSummary = it.variantSummarySnapshot,
                quantity = it.qtyMilliUnits / 1000.0,
                unit = it.unitSnapshot,
                unitSellingPricePaise = it.unitSellPaise,
                lineTotalPaise = it.lineTotalPaise
            )
        },
        grandTotalPaise = header.grandTotalPaise,
        paymentMode = header.paymentMode,
        subtotalPaise = header.subtotalPaise,
        discountPaise = header.discountPaise,
        taxPaise = header.cgstPaise + header.sgstPaise + header.igstPaise,
        amountPaidPaise = header.amountPaidPaise,
        balanceDuePaise = header.balanceDuePaise,
        upiId = SyncManager.getUpiId(context),
        gstin = SyncManager.getGstin(context)
    )

    /**
     * Prepares an Estimate (Quotation / Kaccha Parcha) photo receipt without deducting stock.
     */
    suspend fun prepareEstimateReceiptData(
        cartItems: List<CartLineItem>,
        buyerName: String = "",
        buyerPhone: String = ""
    ): SaleReceiptData = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val receiptNo = "EST-" + java.text.SimpleDateFormat("yyMMdd-HHmmss", Locale.US).format(java.util.Date(now))
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

    suspend fun returnSaleLine(actionId: String, sale: SaleRecordEntity, qtyMilli: Long) {
        sales.returnItems(actionId, sale.invoiceId, listOf(ReturnLine(sale.id, qtyMilli)))
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
    ): String {
        if (id.isNullOrBlank()) {
            return khata.createParty(newActionId(), name, phone, partyType, gstin, openingBalancePaise)
        }
        val current = shopDao.getPartyById(id) ?: throw CommandException.NotFound("Party")
        khata.updateParty(newActionId(), id, current.updatedAt, name, phone, partyType, gstin)
        return id
    }

    /**
     * Records a *Jama* (payment received from customer or paid to supplier), updating balance atomically.
     */
    suspend fun recordPartyPaymentReceived(
        partyId: String,
        amountPaise: Long,
        paymentMode: String = "CASH",
        note: String = ""
    ) {
        khata.recordPayment(newActionId(), partyId, amountPaise, paymentMode, PaymentDirection.IN, note)
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
                    shopId = env.shopId(),
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
     * Restores items, variants, sales, and parties from either a v1 LPS Hardware or v2 ShopLedger JSON backup.
     */
    suspend fun importBackupJson(jsonString: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { LegacyJsonImporter(database, env).import(jsonString) }
            .onFailure { it.printStackTrace() }
            .isSuccess
    }

    suspend fun seedStarterCatalogIfEmpty() = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("lps_seed_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("catalog_seeded_v1", false)) return@withContext
        if (inventoryDao.countItems() > 0) {
            prefs.edit().putBoolean("catalog_seeded_v1", true).apply()
            return@withContext
        }
        fun spec(size: String, material: String, color: String, unit: String, cost: Long, sell: Long, stock: Long, low: Long) =
            VariantSpec(
                sizeLabel = size, materialLabel = material, colorFinishLabel = color, unit = unit,
                purchasePricePaise = cost, sellingPricePaise = sell,
                openingStockMilli = stock * 1000L, lowStockMilli = low * 1000L
            )
        // Fixed action ids make seeding idempotent even if the app is killed half-way.
        catalog.createItem("seed-v1-hinge", "Butt Hinge (Kabza)", "Door & Window Fittings", listOf(
            spec("4 inch (100mm)", "SS 304", "Silver / Chrome", "pcs", 4800L, 7500L, 40, 10),
            spec("4 inch (100mm)", "SS 304", "Matt Black", "pcs", 6500L, 9500L, 18, 6),
            spec("6 inch (150mm)", "Brass", "Antique Brass", "pcs", 14500L, 21000L, 12, 4)
        ), isDemo = true)
        catalog.createItem("seed-v1-screw", "Drywall Screw (Black)", "Fasteners & Screws", listOf(
            spec("1.5 inch (38mm)", "MS (Iron)", "Black", "box", 13500L, 19000L, 15, 5),
            spec("2 inch (50mm)", "MS (Iron)", "Black", "box", 16000L, 22500L, 4, 5)
        ), isDemo = true)
        catalog.createItem("seed-v1-bolt", "Tower Bolt (Chatkani)", "Door & Window Fittings", listOf(
            spec("6 inch (150mm)", "SS", "Silver / Chrome", "pcs", 8200L, 12500L, 24, 6),
            spec("8 inch (200mm)", "Aluminium", "Rose Gold", "pcs", 11000L, 16500L, 3, 5)
        ), isDemo = true)
        catalog.createItem("seed-v1-adhesive", "Wood Adhesive (Fevicol SH)", "Paints & Adhesives", listOf(
            spec("1 kg", "", "White", "pkt", 23000L, 29000L, 10, 3)
        ), isDemo = true)
        prefs.edit().putBoolean("catalog_seeded_v1", true).apply()
    }
}
