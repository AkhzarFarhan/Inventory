package com.shopledger.pos.ui.screens.stockin

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.shopledger.pos.BuildConfig
import com.shopledger.pos.R
import com.shopledger.pos.ui.text.UiText
import com.shopledger.pos.core.error.AppErrorManager
import com.shopledger.pos.core.nlp.ShopVertical
import com.shopledger.pos.core.scanner.OnDeviceOrderScannerManager
import com.shopledger.pos.core.scanner.MatchStatus
import com.shopledger.pos.core.scanner.ParsedStockLine
import com.shopledger.pos.core.scanner.StockLineMatch
import com.shopledger.pos.core.scanner.StockLineMatcher
import com.shopledger.pos.core.scanner.StockListParser
import com.shopledger.pos.core.sync.SyncManager
import com.shopledger.pos.data.command.CommandException
import com.shopledger.pos.data.command.Role
import com.shopledger.pos.data.command.StockInRequest
import com.shopledger.pos.data.command.StockInResult
import com.shopledger.pos.data.command.StockInRow
import com.shopledger.pos.data.local.entity.ItemVariantEntity
import com.shopledger.pos.data.local.entity.PartyEntity
import com.shopledger.pos.data.model.InventoryItemWithVariants
import com.shopledger.pos.data.repository.LpsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import kotlin.math.roundToLong

/** The one thing the review screen needs from the data layer. */
fun interface StockInSaver {
    suspend fun receive(request: StockInRequest): StockInResult
}

enum class ReviewFilter { ALL, NEW, CHECK }

data class SupplierOption(val id: String, val name: String)

data class ReviewRow(
    val key: Long,
    val status: MatchStatus,
    val itemId: String?,
    val variantId: String?,
    /** Matched item's name, or the (editable) name of the new item. */
    val itemName: String,
    val variantSummary: String,
    /** What the bill/list called it. */
    val scannedName: String,
    val unit: String,
    val qtyMilli: Long?,
    val costPaise: Long?,
    /** New items: the price to sell at. Existing items: today's price, shown read-only. */
    val sellPaise: Long?,
    val currentCostPaise: Long?,
    /** The rate printed on the bill, if any. */
    val scannedRatePaise: Long?,
    /** True once the user typed (or cleared) the sell price, so it is never overwritten by a suggestion. */
    val sellEdited: Boolean = false,
    /** True while [sellPaise] is a suggestion made from the cost, so it follows the cost. */
    val sellAuto: Boolean = false,
) : java.io.Serializable

enum class BlockKind { EMPTY, QTY, NAME, COST, COST_OWNER, SELL, CHECK, DUPLICATE, SUPPLIER, PAID }

data class BlockReason(val kind: BlockKind, val rowLabel: String? = null)

