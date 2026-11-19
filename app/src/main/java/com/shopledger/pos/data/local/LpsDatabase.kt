package com.shopledger.pos.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.shopledger.pos.core.sync.SyncManager
import com.shopledger.pos.data.local.dao.AppliedActionDao
import com.shopledger.pos.data.local.dao.DeviceDao
import com.shopledger.pos.data.local.dao.InventoryDao
import com.shopledger.pos.data.local.dao.SaleDao
import com.shopledger.pos.data.local.dao.SearchHistoryDao
import com.shopledger.pos.data.local.dao.ShopPartyAndLedgerDao
import com.shopledger.pos.data.local.dao.SyncOutboxDao
import com.shopledger.pos.data.local.entity.AppliedActionEntity
import com.shopledger.pos.data.local.entity.DeviceEntity
import com.shopledger.pos.data.local.entity.InventoryItemEntity
import com.shopledger.pos.data.local.entity.InvoiceHeaderEntity
import com.shopledger.pos.data.local.entity.InvoiceLineEntity
import com.shopledger.pos.data.local.entity.ItemVariantEntity
import com.shopledger.pos.data.local.entity.PartyEntity
import com.shopledger.pos.data.local.entity.PartyTransactionEntity
import com.shopledger.pos.data.local.entity.SaleRecordEntity
import com.shopledger.pos.data.local.entity.SearchHistoryEntity
import com.shopledger.pos.data.local.entity.ShopProfileEntity
import com.shopledger.pos.data.local.entity.StockMovementEntity
import com.shopledger.pos.data.local.entity.SyncOutboxEntity
import com.shopledger.pos.data.local.migrations.Migration2To3
import com.shopledger.pos.data.local.migrations.MigrationContext

typealias ShopDatabase = LpsDatabase

@Database(
    entities = [
        InventoryItemEntity::class,
        ItemVariantEntity::class,
        SearchHistoryEntity::class,
        ShopProfileEntity::class,
        PartyEntity::class,
        InvoiceHeaderEntity::class,
        InvoiceLineEntity::class,
        StockMovementEntity::class,
        PartyTransactionEntity::class,
        DeviceEntity::class,
        SyncOutboxEntity::class,
        AppliedActionEntity::class
    ],
    views = [SaleRecordEntity::class],
    version = LpsDatabase.SCHEMA_VERSION,
    exportSchema = true
)
abstract class LpsDatabase : RoomDatabase() {

    abstract fun inventoryDao(): InventoryDao
    abstract fun saleDao(): SaleDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun shopPartyAndLedgerDao(): ShopPartyAndLedgerDao
    abstract fun deviceDao(): DeviceDao
    abstract fun syncOutboxDao(): SyncOutboxDao
    abstract fun appliedActionDao(): AppliedActionDao

