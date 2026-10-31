package com.lpshardware.inventory.core.cipher

import androidx.compose.runtime.Immutable
import kotlin.math.roundToLong

/**
 * Represents a single symbol token in the LPS Hardware secret purchase price cipher
 * derived from `IMG_20261031_222339.jpg`:
 *
 * - `1` = Vertical line `|` (1 stroke)
 * - `2` = Cross `X` (2 diagonal strokes)
 * - `3` = Triangle `△` (3 strokes)
 * - `4` = Square `□` (4 strokes)
 * - `5` = Square with 1 diagonal `/` (5 strokes)
 * - `6` = Square with 2 diagonals `☒` (6 strokes)
 * - `7` = Square with triangle roof `⌂` (7 strokes)
 * - `8` = House (`7`) + bottom vertical stem `|` (8 strokes)
 * - `9` = House with bottom stem (`8`) + horizontal crossbar `+` at bottom (9 strokes)
 * - `0` = Circle `○`
 */
@Immutable
enum class CipherSymbol(
    val digitChar: Char,
    val strokeDescription: String
) {
    ZERO('0', "Circle (0)"),
    ONE('1', "Vertical Line (1)"),
    TWO('2', "Diagonal Cross X (2)"),
    THREE('3', "Triangle (3)"),
    FOUR('4', "Square (4)"),
    FIVE('5', "Square + 1 Diagonal (5)"),
    SIX('6', "Square + 2 Diagonals (6)"),
    SEVEN('7', "Square + Roof House (7)"),
    EIGHT('8', "House + Bottom Vertical Line (8)"),
    NINE('9', "House + Bottom Cross (9)"),
    DECIMAL('.', "Decimal Dot");

    companion object {
        fun fromChar(c: Char): CipherSymbol? = when (c) {
            '0' -> ZERO
            '1' -> ONE
            '2' -> TWO
            '3' -> THREE
            '4' -> FOUR
            '5' -> FIVE
            '6' -> SIX
            '7' -> SEVEN
            '8' -> EIGHT
            '9' -> NINE
            '.' -> DECIMAL
            else -> null
        }
    }
}

object CostCipherEngine {

    /**
     * Converts a purchase price in Paise (`Long`, where ₹1 = 100 paise) into an ordered
     * list of [CipherSymbol] tokens representing the Rupee amount.
     *
     * Examples:
     * - `18500L` (₹185) -> `[ONE, EIGHT, FIVE]`
     * - `4550L` (₹45.50) -> `[FOUR, FIVE, DECIMAL, FIVE]`
     * - `0L` (₹0) -> `[ZERO]`
     */
    fun encodePaiseToSymbols(purchasePricePaise: Long): List<CipherSymbol> {
        if (purchasePricePaise <= 0L) return listOf(CipherSymbol.ZERO)
        val wholeRupees = purchasePricePaise / 100L
        val paiseRemainder = (purchasePricePaise % 100L).toInt()

        val formattedNumeric = if (paiseRemainder == 0) {
            wholeRupees.toString()
        } else if (paiseRemainder % 10 == 0) {
            "$wholeRupees.${paiseRemainder / 10}"
        } else {
            "$wholeRupees.${paiseRemainder.toString().padStart(2, '0')}"
        }

        return encodeNumericString(formattedNumeric)
    }

    /**
     * Converts a raw numeric string (e.g., `"240"` or `"67.5"`) into [CipherSymbol] tokens.
     */
    fun encodeNumericString(numericInput: String): List<CipherSymbol> {
        val cleaned = numericInput.trim()
        if (cleaned.isEmpty()) return emptyList()
        val result = ArrayList<CipherSymbol>(cleaned.length)
        for (ch in cleaned) {
            CipherSymbol.fromChar(ch)?.let { result.add(it) }
        }
        return result
    }

    /**
     * Decodes a sequence of [CipherSymbol] tokens back into Paise (`Long`).
     */
    fun decodeSymbolsToPaise(symbols: List<CipherSymbol>): Long {
        if (symbols.isEmpty()) return 0L
        val sb = StringBuilder(symbols.size)
        for (s in symbols) {
            sb.append(s.digitChar)
        }
        val value = sb.toString().toDoubleOrNull() ?: 0.0
        return (value * 100.0).roundToLong()
    }
}
