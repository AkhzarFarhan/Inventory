package com.shopledger.pos.core.cipher

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.roundToLong

enum class CipherMode(val displayName: String) {
    CUSTOM_CODE("Custom Coded Price"),
    HIDDEN_PIN("PIN-Protected Hidden");

    companion object {
        fun fromStoredName(name: String?): CipherMode = when (name) {
            HIDDEN_PIN.name -> HIDDEN_PIN
            else -> CUSTOM_CODE
        }
    }
}

typealias CostCodeEngine = CostCipherEngine

/**
 * Configurable User-Defined Cost Price Cipher Engine.
 *
 * Replaces shop-specific stroke glyphs with a customizable digit-to-code cipher where
 * every shopkeeper defines their own secret mapping for digits `1..9, 0` (either as a
 * 10-character code key like `BLACKHORSE` / `MONEYCHARM` / `@#$%^&*+=?` or custom tokens
 * per digit), plus optional PIN-protected `••••` masking.
 */
object CostCipherEngine {

    const val DEFAULT_WORD_10 = "BLACKHORSE"
    val ORDERED_DIGITS: List<Char> = listOf('1', '2', '3', '4', '5', '6', '7', '8', '9', '0')

    fun defaultDigitMap(): Map<Char, String> = word10ToDigitMap(DEFAULT_WORD_10)

    private val _activeModeFlow = MutableStateFlow(CipherMode.CUSTOM_CODE)
    val activeModeFlow: StateFlow<CipherMode> = _activeModeFlow.asStateFlow()

    private val _activeWord10Flow = MutableStateFlow(DEFAULT_WORD_10)
    val activeWord10Flow: StateFlow<String> = _activeWord10Flow.asStateFlow()

    private val _activeCustomDigitMapFlow = MutableStateFlow(defaultDigitMap())
    val activeCustomDigitMapFlow: StateFlow<Map<Char, String>> = _activeCustomDigitMapFlow.asStateFlow()

    fun setActiveConfig(mode: CipherMode, word10: String = DEFAULT_WORD_10) {
        val norm = normalizedWord10(word10)
        _activeModeFlow.value = mode
        _activeWord10Flow.value = norm
        _activeCustomDigitMapFlow.value = word10ToDigitMap(norm)
    }

