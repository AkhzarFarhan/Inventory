package com.lpshardware.inventory

import com.lpshardware.inventory.core.auth.SignedInGoogleUser
import com.lpshardware.inventory.core.buyer.BuyerHistoryHelper
import com.lpshardware.inventory.core.cipher.CipherSymbol
import com.lpshardware.inventory.core.cipher.CostCipherEngine
import com.lpshardware.inventory.core.format.IndianCurrencyFormatter
import com.lpshardware.inventory.core.nlp.DuplicateDecisionType
import com.lpshardware.inventory.core.nlp.SmartItemMatcher
import com.lpshardware.inventory.core.share.ReceiptShareManager
import com.lpshardware.inventory.core.update.AppUpdateManager
import com.lpshardware.inventory.data.local.entity.InventoryItemEntity
import com.lpshardware.inventory.data.local.entity.ItemVariantEntity
import com.lpshardware.inventory.data.local.entity.SaleRecordEntity
import com.lpshardware.inventory.data.model.InventoryItemWithVariants
import com.lpshardware.inventory.data.repository.LpsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LpsHardwareDomainTest {

    @Test
    fun costCipherEngine_encodesAllDigitsFromPhotoCorrectly() {
        // ₹1234567890 -> 123456789000L paise
        val symbols = CostCipherEngine.encodePaiseToSymbols(123456789000L)
        assertEquals(
            listOf(
                CipherSymbol.ONE,
                CipherSymbol.TWO,
                CipherSymbol.THREE,
                CipherSymbol.FOUR,
                CipherSymbol.FIVE,
                CipherSymbol.SIX,
                CipherSymbol.SEVEN,
                CipherSymbol.EIGHT,
                CipherSymbol.NINE,
                CipherSymbol.ZERO
            ),
            symbols
        )
        val decodedPaise = CostCipherEngine.decodeSymbolsToPaise(symbols)
        assertEquals(123456789000L, decodedPaise)
    }

    @Test
    fun indianCurrencyFormatter_formatsLakhsAndCalculatesMarginsAndGains() {
        assertEquals("₹1,25,450", IndianCurrencyFormatter.formatPaise(12545000L))
        assertEquals("₹450.5", IndianCurrencyFormatter.formatPaise(45050L))

        // Purchase = ₹100, Selling = ₹150, Qty = 2 -> Cost = ₹200, Revenue = ₹300, Profit = ₹100
        // Gain (Markup on cost) = +50%, Gross Margin (on revenue) = 33.33%
        val analysis = IndianCurrencyFormatter.computeProfitAnalysis(
            unitPurchasePaise = 10000L,
            unitSellingPaise = 15000L,
            quantity = 2.0
        )
        assertEquals(20000L, analysis.totalCostPaise)
        assertEquals(30000L, analysis.totalRevenuePaise)
        assertEquals(10000L, analysis.totalProfitPaise)
        assertEquals(50.0, analysis.gainMarkupPercent, 0.01)
        assertEquals(33.33, analysis.grossMarginPercent, 0.05)
    }

    @Test
    fun smartItemMatcher_detectsSemanticDuplicateAcrossHinglishAndMetricUnits() {
        val existingHinge = InventoryItemWithVariants(
            item = InventoryItemEntity(
                id = "item-1",
                displayName = "Butt Hinge",
                sortKey = "BUTT HINGE",
                category = "Door & Window Fittings"
            ),
            allVariants = listOf(
                ItemVariantEntity(
                    id = "var-1",
                    itemId = "item-1",
                    sizeLabel = "4 inch (100mm)",
                    materialLabel = "SS",
                    colorFinishLabel = "Matt Black",
                    unit = "pcs",
                    purchasePricePaise = 4800L,
                    sellingPricePaise = 7500L,
                    stockQuantity = 20.0
                )
            )
        )

        // Candidate uses Hinglish "Kabza", metric "100mm" (= 4 inch), and "Stainless" (= SS)
        val result = SmartItemMatcher.checkDuplicateOrVariant(
            candidateName = "100mm Stainless Kabza Matt Black",
            candidateSize = "",
            candidateMaterial = "",
            candidateColorFinish = "",
            existingInventory = listOf(existingHinge)
        )

        assertEquals(DuplicateDecisionType.EXACT_DUPLICATE, result.decision)
        assertEquals("item-1", result.matchedItem?.item?.id)
    }

    @Test
    fun smartItemMatcher_detectsNewVariantWhenSizeOrColorOrMaterialDiffers() {
        val existingHinge = InventoryItemWithVariants(
            item = InventoryItemEntity(
                id = "item-1",
                displayName = "Butt Hinge",
                sortKey = "BUTT HINGE",
                category = "Door & Window Fittings"
            ),
            allVariants = listOf(
                ItemVariantEntity(
                    id = "var-1",
                    itemId = "item-1",
                    sizeLabel = "4 inch (100mm)",
                    materialLabel = "SS",
                    colorFinishLabel = "Matt Black",
                    unit = "pcs",
                    purchasePricePaise = 4800L,
                    sellingPricePaise = 7500L,
                    stockQuantity = 20.0
                )
            )
        )

        // Candidate is same base item ("Kabja" == Butt Hinge), but 6 inch Brass Antique!
        val result = SmartItemMatcher.checkDuplicateOrVariant(
            candidateName = "6 inch Brass Kabja Antique",
            candidateSize = "",
            candidateMaterial = "",
            candidateColorFinish = "",
            existingInventory = listOf(existingHinge)
        )

        assertEquals(DuplicateDecisionType.NEW_VARIANT_OF_EXISTING, result.decision)
        assertEquals("item-1", result.matchedItem?.item?.id)
    }

    @Test
    fun smartItemMatcher_matchesSearchQueriesWithSynonymsAndFractions() {
        val towerBolt = InventoryItemWithVariants(
            item = InventoryItemEntity(
                id = "item-2",
                displayName = "Tower Bolt",
                sortKey = "TOWER BOLT",
                category = "Door & Window Fittings"
            ),
            allVariants = listOf(
                ItemVariantEntity(
                    id = "var-2",
                    itemId = "item-2",
                    sizeLabel = "1.5 inch (38mm)",
                    materialLabel = "Aluminium",
                    colorFinishLabel = "Rose Gold",
                    unit = "pcs",
                    purchasePricePaise = 3500L,
                    sellingPricePaise = 5500L,
                    stockQuantity = 15.0
                )
            )
        )

        assertTrue(SmartItemMatcher.matchesSearchQuery("chatkani", towerBolt))
        assertTrue(SmartItemMatcher.matchesSearchQuery("dedh inch kundi", towerBolt))
    }

    @Test
    fun googleAuthUser_generatesDeterministicCloudDocumentIdAcrossDevices() {
        val userOnPhoneA = SignedInGoogleUser(
            googleUserId = "1098234",
            email = "Lps.Hardware.Shop@gmail.com",
            displayName = "LPS Owner"
        )
        val userOnPhoneB = SignedInGoogleUser(
            googleUserId = "1098234",
            email = "lps.hardware.shop@gmail.com",
            displayName = "LPS Hardware"
        )
        assertEquals("user_lps_hardware_shop_gmail_com", userOnPhoneA.cloudAccountDocumentId)
        assertEquals(userOnPhoneA.cloudAccountDocumentId, userOnPhoneB.cloudAccountDocumentId)
    }

    @Test
    fun smartReceiptId_formatsDateAndDailyCounterAsYYYYMMDDCCCC() {
        assertEquals("202611010001", LpsRepository.formatSmartReceiptId(2026, 11, 1, 1))
        assertEquals("202611010015", LpsRepository.formatSmartReceiptId(2026, 11, 1, 15))
        assertEquals("202604090108", LpsRepository.formatSmartReceiptId(2026, 4, 9, 108))
    }

    @Test
    fun buyerHistoryHelper_extractsBuyerProfilesAndMatchesByNameOrPhone() {
        val sales = listOf(
            SaleRecordEntity(
                id = "sale-2",
                receiptNumber = "202611010002",
                itemId = "item-1",
                variantId = "var-1",
                itemNameSnapshot = "Butt Hinge",
                variantSummarySnapshot = "4 inch (100mm) • SS • Matt Black",
                unitSnapshot = "pcs",
                quantitySold = 6.0,
                unitPurchasePricePaise = 4800L,
                unitSellingPricePaise = 7200L,
                totalCostPaise = 28800L,
                totalRevenuePaise = 43200L,
                totalProfitPaise = 14400L,
                gainMarkupPercent = 50.0,
                grossMarginPercent = 33.33,
                buyerName = "Ramesh Sharma",
                buyerPhone = "+91 98765 43210",
                soldAt = 2000L,
                yearInt = 2026,
                monthInt = 11,
                dayInt = 1
            ),
            SaleRecordEntity(
                id = "sale-1",
                receiptNumber = "202610310001",
                itemId = "item-2",
                variantId = "var-2",
                itemNameSnapshot = "Tower Bolt",
                variantSummarySnapshot = "6 inch • Brass",
                unitSnapshot = "pcs",
                quantitySold = 4.0,
                unitPurchasePricePaise = 5000L,
                unitSellingPricePaise = 8000L,
                totalCostPaise = 20000L,
                totalRevenuePaise = 32000L,
                totalProfitPaise = 12000L,
                gainMarkupPercent = 60.0,
                grossMarginPercent = 37.5,
                buyerName = "Ramesh Sharma",
                buyerPhone = "9876543210",
                soldAt = 1000L,
                yearInt = 2026,
                monthInt = 10,
                dayInt = 31
            )
        )

        val profiles = BuyerHistoryHelper.extractBuyerProfiles(sales)
        assertEquals(1, profiles.size)
        val ramesh = profiles.first()
        assertEquals("Ramesh Sharma", ramesh.buyerName)
        assertEquals("9876543210", ramesh.buyerPhone)
        assertEquals(2, ramesh.totalDistinctOrders)
        assertEquals(1, ramesh.lastOrderItems.size)
        assertEquals("Butt Hinge", ramesh.lastOrderItems.first().itemName)
        assertEquals(2, ramesh.allDistinctPurchasedItems.size)

        // Match by partial name
        val byName = BuyerHistoryHelper.findMatchingBuyers("ram", "", profiles)
        assertEquals(1, byName.size)

        // Match by partial phone
        val byPhone = BuyerHistoryHelper.findMatchingBuyers("", "9876", profiles)
        assertEquals(1, byPhone.size)
    }

    @Test
    fun appUpdateManager_isNewerVersionEvaluatesCorrectly() {
        // Tag-based semantic versioning
        assertTrue(AppUpdateManager.isNewerVersion("1.0.0", 1, "inventory-v1.0.1", null))
        assertTrue(AppUpdateManager.isNewerVersion("1.0.0", 1, "v1.1.0", null))
        assertTrue(AppUpdateManager.isNewerVersion("1.0.5", 5, "v2.0.0", null))
        assertFalse(AppUpdateManager.isNewerVersion("1.0.1", 1, "inventory-v1.0.1", null))
        assertFalse(AppUpdateManager.isNewerVersion("1.0.2", 2, "inventory-v1.0.1", null))

        // VersionCode-based evaluation
        assertTrue(AppUpdateManager.isNewerVersion("1.0.0", 5, "inventory-v1.0.0", 6L))
        assertFalse(AppUpdateManager.isNewerVersion("1.0.0", 5, "inventory-v1.0.0", 5L))
        assertFalse(AppUpdateManager.isNewerVersion("1.0.0", 5, "inventory-v1.0.0", 4L))
    }

    @Test
    fun receiptShareManager_normalizesIndianPhoneNumberForSingleTapWhatsAppLink() {
        assertEquals("919876543210", ReceiptShareManager.normalizeIndianPhoneNumber("9876543210"))
        assertEquals("919876543210", ReceiptShareManager.normalizeIndianPhoneNumber("+91 98765 43210"))
        assertEquals("919876543210", ReceiptShareManager.normalizeIndianPhoneNumber("09876543210"))
        assertEquals("919876543210", ReceiptShareManager.normalizeIndianPhoneNumber("919876543210"))
    }
}
