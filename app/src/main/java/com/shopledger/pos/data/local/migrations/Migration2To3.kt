package com.shopledger.pos.data.local.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.shopledger.pos.data.command.FaultInjector
import com.shopledger.pos.data.local.DatabaseSeed
import com.shopledger.pos.data.local.IntegrityChecker
import com.shopledger.pos.data.local.OutboxRebuilder
import com.shopledger.pos.data.local.entity.DEFAULT_SHOP_ID
import com.shopledger.pos.data.local.entity.SALE_RECORDS_VIEW_SQL
import java.util.Locale
import java.util.UUID

/** Values the migration needs from app settings (SharedPreferences are not reachable from SQL). */
data class MigrationContext(
    val activeShopId: String = DEFAULT_SHOP_ID,
    val legacyDeviceCode: String = ""
)

/**
 * v2 → v3: ledgers become the source of truth, invoices become immutable, sale_records becomes a view.
 * Runs inside Room's migration transaction: any exception rolls everything back and leaves v2 intact.
 */
class Migration2To3(
    private val context: MigrationContext = MigrationContext(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val newDeviceId: () -> String = { UUID.randomUUID().toString() },
    private val faults: FaultInjector = FaultInjector.NONE
) : Migration(2, 3) {

    override fun migrate(db: SupportSQLiteDatabase) {
        val now = clock()
        val deviceId = newDeviceId()
        val deviceCode = context.legacyDeviceCode.trim().uppercase(Locale.ROOT).take(4)
            .ifEmpty { DatabaseSeed.LOCAL_DEVICE_CODE }
        val itemsBefore = count(db, "inventory_items")
        val variantsBefore = count(db, "item_variants")
        val partiesBefore = count(db, "parties")
        val saleLinesBefore = count(db, "sale_records")
        val ledgerBefore = count(db, "party_ledger_entries")
        val headersBefore = count(db, "invoice_headers")
        val linesBefore = count(db, "invoice_lines")
        val movementsBefore = count(db, "stock_movements")

        shopsAndDevices(db, now, deviceId, deviceCode)
        rebuildInventoryItems(db)
        rebuildItemVariants(db)
        rebuildStockMovements(db, now, deviceId, deviceCode)
        rebuildParties(db)
        rebuildLedger(db)
        // The migration and IntegrityChecker look rows up by skuId / partyId alone, which the (shopId, ...)
        // composite indexes cannot serve. Without these, large shops would scan quadratically (ANR risk).
        createTempIndexes(db)
        insertLedgerOpening(db, now, deviceId)
        rebuildInvoices(db)
        convertLegacySales(db)
        replaceSaleRecordsWithView(db)
        createSyncTables(db)
        recomputeProjections(db)
        repairLegacyInvoiceTotals(db)
        OutboxRebuilder.enqueueEverything(db, now)

        check(count(db, "inventory_items") == itemsBefore) { "Item count changed during upgrade" }
        check(count(db, "item_variants") == variantsBefore) { "Variant count changed during upgrade" }
        check(count(db, "parties") == partiesBefore) { "Party count changed during upgrade" }
        check(count(db, "sale_records") >= saleLinesBefore) { "Sale lines were lost during upgrade" }
        faults.at("POST_MIGRATE")
        check(count(db, "party_ledger_entries") >= ledgerBefore) { "Ledger entries were lost during upgrade" }
        check(count(db, "invoice_headers") >= headersBefore) { "Invoice headers were lost during upgrade" }
        check(count(db, "invoice_lines") >= linesBefore) { "Invoice lines were lost during upgrade" }
        check(count(db, "stock_movements_v2_archive") == movementsBefore) { "Stock movement archive is incomplete" }
        val missingSaleLines = db.query(
            "SELECT COUNT(*) FROM sale_records_v2_archive s WHERE NOT EXISTS (SELECT 1 FROM invoice_lines l WHERE l.id = s.id)"
        ).use { it.moveToFirst(); it.getLong(0) }
        check(missingSaleLines == 0L) { "$missingSaleLines v2 sale line(s) have no invoice line after upgrade" }
        val violations = IntegrityChecker.findViolations(db, includePurchases = false)
        check(violations.isEmpty()) { "v3 upgrade check failed: ${violations.take(5)}" }
        dropTempIndexes(db)
    }

    private fun createTempIndexes(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS `$TMP_STOCK_INDEX` ON `stock_movements` (`skuId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `$TMP_LEDGER_INDEX` ON `party_ledger_entries` (`partyId`)")
    }

    /** Room validates indices, so the temporary ones must not survive the migration. */
    private fun dropTempIndexes(db: SupportSQLiteDatabase) {
        db.execSQL("DROP INDEX IF EXISTS `$TMP_STOCK_INDEX`")
        db.execSQL("DROP INDEX IF EXISTS `$TMP_LEDGER_INDEX`")
    }

    private fun shopsAndDevices(db: SupportSQLiteDatabase, now: Long, deviceId: String, deviceCode: String) {
        db.execSQL("ALTER TABLE `shops` ADD COLUMN `maxStaffDiscountBps` INTEGER NOT NULL DEFAULT 1000")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `devices` (`deviceId` TEXT NOT NULL, `shopId` TEXT NOT NULL, `deviceCode` TEXT NOT NULL, " +
                "`label` TEXT NOT NULL, `isLocal` INTEGER NOT NULL, `lastInvoiceSeq` INTEGER NOT NULL, `lastSeqDate` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`deviceId`))"
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_devices_shopId_deviceCode` ON `devices` (`shopId`, `deviceCode`)")
        DatabaseSeed.seedFreshDatabase(db, now, deviceId, context.activeShopId, deviceCode)
    }

    private fun rebuildInventoryItems(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `inventory_items_v3` (`id` TEXT NOT NULL, `shopId` TEXT NOT NULL, `displayName` TEXT NOT NULL, " +
                "`sortKey` TEXT NOT NULL, `category` TEXT NOT NULL, `defaultHsnCode` TEXT NOT NULL, `defaultGstRateBps` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `isDeleted` INTEGER NOT NULL, `isDemo` INTEGER NOT NULL, " +
                "`createdByUid` TEXT NOT NULL, `deviceId` TEXT NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "INSERT INTO inventory_items_v3 (id, shopId, displayName, sortKey, category, defaultHsnCode, defaultGstRateBps, createdAt, " +
                "updatedAt, isDeleted, isDemo, createdByUid, deviceId) SELECT id, shopId, displayName, sortKey, category, defaultHsnCode, " +
                "defaultGstRateBps, createdAt, updatedAt, isDeleted, CASE WHEN syncStatus = 'DEMO_SEED' THEN 1 ELSE 0 END, '', '' FROM inventory_items"
        )
        db.execSQL("DROP TABLE inventory_items")
        db.execSQL("ALTER TABLE inventory_items_v3 RENAME TO inventory_items")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_inventory_items_sortKey` ON `inventory_items` (`sortKey`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_inventory_items_shopId_sortKey` ON `inventory_items` (`shopId`, `sortKey`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_inventory_items_isDeleted` ON `inventory_items` (`isDeleted`)")
    }

    private fun rebuildItemVariants(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `item_variants_v3` (`id` TEXT NOT NULL, `shopId` TEXT NOT NULL, `itemId` TEXT NOT NULL, " +
                "`skuCode` TEXT NOT NULL, `barcode` TEXT NOT NULL, `sizeLabel` TEXT NOT NULL, `materialLabel` TEXT NOT NULL, " +
                "`colorFinishLabel` TEXT NOT NULL, `unit` TEXT NOT NULL, `mrpPaise` INTEGER NOT NULL, `purchasePricePaise` INTEGER NOT NULL, " +
                "`sellingPricePaise` INTEGER NOT NULL, `gstRateBps` INTEGER NOT NULL, `stockMilliUnits` INTEGER NOT NULL, " +
                "`lowStockMilliUnits` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `isDeleted` INTEGER NOT NULL, " +
                "`isDemo` INTEGER NOT NULL, `createdByUid` TEXT NOT NULL, `deviceId` TEXT NOT NULL, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`itemId`) REFERENCES `inventory_items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        // v2 screens showed the Double columns, so they are the truth when the two disagree.
        db.execSQL(
            "INSERT INTO item_variants_v3 (id, shopId, itemId, skuCode, barcode, sizeLabel, materialLabel, colorFinishLabel, unit, mrpPaise, " +
                "purchasePricePaise, sellingPricePaise, gstRateBps, stockMilliUnits, lowStockMilliUnits, createdAt, updatedAt, isDeleted, " +
                "isDemo, createdByUid, deviceId) SELECT id, shopId, itemId, skuCode, barcode, sizeLabel, materialLabel, colorFinishLabel, unit, " +
                "mrpPaise, purchasePricePaise, sellingPricePaise, gstRateBps, CAST(ROUND(stockQuantity * 1000.0) AS INTEGER), " +
                "CAST(ROUND(lowStockThreshold * 1000.0) AS INTEGER), updatedAt, updatedAt, isDeleted, " +
                "CASE WHEN syncStatus = 'DEMO_SEED' THEN 1 ELSE 0 END, '', '' FROM item_variants"
        )
        db.execSQL("DROP TABLE item_variants")
        db.execSQL("ALTER TABLE item_variants_v3 RENAME TO item_variants")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_item_variants_itemId` ON `item_variants` (`itemId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_item_variants_shopId_barcode` ON `item_variants` (`shopId`, `barcode`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_item_variants_isDeleted` ON `item_variants` (`isDeleted`)")
    }

    private fun rebuildStockMovements(db: SupportSQLiteDatabase, now: Long, deviceId: String, deviceCode: String) {
        // Keep v2's log for audit; v3 starts from one OPENING movement per variant so I1 holds from day one.
        db.execSQL("DROP INDEX IF EXISTS `index_stock_movements_shopId_skuId`")
        db.execSQL("DROP INDEX IF EXISTS `index_stock_movements_syncStatus`")
        db.execSQL("ALTER TABLE stock_movements RENAME TO stock_movements_v2_archive")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `stock_movements` (`movementId` TEXT NOT NULL, `shopId` TEXT NOT NULL, `skuId` TEXT NOT NULL, " +
                "`deviceId` TEXT NOT NULL, `deviceShortCode` TEXT NOT NULL, `deltaMilliUnits` INTEGER NOT NULL, `movementType` TEXT NOT NULL, " +
                "`refDocId` TEXT NOT NULL, `note` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `createdByUid` TEXT NOT NULL, PRIMARY KEY(`movementId`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_stock_movements_shopId_skuId` ON `stock_movements` (`shopId`, `skuId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_stock_movements_refDocId` ON `stock_movements` (`refDocId`)")
        db.execSQL(
            "INSERT INTO stock_movements (movementId, shopId, skuId, deviceId, deviceShortCode, deltaMilliUnits, movementType, refDocId, note, createdAt, createdByUid) " +
                "SELECT 'v3open_' || id, shopId, id, ?, ?, stockMilliUnits, 'OPENING', itemId, 'Stock at upgrade', ?, '' " +
                "FROM item_variants WHERE stockMilliUnits != 0",
            arrayOf<Any>(deviceId, deviceCode, now)
        )
    }

    private fun rebuildParties(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `parties_v3` (`id` TEXT NOT NULL, `shopId` TEXT NOT NULL, `partyType` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                "`phone` TEXT NOT NULL, `gstin` TEXT NOT NULL, `billingAddress` TEXT NOT NULL, `currentBalancePaise` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `isDeleted` INTEGER NOT NULL, `createdByUid` TEXT NOT NULL, " +
                "`deviceId` TEXT NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "INSERT INTO parties_v3 (id, shopId, partyType, name, phone, gstin, billingAddress, currentBalancePaise, createdAt, updatedAt, isDeleted, createdByUid, deviceId) " +
                "SELECT id, shopId, partyType, name, phone, gstin, billingAddress, currentBalancePaise, updatedAt, updatedAt, isDeleted, '', '' FROM parties"
        )
        db.execSQL("DROP TABLE parties")
        db.execSQL("ALTER TABLE parties_v3 RENAME TO parties")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_parties_shopId_partyType` ON `parties` (`shopId`, `partyType`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_parties_shopId_phone` ON `parties` (`shopId`, `phone`)")
    }

    private fun rebuildLedger(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `party_ledger_entries_v3` (`entryId` TEXT NOT NULL, `shopId` TEXT NOT NULL, `partyId` TEXT NOT NULL, " +
                "`refDocId` TEXT NOT NULL, `entryType` TEXT NOT NULL, `amountDeltaPaise` INTEGER NOT NULL, `paymentMode` TEXT NOT NULL, " +
                "`note` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `createdByUid` TEXT NOT NULL, `deviceId` TEXT NOT NULL, PRIMARY KEY(`entryId`))"
        )
        db.execSQL(
            "INSERT INTO party_ledger_entries_v3 (entryId, shopId, partyId, refDocId, entryType, amountDeltaPaise, paymentMode, note, createdAt, createdByUid, deviceId) " +
                "SELECT entryId, shopId, partyId, refDocId, CASE WHEN entryType IN ('JAMA_CASH', 'JAMA_UPI') THEN 'PAYMENT_IN' ELSE entryType END, " +
                "amountDeltaPaise, paymentMode, note, createdAt, '', '' FROM party_ledger_entries"
        )
        db.execSQL("DROP TABLE party_ledger_entries")
        db.execSQL("ALTER TABLE party_ledger_entries_v3 RENAME TO party_ledger_entries")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_party_ledger_entries_shopId_partyId` ON `party_ledger_entries` (`shopId`, `partyId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_party_ledger_entries_refDocId` ON `party_ledger_entries` (`refDocId`)")
    }

    private fun insertLedgerOpening(db: SupportSQLiteDatabase, now: Long, deviceId: String) {
        // v2 clamped payments at zero, so entries may not add up to the balance the shopkeeper saw.
        // An OPENING entry for the difference makes I2 hold without changing any balance.
        db.execSQL(
            "INSERT INTO party_ledger_entries (entryId, shopId, partyId, refDocId, entryType, amountDeltaPaise, paymentMode, note, createdAt, createdByUid, deviceId) " +
                "SELECT 'v3open_' || p.id, p.shopId, p.id, '', 'OPENING', " +
                "p.currentBalancePaise - COALESCE((SELECT SUM(e.amountDeltaPaise) FROM party_ledger_entries e WHERE e.partyId = p.id), 0), " +
                "'CASH', 'Balance at upgrade', ?, '', ? FROM parties p " +
                "WHERE p.currentBalancePaise != COALESCE((SELECT SUM(e.amountDeltaPaise) FROM party_ledger_entries e WHERE e.partyId = p.id), 0)",
            arrayOf<Any>(now, deviceId)
        )
    }

    private fun rebuildInvoices(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `invoice_headers_v3` (`id` TEXT NOT NULL, `shopId` TEXT NOT NULL, `deviceId` TEXT NOT NULL, " +
                "`deviceShortCode` TEXT NOT NULL, `invoiceNumber` TEXT NOT NULL, `docType` TEXT NOT NULL, `originalInvoiceId` TEXT, " +
                "`partyId` TEXT, `buyerName` TEXT NOT NULL, `buyerPhone` TEXT NOT NULL, `paymentMode` TEXT NOT NULL, " +
                "`subtotalPaise` INTEGER NOT NULL, `discountPaise` INTEGER NOT NULL, `cgstPaise` INTEGER NOT NULL, `sgstPaise` INTEGER NOT NULL, " +
                "`igstPaise` INTEGER NOT NULL, `grandTotalPaise` INTEGER NOT NULL, `amountPaidPaise` INTEGER NOT NULL, " +
                "`balanceDuePaise` INTEGER NOT NULL, `issuedAt` INTEGER NOT NULL, `yearInt` INTEGER NOT NULL, `monthInt` INTEGER NOT NULL, " +
                "`dayInt` INTEGER NOT NULL, `createdByUid` TEXT NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "INSERT INTO invoice_headers_v3 (id, shopId, deviceId, deviceShortCode, invoiceNumber, docType, originalInvoiceId, partyId, buyerName, " +
                "buyerPhone, paymentMode, subtotalPaise, discountPaise, cgstPaise, sgstPaise, igstPaise, grandTotalPaise, amountPaidPaise, " +
                "balanceDuePaise, issuedAt, yearInt, monthInt, dayInt, createdByUid) SELECT id, shopId, '', deviceShortCode, invoiceNumber, " +
                "'SALE', NULL, partyId, buyerName, buyerPhone, paymentMode, subtotalPaise, discountPaise, cgstPaise, sgstPaise, igstPaise, " +
                "grandTotalPaise, amountPaidPaise, balanceDuePaise, issuedAt, yearInt, monthInt, dayInt, '' FROM invoice_headers"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `invoice_lines_v3` (`id` TEXT NOT NULL, `shopId` TEXT NOT NULL, `invoiceId` TEXT NOT NULL, " +
                "`originalLineId` TEXT, `productId` TEXT NOT NULL, `skuId` TEXT NOT NULL, `itemNameSnapshot` TEXT NOT NULL, " +
                "`variantSummarySnapshot` TEXT NOT NULL, `hsnSnapshot` TEXT NOT NULL, `qtyMilliUnits` INTEGER NOT NULL, `unitSnapshot` TEXT NOT NULL, " +
                "`unitCostPaise` INTEGER NOT NULL, `unitSellPaise` INTEGER NOT NULL, `gstRateBps` INTEGER NOT NULL, `lineTaxPaise` INTEGER NOT NULL, " +
                "`lineTotalPaise` INTEGER NOT NULL, `lineProfitPaise` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`invoiceId`) REFERENCES `invoice_headers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL(
            "INSERT INTO invoice_lines_v3 (id, shopId, invoiceId, originalLineId, productId, skuId, itemNameSnapshot, variantSummarySnapshot, " +
                "hsnSnapshot, qtyMilliUnits, unitSnapshot, unitCostPaise, unitSellPaise, gstRateBps, lineTaxPaise, lineTotalPaise, lineProfitPaise) " +
                "SELECT id, shopId, invoiceId, NULL, productId, skuId, itemNameSnapshot, variantSummarySnapshot, hsnSnapshot, qtyMilliUnits, " +
                "unitSnapshot, unitCostPaise, unitSellPaise, gstRateBps, lineTaxPaise, lineTotalPaise, lineProfitPaise FROM invoice_lines"
        )
        db.execSQL("DROP TABLE invoice_lines")
        db.execSQL("DROP TABLE invoice_headers")
        db.execSQL("ALTER TABLE invoice_headers_v3 RENAME TO invoice_headers")
        db.execSQL("ALTER TABLE invoice_lines_v3 RENAME TO invoice_lines")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_invoice_headers_shopId_invoiceNumber` ON `invoice_headers` (`shopId`, `invoiceNumber`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_invoice_headers_shopId_issuedAt` ON `invoice_headers` (`shopId`, `issuedAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_invoice_headers_shopId_docType` ON `invoice_headers` (`shopId`, `docType`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_invoice_headers_originalInvoiceId` ON `invoice_headers` (`originalInvoiceId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_invoice_lines_invoiceId` ON `invoice_lines` (`invoiceId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_invoice_lines_shopId_invoiceId` ON `invoice_lines` (`shopId`, `invoiceId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_invoice_lines_originalLineId` ON `invoice_lines` (`originalLineId`)")
    }

    /** v2 sync pulls and JSON imports wrote sale_records only; give those lines an invoice. Returns become credit notes. */
    private fun convertLegacySales(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO invoice_headers (id, shopId, deviceId, deviceShortCode, invoiceNumber, docType, originalInvoiceId, partyId, buyerName, " +
                "buyerPhone, paymentMode, subtotalPaise, discountPaise, cgstPaise, sgstPaise, igstPaise, grandTotalPaise, amountPaidPaise, " +
                "balanceDuePaise, issuedAt, yearInt, monthInt, dayInt, createdByUid) " +
                "SELECT 'v3mig_' || s.shopId || '_' || s.receiptNumber, s.shopId, '', '', " +
                "CASE WHEN EXISTS (SELECT 1 FROM invoice_headers x WHERE x.shopId = s.shopId AND x.invoiceNumber = s.receiptNumber) " +
                "THEN s.receiptNumber || '-M' ELSE s.receiptNumber END, 'SALE', NULL, MAX(s.partyId), MAX(s.buyerName), MAX(s.buyerPhone), " +
                "MAX(s.paymentMode), SUM(s.totalRevenuePaise), MAX(s.discountPaise), MAX(s.gstPaise) / 2, MAX(s.gstPaise) - MAX(s.gstPaise) / 2, 0, " +
                "SUM(s.totalRevenuePaise) - MAX(s.discountPaise) + MAX(s.gstPaise), " +
                "(SUM(s.totalRevenuePaise) - MAX(s.discountPaise) + MAX(s.gstPaise)) - " +
                "MIN(MAX(MAX(s.balanceDuePaise), 0), SUM(s.totalRevenuePaise) - MAX(s.discountPaise) + MAX(s.gstPaise)), " +
                "MIN(MAX(MAX(s.balanceDuePaise), 0), SUM(s.totalRevenuePaise) - MAX(s.discountPaise) + MAX(s.gstPaise)), " +
                "MAX(s.soldAt), MAX(s.yearInt), MAX(s.monthInt), MAX(s.dayInt), '' FROM sale_records s " +
                "WHERE NOT EXISTS (SELECT 1 FROM invoice_lines l WHERE l.id = s.id) GROUP BY s.shopId, s.receiptNumber"
        )
        db.execSQL(
            "INSERT INTO invoice_lines (id, shopId, invoiceId, originalLineId, productId, skuId, itemNameSnapshot, variantSummarySnapshot, " +
                "hsnSnapshot, qtyMilliUnits, unitSnapshot, unitCostPaise, unitSellPaise, gstRateBps, lineTaxPaise, lineTotalPaise, lineProfitPaise) " +
                "SELECT s.id, s.shopId, 'v3mig_' || s.shopId || '_' || s.receiptNumber, NULL, s.itemId, s.variantId, s.itemNameSnapshot, " +
                "s.variantSummarySnapshot, '', CAST(ROUND(s.quantitySold * 1000.0) AS INTEGER), s.unitSnapshot, s.unitPurchasePricePaise, " +
                "s.unitSellingPricePaise, 0, 0, s.totalRevenuePaise, s.totalProfitPaise FROM sale_records s " +
                "WHERE NOT EXISTS (SELECT 1 FROM invoice_lines l WHERE l.id = s.id)"
        )
        // v2 already restored stock for returns (OPENING carries it) and never touched khata for them,
        // so historical credit notes are cash refunds at line value with no movements or ledger rows.
        db.execSQL(
            "INSERT INTO invoice_headers (id, shopId, deviceId, deviceShortCode, invoiceNumber, docType, originalInvoiceId, partyId, buyerName, " +
                "buyerPhone, paymentMode, subtotalPaise, discountPaise, cgstPaise, sgstPaise, igstPaise, grandTotalPaise, amountPaidPaise, " +
                "balanceDuePaise, issuedAt, yearInt, monthInt, dayInt, createdByUid) " +
                "SELECT 'v3cn_' || h.id, h.shopId, '', h.deviceShortCode, h.invoiceNumber || '-R', 'CREDIT_NOTE', h.id, h.partyId, h.buyerName, " +
                "h.buyerPhone, h.paymentMode, -SUM(l.lineTotalPaise), 0, 0, 0, 0, -SUM(l.lineTotalPaise), -SUM(l.lineTotalPaise), 0, " +
                "h.issuedAt, h.yearInt, h.monthInt, h.dayInt, '' FROM sale_records s " +
                "JOIN invoice_lines l ON l.id = s.id JOIN invoice_headers h ON h.id = l.invoiceId WHERE s.isReturned = 1 AND l.qtyMilliUnits > 0 GROUP BY h.id"
        )
        db.execSQL(
            "INSERT INTO invoice_lines (id, shopId, invoiceId, originalLineId, productId, skuId, itemNameSnapshot, variantSummarySnapshot, " +
                "hsnSnapshot, qtyMilliUnits, unitSnapshot, unitCostPaise, unitSellPaise, gstRateBps, lineTaxPaise, lineTotalPaise, lineProfitPaise) " +
                "SELECT 'v3cn_' || l.id, l.shopId, 'v3cn_' || l.invoiceId, l.id, l.productId, l.skuId, l.itemNameSnapshot, l.variantSummarySnapshot, " +
                "l.hsnSnapshot, -l.qtyMilliUnits, l.unitSnapshot, l.unitCostPaise, l.unitSellPaise, l.gstRateBps, 0, -l.lineTotalPaise, " +
                "-l.lineProfitPaise FROM sale_records s JOIN invoice_lines l ON l.id = s.id " +
                "WHERE s.isReturned = 1 AND l.qtyMilliUnits > 0"
        )
    }

    private fun replaceSaleRecordsWithView(db: SupportSQLiteDatabase) {
        // Kept (not dropped) so the original v2 sale rows stay recoverable.
        db.execSQL("ALTER TABLE `sale_records` RENAME TO `sale_records_v2_archive`")
        db.execSQL("CREATE VIEW `sale_records` AS $SALE_RECORDS_VIEW_SQL")
    }

    private fun createSyncTables(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `sync_outbox` (`seq` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `docType` TEXT NOT NULL, " +
                "`docId` TEXT NOT NULL, `op` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `attempts` INTEGER NOT NULL, `lastError` TEXT NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_outbox_docType_docId` ON `sync_outbox` (`docType`, `docId`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `applied_actions` (`actionId` TEXT NOT NULL, `kind` TEXT NOT NULL, `resultRef` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`actionId`))"
        )
    }

    private fun recomputeProjections(db: SupportSQLiteDatabase) {
        db.execSQL(
            "UPDATE item_variants SET stockMilliUnits = " +
                "(SELECT COALESCE(SUM(m.deltaMilliUnits), 0) FROM stock_movements m WHERE m.skuId = item_variants.id)"
        )
        db.execSQL(
            "UPDATE parties SET currentBalancePaise = " +
                "(SELECT COALESCE(SUM(e.amountDeltaPaise), 0) FROM party_ledger_entries e WHERE e.partyId = parties.id)"
        )
    }

    /** Odd legacy rows (partial syncs, old imports) may have header totals that don't match their lines. */
    private fun repairLegacyInvoiceTotals(db: SupportSQLiteDatabase) {
        val lineSum = "(SELECT COALESCE(SUM(l.lineTotalPaise), 0) FROM invoice_lines l WHERE l.invoiceId = invoice_headers.id)"
        var repaired = db.compileStatement("UPDATE invoice_headers SET subtotalPaise = $lineSum WHERE subtotalPaise != $lineSum").executeUpdateDelete()
        repaired += db.compileStatement(
            "UPDATE invoice_headers SET grandTotalPaise = subtotalPaise - discountPaise + cgstPaise + sgstPaise + igstPaise " +
                "WHERE grandTotalPaise != subtotalPaise - discountPaise + cgstPaise + sgstPaise + igstPaise"
        ).executeUpdateDelete()
        repaired += db.compileStatement(
            "UPDATE invoice_headers SET amountPaidPaise = grandTotalPaise - balanceDuePaise WHERE amountPaidPaise + balanceDuePaise != grandTotalPaise"
        ).executeUpdateDelete()
        if (repaired > 0) Log.w("Migration2To3", "Repaired $repaired legacy invoice total(s)")
    }

    private fun count(db: SupportSQLiteDatabase, table: String): Long =
        db.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getLong(0) }

    private companion object {
        const val TMP_STOCK_INDEX = "tmp_v3_stock_movements_skuId"
        const val TMP_LEDGER_INDEX = "tmp_v3_party_ledger_partyId"
    }
}
