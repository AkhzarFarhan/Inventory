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
    val shopId: String = DEFAULT_SHOP_ID,
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
    /** Largest bill discount STAFF may give without the owner PIN, in basis points (1000 = 10%). */
    val maxStaffDiscountBps: Int = 1000,
    val updatedAt: Long = System.currentTimeMillis()
)

@Immutable
@Entity(
    tableName = "parties",
    indices = [
        Index(value = ["shopId", "partyType"]),
        Index(value = ["shopId", "phone"])
    ]
)
data class PartyEntity(
    @PrimaryKey
    val id: String,
    val shopId: String = DEFAULT_SHOP_ID,
    val partyType: String = "CUSTOMER",
    val name: String,
    val phone: String = "",
    val gstin: String = "",
    val billingAddress: String = "",
    /** Cached projection: always equals SUM(party_ledger_entries.amountDeltaPaise) (invariant I2). + = party owes shop. */
    val currentBalancePaise: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isDeleted: Boolean = false,
    val createdByUid: String = "",
    val deviceId: String = ""
)

/** Immutable once written. Returns are separate CREDIT_NOTE documents with negative amounts. */
@Immutable
@Entity(
    tableName = "invoice_headers",
    indices = [
        Index(value = ["shopId", "invoiceNumber"], unique = true),
        Index(value = ["shopId", "issuedAt"]),
        Index(value = ["shopId", "docType"]),
        Index(value = ["originalInvoiceId"])
    ]
)
data class InvoiceHeaderEntity(
    @PrimaryKey
    val id: String,
    val shopId: String = DEFAULT_SHOP_ID,
    val deviceId: String = "",
    val deviceShortCode: String = "",
    val invoiceNumber: String,
    /** "SALE" or "CREDIT_NOTE". */
    val docType: String = "SALE",
    /** For a CREDIT_NOTE: the SALE it reverses. */
    val originalInvoiceId: String? = null,
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
    val createdByUid: String = ""
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
        Index(value = ["shopId", "invoiceId"]),
        Index(value = ["originalLineId"])
    ]
)
data class InvoiceLineEntity(
    @PrimaryKey
    val id: String,
    val shopId: String = DEFAULT_SHOP_ID,
    val invoiceId: String,
    /** For a credit-note line: the sale line it returns. */
    val originalLineId: String? = null,
    val productId: String,
    val skuId: String,
    val itemNameSnapshot: String,
    val variantSummarySnapshot: String,
    val hsnSnapshot: String = "",
    /** Negative on credit notes. */
    val qtyMilliUnits: Long,
    val unitSnapshot: String,
    val unitCostPaise: Long,
    val unitSellPaise: Long,
    val gstRateBps: Int = 0,
    val lineTaxPaise: Long = 0L,
    val lineTotalPaise: Long,
    val lineProfitPaise: Long
)

/** Append-only. Never updated or deleted; corrections are new rows. Source of truth for stock. */
@Immutable
@Entity(
    tableName = "stock_movements",
    indices = [
        Index(value = ["shopId", "skuId"]),
        Index(value = ["refDocId"])
    ]
)
data class StockMovementEntity(
    @PrimaryKey
    val movementId: String,
    val shopId: String = DEFAULT_SHOP_ID,
    val skuId: String,
    val deviceId: String = "",
    val deviceShortCode: String = "",
    val deltaMilliUnits: Long,
    val movementType: String,
    val refDocId: String = "",
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val createdByUid: String = ""
)

/** Append-only. Source of truth for Khata balances. + = party owes shop, − = shop owes party / advance. */
@Immutable
@Entity(
    tableName = "party_ledger_entries",
    indices = [
        Index(value = ["shopId", "partyId"]),
        Index(value = ["refDocId"])
    ]
)
data class PartyTransactionEntity(
    @PrimaryKey
    val entryId: String,
    val shopId: String = DEFAULT_SHOP_ID,
    val partyId: String,
    val refDocId: String = "",
    val entryType: String,
    val amountDeltaPaise: Long,
    val paymentMode: String = "CASH",
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val createdByUid: String = "",
    val deviceId: String = ""
)