    fun setActiveCustomDigitMap(mode: CipherMode, digitMap: Map<Char, String>) {
        val sanitized = normalizeDigitMap(digitMap)
        _activeModeFlow.value = mode
        _activeCustomDigitMapFlow.value = sanitized
        _activeWord10Flow.value = digitMapToWord10(sanitized)
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
     * Validates that a merchant's 10-character code key has 10 unique non-whitespace symbols/letters.
     */
    fun isValidCipherWord10(word: String): Boolean {
        val cleaned = word.trim()
            .uppercase(Locale.ROOT)
            .filter { !it.isWhitespace() && it != '[' && it != ']' && it != '=' }
        return cleaned.length == 10 && cleaned.toSet().size == 10
    }

    fun normalizedWord10(word10: String): String {
        val cleaned = word10.trim()
            .uppercase(Locale.ROOT)
            .filter { !it.isWhitespace() && it != '[' && it != ']' && it != '=' }
        return if (cleaned.length == 10 && cleaned.toSet().size == 10) cleaned else DEFAULT_WORD_10
    }

    fun sanitizeCodeToken(raw: String, fallback: String): String {
        val cleaned = raw.trim()
            .replace("[", "")
            .replace("]", "")
            .replace("=", "")
            .replace("\n", "")
            .replace("\r", "")
            .take(4)
            .uppercase(Locale.ROOT)
        return cleaned.ifEmpty { fallback }
    }

    fun normalizeDigitMap(input: Map<Char, String>): Map<Char, String> {
        val defaults = defaultDigitMap()
        val result = LinkedHashMap<Char, String>(10)
        for (digit in ORDERED_DIGITS) {
            val fallback = defaults[digit] ?: digit.toString()
            result[digit] = sanitizeCodeToken(input[digit].orEmpty(), fallback)
        }
        return result
    }

    fun word10ToDigitMap(word10: String): Map<Char, String> {
        val key = normalizedWord10(word10)
        val result = LinkedHashMap<Char, String>(10)
        ORDERED_DIGITS.forEachIndexed { idx, digit ->
            result[digit] = key[idx].toString()
        }
        return result
    }

    fun digitMapToWord10(map: Map<Char, String>): String {
        val normalized = normalizeDigitMap(map)
        val singleChars = ORDERED_DIGITS.map { normalized[it].orEmpty().firstOrNull() ?: it }.joinToString("")
        return if (isValidCipherWord10(singleChars)) singleChars else _activeWord10Flow.value
    }

    fun serializeDigitMap(map: Map<Char, String>): String {
        val normalized = normalizeDigitMap(map)
        return ORDERED_DIGITS.joinToString("\n") { digit ->
            "$digit=${normalized[digit].orEmpty()}"
        }
    }

    fun parseDigitMap(serialized: String): Map<Char, String> {
        if (serialized.isBlank()) return word10ToDigitMap(_activeWord10Flow.value)
        val parsed = LinkedHashMap<Char, String>()
        serialized.lines().forEach { line ->
            val idx = line.indexOf('=')
            if (idx == 1 && line[0] in '0'..'9') {
                val digit = line[0]
                val value = line.substring(2).trim()
                if (value.isNotEmpty()) {
                    parsed[digit] = value
                }
            }
        }
        return normalizeDigitMap(parsed)
    }

    /**
     * Encodes Paise using the user's custom digit mapping (`1..9, 0`).
     */
    fun encodePaiseToCustomCode(
        purchasePricePaise: Long,
        digitMap: Map<Char, String> = _activeCustomDigitMapFlow.value
    ): String {
        val map = normalizeDigitMap(digitMap)
        val numeric = formatPaiseNumericString(purchasePricePaise)
        val tokens = ArrayList<String>(numeric.length)
        for (ch in numeric) {
            when (ch) {
                in '0'..'9' -> tokens.add(map[ch] ?: ch.toString())
                '.' -> tokens.add(".")
            }
        }
        return "[${tokens.joinToString(" ")}]"
    }

    /**
     * Decodes a custom user-defined coded price back into Paise (`Long`).
     */
    fun decodeCustomCodeToPaise(
        coded: String,
        digitMap: Map<Char, String> = _activeCustomDigitMapFlow.value
    ): Long {
        val map = normalizeDigitMap(digitMap)
        val reverseMap = HashMap<String, Char>(10)
        for ((digit, token) in map) {
            reverseMap[token.uppercase(Locale.ROOT)] = digit
        }
        val stripped = coded.replace("[", "").replace("]", "").trim()
        if (stripped.isEmpty()) return 0L

        val spacedTokens = stripped.split(Regex("""\s+""")).filter { it.isNotEmpty() }
        val sb = StringBuilder()
        if (spacedTokens.size > 1 || spacedTokens.all { it.length > 1 }) {
            for (token in spacedTokens) {
                if (token == ".") {
                    sb.append('.')
                } else {
                    val mapped = reverseMap[token.uppercase(Locale.ROOT)]
                    if (mapped != null) sb.append(mapped)
                }
            }
        } else {
            // Single unspaced string or 1-token input
            val singleUpper = stripped.uppercase(Locale.ROOT)
            val direct = reverseMap[singleUpper]
            if (direct != null) {
                sb.append(direct)
            } else {
                for (ch in singleUpper) {
                    if (ch == '.') {
                        sb.append('.')
                    } else {
                        reverseMap[ch.toString()]?.let { sb.append(it) }
                    }
                }
            }
        }
        val value = sb.toString().toDoubleOrNull() ?: 0.0
        return (value * 100.0).roundToLong()
    }

    /**
     * Encodes Paise into a 10-character merchant code key (e.g. `"BLACKHORSE"` where
     * `B=1, L=2, A=3, C=4, K=5, H=6, O=7, R=8, S=9, E=0`: ₹185 -> `"[B R K]"`).
     */
    fun encodePaiseToWordCode(
        purchasePricePaise: Long,
        word10: String = DEFAULT_WORD_10
    ): String {
        return encodePaiseToCustomCode(purchasePricePaise, word10ToDigitMap(word10))
    }

    /**
     * Decodes a 10-character merchant word code back into Paise (`Long`).
     */
    fun decodeWordCodeToPaise(
        coded: String,
        word10: String = DEFAULT_WORD_10
    ): Long {
        return decodeCustomCodeToPaise(coded, word10ToDigitMap(word10))
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
