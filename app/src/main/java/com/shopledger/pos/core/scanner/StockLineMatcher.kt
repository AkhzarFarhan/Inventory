package com.shopledger.pos.core.scanner

import com.shopledger.pos.core.nlp.ShopVertical
import com.shopledger.pos.core.nlp.SmartItemMatcher
import com.shopledger.pos.core.sync.SyncManager
import com.shopledger.pos.data.model.InventoryItemWithVariants
import java.util.Locale
import kotlin.math.roundToLong

enum class MatchStatus { MATCHED, CHECK, NEW }

data class StockLineMatch(
    val line: ParsedStockLine,
    val status: MatchStatus,
    val itemId: String?,
    val variantId: String?,
    val currentCostPaise: Long?,
    val currentSellPaise: Long?,
    /** For NEW: cost x (1 + median markup), whole rupees. */
    val suggestedSellPaise: Long?
)

object StockLineMatcher {
    const val HIGH_SCORE = 1200.0
    const val LOW_SCORE = 500.0
    const val LEAD_RATIO = 1.25
    const val DEFAULT_MARKUP = 0.25

    fun match(
        lines: List<ParsedStockLine>,
        inventory: List<InventoryItemWithVariants>,
        vertical: ShopVertical = SyncManager.getActiveVertical()
    ): List<StockLineMatch> {
        val markup = medianMarkup(inventory)
        val candidates = inventory.filter { it.variants.isNotEmpty() }
        val results = ArrayList<StockLineMatch>()
        val byVariant = HashMap<String, Int>()

        for (line in lines) {
            val queries = LinkedHashSet<String>()
            queries.add(line.name)
            val stripped = line.name.split(' ')
                .filter { it.isNotBlank() && it.lowercase(Locale.ENGLISH) !in OnDeviceOrderScannerManager.KNOWN_UNITS }
                .joinToString(" ")
            if (stripped.isNotBlank()) queries.add(stripped)

            var best: InventoryItemWithVariants? = null
            var bestScore = 0.0
            var second = 0.0
            for (item in candidates) {
                val score = queries.maxOf { SmartItemMatcher.calculateSearchRelevanceScore(it, item, vertical) }
                if (score > bestScore) {
                    second = bestScore
                    bestScore = score
                    best = item
                } else if (score > second) {
                    second = score
                }
            }

            val variant = best?.let { OnDeviceOrderScannerManager.pickBestVariant(it, line.name) }
            val status = when {
                best == null || variant == null || bestScore < LOW_SCORE -> MatchStatus.NEW
                bestScore >= HIGH_SCORE && bestScore >= LEAD_RATIO * second && line.consistent -> MatchStatus.MATCHED
                else -> MatchStatus.CHECK
            }

            if (best == null || variant == null || status == MatchStatus.NEW) {
                val suggested = line.ratePaise?.let { wholeRupees(it * (1.0 + markup)) }
                results.add(StockLineMatch(line, MatchStatus.NEW, null, null, null, null, suggested))
                continue
            }
            val m = StockLineMatch(
                line, status, best.item.id, variant.id,
                variant.purchasePricePaise, variant.sellingPricePaise, null
            )
            val prior = byVariant[variant.id]
            if (prior == null) {
                byVariant[variant.id] = results.size
                results.add(m)
            } else {
                results[prior] = merge(results[prior], m)
            }
        }
        return results
    }

    private fun merge(a: StockLineMatch, b: StockLineMatch): StockLineMatch {
        val ra = a.line.ratePaise
        val rb = b.line.ratePaise
        val rateDiffers = ra != null && rb != null && ra != rb
        val amountA = a.line.amountPaise
        val amountB = b.line.amountPaise
        val line = a.line.copy(
            qtyMilli = a.line.qtyMilli + b.line.qtyMilli,
            unit = a.line.unit ?: b.line.unit,
            ratePaise = rb ?: ra,
            amountPaise = if (amountA != null && amountB != null) amountA + amountB else null,
            consistent = a.line.consistent && b.line.consistent,
            rawLine = a.line.rawLine + "\n" + b.line.rawLine
        )
        val status =
            if (rateDiffers || a.status == MatchStatus.CHECK || b.status == MatchStatus.CHECK) MatchStatus.CHECK
            else MatchStatus.MATCHED
        return a.copy(line = line, status = status)
    }

    private fun wholeRupees(paise: Double): Long = (paise / 100.0).roundToLong() * 100L

    fun medianMarkup(inventory: List<InventoryItemWithVariants>): Double {
        val m = inventory.flatMap { it.variants }
            .filter { it.purchasePricePaise > 0L && it.sellingPricePaise > 0L }
            .map { it.sellingPricePaise.toDouble() / it.purchasePricePaise - 1.0 }
            .sorted()
        if (m.isEmpty()) return DEFAULT_MARKUP
        val mid = m.size / 2
        return if (m.size % 2 == 1) m[mid] else (m[mid - 1] + m[mid]) / 2.0
    }
}
