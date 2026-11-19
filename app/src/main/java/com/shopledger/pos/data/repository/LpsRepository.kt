package com.shopledger.pos.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.shopledger.pos.core.format.IndianCurrencyFormatter
import com.shopledger.pos.core.nlp.SmartItemMatcher
import com.shopledger.pos.core.share.ReceiptLineItem
import com.shopledger.pos.core.share.SaleReceiptData
import com.shopledger.pos.core.sync.SyncManager
import com.shopledger.pos.data.command.AppCommandEnvironment
import com.shopledger.pos.data.command.CatalogCommands
import com.shopledger.pos.data.command.CommandEnvironment
import com.shopledger.pos.data.command.CommandException
import com.shopledger.pos.data.command.KhataCommands
import com.shopledger.pos.data.command.LegacyJsonImporter
import com.shopledger.pos.data.command.OutboxDocType
import com.shopledger.pos.data.command.OutboxOp
import com.shopledger.pos.data.command.PaymentDirection
import com.shopledger.pos.data.command.VariantEdit
import com.shopledger.pos.data.command.VariantSpec
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
import com.shopledger.pos.data.local.entity.SyncOutboxEntity
import com.shopledger.pos.data.model.CartLineItem
import com.shopledger.pos.data.model.InventoryItemWithVariants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import kotlin.math.max

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
        val shopId = env.shopId()
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

        var resolvedPartyId: String? = null
        var newParty: PartyEntity? = null
        if (balanceDuePaise > 0L || cleanBuyerPhone.isNotBlank()) {
            val phoneDigits = cleanBuyerPhone.filter { it.isDigit() }.takeLast(10)
            val matched = shopDao.getAllActivePartiesSnapshot().firstOrNull { p ->
                (phoneDigits.length >= 6 && p.phone.filter { it.isDigit() }.takeLast(10) == phoneDigits) ||
                    (cleanBuyerName.isNotBlank() && p.name.equals(cleanBuyerName, ignoreCase = true))
            }
            if (matched != null) {
                resolvedPartyId = matched.id
            } else if (balanceDuePaise > 0L || cleanBuyerName.isNotBlank() || phoneDigits.length >= 6) {
                val created = PartyEntity(
                    id = UUID.randomUUID().toString(),
                    shopId = shopId,
                    name = cleanBuyerName.ifBlank { cleanBuyerPhone.ifBlank { "Credit Customer" } },
                    phone = if (phoneDigits.length >= 10) phoneDigits else cleanBuyerPhone,
                    createdAt = now,
                    updatedAt = now
                )
                newParty = created
                resolvedPartyId = created.id
            }
        }
        // vals (not vars) so they can be used inside the transaction lambda below
        val partyToCreate: PartyEntity? = newParty
        val ledgerPartyId: String? = resolvedPartyId

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
                    id = lineId, shopId = shopId, receiptNumber = receiptNo, buyerName = cleanBuyerName,
                    buyerPhone = cleanBuyerPhone, partyId = resolvedPartyId, itemId = line.itemId,
                    variantId = line.variantId, itemNameSnapshot = line.itemName,
                    variantSummarySnapshot = line.variantSummary, quantitySold = line.quantity,
                    unitSnapshot = line.unit, unitPurchasePricePaise = line.unitPurchasePricePaise,
                    unitSellingPricePaise = line.customSellingPricePaise, totalCostPaise = analysis.totalCostPaise,
                    totalRevenuePaise = analysis.totalRevenuePaise, totalProfitPaise = analysis.totalProfitPaise,
                    gainMarkupPercent = analysis.gainMarkupPercent, grossMarginPercent = analysis.grossMarginPercent,
                    paymentMode = normalizedPaymentMode, amountPaidPaise = resolvedPaidPaise,
                    balanceDuePaise = balanceDuePaise, discountPaise = cleanDiscount,
                    gstPaise = taxBreakdown.totalTaxPaise, soldAt = now, yearInt = yearInt, monthInt = monthInt,
                    dayInt = dayInt, updatedAt = now
                )
            )
            invoiceLines.add(
                InvoiceLineEntity(
                    id = lineId, shopId = shopId, invoiceId = invoiceId, productId = line.itemId,
                    skuId = line.variantId, itemNameSnapshot = line.itemName,
                    variantSummarySnapshot = line.variantSummary, qtyMilliUnits = qtyMilli, unitSnapshot = line.unit,
                    unitCostPaise = line.unitPurchasePricePaise, unitSellPaise = line.customSellingPricePaise,
                    gstRateBps = effectiveGstBps, lineTotalPaise = analysis.totalRevenuePaise,
                    lineProfitPaise = analysis.totalProfitPaise
                )
            )
        }

        val invoiceHeader = InvoiceHeaderEntity(
            id = invoiceId, shopId = shopId, deviceShortCode = deviceCode, invoiceNumber = receiptNo,
            partyId = resolvedPartyId, buyerName = cleanBuyerName, buyerPhone = cleanBuyerPhone,
            paymentMode = normalizedPaymentMode, subtotalPaise = subtotalPaise, discountPaise = cleanDiscount,
            cgstPaise = taxBreakdown.cgstPaise, sgstPaise = taxBreakdown.sgstPaise, igstPaise = taxBreakdown.igstPaise,
            grandTotalPaise = grandTotalPaise, amountPaidPaise = resolvedPaidPaise, balanceDuePaise = balanceDuePaise,
            issuedAt = now, yearInt = yearInt, monthInt = monthInt, dayInt = dayInt, updatedAt = now
        )

        database.withTransaction {
            val device = database.deviceDao().getLocalDevice() ?: error("Local device row missing")
            for (line in invoiceLines) {
                shopDao.insertStockMovement(
                    StockMovementEntity(
                        movementId = UUID.randomUUID().toString(), shopId = shopId, skuId = line.skuId,
                        deviceId = device.deviceId, deviceShortCode = device.deviceCode,
                        deltaMilliUnits = -line.qtyMilliUnits, movementType = "SALE", refDocId = invoiceId,
                        note = "Sale #$receiptNo", createdAt = now
                    )
                )
                inventoryDao.addToStockProjection(line.skuId, -line.qtyMilliUnits)
            }
            saleDao.insertSales(saleEntities)
            shopDao.upsertInvoiceHeader(invoiceHeader)
            shopDao.upsertInvoiceLines(invoiceLines)
            if (partyToCreate != null) shopDao.insertParty(partyToCreate)
            if (ledgerPartyId != null && balanceDuePaise > 0L) {
                shopDao.insertLedgerEntry(
                    PartyTransactionEntity(
                        entryId = UUID.randomUUID().toString(), shopId = shopId, partyId = ledgerPartyId,
                        refDocId = invoiceId, entryType = "CREDIT_BILL", amountDeltaPaise = balanceDuePaise,
                        paymentMode = normalizedPaymentMode, note = "Invoice #$receiptNo", createdAt = now,
                        deviceId = device.deviceId
                    )
                )
                shopDao.addToPartyBalance(ledgerPartyId, balanceDuePaise)
            }
            database.syncOutboxDao().insert(SyncOutboxEntity(docType = OutboxDocType.INVOICE, docId = invoiceId, op = OutboxOp.APPEND, createdAt = now))
            if (partyToCreate != null) database.syncOutboxDao().insert(SyncOutboxEntity(docType = OutboxDocType.PARTY, docId = partyToCreate.id, op = OutboxOp.UPSERT, createdAt = now))
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
        val qtyMilli = IndianCurrencyFormatter.doubleToMilliUnits(sale.quantitySold)
        database.withTransaction {
            val device = database.deviceDao().getLocalDevice() ?: error("Local device row missing")
            saleDao.markSaleReturned(sale.id, now)
            val movement = StockMovementEntity(
                movementId = UUID.randomUUID().toString(), shopId = sale.shopId.ifBlank { env.shopId() },
                skuId = sale.variantId, deviceId = device.deviceId, deviceShortCode = device.deviceCode,
                deltaMilliUnits = qtyMilli, movementType = "SALE_RETURN", refDocId = sale.receiptNumber,
                note = "Return of #${sale.receiptNumber}", createdAt = now
            )
            shopDao.insertStockMovement(movement)
            inventoryDao.addToStockProjection(sale.variantId, qtyMilli)
            database.syncOutboxDao().insert(SyncOutboxEntity(docType = OutboxDocType.MOVEMENT, docId = movement.movementId, op = OutboxOp.APPEND, createdAt = now))
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
