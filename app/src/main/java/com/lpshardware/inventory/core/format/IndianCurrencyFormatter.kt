package com.lpshardware.inventory.core.format

import androidx.compose.runtime.Immutable
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

@Immutable
data class ProfitAnalysis(
    val unitPurchasePaise: Long,
    val unitSellingPaise: Long,
    val quantity: Double,
    val totalCostPaise: Long,
    val totalRevenuePaise: Long,
    val totalProfitPaise: Long,
    /** Markup / Gain % over Purchase Price: ((Selling - Purchase) / Purchase) * 100 */
    val gainMarkupPercent: Double,
    /** Gross Margin % over Selling Price: ((Selling - Purchase) / Selling) * 100 */
    val grossMarginPercent: Double
)

object IndianCurrencyFormatter {

    /**
     * Formats Paise (`Long`, ₹1 = 100L) into Indian Rupee representation (`₹1,25,450` or `₹450.50`).
     * Uses fast integer math with zero heavy `BigDecimal` allocations for smooth 60fps scrolling on low-RAM devices.
     */
    fun formatPaise(paise: Long, includeSymbol: Boolean = true, showPlusSign: Boolean = false): String {
        val isNegative = paise < 0
        val absPaise = abs(paise)
        val wholeRupees = absPaise / 100L
        val decimalPaise = (absPaise % 100L).toInt()

        val formattedWhole = formatIndianNumber(wholeRupees)
        val prefix = buildString {
            if (isNegative) append("-")
            else if (showPlusSign && paise > 0) append("+")
            if (includeSymbol) append("₹")
        }

        return if (decimalPaise == 0) {
            "$prefix$formattedWhole"
        } else if (decimalPaise % 10 == 0) {
            "$prefix$formattedWhole.${decimalPaise / 10}"
        } else {
            "$prefix$formattedWhole.${decimalPaise.toString().padStart(2, '0')}"
        }
    }

    /**
     * Applies Indian numbering commas (e.g. `1234567` -> `"12,34,567"`).
     */
    fun formatIndianNumber(number: Long): String {
        val raw = abs(number).toString()
        if (raw.length <= 3) return if (number < 0) "-$raw" else raw

        val lastThree = raw.substring(raw.length - 3)
        val remaining = raw.substring(0, raw.length - 3)
        val sb = StringBuilder(raw.length + 4)
        if (number < 0) sb.append('-')

        var i = 0
        val remLen = remaining.length
        if (remLen % 2 != 0) {
            sb.append(remaining[0])
            sb.append(',')
            i = 1
        }
        while (i < remLen) {
            sb.append(remaining[i])
            sb.append(remaining[i + 1])
            sb.append(',')
            i += 2
        }
        sb.append(lastThree)
        return sb.toString()
    }

    /**
     * Parses a user-entered Rupee string (e.g. `"450"`, `"1,250.50"`) into Paise (`Long`).
     */
    fun parseRupeesToPaise(input: String): Long {
        val cleaned = input.replace("₹", "").replace(",", "").trim()
        if (cleaned.isEmpty()) return 0L
        val value = cleaned.toDoubleOrNull() ?: return 0L
        return (value * 100.0).roundToLong()
    }

    /**
     * Converts Paise (`Long`) to plain numeric string for edit fields (e.g. `45000L` -> `"450"`).
     */
    fun paiseToEditableString(paise: Long): String {
        if (paise <= 0L) return ""
        val whole = paise / 100L
        val rem = (paise % 100L).toInt()
        return if (rem == 0) {
            whole.toString()
        } else if (rem % 10 == 0) {
            "$whole.${rem / 10}"
        } else {
            "$whole.${rem.toString().padStart(2, '0')}"
        }
    }

    /**
     * Formats quantity cleanly (e.g., `4.0` -> `"4"`, `1.5` -> `"1.5"`).
     */
    fun formatQuantity(qty: Double): String {
        val asLong = qty.toLong()
        return if (abs(qty - asLong.toDouble()) < 0.0001) {
            asLong.toString()
        } else {
            String.format(Locale.US, "%.2f", qty).trimEnd('0').trimEnd('.')
        }
    }

    /**
     * Formats percentage to 1 decimal place (e.g. `+36.4%` or `24%`).
     */
    fun formatPercentage(percent: Double, showPlus: Boolean = true): String {
        val prefix = if (showPlus && percent > 0.0) "+" else ""
        val roundedOneDec = (percent * 10.0).roundToLong() / 10.0
        val asLong = roundedOneDec.toLong()
        return if (abs(roundedOneDec - asLong.toDouble()) < 0.01) {
            "$prefix$asLong%"
        } else {
            "$prefix${String.format(Locale.US, "%.1f", roundedOneDec)}%"
        }
    }

    /**
     * Computes exact Profit, Gain % (Markup on Cost), and Margin % (Gross Margin on Selling Price).
     */
    fun computeProfitAnalysis(
        unitPurchasePaise: Long,
        unitSellingPaise: Long,
        quantity: Double
    ): ProfitAnalysis {
        val totalCost = (unitPurchasePaise * quantity).roundToLong()
        val totalRevenue = (unitSellingPaise * quantity).roundToLong()
        val totalProfit = totalRevenue - totalCost

        val gainMarkupPercent = if (totalCost > 0L) {
            (totalProfit.toDouble() / totalCost.toDouble()) * 100.0
        } else {
            0.0
        }

        val grossMarginPercent = if (totalRevenue > 0L) {
            (totalProfit.toDouble() / totalRevenue.toDouble()) * 100.0
        } else {
            0.0
        }

        return ProfitAnalysis(
            unitPurchasePaise = unitPurchasePaise,
            unitSellingPaise = unitSellingPaise,
            quantity = quantity,
            totalCostPaise = totalCost,
            totalRevenuePaise = totalRevenue,
            totalProfitPaise = totalProfit,
            gainMarkupPercent = gainMarkupPercent,
            grossMarginPercent = grossMarginPercent
        )
    }
}
