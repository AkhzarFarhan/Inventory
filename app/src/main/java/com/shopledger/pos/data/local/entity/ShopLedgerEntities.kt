package com.shopledger.pos.data.local.entity

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.shopledger.pos.core.cipher.CipherMode
import com.shopledger.pos.core.cipher.CostCipherEngine
import com.shopledger.pos.core.nlp.ShopVertical

@Immutable
@Entity(tableName = "shops")
data class ShopProfileEntity(
    @PrimaryKey
    val shopId: String = "default_shop",
    val shopName: String = "ShopLedger POS",
    val verticalType: String = ShopVertical.HARDWARE.name,
    val ownerUid: String = "",
    val gstin: String = "",
    val stateCode: String = "09",
    val gstEnabled: Boolean = false,
    val upiId: String = "",
    val whatsappPhone: String = "",
    val deviceShortCode: String = "",
    val invoicePrefix: String = "INV",
    val cipherMode: String = CipherMode.CUSTOM_CODE.name,
    val cipherWord10: String = CostCipherEngine.DEFAULT_WORD_10,
    val ownerPinHash: String = "",
    val isSetupCompleted: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

@Immutable
@Entity(
    tableName = "parties",
    indices = [
        Index(value = ["shopId", "partyType"]),
        Index(value = ["shopId", "phone"]),
        Index(value = ["syncStatus"])
    ]
)
data class PartyEntity(
    @PrimaryKey
    val id: String,
    val shopId: String = "default_shop",
    val partyType: String = "CUSTOMER",
    val name: String,
    val phone: String = "",
    val gstin: String = "",
    val billingAddress: String = "",
    val openingBalancePaise: Long = 0L,
    val currentBalancePaise: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis(),
    val isDeleted: Boolean = false,
    val syncStatus: String = SyncState.PENDING_UPLOAD
)

@Immutable
@Entity(
    tableName = "invoice_headers",
    indices = [
        Index(value = ["shopId", "invoiceNumber"], unique = true),
        Index(value = ["shopId", "issuedAt"]),
        Index(value = ["shopId", "docType"]),
        Index(value = ["syncStatus"])
    ]
)
data class InvoiceHeaderEntity(
    @PrimaryKey
    val id: String,
    val shopId: String = "default_shop",
    val deviceShortCode: String = "",
    val invoiceNumber: String,
    val docType: String = "SALE",
    val partyId: String? = null,
    val buyerName: String = "",
    val buyerPhone: String = "",
    val paymentMode: String = "CASH",
    val subtotalPaise: Long,
    val discountPaise: Long = 0L,
    val cgstPaise: Long = 0L,
    val sgstPaise: Long = 0L,
    val igstPaise: Long = 0L,
    val grandTotalPaise: Long,
    val amountPaidPaise: Long,
    val balanceDuePaise: Long = 0L,
    val issuedAt: Long,
    val yearInt: Int,
    val monthInt: Int,
    val dayInt: Int,
    val isReturned: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
    val syncStatus: String = SyncState.PENDING_UPLOAD
)

@Immutable
@Entity(
    tableName = "invoice_lines",
    foreignKeys = [
        ForeignKey(
            entity = InvoiceHeaderEntity::class,
            parentColumns = ["id"],
            childColumns = ["invoiceId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["invoiceId"]),
        Index(value = ["shopId", "invoiceId"])
    ]
)
data class InvoiceLineEntity(
    @PrimaryKey
    val id: String,
    val shopId: String = "default_shop",
    val invoiceId: String,
    val productId: String,
    val skuId: String,
    val itemNameSnapshot: String,
    val variantSummarySnapshot: String,
    val hsnSnapshot: String = "",
    val qtyMilliUnits: Long,
    val unitSnapshot: String,
    val unitCostPaise: Long,
    val unitSellPaise: Long,
    val gstRateBps: Int = 0,
    val lineTaxPaise: Long = 0L,
    val lineTotalPaise: Long,
    val lineProfitPaise: Long,
    val isReturned: Boolean = false
)

@Immutable
@Entity(
    tableName = "stock_movements",
    indices = [
        Index(value = ["shopId", "skuId"]),
        Index(value = ["syncStatus"])
    ]
)
data class StockMovementEntity(
    @PrimaryKey
    val movementId: String,
    val shopId: String = "default_shop",
    val skuId: String,
    val deviceShortCode: String = "",
    val deltaMilliUnits: Long,
    val movementType: String,
    val refDocId: String = "",
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val syncStatus: String = SyncState.PENDING_UPLOAD
)

@Immutable
@Entity(
    tableName = "party_ledger_entries",
    indices = [
        Index(value = ["shopId", "partyId"]),
        Index(value = ["syncStatus"])
    ]
)
data class PartyTransactionEntity(
    @PrimaryKey
    val entryId: String,
    val shopId: String = "default_shop",
    val partyId: String,
    val refDocId: String = "",
    val entryType: String,
    val amountDeltaPaise: Long,
    val paymentMode: String = "CASH",
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val syncStatus: String = SyncState.PENDING_UPLOAD
)
