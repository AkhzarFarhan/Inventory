package com.shopledger.pos.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One row per phone known to this shop. `isLocal = 1` marks this install. */
@Entity(
    tableName = "devices",
    indices = [Index(value = ["shopId", "deviceCode"], unique = true)]
)
data class DeviceEntity(
    @PrimaryKey
    val deviceId: String,
    val shopId: String = DEFAULT_SHOP_ID,
    /** Short code used in receipt numbers (A, B, D1…). "L" until the phone joins a shop (sub-project 2). */
    val deviceCode: String,
    val label: String = "",
    val isLocal: Boolean = false,
    /** Last receipt sequence handed out on [lastSeqDate] (yyMMdd). */
    val lastInvoiceSeq: Long = 0L,
    val lastSeqDate: String = "",
    val createdAt: Long
)

/** Written in the same transaction as each change; deleted only after the server acknowledges it. */
@Entity(
    tableName = "sync_outbox",
    indices = [Index(value = ["docType", "docId"])]
)
data class SyncOutboxEntity(
    @PrimaryKey(autoGenerate = true)
    val seq: Long = 0L,
    val docType: String,
    val docId: String,
    val op: String,
    val createdAt: Long,
    val attempts: Int = 0,
    val lastError: String = ""
)

/** Idempotency keys: a command with a seen actionId replays its stored result instead of writing again. */
@Entity(tableName = "applied_actions")
data class AppliedActionEntity(
    @PrimaryKey
    val actionId: String,
    val kind: String,
    val resultRef: String,
    val createdAt: Long
)