    companion object {
        private const val LEGACY_DB_NAME = "lps_hardware_inventory.db"
        const val DB_NAME = "shop_ledger.db"

        /** Keep in sync with the newest migration. */
        const val SCHEMA_VERSION = 3

        @Volatile
        private var INSTANCE: LpsDatabase? = null

        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Upgrade inventory_items
                db.execSQL("ALTER TABLE inventory_items ADD COLUMN shopId TEXT NOT NULL DEFAULT 'default_shop'")
                db.execSQL("ALTER TABLE inventory_items ADD COLUMN defaultHsnCode TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE inventory_items ADD COLUMN defaultGstRateBps INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_inventory_items_shopId_sortKey ON inventory_items(shopId, sortKey)")

                // 2. Upgrade item_variants
                db.execSQL("ALTER TABLE item_variants ADD COLUMN shopId TEXT NOT NULL DEFAULT 'default_shop'")
                db.execSQL("ALTER TABLE item_variants ADD COLUMN skuCode TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE item_variants ADD COLUMN barcode TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE item_variants ADD COLUMN mrpPaise INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE item_variants ADD COLUMN gstRateBps INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE item_variants ADD COLUMN stockMilliUnits INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE item_variants ADD COLUMN lowStockMilliUnits INTEGER NOT NULL DEFAULT 5000")
                db.execSQL(
                    """
                    UPDATE item_variants
                    SET stockMilliUnits = CAST(ROUND(stockQuantity * 1000.0) AS INTEGER),
                        lowStockMilliUnits = CAST(ROUND(lowStockThreshold * 1000.0) AS INTEGER)
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_item_variants_shopId_barcode ON item_variants(shopId, barcode)")

                // 3. Upgrade sale_records
                db.execSQL("ALTER TABLE sale_records ADD COLUMN shopId TEXT NOT NULL DEFAULT 'default_shop'")
                db.execSQL("ALTER TABLE sale_records ADD COLUMN paymentMode TEXT NOT NULL DEFAULT 'CASH'")
                db.execSQL("ALTER TABLE sale_records ADD COLUMN amountPaidPaise INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sale_records ADD COLUMN balanceDuePaise INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sale_records ADD COLUMN discountPaise INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sale_records ADD COLUMN gstPaise INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sale_records ADD COLUMN partyId TEXT")
                db.execSQL("UPDATE sale_records SET amountPaidPaise = totalRevenuePaise")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sale_records_shopId_soldAt ON sale_records(shopId, soldAt)")

                // 4. Rebuild search_history to scope unique normalizedQuery per shopId
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS search_history_v2 (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        shopId TEXT NOT NULL DEFAULT 'default_shop',
                        rawQuery TEXT NOT NULL,
                        normalizedQuery TEXT NOT NULL,
                        resultCount INTEGER NOT NULL,
                        searchFrequency INTEGER NOT NULL,
                        lastSearchedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO search_history_v2 (id, shopId, rawQuery, normalizedQuery, resultCount, searchFrequency, lastSearchedAt)
                    SELECT id, 'default_shop', rawQuery, normalizedQuery, resultCount, searchFrequency, lastSearchedAt
                    FROM search_history
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE search_history")
                db.execSQL("ALTER TABLE search_history_v2 RENAME TO search_history")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_search_history_shopId_normalizedQuery ON search_history(shopId, normalizedQuery)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_search_history_lastSearchedAt ON search_history(lastSearchedAt)")

                // 5. Create new multi-tenant tables
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS shops (
                        shopId TEXT PRIMARY KEY NOT NULL,
                        shopName TEXT NOT NULL,
                        verticalType TEXT NOT NULL,
                        ownerUid TEXT NOT NULL,
                        gstin TEXT NOT NULL,
                        stateCode TEXT NOT NULL,
                        gstEnabled INTEGER NOT NULL,
                        upiId TEXT NOT NULL,
                        whatsappPhone TEXT NOT NULL,
                        deviceShortCode TEXT NOT NULL,
                        invoicePrefix TEXT NOT NULL,
                        cipherMode TEXT NOT NULL,
                        cipherWord10 TEXT NOT NULL,
                        ownerPinHash TEXT NOT NULL,
                        isSetupCompleted INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS parties (
                        id TEXT PRIMARY KEY NOT NULL,
                        shopId TEXT NOT NULL,
                        partyType TEXT NOT NULL,
                        name TEXT NOT NULL,
                        phone TEXT NOT NULL,
                        gstin TEXT NOT NULL,
                        billingAddress TEXT NOT NULL,
                        openingBalancePaise INTEGER NOT NULL,
                        currentBalancePaise INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        isDeleted INTEGER NOT NULL,
                        syncStatus TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_parties_shopId_partyType ON parties(shopId, partyType)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_parties_shopId_phone ON parties(shopId, phone)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_parties_syncStatus ON parties(syncStatus)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS invoice_headers (
                        id TEXT PRIMARY KEY NOT NULL,
                        shopId TEXT NOT NULL,
                        deviceShortCode TEXT NOT NULL,
                        invoiceNumber TEXT NOT NULL,
                        docType TEXT NOT NULL,
                        partyId TEXT,
                        buyerName TEXT NOT NULL,
                        buyerPhone TEXT NOT NULL,
                        paymentMode TEXT NOT NULL,
                        subtotalPaise INTEGER NOT NULL,
                        discountPaise INTEGER NOT NULL,
                        cgstPaise INTEGER NOT NULL,
                        sgstPaise INTEGER NOT NULL,
                        igstPaise INTEGER NOT NULL,
                        grandTotalPaise INTEGER NOT NULL,
                        amountPaidPaise INTEGER NOT NULL,
                        balanceDuePaise INTEGER NOT NULL,
                        issuedAt INTEGER NOT NULL,
                        yearInt INTEGER NOT NULL,
                        monthInt INTEGER NOT NULL,
                        dayInt INTEGER NOT NULL,
                        isReturned INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        syncStatus TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_invoice_headers_shopId_invoiceNumber ON invoice_headers(shopId, invoiceNumber)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_invoice_headers_shopId_issuedAt ON invoice_headers(shopId, issuedAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_invoice_headers_shopId_docType ON invoice_headers(shopId, docType)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_invoice_headers_syncStatus ON invoice_headers(syncStatus)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS invoice_lines (
                        id TEXT PRIMARY KEY NOT NULL,
                        shopId TEXT NOT NULL,
                        invoiceId TEXT NOT NULL,
                        productId TEXT NOT NULL,
                        skuId TEXT NOT NULL,
                        itemNameSnapshot TEXT NOT NULL,
                        variantSummarySnapshot TEXT NOT NULL,
                        hsnSnapshot TEXT NOT NULL,
                        qtyMilliUnits INTEGER NOT NULL,
                        unitSnapshot TEXT NOT NULL,
                        unitCostPaise INTEGER NOT NULL,
                        unitSellPaise INTEGER NOT NULL,
                        gstRateBps INTEGER NOT NULL,
                        lineTaxPaise INTEGER NOT NULL,
                        lineTotalPaise INTEGER NOT NULL,
                        lineProfitPaise INTEGER NOT NULL,
                        isReturned INTEGER NOT NULL,
                        FOREIGN KEY(invoiceId) REFERENCES invoice_headers(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_invoice_lines_invoiceId ON invoice_lines(invoiceId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_invoice_lines_shopId_invoiceId ON invoice_lines(shopId, invoiceId)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS stock_movements (
                        movementId TEXT PRIMARY KEY NOT NULL,
                        shopId TEXT NOT NULL,
                        skuId TEXT NOT NULL,
                        deviceShortCode TEXT NOT NULL,
                        deltaMilliUnits INTEGER NOT NULL,
                        movementType TEXT NOT NULL,
                        refDocId TEXT NOT NULL,
                        note TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        syncStatus TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_stock_movements_shopId_skuId ON stock_movements(shopId, skuId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_stock_movements_syncStatus ON stock_movements(syncStatus)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS party_ledger_entries (
                        entryId TEXT PRIMARY KEY NOT NULL,
                        shopId TEXT NOT NULL,
                        partyId TEXT NOT NULL,
                        refDocId TEXT NOT NULL,
                        entryType TEXT NOT NULL,
                        amountDeltaPaise INTEGER NOT NULL,
                        paymentMode TEXT NOT NULL,
                        note TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        syncStatus TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_party_ledger_entries_shopId_partyId ON party_ledger_entries(shopId, partyId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_party_ledger_entries_syncStatus ON party_ledger_entries(syncStatus)")

                // 6. Seed default shop profile & backfill additive opening stock movements
                val now = System.currentTimeMillis()
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO shops (
                        shopId, shopName, verticalType, ownerUid, gstin, stateCode, gstEnabled,
                        upiId, whatsappPhone, deviceShortCode, invoicePrefix, cipherMode, cipherWord10,
                        ownerPinHash, isSetupCompleted, updatedAt
                    ) VALUES (
                        'default_shop', 'ShopLedger POS', 'HARDWARE', '', '', '09', 0,
                        '', '', '', 'INV', 'CUSTOM_CODE', 'BLACKHORSE',
                        '', 1, $now
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    INSERT OR IGNORE INTO stock_movements (
                        movementId, shopId, skuId, deviceShortCode, deltaMilliUnits,
                        movementType, refDocId, note, createdAt, syncStatus
                    )
                    SELECT 'opening_' || id, shopId, id, '', stockMilliUnits,
                           'OPENING', '', 'Migrated opening balance', updatedAt, syncStatus
                    FROM item_variants
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    INSERT OR IGNORE INTO invoice_headers (
                        id, shopId, deviceShortCode, invoiceNumber, docType, partyId,
                        buyerName, buyerPhone, paymentMode, subtotalPaise, discountPaise,
                        cgstPaise, sgstPaise, igstPaise, grandTotalPaise, amountPaidPaise,
                        balanceDuePaise, issuedAt, yearInt, monthInt, dayInt, isReturned,
                        updatedAt, syncStatus
                    )
                    SELECT 'inv_' || receiptNumber, shopId, '', receiptNumber, 'SALE', NULL,
                           MAX(buyerName), MAX(buyerPhone), 'CASH', SUM(totalRevenuePaise), 0,
                           0, 0, 0, SUM(totalRevenuePaise), SUM(totalRevenuePaise),
                           0, MAX(soldAt), MAX(yearInt), MAX(monthInt), MAX(dayInt), MIN(isReturned),
                           MAX(updatedAt), MAX(syncStatus)
                    FROM sale_records
                    GROUP BY receiptNumber
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    INSERT OR IGNORE INTO invoice_lines (
                        id, shopId, invoiceId, productId, skuId, itemNameSnapshot,
                        variantSummarySnapshot, hsnSnapshot, qtyMilliUnits, unitSnapshot,
                        unitCostPaise, unitSellPaise, gstRateBps, lineTaxPaise,
                        lineTotalPaise, lineProfitPaise, isReturned
                    )
                    SELECT id, shopId, 'inv_' || receiptNumber, itemId, variantId, itemNameSnapshot,
                           variantSummarySnapshot, '', CAST(ROUND(quantitySold * 1000.0) AS INTEGER), unitSnapshot,
                           unitPurchasePricePaise, unitSellingPricePaise, 0, 0,
                           totalRevenuePaise, totalProfitPaise, isReturned
                    FROM sale_records
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): LpsDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context.applicationContext).also { INSTANCE = it }
            }
        }

        /** Used by backup restore: the next getInstance() reopens (and migrates) the swapped file. */
        fun closeInstance() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }

        private fun buildDatabase(appContext: Context): LpsDatabase {
            val legacyDbFile = appContext.getDatabasePath(LEGACY_DB_NAME)
            val targetDbFile = appContext.getDatabasePath(DB_NAME)
            if (legacyDbFile.exists() && !targetDbFile.exists()) {
                runCatching {
                    targetDbFile.parentFile?.mkdirs()
                    legacyDbFile.copyTo(targetDbFile, overwrite = false)
                }
            }
            when (val outcome = PreMigrationGuard.prepare(appContext, DB_NAME, SCHEMA_VERSION)) {
                is PreMigrationGuard.Outcome.Corrupt -> DatabaseHealth.markFailed(
                    "Your data file failed its safety check (${outcome.detail}). Nothing was changed.", null
                )
                else -> Unit
            }
            val migrationContext = MigrationContext(
                activeShopId = SyncManager.getConfig(appContext).shopCollectionId,
                legacyDeviceCode = SyncManager.getDeviceShortCode(appContext)
            )
            return Room.databaseBuilder(appContext, LpsDatabase::class.java, DB_NAME)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .openHelperFactory(QuarantiningOpenHelperFactory())
                .withShopLedgerDefaults(migrationContext)
                .build()
        }
    }
}

/** Shared by the app and tests so every database gets the same migrations and seed rows. */
fun RoomDatabase.Builder<LpsDatabase>.withShopLedgerDefaults(
    migrationContext: MigrationContext = MigrationContext()
): RoomDatabase.Builder<LpsDatabase> =
    addMigrations(LpsDatabase.MIGRATION_1_2, Migration2To3(migrationContext))
        .addCallback(object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                DatabaseSeed.seedFreshDatabase(db, System.currentTimeMillis())
            }
        })
