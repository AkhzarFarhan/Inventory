package com.shopledger.pos.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import com.shopledger.pos.core.cipher.CostCipherEngine
import com.shopledger.pos.data.local.entity.DEFAULT_SHOP_ID
import java.util.UUID

/** Rows every database needs: a shop profile and exactly one `isLocal = 1` device. Idempotent. */
object DatabaseSeed {
    const val LOCAL_DEVICE_CODE = "L"

    fun seedFreshDatabase(
        db: SupportSQLiteDatabase,
        now: Long,
        deviceId: String = UUID.randomUUID().toString(),
        shopId: String = DEFAULT_SHOP_ID,
        deviceCode: String = LOCAL_DEVICE_CODE
    ) {
        db.execSQL(
            "INSERT OR IGNORE INTO shops (shopId, shopName, verticalType, ownerUid, gstin, stateCode, gstEnabled, " +
                "upiId, whatsappPhone, deviceShortCode, invoicePrefix, cipherMode, cipherWord10, ownerPinHash, " +
                "isSetupCompleted, maxStaffDiscountBps, updatedAt) VALUES (?, 'ShopLedger POS', 'HARDWARE', '', '', " +
                "'09', 0, '', '', '', 'INV', 'CUSTOM_CODE', ?, '', 1, 1000, ?)",
            arrayOf<Any>(shopId, CostCipherEngine.DEFAULT_WORD_10, now)
        )
        db.execSQL(
            "INSERT INTO devices (deviceId, shopId, deviceCode, label, isLocal, lastInvoiceSeq, lastSeqDate, createdAt) " +
                "SELECT ?, ?, ?, 'This phone', 1, 0, '', ? WHERE NOT EXISTS (SELECT 1 FROM devices WHERE isLocal = 1)",
            arrayOf<Any>(deviceId, shopId, deviceCode, now)
        )
    }
}
