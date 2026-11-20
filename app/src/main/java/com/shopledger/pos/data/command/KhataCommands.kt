package com.shopledger.pos.data.command

import com.shopledger.pos.R
import com.shopledger.pos.data.local.LpsDatabase
import com.shopledger.pos.data.local.entity.DeviceEntity
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
        if (cleanName.isEmpty()) throw CommandException.Validation(R.string.msg_name_required)
        val type = normalizeType(partyType)
        val device = writer.localDevice()
        val partyId = insertParty(type, cleanName, phone, gstin, device)
        if (openingBalancePaise != 0L) {
            writer.appendLedgerEntry(partyId, openingBalancePaise, LedgerEntryType.OPENING, "CASH", partyId, "Opening balance", device, enqueue = true)
        }
        partyId to partyId
    }

    /** Shared by [createParty] and the stock-in command: inserts the party row and its outbox entry. */
    internal suspend fun insertParty(type: String, cleanName: String, phone: String, gstin: String, device: DeviceEntity): String {
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
        return partyId
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
                ?: throw CommandException.NotFound(R.string.msg_not_found_party)
            if (current.updatedAt != expectedUpdatedAt) throw CommandException.StaleEdit.party(current.name)
            val cleanName = name.trim()
            if (cleanName.isEmpty()) throw CommandException.Validation(R.string.msg_name_required)
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
        if (amountPaise <= 0L) throw CommandException.Validation(R.string.msg_amount_positive)
        val party = parties.getPartyById(partyId)?.takeUnless { it.isDeleted }
            ?: throw CommandException.NotFound(R.string.msg_not_found_party)
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
        if (type !in setOf("CUSTOMER", "SUPPLIER", "BOTH")) throw CommandException.Validation(R.string.msg_party_type_unknown)
        return type
    }
}
