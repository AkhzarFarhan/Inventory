package com.shopledger.pos.data.command

import androidx.room.withTransaction
import com.shopledger.pos.data.local.LpsDatabase
import com.shopledger.pos.data.local.entity.AppliedActionEntity
import com.shopledger.pos.data.local.entity.DeviceEntity
import com.shopledger.pos.data.local.entity.PartyTransactionEntity
import com.shopledger.pos.data.local.entity.StockMovementEntity
import com.shopledger.pos.data.local.entity.SyncOutboxEntity

/**
 * The only code that appends ledger rows. Every append updates its cached projection in the same
 * transaction, so invariants I1 (stock) and I2 (balances) can never drift.
 */
class LedgerWriter(private val db: LpsDatabase, private val env: CommandEnvironment) {
    private val inventory = db.inventoryDao()
    private val ledger = db.shopPartyAndLedgerDao()
    private val outbox = db.syncOutboxDao()
    private val actions = db.appliedActionDao()
    private val devices = db.deviceDao()

    /**
     * Runs [body] in ONE transaction unless [actionId] was already applied, in which case [replay]
     * rebuilds the earlier result from its stored reference. `body` returns (result, resultRef).
     */
    suspend fun <T> runIdempotent(
        actionId: String,
        kind: String,
        replay: suspend (resultRef: String) -> T,
        body: suspend () -> Pair<T, String>
    ): T {
        if (actionId.isBlank()) throw CommandException.Validation("Missing action id")
        val result = db.withTransaction {
            val prior = actions.get(actionId)
            if (prior != null) {
                if (prior.kind != kind) {
                    throw CommandException.Validation("Action id already used for ${prior.kind}")
                }
                replay(prior.resultRef)
            } else {
                val (value, resultRef) = body()
                actions.insert(AppliedActionEntity(actionId = actionId, kind = kind, resultRef = resultRef, createdAt = env.now()))
                value
            }
        }
        env.afterCommit()
        return result
    }

    suspend fun localDevice(): DeviceEntity =
        devices.getLocalDevice() ?: throw IllegalStateException("Local device row is missing; database seed did not run")

    suspend fun appendMovement(
        skuId: String,
        deltaMilli: Long,
        movementType: String,
        refDocId: String,
        note: String,
        device: DeviceEntity,
        enqueue: Boolean
    ): StockMovementEntity {
        if (deltaMilli == 0L) throw CommandException.Validation("Stock change cannot be zero")
        val movement = StockMovementEntity(
            movementId = env.newId(),
            shopId = env.shopId(),
            skuId = skuId,
            deviceId = device.deviceId,
            deviceShortCode = device.deviceCode,
            deltaMilliUnits = deltaMilli,
            movementType = movementType,
            refDocId = refDocId,
            note = note,
            createdAt = env.now(),
            createdByUid = env.actor().uid
        )
        ledger.insertStockMovement(movement)
        if (inventory.addToStockProjection(skuId, deltaMilli) != 1) {
            throw CommandException.NotFound("Item variant $skuId")
        }
        if (enqueue) enqueue(OutboxDocType.MOVEMENT, movement.movementId, OutboxOp.APPEND)
        return movement
    }

    suspend fun appendLedgerEntry(
        partyId: String,
        deltaPaise: Long,
        entryType: String,
        paymentMode: String,
        refDocId: String,
        note: String,
        device: DeviceEntity,
        enqueue: Boolean
    ): PartyTransactionEntity {
        if (deltaPaise == 0L) throw CommandException.Validation("Amount cannot be zero")
        val entry = PartyTransactionEntity(
            entryId = env.newId(),
            shopId = env.shopId(),
            partyId = partyId,
            refDocId = refDocId,
            entryType = entryType,
            amountDeltaPaise = deltaPaise,
            paymentMode = paymentMode,
            note = note,
            createdAt = env.now(),
            createdByUid = env.actor().uid,
            deviceId = device.deviceId
        )
        ledger.insertLedgerEntry(entry)
        if (ledger.addToPartyBalance(partyId, deltaPaise) != 1) {
            throw CommandException.NotFound("Party $partyId")
        }
        if (enqueue) enqueue(OutboxDocType.LEDGER, entry.entryId, OutboxOp.APPEND)
        return entry
    }

    suspend fun enqueue(docType: String, docId: String, op: String) {
        outbox.insert(SyncOutboxEntity(docType = docType, docId = docId, op = op, createdAt = env.now()))
    }
}
