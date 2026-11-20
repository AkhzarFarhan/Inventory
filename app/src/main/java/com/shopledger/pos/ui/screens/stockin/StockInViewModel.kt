package com.shopledger.pos.ui.screens.stockin

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.shopledger.pos.BuildConfig
import com.shopledger.pos.core.nlp.ShopVertical
import com.shopledger.pos.core.scanner.MatchStatus
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

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
)

enum class BlockKind { EMPTY, QTY, NAME, COST, SELL, CHECK, DUPLICATE, SUPPLIER, PAID }

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
    val saving: Boolean = false,
    val saved: StockInResult? = null,
    val error: String? = null,
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
        val duplicate = row.variantId != null && rows.count { it.variantId == row.variantId } > 1
        return when {
            (row.qtyMilli ?: 0L) <= 0L -> BlockKind.QTY
            row.status == MatchStatus.NEW && row.itemName.isBlank() -> BlockKind.NAME
            row.costPaise == null -> BlockKind.COST
            row.status == MatchStatus.NEW && row.sellPaise == null -> BlockKind.SELL
            row.status == MatchStatus.CHECK -> BlockKind.CHECK
            duplicate -> BlockKind.DUPLICATE
            else -> null
        }
    }

    private val hasSupplier: Boolean get() = supplierId != null || newSupplierName.isNotBlank()

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
                qtyMilli = r.qtyMilli ?: 0L,
                costPaise = r.costPaise ?: 0L,
                sellPaise = if (r.variantId == null) r.sellPaise else null,
            )
        },
        supplierPartyId = supplierId,
        newSupplierName = if (supplierId == null) newSupplierName.trim().ifBlank { null } else null,
        billTotalPaise = billTotalPaise ?: 0L,
        amountPaidPaise = amountPaidPaise ?: 0L,
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
    data class MakeNew(val key: Long) : StockInEvent
    data class SetSupplier(val id: String?) : StockInEvent
    data class SetNewSupplier(val name: String) : StockInEvent
    data class SetBillTotal(val paise: Long?) : StockInEvent
    data class SetPaid(val paise: Long?) : StockInEvent
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

