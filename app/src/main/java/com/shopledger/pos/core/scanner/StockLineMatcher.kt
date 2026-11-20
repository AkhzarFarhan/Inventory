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

            var item = best
            var variant = if (best != null && bestScore >= LOW_SCORE) {
                OnDeviceOrderScannerManager.pickBestVariant(best, line.name)
            } else null
            var status = when {
                item == null || variant == null -> MatchStatus.NEW
                bestScore >= HIGH_SCORE && bestScore >= LEAD_RATIO * second && line.consistent &&
                    hasNameEvidence(line.name, item) -> MatchStatus.MATCHED
                else -> MatchStatus.CHECK
            }
            if (status == MatchStatus.NEW) {
                tokenFallback(line.name, candidates, vertical)?.let { (fi, fv) ->
                    item = fi
                    variant = fv
                    status = MatchStatus.CHECK
                }
            }

            val best2 = item
            if (best2 == null || variant == null || status == MatchStatus.NEW) {
                val suggested = line.ratePaise?.let { wholeRupees(it * (1.0 + markup)) }
                results.add(StockLineMatch(line, MatchStatus.NEW, null, null, null, null, suggested))
                continue
            }
            val m = StockLineMatch(
                line, status, best2.item.id, variant!!.id,
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

    private fun words(s: String): List<String> =
        Regex("[A-Za-z]+").findAll(s).map { it.value.lowercase(Locale.ENGLISH) }.toList()

    /**
     * True when the line's alphabetic non-unit tokens (>= 3 chars) include at least one and every one of them equals
     * or prefixes a word of the item's name, so a typo or an unexplained extra word cannot reach MATCHED.
     */
    private fun hasNameEvidence(lineName: String, item: InventoryItemWithVariants): Boolean {
        val itemWords = words(item.item.displayName)
        val tokens = words(lineName).filter { it.length >= 3 && it !in OnDeviceOrderScannerManager.KNOWN_UNITS }
        return tokens.isNotEmpty() && tokens.all { t -> itemWords.any { it.startsWith(t) } }
    }

    /** CHECK-only fallback: token overlap (>= 2 tokens, 1 token plus a variant size match, or a vowel-dropped abbreviation). */
    private fun tokenFallback(
        lineName: String,
        candidates: List<InventoryItemWithVariants>,
        vertical: ShopVertical
    ): Pair<InventoryItemWithVariants, com.shopledger.pos.data.local.entity.ItemVariantEntity>? {
        val tokens = lineName.split(Regex("\\s+")).filter {
            it.isNotBlank() && it.lowercase(Locale.ENGLISH) !in OnDeviceOrderScannerManager.KNOWN_UNITS
        }
        if (tokens.isEmpty()) return null
        val lower = lineName.lowercase(Locale.ENGLISH)
        var bestItem: InventoryItemWithVariants? = null
        var bestCount = 0
        for (item in candidates) {
            val n = OnDeviceOrderScannerManager.countTokenMatches(tokens, item, vertical)
            val abbr = tokens.any { isAbbreviationOf(it, item) }
            val sizeMatch = item.variants.any { v ->
                v.sizeLabel.lowercase(Locale.ENGLISH).split(' ').filter { it.length >= 2 }.any { lower.contains(it) }
            }
            val ok = n >= 2 || (n == 1 && sizeMatch) || abbr
            val total = n + (if (abbr) 1 else 0)
            if (ok && total > bestCount) {
                bestCount = total
                bestItem = item
            }
        }
        val found = bestItem ?: return null
        val v = OnDeviceOrderScannerManager.pickBestVariant(found, lineName) ?: return null
        return found to v
    }

    /** Vowel-dropped handwriting ("scrw" for "screw"): same first and last letter, token is a subsequence, length >= 4. */
    private fun isAbbreviationOf(token: String, item: InventoryItemWithVariants): Boolean {
        val t = token.lowercase(Locale.ENGLISH)
        if (t.length < 4 || t.any { !it.isLetter() }) return false
        return words(item.item.displayName).any { w ->
            w.length > t.length && w.first() == t.first() && w.last() == t.last() && run {
                var i = 0
                for (c in w) if (i < t.length && c == t[i]) i++
                i == t.length
            }
        }
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
        val unitDiffers = a.line.unit != null && b.line.unit != null && a.line.unit != b.line.unit
        val status =
            if (rateDiffers || unitDiffers || a.status == MatchStatus.CHECK || b.status == MatchStatus.CHECK) MatchStatus.CHECK
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
        val median = if (m.size % 2 == 1) m[mid] else (m[mid - 1] + m[mid]) / 2.0
        return maxOf(0.0, median)
    }
}
