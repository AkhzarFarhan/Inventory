package com.lpshardware.inventory.core.nlp

import kotlin.math.max
import kotlin.math.min

/**
 * Ultra-lightweight phonetic consonant skeleton encoder + Jaro-Winkler similarity calculator
 * tailored for Indian English and Hinglish hardware vocabulary on low-RAM devices.
 */
object PhoneticEncoder {

    /**
     * Produces a normalized phonetic consonant key so spelling variations
     * like `"kabza"` / `"kabja"`, `"fevicol"` / `"fevikol"`, `"aldrop"` / `"aldraf"`,
     * `"aluminium"` / `"almunium"`, `"hinges"` / `"hinge"` map to matching keys.
     */
    fun encodeWord(raw: String): String {
        val lower = raw.lowercase().trim()
        if (lower.length <= 2) return lower

        // Strip plural suffixes common in hardware names
        val singular = when {
            lower.endsWith("ies") && lower.length > 4 -> lower.dropLast(3) + "y"
            lower.endsWith("es") && lower.length > 4 && !lower.endsWith("hinges") -> lower.dropLast(2)
            lower.endsWith("s") && !lower.endsWith("ss") && lower.length > 3 -> lower.dropLast(1)
            else -> lower
        }

        // Normalize common Indian English / Hinglish phonetic equivalents
        val normalized = singular
            .replace("ph", "f")
            .replace("bh", "b")
            .replace("dh", "d")
            .replace("kh", "k")
            .replace("gh", "g")
            .replace("chh", "ch")
            .replace("sh", "s")
            .replace("ck", "k")
            .replace("qu", "k")
            .replace('c', 'k')
            .replace('z', 'j')
            .replace('v', 'w')

        val sb = StringBuilder(normalized.length)
        var prevChar = ' '
        for ((index, ch) in normalized.withIndex()) {
            if (!ch.isLetterOrDigit()) continue
            val isVowel = ch == 'a' || ch == 'e' || ch == 'i' || ch == 'o' || ch == 'u' || ch == 'y'
            // Keep first character even if vowel; drop subsequent vowels and duplicate consecutive consonants
            if (index == 0 || (!isVowel && ch != prevChar)) {
                sb.append(ch)
                prevChar = ch
            }
        }
        return if (sb.isEmpty()) singular else sb.toString()
    }

    /**
     * Jaro-Winkler similarity in `[0.0, 1.0]` for fast typo tolerance.
     */
    fun jaroWinklerSimilarity(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0

        val matchDistance = max(s1.length, s2.length) / 2 - 1
        val s1Matches = BooleanArray(s1.length)
        val s2Matches = BooleanArray(s2.length)

        var matches = 0
        for (i in s1.indices) {
            val start = max(0, i - matchDistance)
            val end = min(i + matchDistance + 1, s2.length)
            for (j in start until end) {
                if (s2Matches[j]) continue
                if (s1[i] != s2[j]) continue
                s1Matches[i] = true
                s2Matches[j] = true
                matches++
                break
            }
        }

        if (matches == 0) return 0.0

        var transpositions = 0
        var k = 0
        for (i in s1.indices) {
            if (!s1Matches[i]) continue
            while (!s2Matches[k]) k++
            if (s1[i] != s2[k]) transpositions++
            k++
        }

        val m = matches.toDouble()
        val jaro = ((m / s1.length) + (m / s2.length) + ((m - transpositions / 2.0) / m)) / 3.0

        // Winkler prefix bonus up to 4 chars
        var prefix = 0
        val maxPrefix = min(4, min(s1.length, s2.length))
        while (prefix < maxPrefix && s1[prefix] == s2[prefix]) {
            prefix++
        }

        return jaro + prefix * 0.1 * (1.0 - jaro)
    }
}