class StockInViewModel(
    private val saver: StockInSaver,
    private val role: () -> Role = { Role.OWNER },
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {

    private val lock = Any()
    private val _state = MutableStateFlow<StockInUiState?>(null)
    /** null = no review open. */
    val state: StateFlow<StockInUiState?> = _state.asStateFlow()

    private var suppliers: List<SupplierOption> = emptyList()
    private var nextKey = 1L

    fun setSuppliers(parties: List<PartyEntity>) {
        suppliers = parties
            .filter { !it.isDeleted && (it.partyType == "SUPPLIER" || it.partyType == "BOTH") }
            .map { SupplierOption(it.id, it.name) }
        synchronized(lock) { _state.value = _state.value?.copy(suppliers = suppliers) }
    }

    /** Parse OCR text, match it against the catalogue and open the review. Safe to call off the main thread. */
    fun open(
        text: String,
        inventory: List<InventoryItemWithVariants>,
        imageUri: Uri?,
        vertical: ShopVertical = SyncManager.getActiveVertical(),
    ) {
        val matches = StockLineMatcher.match(StockListParser.parse(text), inventory, vertical)
        openMatches(matches, inventory, StockListParser.detectBillTotalPaise(text), imageUri)
    }

    fun openMatches(
        matches: List<StockLineMatch>,
        inventory: List<InventoryItemWithVariants>,
        billTotalPaise: Long?,
        imageUri: Uri?,
    ) {
        val currentRole = role()
        val rows = matches.map { buildRow(it, inventory, currentRole) }
        synchronized(lock) {
            _state.value = StockInUiState(
                actionId = newId(),
                rows = rows,
                suppliers = suppliers,
                billTotalPaise = billTotalPaise,
                amountPaidPaise = 0L,
                role = currentRole,
                inventory = inventory,
                imageUri = imageUri,
            )
        }
    }

    fun close() {
        synchronized(lock) { _state.value = null }
    }

    private fun variantSummary(v: ItemVariantEntity): String =
        listOf(v.sizeLabel, v.materialLabel, v.colorFinishLabel).filter { it.isNotBlank() }.joinToString(" · ")

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
        _state.value = transform(s)
    }

    fun onEvent(event: StockInEvent) {
        when (event) {
            is StockInEvent.SetFilter -> update { it.copy(filter = event.filter) }
            is StockInEvent.EditQty -> updateRow(event.key) { it.copy(qtyMilli = event.qtyMilli) }
            is StockInEvent.EditCost -> update { s ->
                s.copy(rows = s.rows.map { if (it.key == event.key && s.canEditCost(it)) it.copy(costPaise = event.paise) else it })
            }
            is StockInEvent.EditSell -> updateRow(event.key) { if (it.variantId == null) it.copy(sellPaise = event.paise) else it }
            is StockInEvent.EditName -> updateRow(event.key) { if (it.variantId == null) it.copy(itemName = event.name) else it }
            is StockInEvent.DeleteRow -> update { s -> s.copy(rows = s.rows.filterNot { it.key == event.key }) }
            is StockInEvent.PickVariant -> update { s ->
                val item = s.inventory.firstOrNull { it.item.id == event.itemId }
                val variant = item?.allVariants?.firstOrNull { it.id == event.variantId }
                if (item == null || variant == null) s else s.copy(rows = s.rows.map { r ->
                    if (r.key != event.key) r else r.copy(
                        status = MatchStatus.MATCHED, itemId = item.item.id, variantId = variant.id,
                        itemName = item.item.displayName, variantSummary = variantSummary(variant),
                        unit = variant.unit, sellPaise = variant.sellingPricePaise,
                        costPaise = costForExisting(s.role, r.scannedRatePaise, variant.purchasePricePaise),
                        currentCostPaise = variant.purchasePricePaise,
                    )
                })
            }
            is StockInEvent.MakeNew -> updateRow(event.key) { r ->
                r.copy(
                    status = MatchStatus.NEW, itemId = null, variantId = null,
                    itemName = r.scannedName, variantSummary = "", sellPaise = null,
                    costPaise = r.scannedRatePaise, currentCostPaise = null,
                )
            }
            is StockInEvent.SetSupplier -> update { it.copy(supplierId = event.id, newSupplierName = if (event.id != null) "" else it.newSupplierName) }
            is StockInEvent.SetNewSupplier -> update { it.copy(newSupplierName = event.name, supplierId = null) }
            is StockInEvent.SetBillTotal -> update { it.copy(billTotalPaise = event.paise) }
            is StockInEvent.SetPaid -> update { it.copy(amountPaidPaise = event.paise) }
            StockInEvent.Save -> viewModelScope.launch { save() }
            StockInEvent.DismissError -> synchronized(lock) { _state.value = _state.value?.copy(error = null, errorIsGeneric = false) }
            StockInEvent.Close -> close()
            StockInEvent.Rescan, StockInEvent.AddManually -> Unit // handled by the host, which closes the review
        }
    }

    suspend fun save() {
        val request = synchronized(lock) {
            val s = _state.value ?: return
            if (s.saving || s.saved != null || s.blockReason != null) return
            _state.value = s.copy(saving = true, error = null, errorIsGeneric = false)
            s.buildRequest()
        }
        try {
            val result = saver.receive(request)
            synchronized(lock) { _state.value = _state.value?.copy(saving = false, saved = result) }
        } catch (e: CancellationException) {
            synchronized(lock) { _state.value = _state.value?.copy(saving = false) }
            throw e
        } catch (e: CommandException) {
            synchronized(lock) { _state.value = _state.value?.copy(saving = false, error = e.message ?: "", errorIsGeneric = false) }
        } catch (t: Throwable) {
            val detail = if (BuildConfig.DEBUG) t.localizedMessage.orEmpty() else ""
            synchronized(lock) { _state.value = _state.value?.copy(saving = false, error = detail, errorIsGeneric = true) }
        }
    }

    class Factory(private val repository: LpsRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            StockInViewModel({ repository.receiveScannedStock(it) }) as T
    }
}
