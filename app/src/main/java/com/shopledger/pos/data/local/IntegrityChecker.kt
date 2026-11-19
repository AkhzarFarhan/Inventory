package com.shopledger.pos.data.local

import androidx.sqlite.db.SupportSQLiteDatabase

/** The spec's invariants as SQL. Returns human-readable violations; empty means healthy. */
object IntegrityChecker {

    fun findViolations(db: SupportSQLiteDatabase): List<String> {
        val violations = mutableListOf<String>()
        fun collect(label: String, sql: String) {
            db.query(sql).use { c -> while (c.moveToNext()) violations += "$label: ${c.getString(0)}" }
        }
        collect(
            "I1 stock differs from movements",
            "SELECT v.id FROM item_variants v WHERE v.stockMilliUnits != " +
                "(SELECT COALESCE(SUM(m.deltaMilliUnits), 0) FROM stock_movements m WHERE m.skuId = v.id)"
        )
        collect(
            "I2 balance differs from ledger",
            "SELECT p.id FROM parties p WHERE p.currentBalancePaise != " +
                "(SELECT COALESCE(SUM(e.amountDeltaPaise), 0) FROM party_ledger_entries e WHERE e.partyId = p.id)"
        )
        collect(
            "I3 invoice totals inconsistent",
            "SELECT h.id FROM invoice_headers h WHERE " +
                "h.subtotalPaise != (SELECT COALESCE(SUM(l.lineTotalPaise), 0) FROM invoice_lines l WHERE l.invoiceId = h.id) " +
                "OR h.grandTotalPaise != h.subtotalPaise - h.discountPaise + h.cgstPaise + h.sgstPaise + h.igstPaise " +
                "OR h.amountPaidPaise + h.balanceDuePaise != h.grandTotalPaise"
        )
        collect(
            "I4 returned more than sold",
            "SELECT l.id FROM invoice_lines l WHERE l.originalLineId IS NULL AND " +
                "(SELECT COALESCE(-SUM(r.qtyMilliUnits), 0) FROM invoice_lines r WHERE r.originalLineId = l.id) > l.qtyMilliUnits"
        )
        collect(
            "I4 return line not negative",
            "SELECT l.id FROM invoice_lines l WHERE l.originalLineId IS NOT NULL AND l.qtyMilliUnits >= 0"
        )
        collect(
            "I5 duplicate invoice number",
            "SELECT h.invoiceNumber FROM invoice_headers h GROUP BY h.shopId, h.invoiceNumber HAVING COUNT(*) > 1"
        )
        return violations
    }
}
