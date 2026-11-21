package com.shopledger.pos.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.shopledger.pos.data.local.entity.PurchaseBillEntity
import com.shopledger.pos.data.local.entity.PurchaseBillLineEntity
import kotlinx.coroutines.flow.Flow

data class SupplierItemSummary(
    val skuId: String,
    val productId: String,
    val itemNameSnapshot: String,
    val variantSummarySnapshot: String,
    val unitSnapshot: String,
    val totalQtyMilli: Long,
    val lastUnitCostPaise: Long,
    val lastBoughtAt: Long,
    val billCount: Int
)

data class LastPurchaseInfo(
    val supplierPartyId: String?,
    val supplierNameSnapshot: String,
    val unitCostPaise: Long,
    val createdAt: Long,
    val isLegacy: Boolean
)

@Dao
interface PurchaseDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertBill(bill: PurchaseBillEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertLines(lines: List<PurchaseBillLineEntity>)

    @Query("SELECT * FROM purchase_bills ORDER BY createdAt DESC")
    fun observeAllBills(): Flow<List<PurchaseBillEntity>>

    @Query("SELECT * FROM purchase_bills WHERE supplierPartyId = :partyId ORDER BY createdAt DESC")
    fun observeBillsForSupplier(partyId: String): Flow<List<PurchaseBillEntity>>

    @Query("SELECT * FROM purchase_bills WHERE id = :id LIMIT 1")
    suspend fun getBill(id: String): PurchaseBillEntity?

    @Query("SELECT * FROM purchase_bill_lines WHERE purchaseId = :purchaseId ORDER BY itemNameSnapshot")
    suspend fun getLines(purchaseId: String): List<PurchaseBillLineEntity>

    @Query(
        "SELECT l.skuId AS skuId, l.productId AS productId, l.itemNameSnapshot AS itemNameSnapshot, " +
            "l.variantSummarySnapshot AS variantSummarySnapshot, l.unitSnapshot AS unitSnapshot, " +
            "SUM(l.qtyMilliUnits) AS totalQtyMilli, " +
            "(SELECT l2.unitCostPaise FROM purchase_bill_lines l2 JOIN purchase_bills b2 ON b2.id = l2.purchaseId " +
            "  WHERE l2.skuId = l.skuId AND b2.supplierPartyId = :partyId ORDER BY b2.createdAt DESC LIMIT 1) AS lastUnitCostPaise, " +
            "MAX(b.createdAt) AS lastBoughtAt, COUNT(DISTINCT b.id) AS billCount " +
            "FROM purchase_bill_lines l JOIN purchase_bills b ON b.id = l.purchaseId " +
            "WHERE b.supplierPartyId = :partyId GROUP BY l.skuId ORDER BY lastBoughtAt DESC"
    )
    fun observeItemsForSupplier(partyId: String): Flow<List<SupplierItemSummary>>

    @Query(
        "SELECT b.supplierPartyId AS supplierPartyId, b.supplierNameSnapshot AS supplierNameSnapshot, " +
            "l.unitCostPaise AS unitCostPaise, b.createdAt AS createdAt, b.isLegacy AS isLegacy " +
            "FROM purchase_bill_lines l JOIN purchase_bills b ON b.id = l.purchaseId " +
            "WHERE l.skuId = :skuId ORDER BY b.createdAt DESC LIMIT 1"
    )
    suspend fun lastPurchaseForSku(skuId: String): LastPurchaseInfo?

    @Query("SELECT * FROM purchase_bills")
    suspend fun getAllBills(): List<PurchaseBillEntity>
}
