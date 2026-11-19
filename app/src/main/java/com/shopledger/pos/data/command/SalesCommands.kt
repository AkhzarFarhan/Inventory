package com.shopledger.pos.data.command

import com.shopledger.pos.core.format.IndianCurrencyFormatter
import com.shopledger.pos.core.ledger.CalendarParts
import com.shopledger.pos.core.ledger.InvoiceMath
import com.shopledger.pos.core.ledger.ReceiptNumbers
import com.shopledger.pos.data.local.LpsDatabase
import com.shopledger.pos.data.local.entity.DeviceEntity
import com.shopledger.pos.data.local.entity.InvoiceHeaderEntity
import com.shopledger.pos.data.local.entity.InvoiceLineEntity
import com.shopledger.pos.data.local.entity.PartyEntity
import java.util.Locale

object DocType {
    const val SALE = "SALE"
    const val CREDIT_NOTE = "CREDIT_NOTE"
}

data class CheckoutLine(val variantId: String, val qtyMilli: Long, val unitSellPaise: Long)

data class CheckoutRequest(
    val actionId: String,
    val lines: List<CheckoutLine>,
    val buyerName: String = "",
    val buyerPhone: String = "",
    val paymentMode: String = "CASH",
    val discountPaise: Long = 0L,
    /** null = paid in full (or nothing for CREDIT). */
    val amountPaidPaise: Long? = null,
    val gstRateBps: Int = 0,
    val ownerPinVerified: Boolean = false
)

data class ReturnLine(val originalLineId: String, val qtyMilli: Long)

data class InvoiceResult(
    val header: InvoiceHeaderEntity,
    val lines: List<InvoiceLineEntity>,
    /** Variants this bill pushed below zero (warn the cashier to recount). */
    val oversoldVariantIds: List<String> = emptyList(),
    /** True when this result is a replay of an earlier identical action (double tap / retry). */
    val replayed: Boolean = false
)

