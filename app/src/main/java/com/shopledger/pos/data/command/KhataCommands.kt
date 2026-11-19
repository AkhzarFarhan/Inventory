package com.shopledger.pos.data.command

import com.shopledger.pos.data.local.LpsDatabase
import com.shopledger.pos.data.local.entity.PartyEntity
import java.util.Locale

enum class PaymentDirection { IN, OUT }

class KhataCommands(private val db: LpsDatabase, private val env: CommandEnvironment) {
    private val writer = LedgerWriter(db, env)
    private val parties = db.shopPartyAndLedgerDao()

    suspend fun createParty(
        actionId: String,
        name: String,
        phone: String,
        partyType: String = "CUSTOMER",
        gstin: String = "",
        openingBalancePaise: Long = 0L
    ): String = writer.runIdempotent(actionId, "CREATE_PARTY", replay = { it }) {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) throw CommandException.Validation("Name is required")
        val type = normalizeType(partyType)
        val device = writer.localDevice()
        val now = env.now()
        val partyId = env.newId()
        parties.insertParty(
            PartyEntity(
                id = partyId,
                shopId = env.shopId(),
                partyType = type,
                name = cleanName,
                phone = phone.trim(),
                gstin = gstin.trim().uppercase(Locale.ROOT),
                currentBalancePaise = 0L,
                createdAt = now,
                updatedAt = now,
                createdByUid = env.actor().uid,
                deviceId = device.deviceId
            )
        )
        writer.enqueue(OutboxDocType.PARTY, partyId, OutboxOp.UPSERT)
        if (openingBalancePaise != 0L) {
            writer.appendLedgerEntry(partyId, openingBalancePaise, LedgerEntryType.OPENING, "CASH", partyId, "Opening balance", device, enqueue = true)
        }
        partyId to partyId
    }

    suspend fun updateParty(
        actionId: String,
        partyId: String,
        expectedUpdatedAt: Long,
        name: String,
        phone: String,
        partyType: String,
        gstin: String
    ) {
        writer.runIdempotent(actionId, "UPDATE_PARTY", replay = { }) {
            val current = parties.getPartyById(partyId)?.takeUnless { it.isDeleted }
                ?: throw CommandException.NotFound("Party")
            if (current.updatedAt != expectedUpdatedAt) throw CommandException.StaleEdit("Party \"${current.name}\"")
            val cleanName = name.trim()
            if (cleanName.isEmpty()) throw CommandException.Validation("Name is required")
            parties.updateParty(
                current.copy(
                    name = cleanName,
                    phone = phone.trim(),
                    partyType = normalizeType(partyType),
                    gstin = gstin.trim().uppercase(Locale.ROOT),
                    updatedAt = env.now()
                )
            )
            writer.enqueue(OutboxDocType.PARTY, partyId, OutboxOp.UPSERT)
            Unit to partyId
        }
    }

    /** IN = money received from the party (balance goes down). OUT = money paid to the party (goes up). */
    suspend fun recordPayment(
        actionId: String,
        partyId: String,
        amountPaise: Long,
        paymentMode: String = "CASH",
        direction: PaymentDirection = PaymentDirection.IN,
        note: String = ""
    ): String = writer.runIdempotent(actionId, "RECORD_PAYMENT", replay = { it }) {
        if (amountPaise <= 0L) throw CommandException.Validation("Amount must be more than 0")
        val party = parties.getPartyById(partyId)?.takeUnless { it.isDeleted }
            ?: throw CommandException.NotFound("Party")
        val mode = paymentMode.trim().uppercase(Locale.ROOT).ifEmpty { "CASH" }
        val incoming = direction == PaymentDirection.IN
        val entry = writer.appendLedgerEntry(
            partyId = party.id,
            deltaPaise = if (incoming) -amountPaise else amountPaise,
            entryType = if (incoming) LedgerEntryType.PAYMENT_IN else LedgerEntryType.PAYMENT_OUT,
            paymentMode = mode,
            refDocId = "",
            note = note.trim().ifEmpty { if (incoming) "Payment received ($mode)" else "Payment made ($mode)" },
            device = writer.localDevice(),
            enqueue = true
        )
        entry.entryId to entry.entryId
    }

    private fun normalizeType(raw: String): String {
        val type = raw.trim().uppercase(Locale.ROOT)
        if (type !in setOf("CUSTOMER", "SUPPLIER", "BOTH")) throw CommandException.Validation("Unknown party type $raw")
        return type
    }
}