data class StockInUiState(
    val actionId: String,
    val rows: List<ReviewRow>,
    val filter: ReviewFilter = ReviewFilter.ALL,
    val suppliers: List<SupplierOption> = emptyList(),
    val supplierId: String? = null,
    val newSupplierName: String = "",
    val billTotalPaise: Long? = null,
    val amountPaidPaise: Long? = null,
    val paidMode: String = "CASH",
    val billNumber: String = "",
    val isManual: Boolean = false,
    /** True once the user typed the paid amount; until then it follows the bill total (when there is no supplier). */
    val paidEdited: Boolean = false,
    val saving: Boolean = false,
    val saved: StockInResult? = null,
    val error: UiText? = null,
    /** True when [error] is a generic "could not save" message rather than a plain explanation. */
    val errorIsGeneric: Boolean = false,
    val role: Role = Role.OWNER,
    val inventory: List<InventoryItemWithVariants> = emptyList(),
    val imageUri: Uri? = null,
) {
    val newCount: Int get() = rows.count { it.status == MatchStatus.NEW }
    val checkCount: Int get() = rows.count { it.status == MatchStatus.CHECK }

    val visibleRows: List<ReviewRow>
        get() = when (filter) {
            ReviewFilter.ALL -> rows
            ReviewFilter.NEW -> rows.filter { it.status == MatchStatus.NEW }
            ReviewFilter.CHECK -> rows.filter { it.status == MatchStatus.CHECK }
        }

    /** Staff may not change the cost of an item that already exists. */
    fun canEditCost(row: ReviewRow): Boolean = role == Role.OWNER || row.variantId == null

    fun rowProblem(row: ReviewRow): BlockKind? {
        val duplicate = (row.variantId != null && rows.count { it.variantId == row.variantId } > 1) ||
            (row.variantId == null && row.status == MatchStatus.NEW && row.itemName.isNotBlank() &&
                rows.count { it.variantId == null && it.status == MatchStatus.NEW && it.itemName.trim().equals(row.itemName.trim(), ignoreCase = true) } > 1)
        return when {
            (row.qtyMilli ?: 0L) <= 0L -> BlockKind.QTY
            row.status == MatchStatus.NEW && row.itemName.isBlank() -> BlockKind.NAME
            (row.costPaise ?: 0L) <= 0L -> if (canEditCost(row)) BlockKind.COST else BlockKind.COST_OWNER
            row.status == MatchStatus.NEW && row.sellPaise == null -> BlockKind.SELL
            row.status == MatchStatus.CHECK -> BlockKind.CHECK
            duplicate -> BlockKind.DUPLICATE
            else -> null
        }
    }

    val hasSupplier: Boolean get() = supplierId != null || newSupplierName.isNotBlank()

    /** With no supplier the bill is paid in full: until the user edits paid, it follows the total again. */
    fun withPaidFollowing(next: StockInUiState): StockInUiState =
        if (!next.hasSupplier && !next.paidEdited) next.copy(amountPaidPaise = next.billTotalPaise ?: 0L) else next

    val blockReason: BlockReason?
        get() {
            if (rows.isEmpty()) return BlockReason(BlockKind.EMPTY)
            for (row in rows) {
                val kind = rowProblem(row) ?: continue
                return BlockReason(kind, if (kind == BlockKind.CHECK) row.scannedName else row.itemName.ifBlank { row.scannedName })
            }
            val total = billTotalPaise ?: 0L
            val paid = amountPaidPaise ?: 0L
            if (paid > total) return BlockReason(BlockKind.PAID)
            if (total > paid && !hasSupplier) return BlockReason(BlockKind.SUPPLIER)
            return null
        }

    fun buildRequest(): StockInRequest = StockInRequest(
        actionId = actionId,
        rows = rows.map { r ->
            StockInRow(
                existingVariantId = r.variantId,
                newItemName = if (r.variantId == null) r.itemName.trim() else "",
                unit = r.unit,
                qtyMilli = requireNotNull(r.qtyMilli) { "quantity is checked before saving" },
                costPaise = requireNotNull(r.costPaise) { "cost is checked before saving" },
                sellPaise = if (r.variantId == null) r.sellPaise else null,
            )
        },
        supplierPartyId = supplierId,
        newSupplierName = if (supplierId == null) newSupplierName.trim().ifBlank { null } else null,
        billTotalPaise = billTotalPaise ?: 0L,
        amountPaidPaise = amountPaidPaise ?: 0L,
        paidMode = paidMode,
        billNumber = billNumber.trim(),
    )
}

sealed interface StockInEvent {
    data class SetFilter(val filter: ReviewFilter) : StockInEvent
    data class EditQty(val key: Long, val qtyMilli: Long?) : StockInEvent
    data class EditCost(val key: Long, val paise: Long?) : StockInEvent
    data class EditSell(val key: Long, val paise: Long?) : StockInEvent
    data class EditName(val key: Long, val name: String) : StockInEvent
    data class DeleteRow(val key: Long) : StockInEvent
    data class PickVariant(val key: Long, val itemId: String, val variantId: String) : StockInEvent
    data class AddRow(val itemId: String, val variantId: String) : StockInEvent
    data class MakeNew(val key: Long) : StockInEvent
    data class SetSupplier(val id: String?) : StockInEvent
    data class SetNewSupplier(val name: String) : StockInEvent
    data class SetBillTotal(val paise: Long?) : StockInEvent
    data class SetPaid(val paise: Long?) : StockInEvent
    data class SetPaidMode(val mode: String) : StockInEvent
    data class SetBillNumber(val text: String) : StockInEvent
    data object Save : StockInEvent
    data object DismissError : StockInEvent
    data object Close : StockInEvent
    data object Rescan : StockInEvent
    data object AddManually : StockInEvent
}

