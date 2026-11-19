package com.shopledger.pos.data.local

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Queues every syncable document once (after the v3 upgrade and after a restore). Movements and
 * ledger entries that belong to an invoice travel inside that INVOICE document, so they are skipped.
 */
object OutboxRebuilder {
    fun enqueueEverything(db: SupportSQLiteDatabase, now: Long) {
        val insert = "INSERT INTO sync_outbox (docType, docId, op, createdAt, attempts, lastError) "
        val args = arrayOf<Any>(now)
        db.execSQL(insert + "SELECT 'ITEM', id, 'UPSERT', ?, 0, '' FROM inventory_items WHERE isDemo = 0", args)
        db.execSQL(insert + "SELECT 'VARIANT', id, 'UPSERT', ?, 0, '' FROM item_variants WHERE isDemo = 0", args)
        db.execSQL(insert + "SELECT 'PARTY', id, 'UPSERT', ?, 0, '' FROM parties", args)
        db.execSQL(
            insert + "SELECT 'MOVEMENT', m.movementId, 'APPEND', ?, 0, '' FROM stock_movements m " +
                "WHERE m.refDocId NOT IN (SELECT id FROM invoice_headers) " +
                "AND NOT EXISTS (SELECT 1 FROM item_variants v WHERE v.id = m.skuId AND v.isDemo = 1)",
            args
        )
        db.execSQL(
            insert + "SELECT 'LEDGER', e.entryId, 'APPEND', ?, 0, '' FROM party_ledger_entries e " +
                "WHERE e.refDocId NOT IN (SELECT id FROM invoice_headers)",
            args
        )
        db.execSQL(insert + "SELECT 'INVOICE', id, 'APPEND', ?, 0, '' FROM invoice_headers", args)
    }
}
