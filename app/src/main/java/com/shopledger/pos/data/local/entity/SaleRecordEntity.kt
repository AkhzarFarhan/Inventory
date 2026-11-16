package com.shopledger.pos.data.local.entity

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Immutable
@Entity(
    tableName = "sale_records",
    indices = [
        Index(value = ["soldAt"]),
        Index(value = ["yearInt", "monthInt", "dayInt"]),
        Index(value = ["receiptNumber"]),
        Index(value = ["syncStatus"]),
        Index(value = ["shopId", "soldAt"])
    ]
)
data class SaleRecordEntity(
    @PrimaryKey
    val id: String,
    val shopId: String = "default_shop",
    val receiptNumber: String,
    val buyerName: String = "",
    val buyerPhone: String = "",
    val partyId: String? = null,
    val itemId: String,
    val variantId: String,
    val itemNameSnapshot: String,
    val variantSummarySnapshot: String,
    val quantitySold: Double,
    val unitSnapshot: String,
    val unitPurchasePricePaise: Long,
    val unitSellingPricePaise: Long,
    val totalCostPaise: Long,
    val totalRevenuePaise: Long,
    val totalProfitPaise: Long,
    /** Markup / Gain % over Purchase Cost: ((Selling - Purchase) / Purchase) * 100 */
    val gainMarkupPercent: Double,
    /** Gross Margin % over Selling Price: ((Selling - Purchase) / Selling) * 100 */
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
    val isReturned: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
    val syncStatus: String = SyncState.PENDING_UPLOAD
)