/** "12" -> 12000, "2.5" -> 2500; null when it is not a number. */
fun parseQtyMilli(text: String): Long? {
    val d = text.trim().replace(",", "").toBigDecimalOrNull() ?: return null
    if (d.signum() < 0 || d > BigDecimal("999999999")) return null
    return d.multiply(BigDecimal(1000)).setScale(0, RoundingMode.HALF_UP).toLong()
}

/** "12.5" -> 1250 paise, computed exactly (no floating point); null when it is not a number. */
fun parseMoneyPaise(text: String): Long? {
    val d = text.trim().replace(",", "").toBigDecimalOrNull() ?: return null
    if (d.signum() < 0 || d > BigDecimal("999999999")) return null
    return d.movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()
}

/** Sell price to suggest for a new item: cost plus the shop's usual markup, in whole rupees; null when there is no cost. */
fun suggestSellPaise(costPaise: Long?, inventory: List<InventoryItemWithVariants>): Long? {
    if (costPaise == null || costPaise <= 0L) return null
    val markup = StockLineMatcher.medianMarkup(inventory)
    val rupees = (costPaise * (1.0 + markup) / 100.0).roundToLong()
    return if (rupees > 0L) rupees * 100L else null
}

/** What a bill adds up to when it prints no total: the amount of each line, or qty x rate where the amount is missing. */
fun sumOfLinesPaise(lines: List<ParsedStockLine>): Long? {
    if (lines.isEmpty()) return null
    var sum = 0L
    for (l in lines) {
        val amount = l.amountPaise ?: l.ratePaise?.let {
            BigDecimal(l.qtyMilli).multiply(BigDecimal(it)).divide(BigDecimal(1000), 0, RoundingMode.HALF_UP).toLong()
        } ?: return null
        sum += amount
    }
    return sum
}

/** What must survive the app being killed while the camera or gallery is open. */
internal data class StockInSnapshot(
    val actionId: String,
    val rows: ArrayList<ReviewRow>,
    val nextKey: Long,
    val filter: String,
    val supplierId: String?,
    val newSupplierName: String,
    val billTotalPaise: Long?,
    val amountPaidPaise: Long?,
    val paidEdited: Boolean,
    val imageUri: String?,
    val paidMode: String = "CASH",
    val billNumber: String = "",
    val isManual: Boolean = false,
) : java.io.Serializable

