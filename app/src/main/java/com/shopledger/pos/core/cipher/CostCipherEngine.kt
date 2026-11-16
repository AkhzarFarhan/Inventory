package com.shopledger.pos.core.cipher

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.roundToLong

enum class CipherMode(val displayName: String) {
    STROKE_GLYPHS("Stroke Symbols"),
    WORD_10_LETTER("10-Letter Word Code"),
    HIDDEN_PIN("PIN-Protected Hidden")
}

typealias CostCodeEngine = CostCipherEngine

/**
 * Represents a single symbol token in the stroke-based secret purchase price cipher:
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

    const val DEFAULT_WORD_10 = "BLACKHORSE"

    private val _activeModeFlow = MutableStateFlow(CipherMode.STROKE_GLYPHS)
    val activeModeFlow: StateFlow<CipherMode> = _activeModeFlow.asStateFlow()

    private val _activeWord10Flow = MutableStateFlow(DEFAULT_WORD_10)
    val activeWord10Flow: StateFlow<String> = _activeWord10Flow.asStateFlow()

    fun setActiveConfig(mode: CipherMode, word10: String = DEFAULT_WORD_10) {
        _activeModeFlow.value = mode
        _activeWord10Flow.value = normalizedWord10(word10)
    }

    fun formatPaiseNumericString(purchasePricePaise: Long): String {
        if (purchasePricePaise <= 0L) return "0"
        val wholeRupees = purchasePricePaise / 100L
        val paiseRemainder = (purchasePricePaise % 100L).toInt()
        return if (paiseRemainder == 0) {
            wholeRupees.toString()
        } else if (paiseRemainder % 10 == 0) {
            "$wholeRupees.${paiseRemainder / 10}"
        } else {
            "$wholeRupees.${paiseRemainder.toString().padStart(2, '0')}"
        }
    }

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
        return encodeNumericString(formatPaiseNumericString(purchasePricePaise))
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

    /**
     * Validates that a merchant's 10-letter code word has exactly 10 unique alphabetic characters.
     */
    fun isValidCipherWord10(word: String): Boolean {
        val cleaned = word.trim().uppercase(Locale.ROOT).filter { it.isLetter() }
        return cleaned.length == 10 && cleaned.toSet().size == 10
    }

    fun normalizedWord10(word10: String): String {
        val cleaned = word10.trim().uppercase(Locale.ROOT).filter { it.isLetter() }
        return if (cleaned.length == 10 && cleaned.toSet().size == 10) cleaned else DEFAULT_WORD_10
    }

    /**
     * Encodes Paise into a 10-letter merchant word code (e.g. `"BLACKHORSE"` where
     * `B=1, L=2, A=3, C=4, K=5, H=6, O=7, R=8, S=9, E=0`: ₹185 -> `"[B R K]"`).
     */
    fun encodePaiseToWordCode(
        purchasePricePaise: Long,
        word10: String = DEFAULT_WORD_10
    ): String {
        val key = normalizedWord10(word10)
        val numeric = formatPaiseNumericString(purchasePricePaise)
        val tokens = ArrayList<String>(numeric.length)
        for (ch in numeric) {
            when (ch) {
                in '1'..'9' -> tokens.add(key[ch - '1'].toString())
                '0' -> tokens.add(key[9].toString())
                '.' -> tokens.add(".")
            }
        }
        return "[${tokens.joinToString(" ")}]"
    }

    /**
     * Decodes a 10-letter merchant word code back into Paise (`Long`).
     */
    fun decodeWordCodeToPaise(
        coded: String,
        word10: String = DEFAULT_WORD_10
    ): Long {
        val key = normalizedWord10(word10)
        val sb = StringBuilder()
        for (ch in coded.uppercase(Locale.ROOT)) {
            if (ch == '.') {
                sb.append('.')
            } else {
                val idx = key.indexOf(ch)
                if (idx in 0..8) {
                    sb.append(('1'.code + idx).toChar())
                } else if (idx == 9) {
                    sb.append('0')
                }
            }
        }
        val value = sb.toString().toDoubleOrNull() ?: 0.0
        return (value * 100.0).roundToLong()
    }

    /**
     * Computes a SHA-256 hex hash for the shop's optional 4-digit Owner PIN.
     */
    fun hashPin(pin: String): String {
        val trimmed = pin.trim()
        if (trimmed.isEmpty()) return ""
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(trimmed.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Verifies a candidate PIN against a stored SHA-256 hash (if empty hash, no PIN is enforced).
     */
    fun verifyPin(inputPin: String, storedHash: String): Boolean {
        if (storedHash.isBlank()) return true
        return hashPin(inputPin) == storedHash
    }
}
