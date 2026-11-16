package com.shopledger.pos

import com.shopledger.pos.core.auth.SignedInGoogleUser
import com.shopledger.pos.core.buyer.BuyerHistoryHelper
import com.shopledger.pos.core.cipher.CipherMode
import com.shopledger.pos.core.cipher.CostCipherEngine
import com.shopledger.pos.core.draft.ItemDraftManager
import com.shopledger.pos.core.draft.SavedItemDraft
import com.shopledger.pos.core.error.AppErrorManager
import com.shopledger.pos.core.error.MessageSeverity
import com.shopledger.pos.core.format.IndianCurrencyFormatter
import com.shopledger.pos.core.nlp.DuplicateDecisionType
import com.shopledger.pos.core.nlp.ShopVertical
import com.shopledger.pos.core.nlp.SmartItemMatcher
import com.shopledger.pos.core.nlp.TradeTaxonomyRegistry
import com.shopledger.pos.core.print.ThermalPrinterManager
import com.shopledger.pos.core.scanner.OnDeviceOrderScannerManager
import com.shopledger.pos.core.share.ReceiptLineItem
import com.shopledger.pos.core.share.ReceiptShareManager
import com.shopledger.pos.core.share.SaleReceiptData
import com.shopledger.pos.core.update.AppUpdateManager
import com.shopledger.pos.data.local.entity.InventoryItemEntity
import com.shopledger.pos.data.local.entity.ItemVariantEntity
import com.shopledger.pos.data.local.entity.SaleRecordEntity
import com.shopledger.pos.data.model.InventoryItemWithVariants
import com.shopledger.pos.data.repository.LpsRepository
import com.shopledger.pos.ui.screens.inventory.VariantDraft
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LpsHardwareDomainTest {

    @Test
    fun costCipherEngine_encodesAndDecodesCustomUserDigitMapCorrectly() {
        val customDigitMap = mapOf(
            '1' to "@",
            '2' to "#",
            '3' to "$",
            '4' to "%",
            '5' to "&",
            '6' to "*",
            '7' to "+",
            '8' to "?",
            '9' to "!",
            '0' to "~"
        )
        // ₹1,250.75 -> 125075L paise -> [@ # & ~ . + &]
        val coded = CostCipherEngine.encodePaiseToCustomCode(125075L, customDigitMap)
        assertEquals("[@ # & ~ . + &]", coded)
        assertEquals(125075L, CostCipherEngine.decodeCustomCodeToPaise(coded, customDigitMap))

        // Full 10-digit roundtrip via custom digit tokens
        val multiCharMap = mapOf(
            '1' to "AA",
            '2' to "BB",
            '3' to "CC",
            '4' to "DD",
            '5' to "EE",
            '6' to "FF",
            '7' to "GG",
            '8' to "HH",
            '9' to "II",
            '0' to "OO"
        )
        val encodedAll = CostCipherEngine.encodePaiseToCustomCode(123456789000L, multiCharMap)
        assertEquals("[AA BB CC DD EE FF GG HH II OO]", encodedAll)
        assertEquals(123456789000L, CostCipherEngine.decodeCustomCodeToPaise(encodedAll, multiCharMap))
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

        val whiteCement = InventoryItemWithVariants(
            item = InventoryItemEntity(
                id = "wc-1",
                displayName = "White Cement",
                sortKey = "WHITE CEMENT",
                category = "Paints & Adhesives"
            ),
            allVariants = listOf(
                ItemVariantEntity(
                    id = "wc-v1",
                    itemId = "wc-1",
                    sizeLabel = "1 kg",
                    materialLabel = "",
                    colorFinishLabel = "White",
                    unit = "pkt",
                    purchasePricePaise = 2500L,
                    sellingPricePaise = 4000L,
                    stockQuantity = 20.0
                )
            )
        )

        val buttHinge = InventoryItemWithVariants(
            item = InventoryItemEntity(id = "bh-1", displayName = "Butt Hinge (Kabza)", sortKey = "BUTT HINGE (KABZA)", category = "Door & Window Fittings"),
            allVariants = emptyList()
        )
        val drywallScrew = InventoryItemWithVariants(
            item = InventoryItemEntity(id = "ds-1", displayName = "Drywall Screw (Black)", sortKey = "DRYWALL SCREW (BLACK)", category = "Fasteners & Screws"),
            allVariants = emptyList()
        )
        val woodAdhesive = InventoryItemWithVariants(
            item = InventoryItemEntity(id = "wa-1", displayName = "Wood Adhesive (Fevicol SH)", sortKey = "WOOD ADHESIVE (FEVICOL SH)", category = "Paints & Adhesives"),
            allVariants = emptyList()
        )

        // Verifies searching 'Cement' matches White Cement and excludes non-matching products
        assertTrue(SmartItemMatcher.matchesSearchQuery("Cement", whiteCement))
        assertFalse(SmartItemMatcher.matchesSearchQuery("Cement", buttHinge))
        assertFalse(SmartItemMatcher.matchesSearchQuery("Cement", drywallScrew))
        assertFalse(SmartItemMatcher.matchesSearchQuery("Cement", woodAdhesive))
        assertFalse(SmartItemMatcher.matchesSearchQuery("Cement", towerBolt))
    }

    @Test
    fun searchRelevanceRanking_ranksDirectNameMatchesAboveCategoryAndWeakMatches() {
        val whiteCement = InventoryItemWithVariants(
            item = InventoryItemEntity(
                id = "wc-1",
                displayName = "White Cement",
                sortKey = "WHITE CEMENT",
                category = "Paints & Adhesives"
            ),
            allVariants = listOf(
                ItemVariantEntity(
                    id = "wc-v1",
                    itemId = "wc-1",
                    sizeLabel = "1 kg",
                    materialLabel = "",
                    colorFinishLabel = "White",
                    unit = "pkt",
                    purchasePricePaise = 2500L,
                    sellingPricePaise = 4000L,
                    stockQuantity = 20.0
                )
            )
        )
        val wallPuttyWithCementCategory = InventoryItemWithVariants(
            item = InventoryItemEntity(
                id = "wp-1",
                displayName = "Wall Putty (JK White)",
                sortKey = "WALL PUTTY (JK WHITE)",
                category = "Cement & Aggregates"
            ),
            allVariants = emptyList()
        )
        val buttHinge = InventoryItemWithVariants(
            item = InventoryItemEntity(
                id = "bh-1",
                displayName = "Butt Hinge (Kabza)",
                sortKey = "BUTT HINGE (KABZA)",
                category = "Door & Window Fittings"
            ),
            allVariants = listOf(
                ItemVariantEntity(
                    id = "bh-v1",
                    itemId = "bh-1",
                    sizeLabel = "4 inch (100mm)",
                    materialLabel = "SS 304",
                    colorFinishLabel = "Matt Black",
                    unit = "pcs",
                    purchasePricePaise = 4800L,
                    sellingPricePaise = 7500L,
                    stockQuantity = 20.0
                )
            )
        )
        val drywallScrew = InventoryItemWithVariants(
            item = InventoryItemEntity(
                id = "ds-1",
                displayName = "Drywall Screw (Black)",
                sortKey = "DRYWALL SCREW (BLACK)",
                category = "Fasteners & Screws"
            ),
            allVariants = emptyList()
        )

        val inventoryList = listOf(buttHinge, drywallScrew, wallPuttyWithCementCategory, whiteCement)

        // Searching for "Cement"
        val query = "Cement"
        val rankedResults = inventoryList
            .mapNotNull { item ->
                val score = SmartItemMatcher.calculateSearchRelevanceScore(query, item)
                if (score > 0.0) item to score else null
            }
            .sortedWith(
                compareByDescending<Pair<InventoryItemWithVariants, Double>> { it.second }
                    .thenBy { it.first.item.displayName.lowercase() }
            )
            .map { it.first }

        // White Cement has direct word match in title, so it MUST be #1 at the very top!
        assertEquals(2, rankedResults.size)
        assertEquals("White Cement", rankedResults[0].item.displayName)
        assertEquals("Wall Putty (JK White)", rankedResults[1].item.displayName)

        // Searching for "4 inch kabza"
        val hingeQuery = "4 inch kabza"
        val hingeResults = inventoryList
            .mapNotNull { item ->
                val score = SmartItemMatcher.calculateSearchRelevanceScore(hingeQuery, item)
                if (score > 0.0) item to score else null
            }
            .sortedWith(
                compareByDescending<Pair<InventoryItemWithVariants, Double>> { it.second }
                    .thenBy { it.first.item.displayName.lowercase() }
            )
            .map { it.first }

        assertEquals(1, hingeResults.size)
        assertEquals("Butt Hinge (Kabza)", hingeResults[0].item.displayName)
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

    @Test
    fun costCodeEngine_supportsWord10LetterAndPinHashingModes() {
        assertTrue(CostCipherEngine.isValidCipherWord10("BLACKHORSE"))
        assertFalse(CostCipherEngine.isValidCipherWord10("AABBCCDDEE"))
        assertFalse(CostCipherEngine.isValidCipherWord10("SHORT"))

        // ₹185 -> 18500L paise; BLACKHORSE => B=1, L=2, A=3, C=4, K=5, H=6, O=7, R=8, S=9, E=0
        val encoded = CostCipherEngine.encodePaiseToWordCode(18500L, "BLACKHORSE")
        assertEquals("[B R K]", encoded)
        assertEquals(18500L, CostCipherEngine.decodeWordCodeToPaise(encoded, "BLACKHORSE"))

        val pinHash = CostCipherEngine.hashPin("4829")
        assertTrue(pinHash.isNotEmpty())
        assertTrue(CostCipherEngine.verifyPin("4829", pinHash))
        assertFalse(CostCipherEngine.verifyPin("0000", pinHash))
        assertTrue(CostCipherEngine.verifyPin("anything", ""))
    }

    @Test
    fun tradeTaxonomyRegistry_supportsAll5VerticalsAndBarcodeSearchRanking() {
        assertEquals(5, ShopVertical.entries.size)
        for (vertical in ShopVertical.entries) {
            val pack = TradeTaxonomyRegistry.getPack(vertical)
            assertEquals(vertical, pack.vertical)
            assertTrue(pack.defaultCategories.isNotEmpty())
            assertTrue(pack.defaultUnits.isNotEmpty())
        }

        val scannedProduct = InventoryItemWithVariants(
            item = InventoryItemEntity(
                id = "kir-1",
                displayName = "Sugar (Cheeni)",
                sortKey = "SUGAR (CHEENI)",
                category = "Grains & Staples"
            ),
            allVariants = listOf(
                ItemVariantEntity(
                    id = "kir-v1",
                    itemId = "kir-1",
                    skuCode = "SUG-1KG",
                    barcode = "8901058001122",
                    sizeLabel = "1 kg",
                    materialLabel = "Loose",
                    colorFinishLabel = "White",
                    unit = "kg",
                    purchasePricePaise = 4000L,
                    sellingPricePaise = 4600L,
                    stockQuantity = 25.0
                )
            )
        )
        val otherProduct = InventoryItemWithVariants(
            item = InventoryItemEntity(
                id = "kir-2",
                displayName = "Parle-G Gold",
                sortKey = "PARLE-G GOLD",
                category = "Snacks & Biscuits"
            ),
            allVariants = listOf(
                ItemVariantEntity(
                    id = "kir-v2",
                    itemId = "kir-2",
                    skuCode = "PG-100",
                    barcode = "8901719125410",
                    sizeLabel = "100 g",
                    unit = "pkt",
                    purchasePricePaise = 850L,
                    sellingPricePaise = 1000L,
                    stockQuantity = 40.0
                )
            )
        )

        val exactBarcodeScore = SmartItemMatcher.calculateSearchRelevanceScore(
            query = "8901058001122",
            itemWithVariants = scannedProduct,
            vertical = ShopVertical.KIRANA
        )
        val nonMatchingBarcodeScore = SmartItemMatcher.calculateSearchRelevanceScore(
            query = "8901058001122",
            itemWithVariants = otherProduct,
            vertical = ShopVertical.KIRANA
        )
        assertEquals(12000.0, exactBarcodeScore, 0.01)
        assertEquals(0.0, nonMatchingBarcodeScore, 0.01)
        assertTrue(SmartItemMatcher.matchesSearchQuery("cheeni", scannedProduct, ShopVertical.KIRANA))
    }

    @Test
    fun currencyFormatter_milliUnitsAndGstBreakdownCalculateAccurately() {
        assertEquals(1500L, IndianCurrencyFormatter.doubleToMilliUnits(1.5))
        assertEquals(1.5, IndianCurrencyFormatter.milliUnitsToDouble(1500L), 0.0001)
        assertEquals("1.5", IndianCurrencyFormatter.formatMilliUnits(1500L))
        assertEquals("4", IndianCurrencyFormatter.formatMilliUnits(4000L))

        val intra = IndianCurrencyFormatter.computeGstBreakdown(
            taxablePaise = 100000L,
            gstRateBps = 1800,
            isInterState = false
        )
        assertEquals(9000L, intra.cgstPaise)
        assertEquals(9000L, intra.sgstPaise)
        assertEquals(0L, intra.igstPaise)
        assertEquals(18000L, intra.totalTaxPaise)
        assertEquals(118000L, intra.grandTotalPaise)

        val inter = IndianCurrencyFormatter.computeGstBreakdown(
            taxablePaise = 100000L,
            gstRateBps = 1800,
            isInterState = true
        )
        assertEquals(0L, inter.cgstPaise)
        assertEquals(0L, inter.sgstPaise)
        assertEquals(18000L, inter.igstPaise)
        assertEquals(18000L, inter.totalTaxPaise)
        assertEquals(118000L, inter.grandTotalPaise)
    }

    @Test
    fun smartReceiptId_supportsMultiDevicePrefixAndLegacyFormat() {
        assertEquals("202611160007", LpsRepository.formatSmartReceiptId(2026, 11, 16, 7, ""))
        assertEquals("D1-20261116-0007", LpsRepository.formatSmartReceiptId(2026, 11, 16, 7, "d1"))
        assertEquals("C2-20261116-0123", LpsRepository.formatSmartReceiptId(2026, 11, 16, 123, " C2 "))
    }

    @Test
    fun thermalPrinterManager_buildsValid58mmAnd80mmEscPosBytes() {
        val receipt = SaleReceiptData(
            receiptNumber = "D1-20261116-0001",
            timestampMillis = 1794816000000L,
            buyerName = "Suresh Verma",
            buyerPhone = "9876543210",
            sellerName = "Sharma Kirana",
            sellerWhatsAppPhone = "9988776655",
            isEstimate = false,
            items = listOf(
                ReceiptLineItem(
                    itemName = "Tata Salt",
                    variantSummary = "1 kg • Tata",
                    quantity = 3.0,
                    unit = "pkt",
                    unitSellingPricePaise = 5000L,
                    lineTotalPaise = 15000L
                )
            ),
            grandTotalPaise = 15000L,
            paymentMode = "CREDIT",
            subtotalPaise = 15000L,
            discountPaise = 0L,
            taxPaise = 0L,
            amountPaidPaise = 10000L,
            balanceDuePaise = 5000L,
            upiId = "sharma@okicici",
            gstin = "27AABCU9603R1ZM"
        )

        val bytes58 = ThermalPrinterManager.buildEscPosBytes(receipt, paperWidthMm = 58)
        val bytes80 = ThermalPrinterManager.buildEscPosBytes(receipt, paperWidthMm = 80)

        assertTrue(bytes58.size > 100)
        assertTrue(bytes80.size > 100)
        assertEquals(0x1B.toByte(), bytes58[0])
        assertEquals(0x40.toByte(), bytes58[1])
        assertEquals(0x1B.toByte(), bytes80[0])
        assertEquals(0x40.toByte(), bytes80[1])

        val text58 = String(bytes58, Charsets.US_ASCII)
        assertTrue(text58.contains("SHARMA KIRANA"))
        assertTrue(text58.contains("#D1-20261116-0001"))
        assertTrue(text58.contains("BALANCE DUE (UDHAR)"))
        assertTrue(text58.contains("Thank you! Visit again."))
    }

    @Test
    fun orderScannerManager_parsesOrderListLinesAndMatchesInventoryIntoCart() = runBlocking {
        val whiteCement = InventoryItemWithVariants(
            item = InventoryItemEntity(
                id = "item-wc",
                displayName = "White Cement",
                sortKey = "WHITE CEMENT",
                category = "Paints & Adhesives"
            ),
            allVariants = listOf(
                ItemVariantEntity(
                    id = "var-wc-1kg",
                    itemId = "item-wc",
                    sizeLabel = "1 kg",
                    materialLabel = "",
                    colorFinishLabel = "White",
                    unit = "pkt",
                    purchasePricePaise = 2500L,
                    sellingPricePaise = 4000L,
                    stockQuantity = 30.0
                )
            )
        )
        val buttHinge = InventoryItemWithVariants(
            item = InventoryItemEntity(
                id = "item-bh",
                displayName = "Butt Hinge (Kabza)",
                sortKey = "BUTT HINGE (KABZA)",
                category = "Door & Window Fittings"
            ),
            allVariants = listOf(
                ItemVariantEntity(
                    id = "var-bh-3in",
                    itemId = "item-bh",
                    sizeLabel = "3 inch",
                    materialLabel = "Iron",
                    colorFinishLabel = "Black",
                    unit = "pcs",
                    purchasePricePaise = 1800L,
                    sellingPricePaise = 3000L,
                    stockQuantity = 50.0
                ),
                ItemVariantEntity(
                    id = "var-bh-4in-ss",
                    itemId = "item-bh",
                    sizeLabel = "4 inch",
                    materialLabel = "SS 304",
                    colorFinishLabel = "Satin",
                    unit = "pcs",
                    purchasePricePaise = 4200L,
                    sellingPricePaise = 7000L,
                    stockQuantity = 40.0
                )
            )
        )
        val fevicol = InventoryItemWithVariants(
            item = InventoryItemEntity(
                id = "item-fev",
                displayName = "Wood Adhesive (Fevicol SH)",
                sortKey = "WOOD ADHESIVE (FEVICOL SH)",
                category = "Paints & Adhesives"
            ),
            allVariants = listOf(
                ItemVariantEntity(
                    id = "var-fev-1kg",
                    itemId = "item-fev",
                    sizeLabel = "1 kg",
                    materialLabel = "",
                    colorFinishLabel = "White",
                    unit = "box",
                    purchasePricePaise = 18000L,
                    sellingPricePaise = 24000L,
                    stockQuantity = 12.0
                )
            )
        )

        val scannedOrderText = """
            ESTIMATE / ORDER LIST
            1. White Cement 1 kg - 5 pkt
            2. 4 inch SS Kabza x 12
            3. Fevicol SH 2
            Grand Total Rs. 1520
        """.trimIndent()

        val result = OnDeviceOrderScannerManager.parseOrderListToCart(
            rawText = scannedOrderText,
            inventory = listOf(whiteCement, buttHinge, fevicol),
            context = null,
            vertical = ShopVertical.HARDWARE
        )

        assertFalse(result.usedOnDeviceLlm)
        assertTrue(result.unmatchedLines.isEmpty())
        assertEquals(3, result.matchedCartLines.size)

        val line1 = result.matchedCartLines.first { it.variantId == "var-wc-1kg" }
        assertEquals(5.0, line1.quantity, 0.01)

        val line2 = result.matchedCartLines.first { it.variantId == "var-bh-4in-ss" }
        assertEquals(12.0, line2.quantity, 0.01)

        val line3 = result.matchedCartLines.first { it.variantId == "var-fev-1kg" }
        assertEquals(2.0, line3.quantity, 0.01)
    }

    @Test
    fun itemDraftManager_serializesAndRestoresMultiVariantDraftCorrectly() {
        val original = SavedItemDraft(
            itemName = "Asian \"Apex\" Primer",
            category = "Paints & Primers",
            activeDraftIndex = 1,
            updatedAt = 1794816000000L,
            variants = listOf(
                VariantDraft(
                    id = "v-1",
                    sizeLabel = "1 Ltr",
                    materialLabel = "Water Base",
                    colorFinishLabel = "White",
                    barcode = "8901234000111",
                    unit = "ltr",
                    purchasePriceInput = "185",
                    sellingPriceInput = "240",
                    stockQtyInput = "12",
                    lowStockInput = "4"
                ),
                VariantDraft(
                    id = "v-2",
                    sizeLabel = "4 Ltr",
                    materialLabel = "Water Base",
                    colorFinishLabel = "White",
                    barcode = "8901234000444",
                    unit = "ltr",
                    purchasePriceInput = "680",
                    sellingPriceInput = "890",
                    stockQtyInput = "6",
                    lowStockInput = "2"
                )
            )
        )

        val json = ItemDraftManager.serializeDraftToJson(original)
        val restored = ItemDraftManager.deserializeDraftFromJson(json)
        requireNotNull(restored)

        assertTrue(restored.hasContent)
        assertEquals("Asian \"Apex\" Primer", restored.itemName)
        assertEquals("Paints & Primers", restored.category)
        assertEquals(1, restored.activeDraftIndex)
        assertEquals(2, restored.variants.size)
        assertEquals("4 Ltr", restored.variants[1].sizeLabel)
        assertEquals("890", restored.variants[1].sellingPriceInput)
        assertEquals("8901234000111", restored.variants[0].barcode)
    }

    @Test
    fun appErrorManager_capturesAndFormatsExceptionsIntoFloatingMessages() {
        AppErrorManager.dismiss()
        assertEquals(null, AppErrorManager.activeMessage.value)

        AppErrorManager.reportError(
            title = "Checkout Failed",
            throwable = IllegalStateException("Insufficient disk space")
        )
        val errMsg = AppErrorManager.activeMessage.value
        requireNotNull(errMsg)
        assertEquals(MessageSeverity.ERROR, errMsg.severity)
        assertEquals("Checkout Failed", errMsg.title)
        assertEquals("Insufficient disk space", errMsg.detail)

        AppErrorManager.reportWarning("Draft Saved", "Your item draft was saved.")
        val warnMsg = AppErrorManager.activeMessage.value
        requireNotNull(warnMsg)
        assertEquals(MessageSeverity.WARNING, warnMsg.severity)

        AppErrorManager.dismiss(warnMsg.id)
        assertEquals(null, AppErrorManager.activeMessage.value)
    }
}
