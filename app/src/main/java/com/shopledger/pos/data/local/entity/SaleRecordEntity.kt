package com.shopledger.pos.data.local.entity

import androidx.compose.runtime.Immutable
import androidx.room.DatabaseView
import kotlin.math.roundToLong

/**
 * Single-line SQL, shared by the @DatabaseView and Migration2To3, so the view text in sqlite_master
 * matches exactly what Room validates.
 */
const val SALE_RECORDS_VIEW_SQL =
    "SELECT l.id AS id, h.id AS invoiceId, h.shopId AS shopId, h.invoiceNumber AS receiptNumber, " +
        "h.buyerName AS buyerName, h.buyerPhone AS buyerPhone, h.partyId AS partyId, " +
        "l.productId AS itemId, l.skuId AS variantId, l.itemNameSnapshot AS itemNameSnapshot, " +
        "l.variantSummarySnapshot AS variantSummarySnapshot, l.qtyMilliUnits / 1000.0 AS quantitySold, " +
        "l.qtyMilliUnits AS quantitySoldMilli, l.unitSnapshot AS unitSnapshot, " +
        "l.unitCostPaise AS unitPurchasePricePaise, l.unitSellPaise AS unitSellingPricePaise, " +
        "l.lineTotalPaise - l.lineProfitPaise AS totalCostPaise, l.lineTotalPaise AS totalRevenuePaise, " +
        "l.lineProfitPaise AS totalProfitPaise, " +
        "CASE WHEN l.lineTotalPaise - l.lineProfitPaise > 0 THEN l.lineProfitPaise * 100.0 / (l.lineTotalPaise - l.lineProfitPaise) ELSE 0.0 END AS gainMarkupPercent, " +
        "CASE WHEN l.lineTotalPaise > 0 THEN l.lineProfitPaise * 100.0 / l.lineTotalPaise ELSE 0.0 END AS grossMarginPercent, " +
        "h.paymentMode AS paymentMode, h.amountPaidPaise AS amountPaidPaise, h.balanceDuePaise AS balanceDuePaise, " +
        "h.discountPaise AS discountPaise, h.cgstPaise + h.sgstPaise + h.igstPaise AS gstPaise, " +
        "h.issuedAt AS soldAt, h.yearInt AS yearInt, h.monthInt AS monthInt, h.dayInt AS dayInt, " +
        "COALESCE((SELECT -SUM(r.qtyMilliUnits) FROM invoice_lines r WHERE r.originalLineId = l.id), 0) AS returnedMilliUnits, " +
        "CASE WHEN COALESCE((SELECT -SUM(r2.qtyMilliUnits) FROM invoice_lines r2 WHERE r2.originalLineId = l.id), 0) >= l.qtyMilliUnits THEN 1 ELSE 0 END AS isReturned, " +
        "l.lineTotalPaise + COALESCE((SELECT SUM(r3.lineTotalPaise) FROM invoice_lines r3 WHERE r3.originalLineId = l.id), 0) AS netRevenuePaise, " +
        "(l.lineTotalPaise - l.lineProfitPaise) + COALESCE((SELECT SUM(r4.lineTotalPaise - r4.lineProfitPaise) FROM invoice_lines r4 WHERE r4.originalLineId = l.id), 0) AS netCostPaise, " +
        "l.lineProfitPaise + COALESCE((SELECT SUM(r5.lineProfitPaise) FROM invoice_lines r5 WHERE r5.originalLineId = l.id), 0) AS netProfitPaise, " +
        "h.issuedAt AS updatedAt " +
        "FROM invoice_lines l INNER JOIN invoice_headers h ON h.id = l.invoiceId WHERE h.docType = 'SALE'"

/** Read-only per-line view of SALE invoices, kept for the Sales History screen and buyer history. */
@Immutable
@DatabaseView(viewName = "sale_records", value = SALE_RECORDS_VIEW_SQL)
data class SaleRecordEntity(
    val id: String,
    val invoiceId: String = "",
    val shopId: String = DEFAULT_SHOP_ID,
    val receiptNumber: String,
    val buyerName: String = "",
    val buyerPhone: String = "",
    val partyId: String? = null,
    val itemId: String,
    val variantId: String,
    val itemNameSnapshot: String,
    val variantSummarySnapshot: String,
    val quantitySold: Double,
    val quantitySoldMilli: Long = (quantitySold * 1000.0).roundToLong(),
    val unitSnapshot: String,
    val unitPurchasePricePaise: Long,
    val unitSellingPricePaise: Long,
    val totalCostPaise: Long,
    val totalRevenuePaise: Long,
    val totalProfitPaise: Long,
    val gainMarkupPercent: Double,
    val grossMarginPercent: Double,
    val paymentMode: String = "CASH",
    val amountPaidPaise: Long = totalRevenuePaise,
    val balanceDuePaise: Long = 0L,
    val discountPaise: Long = 0L,
    val gstPaise: Long = 0L,
    val soldAt: Long,
    val yearInt: Int,
    val monthInt: Int,
    val dayInt: Int,
    val returnedMilliUnits: Long = 0L,
    /** True once the whole line has been returned (possibly over several credit notes). */
    val isReturned: Boolean = false,
    /** Line value net of credit-note lines (partial returns). */
    val netRevenuePaise: Long = totalRevenuePaise,
    val netCostPaise: Long = totalCostPaise,
    val netProfitPaise: Long = totalProfitPaise,
    val updatedAt: Long = soldAt
)