class StockInViewModel(
    private val saver: StockInSaver,
    private val role: () -> Role = { Role.OWNER },
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val savedState: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {

    private val lock = Any()
    private val _state = MutableStateFlow<StockInUiState?>(null)
    /** null = no review open. */
    val state: StateFlow<StockInUiState?> = _state.asStateFlow()

    private val photoNotRead = UiText.Res(R.string.err_ocr_title)

    private val _reading = MutableStateFlow(false)
    /** True while a photo is being read (OCR and tidy-up). It lives here so it survives rotation. */
    val reading: StateFlow<Boolean> = _reading.asStateFlow()

    // All three are guarded by [lock].
    private var suppliers: List<SupplierOption> = emptyList()
    private var inventory: List<InventoryItemWithVariants> = emptyList()
    private var nextKey = 1L

    init {
        savedState.get<StockInSnapshot>(SNAPSHOT_KEY)?.let { snap ->
            nextKey = snap.nextKey
            _state.value = StockInUiState(
                actionId = snap.actionId,
                rows = snap.rows.toList(),
                filter = runCatching { ReviewFilter.valueOf(snap.filter) }.getOrDefault(ReviewFilter.ALL),
                supplierId = snap.supplierId,
                newSupplierName = snap.newSupplierName,
                billTotalPaise = snap.billTotalPaise,
                amountPaidPaise = snap.amountPaidPaise,
                paidMode = snap.paidMode,
                billNumber = snap.billNumber,
                isManual = snap.isManual,
                paidEdited = snap.paidEdited,
                role = role(),
                imageUri = snap.imageUri?.let { Uri.parse(it) },
            )
        }
    }

    /** Every state change goes through here so the saved copy never lags behind. Call with [lock] held. */
    private fun publish(next: StockInUiState?) {
        _state.value = next
        if (next == null || next.saved != null) {
            savedState.remove<StockInSnapshot>(SNAPSHOT_KEY)
        } else {
            savedState[SNAPSHOT_KEY] = StockInSnapshot(
                actionId = next.actionId,
                rows = ArrayList(next.rows),
                nextKey = nextKey,
                filter = next.filter.name,
                supplierId = next.supplierId,
                newSupplierName = next.newSupplierName,
                billTotalPaise = next.billTotalPaise,
                amountPaidPaise = next.amountPaidPaise,
                paidEdited = next.paidEdited,
                imageUri = next.imageUri?.toString(),
                paidMode = next.paidMode,
                billNumber = next.billNumber,
                isManual = next.isManual,
            )
        }
    }

    fun setSuppliers(parties: List<PartyEntity>) {
        val options = parties
            .filter { !it.isDeleted && (it.partyType == "SUPPLIER" || it.partyType == "BOTH") }
            .map { SupplierOption(it.id, it.name) }
        synchronized(lock) {
            suppliers = options
            _state.value?.let { publish(it.copy(suppliers = options)) }
        }
    }

    /** The catalogue the review matches against; this also fills it back in after the app was restarted. */
    fun setInventory(items: List<InventoryItemWithVariants>) {
        synchronized(lock) {
            inventory = items
            _state.value?.let { publish(it.copy(inventory = items)) }
        }
    }

    /**
     * Parse OCR text, match it against the catalogue and open the review. Safe to call off the main thread.
     * [text] is parsed into lines (it may have been tidied by the on-device model); the printed bill total is read
     * from [rawText], the plain OCR text, and falls back to the sum of the lines.
     */
    fun open(
        text: String,
        inventory: List<InventoryItemWithVariants>,
        imageUri: Uri?,
        vertical: ShopVertical = SyncManager.getActiveVertical(),
        rawText: String = text,
    ) {
        val lines = StockListParser.parse(text)
        val matches = StockLineMatcher.match(lines, inventory, vertical)
        val total = StockListParser.detectPrintedTotalPaise(rawText)
            ?: StockListParser.detectPrintedTotalPaise(text)
            ?: sumOfLinesPaise(lines)
        openMatches(matches, inventory, total, imageUri)
    }

    /** Same as [open], parsing off the main thread. */
    fun openAsync(text: String, rawText: String, inventory: List<InventoryItemWithVariants>, imageUri: Uri?) {
        viewModelScope.launch(Dispatchers.Default) { open(text, inventory, imageUri, rawText = rawText) }
    }

    /**
     * OCR the given photos on this device, tidy the text with the optional model and open the review.
     * Runs in [viewModelScope], so rotating the phone while it works does not lose it.
     */
    fun readPhotos(context: Context, uris: List<Uri>) {
        if (uris.isEmpty() || !_reading.compareAndSet(false, true)) return
        val app = context.applicationContext
        viewModelScope.launch {
            try {
                val combined = StringBuilder()
                var firstError: Throwable? = null
                for (uri in uris) {
                    OnDeviceOrderScannerManager.extractTextFromUri(app, uri)
                        .onSuccess { if (it.isNotBlank()) combined.appendLine(it) }
                        .onFailure { if (firstError == null) firstError = it }
                }
                val raw = combined.toString().trim()
                val error = firstError
                if (raw.isBlank() && error != null) {
                    AppErrorManager.reportError(photoNotRead, UiText.Res(R.string.stock_in_scan_failed), error)
                } else {
                    val text = if (raw.isBlank()) raw else OnDeviceOrderScannerManager.extractStockLines(app, raw) ?: raw
                    withContext(Dispatchers.Default) { open(text, currentInventory(), uris.first(), rawText = raw) }
                }
            } finally {
                _reading.value = false
            }
        }
    }

    /** Same as [readPhotos] for the single preview bitmap some cameras return. */
    fun readBitmap(context: Context, bitmap: Bitmap) {
        if (!_reading.compareAndSet(false, true)) return
        val app = context.applicationContext
        viewModelScope.launch {
            try {
                OnDeviceOrderScannerManager.extractTextFromBitmap(bitmap)
                    .onSuccess { raw ->
                        val text = if (raw.isBlank()) raw else OnDeviceOrderScannerManager.extractStockLines(app, raw) ?: raw
                        withContext(Dispatchers.Default) { open(text, currentInventory(), null, rawText = raw) }
                    }
                    .onFailure { AppErrorManager.reportError(photoNotRead, UiText.Res(R.string.stock_in_scan_failed), it) }
            } finally {
                _reading.value = false
            }
        }
    }

    private fun currentInventory(): List<InventoryItemWithVariants> = synchronized(lock) { inventory }

    fun openMatches(
        matches: List<StockLineMatch>,
        inventory: List<InventoryItemWithVariants>,
        billTotalPaise: Long?,
        imageUri: Uri?,
    ) {
        val currentRole = role()
        synchronized(lock) {
            this.inventory = inventory
            val rows = matches.map { buildRow(it, inventory, currentRole) }
            publish(
                StockInUiState(
                    actionId = newId(),
                    rows = rows,
                    suppliers = suppliers,
                    billTotalPaise = billTotalPaise,
                    // With no supplier yet the bill is paid in full, so paid starts at the total.
                    amountPaidPaise = billTotalPaise ?: 0L,
                    role = currentRole,
                    inventory = inventory,
                    imageUri = imageUri,
                )
            )
        }
    }

    fun openManual() {
        val currentRole = role()
        synchronized(lock) {
            publish(
                StockInUiState(
                    actionId = newId(),
                    rows = emptyList(),
                    suppliers = suppliers,
                    billTotalPaise = null,
                    amountPaidPaise = 0L,
                    role = currentRole,
                    inventory = inventory,
                    imageUri = null,
                    isManual = true,
                )
            )
        }
    }

    fun close() {
        synchronized(lock) { publish(null) }
    }

    private fun variantSummary(v: ItemVariantEntity): String =
        listOf(v.sizeLabel, v.materialLabel, v.colorFinishLabel).filter { it.isNotBlank() }.joinToString(" · ")

    /** Call with [lock] held: it hands out row keys. */
    private fun buildRow(m: StockLineMatch, inventory: List<InventoryItemWithVariants>, role: Role): ReviewRow {
        val key = nextKey++
        val item = m.itemId?.let { id -> inventory.firstOrNull { it.item.id == id } }
        val variant = m.variantId?.let { id -> item?.allVariants?.firstOrNull { it.id == id } }
        if (m.status == MatchStatus.NEW || item == null || variant == null) {
            return ReviewRow(
                key = key, status = MatchStatus.NEW, itemId = null, variantId = null,
                itemName = m.line.name, variantSummary = "", scannedName = m.line.name,
                unit = m.line.unit ?: "pcs", qtyMilli = m.line.qtyMilli,
                costPaise = m.line.ratePaise, sellPaise = m.suggestedSellPaise,
                currentCostPaise = null, scannedRatePaise = m.line.ratePaise,
                sellAuto = m.suggestedSellPaise != null,
            )
        }
        return ReviewRow(
            key = key, status = m.status, itemId = item.item.id, variantId = variant.id,
            itemName = item.item.displayName, variantSummary = variantSummary(variant),
            scannedName = m.line.name, unit = variant.unit, qtyMilli = m.line.qtyMilli,
            costPaise = costForExisting(role, m.line.ratePaise, variant.purchasePricePaise),
            sellPaise = variant.sellingPricePaise,
            currentCostPaise = variant.purchasePricePaise, scannedRatePaise = m.line.ratePaise,
        )
    }

    /** Staff keep the current cost; the owner takes the bill's rate when there is one. */
    private fun costForExisting(role: Role, rate: Long?, current: Long): Long =
        if (role == Role.STAFF) current else rate ?: current

    private fun updateRow(key: Long, transform: (ReviewRow) -> ReviewRow) = update { s ->
        s.copy(rows = s.rows.map { if (it.key == key) transform(it) else it })
    }

    private fun update(transform: (StockInUiState) -> StockInUiState) = synchronized(lock) {
        val s = _state.value ?: return@synchronized
        if (s.saving || s.saved != null) return@synchronized
        publish(transform(s))
    }

    fun onEvent(event: StockInEvent) {
        when (event) {
            is StockInEvent.SetFilter -> update { it.copy(filter = event.filter) }
            is StockInEvent.EditQty -> updateRow(event.key) { it.copy(qtyMilli = event.qtyMilli) }
            is StockInEvent.EditCost -> update { s ->
                s.copy(rows = s.rows.map { r ->
                    if (r.key != event.key || !s.canEditCost(r)) r else {
                        // A new item's sell price follows its cost until the user types a sell price.
                        val follows = r.variantId == null && !r.sellEdited && (r.sellPaise == null || r.sellAuto)
                        if (!follows) r.copy(costPaise = event.paise) else {
                            val suggestion = suggestSellPaise(event.paise, s.inventory)
                            r.copy(costPaise = event.paise, sellPaise = suggestion, sellAuto = suggestion != null)
                        }
                    }
                })
            }
            is StockInEvent.EditSell -> updateRow(event.key) {
                if (it.variantId == null) it.copy(sellPaise = event.paise, sellEdited = true, sellAuto = false) else it
            }
            is StockInEvent.EditName -> updateRow(event.key) { if (it.variantId == null) it.copy(itemName = event.name) else it }
            is StockInEvent.DeleteRow -> update { s -> s.copy(rows = s.rows.filterNot { it.key == event.key }) }
            is StockInEvent.PickVariant -> update { s ->
                val item = s.inventory.firstOrNull { it.item.id == event.itemId }
                val variant = item?.allVariants?.firstOrNull { it.id == event.variantId }
                if (item == null || variant == null) s else s.copy(rows = s.rows.map { r ->
                    if (r.key != event.key) r else r.copy(
                        status = MatchStatus.MATCHED, itemId = item.item.id, variantId = variant.id,
                        itemName = item.item.displayName, variantSummary = variantSummary(variant),
                        unit = variant.unit, sellPaise = variant.sellingPricePaise, sellEdited = false, sellAuto = false,
                        costPaise = costForExisting(s.role, r.scannedRatePaise, variant.purchasePricePaise),
                        currentCostPaise = variant.purchasePricePaise,
                    )
                })
            }
            is StockInEvent.AddRow -> update { s ->
                val item = s.inventory.firstOrNull { it.item.id == event.itemId }
                val variant = item?.allVariants?.firstOrNull { it.id == event.variantId }
                if (item == null || variant == null) s else {
                    val existing = s.rows.firstOrNull { it.variantId == variant.id }
                    if (existing != null) {
                        s.copy(
                            rows = s.rows.map { r ->
                                if (r.key != existing.key) r else r.copy(qtyMilli = (r.qtyMilli ?: 0L) + 1_000L)
                            }
                        )
                    } else {
                        val row = ReviewRow(
                            key = nextKey++,
                            status = MatchStatus.MATCHED,
                            itemId = item.item.id,
                            variantId = variant.id,
                            itemName = item.item.displayName,
                            variantSummary = variantSummary(variant),
                            scannedName = item.item.displayName,
                            unit = variant.unit,
                            qtyMilli = 1_000L,
                            costPaise = variant.purchasePricePaise,
                            sellPaise = variant.sellingPricePaise,
                            currentCostPaise = variant.purchasePricePaise,
                            scannedRatePaise = null,
                        )
                        s.copy(rows = s.rows + row)
                    }
                }
            }
            is StockInEvent.MakeNew -> update { s ->
                s.copy(rows = s.rows.map { r ->
                    if (r.key != event.key) r else {
                        val suggestion = suggestSellPaise(r.scannedRatePaise, s.inventory)
                        r.copy(
                            status = MatchStatus.NEW, itemId = null, variantId = null,
                            itemName = r.scannedName, variantSummary = "", sellPaise = suggestion,
                            sellEdited = false, sellAuto = suggestion != null,
                            costPaise = r.scannedRatePaise, currentCostPaise = null,
                        )
                    }
                })
            }
            is StockInEvent.SetSupplier -> update {
                it.withPaidFollowing(it.copy(supplierId = event.id, newSupplierName = if (event.id != null) "" else it.newSupplierName))
            }
            is StockInEvent.SetNewSupplier -> update { it.withPaidFollowing(it.copy(newSupplierName = event.name, supplierId = null)) }
            is StockInEvent.SetBillTotal -> update { s ->
                // With no supplier the bill is paid in full, so paid follows the total until the user edits it.
                val follows = !s.hasSupplier && !s.paidEdited
                s.copy(billTotalPaise = event.paise, amountPaidPaise = if (follows) event.paise ?: 0L else s.amountPaidPaise)
            }
            is StockInEvent.SetPaid -> update { it.copy(amountPaidPaise = event.paise, paidEdited = true) }
            is StockInEvent.SetPaidMode -> update {
                it.copy(paidMode = if (event.mode.equals("UPI", ignoreCase = true)) "UPI" else "CASH")
            }
            is StockInEvent.SetBillNumber -> update { it.copy(billNumber = event.text.take(40)) }
            StockInEvent.Save -> viewModelScope.launch { save() }
            StockInEvent.DismissError -> synchronized(lock) {
                _state.value?.let { publish(it.copy(error = null, errorIsGeneric = false)) }
            }
            StockInEvent.Close -> close()
            StockInEvent.Rescan, StockInEvent.AddManually -> Unit // handled by the host, which closes the review
        }
    }

    suspend fun save() {
        val request = synchronized(lock) {
            val s = _state.value ?: return
            if (s.saving || s.saved != null || s.blockReason != null) return
            publish(s.copy(saving = true, error = null, errorIsGeneric = false))
            s.buildRequest()
        }
        try {
            val result = saver.receive(request)
            synchronized(lock) { _state.value?.let { publish(it.copy(saving = false, saved = result)) } }
        } catch (e: CancellationException) {
            synchronized(lock) { _state.value?.let { publish(it.copy(saving = false)) } }
            throw e
        } catch (e: CommandException) {
            synchronized(lock) { _state.value?.let { publish(it.copy(saving = false, error = e.uiText, errorIsGeneric = false)) } }
        } catch (t: Throwable) {
            val detail = if (BuildConfig.DEBUG) t.localizedMessage.orEmpty() else ""
            synchronized(lock) { _state.value?.let { publish(it.copy(saving = false, error = UiText.Raw(detail), errorIsGeneric = true)) } }
        }
    }

    class Factory(private val repository: LpsRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
            StockInViewModel(
                saver = { repository.receiveScannedStock(it) },
                role = { repository.currentRole() },
                savedState = extras.createSavedStateHandle(),
            ) as T
    }

    private companion object {
        const val SNAPSHOT_KEY = "stock_in_review"
    }
}