class SalesCommands(
    private val db: LpsDatabase,
    private val env: CommandEnvironment,
    private val faults: FaultInjector = FaultInjector.NONE
) {
    private val writer = LedgerWriter(db, env)
    private val inventory = db.inventoryDao()
    private val ledger = db.shopPartyAndLedgerDao()
    private val devices = db.deviceDao()

    suspend fun checkout(request: CheckoutRequest): InvoiceResult =
        writer.runIdempotent(request.actionId, "CHECKOUT", replay = { loadInvoice(it) }) {
            if (request.lines.isEmpty()) throw CommandException.Validation("The cart is empty")
            if (request.lines.map { it.variantId }.toSet().size != request.lines.size) {
                throw CommandException.Validation("Each item can appear only once in a bill")
            }
            for (line in request.lines) {
                if (line.qtyMilli <= 0L) throw CommandException.Validation("Quantity must be more than 0")
                if (line.unitSellPaise < 0L) throw CommandException.Validation("Price cannot be negative")
            }
            val device = writer.localDevice()
            val now = env.now()
            val shopId = env.shopId()
            val actor = env.actor()

            // Read everything inside the transaction: prices, names and costs come from the database.
            val resolved = request.lines.map { line ->
                val variant = inventory.getVariantById(line.variantId)?.takeUnless { it.isDeleted }
                    ?: throw CommandException.NotFound("Item variant")
                val item = inventory.getItemById(variant.itemId) ?: throw CommandException.NotFound("Item")
                Triple(line, variant, item)
            }
            val lineTotals = resolved.map { (line, _, _) -> InvoiceMath.lineTotalPaise(line.unitSellPaise, line.qtyMilli) }
            val subtotal = lineTotals.sum()
            if (request.discountPaise < 0L || request.discountPaise > subtotal) {
                throw CommandException.Validation("Discount must be between 0 and the bill total")
            }
            val maxStaffBps = ledger.getShopProfile(shopId)?.maxStaffDiscountBps ?: 1000
            if (actor.role == Role.STAFF && !request.ownerPinVerified &&
                request.discountPaise * 10_000L > subtotal * maxStaffBps
            ) {
                throw CommandException.PermissionDenied("A discount above ${maxStaffBps / 100}% needs the owner PIN")
            }
            val gstBps = if (env.gstEnabled() && request.gstRateBps > 0) request.gstRateBps else 0
            val tax = IndianCurrencyFormatter.computeGstBreakdown(subtotal - request.discountPaise, gstBps)
            val grand = tax.grandTotalPaise
            val mode = request.paymentMode.trim().uppercase(Locale.ROOT).ifEmpty { "CASH" }
            val requestedPaid = request.amountPaidPaise
            val paid = when {
                requestedPaid != null -> requestedPaid.coerceIn(0L, grand)
                mode == "CREDIT" -> 0L
                else -> grand
            }
            val due = grand - paid
            val buyerName = request.buyerName.trim()
            val buyerPhone = request.buyerPhone.trim()
            val party = resolveParty(buyerName, buyerPhone, due, device)

            val parts = CalendarParts.of(now)
            val number = nextReceiptNumber(device, shopId, parts.dayKey)
            faults.at(CheckoutStep.NUMBER)

            val invoiceId = env.newId()
            val header = InvoiceHeaderEntity(
                id = invoiceId,
                shopId = shopId,
                deviceId = device.deviceId,
                deviceShortCode = device.deviceCode,
                invoiceNumber = number,
                docType = DocType.SALE,
                partyId = party?.id,
                buyerName = buyerName,
                buyerPhone = buyerPhone,
                paymentMode = mode,
                subtotalPaise = subtotal,
                discountPaise = request.discountPaise,
                cgstPaise = tax.cgstPaise,
                sgstPaise = tax.sgstPaise,
                igstPaise = tax.igstPaise,
                grandTotalPaise = grand,
                amountPaidPaise = paid,
                balanceDuePaise = due,
                issuedAt = now,
                yearInt = parts.year,
                monthInt = parts.month,
                dayInt = parts.day,
                createdByUid = actor.uid
            )
            ledger.insertInvoiceHeader(header)
            faults.at(CheckoutStep.HEADER)

            val lines = resolved.mapIndexed { index, (line, variant, item) ->
                val total = lineTotals[index]
                val cost = InvoiceMath.lineTotalPaise(variant.purchasePricePaise, line.qtyMilli)
                InvoiceLineEntity(
                    id = env.newId(),
                    shopId = shopId,
                    invoiceId = invoiceId,
                    productId = item.id,
                    skuId = variant.id,
                    itemNameSnapshot = item.displayName,
                    variantSummarySnapshot = variant.buildVariantSummary(),
                    hsnSnapshot = item.defaultHsnCode,
                    qtyMilliUnits = line.qtyMilli,
                    unitSnapshot = variant.unit,
                    unitCostPaise = variant.purchasePricePaise,
                    unitSellPaise = line.unitSellPaise,
                    gstRateBps = gstBps,
                    lineTotalPaise = total,
                    lineProfitPaise = total - cost
                )
            }
            ledger.insertInvoiceLines(lines)
            faults.at(CheckoutStep.LINES)

            val oversold = ArrayList<String>()
            for (line in lines) {
                // Movements and ledger entries ride inside the INVOICE outbox document (enqueue = false).
                writer.appendMovement(line.skuId, -line.qtyMilliUnits, MovementType.SALE, invoiceId, "Sale #$number", device, enqueue = false)
                if (inventory.getVariantById(line.skuId)!!.stockMilliUnits < 0L) oversold += line.skuId
            }
            faults.at(CheckoutStep.MOVEMENTS)

            if (due > 0L) {
                writer.appendLedgerEntry(party!!.id, due, LedgerEntryType.CREDIT_BILL, mode, invoiceId, "Bill #$number", device, enqueue = false)
            }
            faults.at(CheckoutStep.LEDGER)

            writer.enqueue(OutboxDocType.INVOICE, invoiceId, OutboxOp.APPEND)
            faults.at(CheckoutStep.OUTBOX)

            InvoiceResult(header, lines, oversold) to invoiceId
        }

    suspend fun returnItems(actionId: String, originalInvoiceId: String, lines: List<ReturnLine>): InvoiceResult =
        writer.runIdempotent(actionId, "RETURN", replay = { loadInvoice(it) }) {
            if (lines.isEmpty()) throw CommandException.Validation("Choose at least one item to return")
            if (lines.map { it.originalLineId }.toSet().size != lines.size) {
                throw CommandException.Validation("Each item can appear only once in a return")
            }
            val original = ledger.getInvoiceById(originalInvoiceId)?.takeIf { it.docType == DocType.SALE }
                ?: throw CommandException.NotFound("Original bill")
            val originalLines = ledger.getInvoiceLines(original.id).associateBy { it.id }
            val device = writer.localDevice()
            val now = env.now()
            val creditNoteId = env.newId()

            val creditLines = lines.map { request ->
                if (request.qtyMilli <= 0L) throw CommandException.Validation("Return quantity must be more than 0")
                val sold = originalLines[request.originalLineId] ?: throw CommandException.NotFound("Bill line")
                val remaining = sold.qtyMilliUnits - ledger.sumReturnedMilliForLine(sold.id)
                if (request.qtyMilli > remaining) throw CommandException.ReturnExceedsSold(sold.itemNameSnapshot, remaining)
                val total = InvoiceMath.lineTotalPaise(sold.unitSellPaise, request.qtyMilli)
                val cost = InvoiceMath.lineTotalPaise(sold.unitCostPaise, request.qtyMilli)
                InvoiceLineEntity(
                    id = env.newId(),
                    shopId = original.shopId,
                    invoiceId = creditNoteId,
                    originalLineId = sold.id,
                    productId = sold.productId,
                    skuId = sold.skuId,
                    itemNameSnapshot = sold.itemNameSnapshot,
                    variantSummarySnapshot = sold.variantSummarySnapshot,
                    hsnSnapshot = sold.hsnSnapshot,
                    qtyMilliUnits = -request.qtyMilli,
                    unitSnapshot = sold.unitSnapshot,
                    unitCostPaise = sold.unitCostPaise,
                    unitSellPaise = sold.unitSellPaise,
                    gstRateBps = sold.gstRateBps,
                    lineTotalPaise = -total,
                    lineProfitPaise = -(total - cost)
                )
            }

            val returnGross = -creditLines.sumOf { it.lineTotalPaise }
            // Give back the same share of the bill discount as the share of goods returned.
            val discountShare = if (original.subtotalPaise > 0L) {
                (original.discountPaise * returnGross + original.subtotalPaise / 2) / original.subtotalPaise
            } else 0L
            val tax = IndianCurrencyFormatter.computeGstBreakdown(returnGross - discountShare, creditLines.first().gstRateBps)
            val creditTotal = tax.grandTotalPaise
            // Money owed on the original bill is cancelled first; only the rest is refunded in cash.
            val khataCredit = if (original.partyId != null) {
                minOf(creditTotal, maxOf(0L, original.balanceDuePaise - ledger.sumKhataCreditedForInvoice(original.id)))
            } else 0L
            val cashRefund = creditTotal - khataCredit

            val parts = CalendarParts.of(now)
            val number = nextReceiptNumber(device, original.shopId, parts.dayKey)
            val header = InvoiceHeaderEntity(
                id = creditNoteId,
                shopId = original.shopId,
                deviceId = device.deviceId,
                deviceShortCode = device.deviceCode,
                invoiceNumber = number,
                docType = DocType.CREDIT_NOTE,
                originalInvoiceId = original.id,
                partyId = original.partyId,
                buyerName = original.buyerName,
                buyerPhone = original.buyerPhone,
                paymentMode = original.paymentMode,
                subtotalPaise = -returnGross,
                discountPaise = -discountShare,
                cgstPaise = -tax.cgstPaise,
                sgstPaise = -tax.sgstPaise,
                igstPaise = -tax.igstPaise,
                grandTotalPaise = -creditTotal,
                amountPaidPaise = -cashRefund,
                balanceDuePaise = -khataCredit,
                issuedAt = now,
                yearInt = parts.year,
                monthInt = parts.month,
                dayInt = parts.day,
                createdByUid = env.actor().uid
            )
            ledger.insertInvoiceHeader(header)
            ledger.insertInvoiceLines(creditLines)
            for (line in creditLines) {
                writer.appendMovement(line.skuId, -line.qtyMilliUnits, MovementType.SALE_RETURN, creditNoteId,
                    "Return against #${original.invoiceNumber}", device, enqueue = false)
            }
            if (khataCredit > 0L) {
                writer.appendLedgerEntry(original.partyId!!, -khataCredit, LedgerEntryType.CREDIT_NOTE, original.paymentMode,
                    creditNoteId, "Return against #${original.invoiceNumber}", device, enqueue = false)
            }
            writer.enqueue(OutboxDocType.INVOICE, creditNoteId, OutboxOp.APPEND)
            InvoiceResult(header, creditLines) to creditNoteId
        }

    /**
     * Next receipt number for [dayKey]. Never trusts the counter alone: if the clock moved backwards the
     * counter was reset, so also look at the numbers already issued for this device and day.
     */
    private suspend fun nextReceiptNumber(device: DeviceEntity, shopId: String, dayKey: String): String {
        val prefix = "${device.deviceCode}-$dayKey-"
        val counter = if (device.lastSeqDate == dayKey) device.lastInvoiceSeq else 0L
        val highestIssued = ledger.getInvoiceNumbersWithPrefix(shopId, prefix)
            .mapNotNull { it.substring(prefix.length).toLongOrNull() }
            .maxOrNull() ?: 0L
        val next = maxOf(counter, highestIssued) + 1
        devices.setInvoiceSeq(device.deviceId, next, dayKey)
        return ReceiptNumbers.format(device.deviceCode, dayKey, next)
    }

    /** Same matching rules as v2: phone digits first, then exact name. Creates a party only when needed. */
    private suspend fun resolveParty(name: String, phone: String, due: Long, device: DeviceEntity): PartyEntity? {
        if (due <= 0L && phone.isEmpty()) return null
        val phoneDigits = phone.filter { it.isDigit() }.takeLast(10)
        val existing = ledger.getAllActivePartiesSnapshot().firstOrNull { p ->
            (phoneDigits.length >= 6 && p.phone.filter { it.isDigit() }.takeLast(10) == phoneDigits) ||
                (name.isNotEmpty() && p.name.equals(name, ignoreCase = true))
        }
        if (existing != null) return existing
        if (due <= 0L && name.isEmpty() && phoneDigits.length < 6) return null
        val now = env.now()
        val created = PartyEntity(
            id = env.newId(),
            shopId = env.shopId(),
            partyType = "CUSTOMER",
            name = name.ifEmpty { phone.ifEmpty { "Credit Customer" } },
            phone = if (phoneDigits.length >= 10) phoneDigits else phone,
            createdAt = now,
            updatedAt = now,
            createdByUid = env.actor().uid,
            deviceId = device.deviceId
        )
        ledger.insertParty(created)
        writer.enqueue(OutboxDocType.PARTY, created.id, OutboxOp.UPSERT)
        return created
    }

    private suspend fun loadInvoice(invoiceId: String): InvoiceResult {
        val header = ledger.getInvoiceById(invoiceId)
            ?: throw IllegalStateException("Applied action points to missing invoice $invoiceId")
        return InvoiceResult(header, ledger.getInvoiceLines(invoiceId), replayed = true)
    }
}
