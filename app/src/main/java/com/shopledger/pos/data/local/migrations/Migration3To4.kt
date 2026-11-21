package com.shopledger.pos.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.shopledger.pos.data.local.IntegrityChecker
import com.shopledger.pos.data.local.OutboxRebuilder

/** v3 -> v4: add immutable purchase_bills and purchase_bill_lines and rebuild legacy bills from past PURCHASE movements. */
class Migration3To4(private val clock: () -> Long = { System.currentTimeMillis() }) : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val now = clock()
        // 1. Tables + indexes copied verbatim from app/schemas/.../LpsDatabase/4.json.
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `purchase_bills` (`id` TEXT NOT NULL, `shopId` TEXT NOT NULL, " +
                "`supplierPartyId` TEXT, `supplierNameSnapshot` TEXT NOT NULL, `billNumber` TEXT NOT NULL, " +
                "`totalPaise` INTEGER NOT NULL, `linesTotalPaise` INTEGER NOT NULL, `paidPaise` INTEGER NOT NULL, " +
                "`duePaise` INTEGER NOT NULL, `paymentMode` TEXT NOT NULL, `lineCount` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, `yearInt` INTEGER NOT NULL, `monthInt` INTEGER NOT NULL, " +
                "`dayInt` INTEGER NOT NULL, `isLegacy` INTEGER NOT NULL, `deviceId` TEXT NOT NULL, " +
                "`createdByUid` TEXT NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_purchase_bills_shopId_supplierPartyId` " +
                "ON `purchase_bills` (`shopId`, `supplierPartyId`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_purchase_bills_shopId_createdAt` " +
                "ON `purchase_bills` (`shopId`, `createdAt`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_purchase_bills_shopId_yearInt_monthInt` " +
                "ON `purchase_bills` (`shopId`, `yearInt`, `monthInt`)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `purchase_bill_lines` (`id` TEXT NOT NULL, `shopId` TEXT NOT NULL, " +
                "`purchaseId` TEXT NOT NULL, `skuId` TEXT NOT NULL, `productId` TEXT NOT NULL, " +
                "`itemNameSnapshot` TEXT NOT NULL, `variantSummarySnapshot` TEXT NOT NULL, `unitSnapshot` TEXT NOT NULL, " +
                "`qtyMilliUnits` INTEGER NOT NULL, `unitCostPaise` INTEGER NOT NULL, `lineTotalPaise` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`), FOREIGN KEY(`purchaseId`) REFERENCES `purchase_bills`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_purchase_bill_lines_purchaseId` " +
                "ON `purchase_bill_lines` (`purchaseId`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_purchase_bill_lines_shopId_skuId` " +
                "ON `purchase_bill_lines` (`shopId`, `skuId`)"
        )

        // 2. Rebuild bills for older stock-ins (one per distinct refDocId of PURCHASE movements).
        db.execSQL(
            "INSERT INTO purchase_bills (id, shopId, supplierPartyId, supplierNameSnapshot, billNumber, totalPaise, linesTotalPaise, " +
                "paidPaise, duePaise, paymentMode, lineCount, createdAt, yearInt, monthInt, dayInt, isLegacy, deviceId, createdByUid) " +
                "SELECT m.refDocId, MIN(m.shopId), " +
                "(SELECT e.partyId FROM party_ledger_entries e WHERE e.refDocId = m.refDocId AND e.entryType = 'CREDIT_BILL' LIMIT 1), " +
                "COALESCE((SELECT p.name FROM parties p WHERE p.id = (SELECT e2.partyId FROM party_ledger_entries e2 WHERE e2.refDocId = m.refDocId AND e2.entryType = 'CREDIT_BILL' LIMIT 1)), ''), " +
                "'', " +
                "COALESCE((SELECT -SUM(e3.amountDeltaPaise) FROM party_ledger_entries e3 WHERE e3.refDocId = m.refDocId AND e3.entryType = 'CREDIT_BILL'), 0), " +
                "0, 0, " +
                "COALESCE((SELECT -SUM(e4.amountDeltaPaise) FROM party_ledger_entries e4 WHERE e4.refDocId = m.refDocId AND e4.entryType = 'CREDIT_BILL'), 0), " +
                "'CREDIT', COUNT(*), MIN(m.createdAt), " +
                "CAST(strftime('%Y', MIN(m.createdAt) / 1000, 'unixepoch', 'localtime') AS INTEGER), " +
                "CAST(strftime('%m', MIN(m.createdAt) / 1000, 'unixepoch', 'localtime') AS INTEGER), " +
                "CAST(strftime('%d', MIN(m.createdAt) / 1000, 'unixepoch', 'localtime') AS INTEGER), " +
                "1, MIN(m.deviceId), MIN(m.createdByUid) " +
                "FROM stock_movements m WHERE m.movementType = 'PURCHASE' AND m.refDocId != '' GROUP BY m.refDocId"
        )
        db.execSQL(
            "INSERT INTO purchase_bill_lines (id, shopId, purchaseId, skuId, productId, itemNameSnapshot, variantSummarySnapshot, " +
                "unitSnapshot, qtyMilliUnits, unitCostPaise, lineTotalPaise) " +
                "SELECT m.movementId, m.shopId, m.refDocId, m.skuId, COALESCE(v.itemId, ''), COALESCE(i.displayName, ''), '', " +
                "COALESCE(v.unit, 'pcs'), m.deltaMilliUnits, 0, 0 " +
                "FROM stock_movements m LEFT JOIN item_variants v ON v.id = m.skuId LEFT JOIN inventory_items i ON i.id = v.itemId " +
                "WHERE m.movementType = 'PURCHASE' AND m.refDocId != ''"
        )
        // 3. Outbox for the rebuilt bills.
        OutboxRebuilder.enqueuePurchases(db, now)
        // 4. Verify.
        val violations = IntegrityChecker.findViolations(db, includePurchases = true)
        check(violations.isEmpty()) { "v4 upgrade check failed: ${violations.take(5)}" }
    }
}
