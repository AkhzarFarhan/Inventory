package com.shopledger.pos.core.nlp

import androidx.compose.runtime.Immutable
import com.shopledger.pos.data.model.InventoryItemWithVariants
import kotlin.math.abs
import kotlin.math.max

/**
 * Structured semantic decomposition of a retail product name and its variant attributes.
 */
@Immutable
data class SemanticItemSignature(
    val originalName: String,
    /** Core product concept tokens (stripped of size, color, and material attributes) */
    val baseConceptTokens: Set<String>,
    /** Phonetic keys of the core product tokens */
    val phoneticBaseTokens: Set<String>,
    /** Extracted or explicitly provided canonical Attr1 / Size (e.g. `"4 inch (100mm)"`, `"1 kg"`) */
    val canonicalSize: String,
    /** Extracted or explicitly provided canonical Attr2 / Material / Brand */
    val canonicalMaterial: String,
    /** Extracted or explicitly provided canonical Attr3 / Color / Finish */
    val canonicalColorFinish: String,
    /** Suggested clean parent item name (with variant attributes separated out) */
    val suggestedCleanBaseName: String
)

enum class DuplicateDecisionType {
    /** Exact same base item AND same variant attributes */
    EXACT_DUPLICATE,
    /** Same base item concept, but different variant attributes */
    NEW_VARIANT_OF_EXISTING,
    /** Close semantic similarity — warn merchant gently */
    SIMILAR_ITEM_FOUND,
    /** Brand new distinct item */
    UNIQUE_NEW_ITEM
}

@Immutable
data class DuplicateCheckResult(
    val decision: DuplicateDecisionType,
    val matchedItem: InventoryItemWithVariants? = null,
    val matchedVariantId: String? = null,
    val confidenceScore: Double = 0.0,
    val candidateSignature: SemanticItemSignature,
    val explanationMessage: String = ""
)

object SmartItemMatcher {

    private val sizeRegex = Regex(
        pattern = """(\d+(?:\.\d+)?|\d+\s*/\s*\d+)\s*(inch|inches|in|"|''|mm|cm|ft|feet|mtr|meter|g|gm|kg|ml|ltr|mg|mcg|gb|tb|w)\b""",
        option = RegexOption.IGNORE_CASE
    )

    private val hindiFractionSizeRegex = Regex(
        pattern = """\b(aadha|half|pauna|sawa|dedh|dhai)\s*(inch|in|"|ft|kg)?\b""",
        option = RegexOption.IGNORE_CASE
    )

    /**
     * Parses any product name + optional explicit variant inputs into a [SemanticItemSignature]
     * using the active or specified [ShopVertical]'s [TradeVocabularyPack].
     */
    fun parseSignature(
        rawItemName: String,
        explicitSize: String = "",
        explicitMaterial: String = "",
        explicitColorFinish: String = "",
        vertical: ShopVertical = TradeTaxonomyRegistry.activeVerticalFlow.value
    ): SemanticItemSignature {
        val pack = TradeTaxonomyRegistry.getPack(vertical)
        var workingText = rawItemName.lowercase().trim()

        // 1. Extract Size / Weight / Strength from explicit field or item name
        var detectedSize = normalizeSizeString(explicitSize, vertical)
        val sizeRangesToRemove = mutableListOf<IntRange>()

        hindiFractionSizeRegex.findAll(workingText).forEach { match ->
            val word = match.groupValues[1].lowercase()
            val key = "${word}_inch"
            val mapped = pack.nominalSizeEquivalents[key]
            if (mapped != null) {
                if (detectedSize.isEmpty()) detectedSize = mapped
                sizeRangesToRemove.add(match.range)
            }
        }

        sizeRegex.findAll(workingText).forEach { match ->
            val norm = normalizeRegexSizeMatch(match.groupValues[1], match.groupValues[2], pack)
            if (detectedSize.isEmpty()) detectedSize = norm
            sizeRangesToRemove.add(match.range)
        }

        for (range in sizeRangesToRemove.sortedByDescending { it.first }) {
            workingText = workingText.removeRange(range)
        }

        // 2. Replace multi-word trade phrases with canonical concept tokens
        val conceptTokens = LinkedHashSet<String>()
        for ((phrase, canonicalConcept) in pack.phraseSynonyms) {
            if (workingText.contains(phrase)) {
                conceptTokens.add(canonicalConcept)
                workingText = workingText.replace(phrase, " ")
            }
        }

        // 3. Tokenize words and separate Attributes & Base Concepts
        val rawTokens = workingText
            .replace(Regex("""[^a-z0-9./]+"""), " ")
            .trim()
            .split(Regex("""\s+"""))
            .filter { it.isNotBlank() }

        var detectedMaterial = normalizeMaterialString(explicitMaterial, vertical)
        val detectedColorParts = LinkedHashSet<String>()
        if (explicitColorFinish.isNotBlank()) {
            detectedColorParts.add(normalizeColorString(explicitColorFinish, vertical))
        }

        val remainingWords = mutableListOf<String>()

        for (token in rawTokens) {
            if (token in HardwareOntology.stopWords) continue

            val materialMatch = pack.materialAliases[token]
            if (materialMatch != null) {
                if (detectedMaterial.isEmpty()) {
                    detectedMaterial = materialMatch
                } else if (materialMatch.startsWith("SS ") && detectedMaterial == "SS") {
                    detectedMaterial = materialMatch
                }
                continue
            }

            val colorMatch = pack.colorFinishAliases[token]
            if (colorMatch != null) {
                detectedColorParts.add(colorMatch)
                continue
            }

            val wordConcept = pack.wordSynonyms[token]
            if (wordConcept != null) {
                conceptTokens.add(wordConcept)
            } else {
                remainingWords.add(token)
            }
        }

        // Normalize composite colors
        val finalColor = when {
            detectedColorParts.contains("Matt") && detectedColorParts.contains("Black") -> "Matt Black"
            detectedColorParts.contains("Rose Gold") && detectedColorParts.contains("Gold") -> "Rose Gold"
            detectedColorParts.isNotEmpty() -> detectedColorParts.joinToString(" ")
            else -> ""
        }

        // Add remaining meaningful words as base tokens
        val phoneticSet = LinkedHashSet<String>()
        for (concept in conceptTokens) {
            phoneticSet.add(concept)
        }
        for (word in remainingWords) {
            val encoded = PhoneticEncoder.encodeWord(word)
            if (encoded.isNotEmpty()) {
                conceptTokens.add(word)
                phoneticSet.add(encoded)
            }
        }

        if (conceptTokens.isEmpty() && rawItemName.isNotBlank()) {
            val fallback = PhoneticEncoder.encodeWord(rawItemName)
            conceptTokens.add(rawItemName.lowercase().trim())
            phoneticSet.add(fallback)
        }

        val cleanBaseTitle = buildCleanBaseTitle(rawItemName, conceptTokens, pack)

        return SemanticItemSignature(
            originalName = rawItemName.trim(),
            baseConceptTokens = conceptTokens,
            phoneticBaseTokens = phoneticSet,
            canonicalSize = detectedSize,
            canonicalMaterial = detectedMaterial,
            canonicalColorFinish = finalColor,
            suggestedCleanBaseName = cleanBaseTitle
        )
    }

    /**
     * Checks a candidate new product against existing inventory using semantic & phonetic concept matching.
     */
    fun checkDuplicateOrVariant(
        candidateName: String,
        candidateSize: String,
        candidateMaterial: String,
        candidateColorFinish: String,
        existingInventory: List<InventoryItemWithVariants>,
        excludeItemId: String? = null,
        vertical: ShopVertical = TradeTaxonomyRegistry.activeVerticalFlow.value
    ): DuplicateCheckResult {
        val candidateSig = parseSignature(
            rawItemName = candidateName,
            explicitSize = candidateSize,
            explicitMaterial = candidateMaterial,
            explicitColorFinish = candidateColorFinish,
            vertical = vertical
        )

        if (candidateName.isBlank() || candidateSig.baseConceptTokens.isEmpty()) {
            return DuplicateCheckResult(
                decision = DuplicateDecisionType.UNIQUE_NEW_ITEM,
                candidateSignature = candidateSig
            )
        }

        var bestItem: InventoryItemWithVariants? = null
        var bestScore = 0.0
        var bestExistingSig: SemanticItemSignature? = null

        for (existing in existingInventory) {
            if (existing.item.id == excludeItemId) continue
            val existingSig = parseSignature(existing.item.displayName, vertical = vertical)
            val score = calculateBaseConceptSimilarity(candidateSig, existingSig)
            if (score > bestScore) {
                bestScore = score
                bestItem = existing
                bestExistingSig = existingSig
            }
        }

        val matched = bestItem
        if (matched == null || bestScore < 0.65) {
            return DuplicateCheckResult(
                decision = DuplicateDecisionType.UNIQUE_NEW_ITEM,
                confidenceScore = bestScore,
                candidateSignature = candidateSig
            )
        }

        if (bestScore >= 0.80) {
            val candSize = candidateSig.canonicalSize
            val candMat = candidateSig.canonicalMaterial.ifEmpty { bestExistingSig?.canonicalMaterial.orEmpty() }
            val candColor = candidateSig.canonicalColorFinish.ifEmpty { bestExistingSig?.canonicalColorFinish.orEmpty() }

            val matchingVariant = matched.variants.firstOrNull { variant ->
                val vSize = normalizeSizeString(variant.sizeLabel, vertical)
                val vMat = normalizeMaterialString(variant.materialLabel, vertical)
                val vColor = normalizeColorString(variant.colorFinishLabel, vertical)

                val sizeSame = (candSize.isEmpty() && vSize.isEmpty()) ||
                    candSize.equals(vSize, ignoreCase = true)
                val matSame = (candMat.isEmpty() && vMat.isEmpty()) ||
                    candMat.equals(vMat, ignoreCase = true)
                val colorSame = (candColor.isEmpty() && vColor.isEmpty()) ||
                    candColor.equals(vColor, ignoreCase = true)

                sizeSame && matSame && colorSame
            }

            if (matchingVariant != null) {
                val variantDesc = listOf(
                    matchingVariant.sizeLabel,
                    matchingVariant.materialLabel,
                    matchingVariant.colorFinishLabel
                ).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { "Standard" }

                return DuplicateCheckResult(
                    decision = DuplicateDecisionType.EXACT_DUPLICATE,
                    matchedItem = matched,
                    matchedVariantId = matchingVariant.id,
                    confidenceScore = bestScore,
                    candidateSignature = candidateSig,
                    explanationMessage = "Same item already exists as \"${matched.item.displayName}\" ($variantDesc). Update its stock or price instead of adding a duplicate."
                )
            } else {
                val newVariantSummary = listOf(candSize, candMat, candColor)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                    .ifEmpty { "New Variant" }

                return DuplicateCheckResult(
                    decision = DuplicateDecisionType.NEW_VARIANT_OF_EXISTING,
                    matchedItem = matched,
                    confidenceScore = bestScore,
                    candidateSignature = candidateSig,
                    explanationMessage = "Matches existing item \"${matched.item.displayName}\". Add ($newVariantSummary) as a variant under \"${matched.item.displayName}\"?"
                )
            }
        }

        return DuplicateCheckResult(
            decision = DuplicateDecisionType.SIMILAR_ITEM_FOUND,
            matchedItem = matched,
            confidenceScore = bestScore,
            candidateSignature = candidateSig,
            explanationMessage = "Similar to existing item \"${matched.item.displayName}\". Tap to add as a variant if it is the same item."
        )
    }

    /**
     * Levenshtein edit distance between two strings for typo tolerance.
     */
    fun levenshteinDistance(s1: String, s2: String): Int {
        if (s1 == s2) return 0
        if (s1.isEmpty()) return s2.length
        if (s2.isEmpty()) return s1.length

        val m = s1.length
        val n = s2.length
        val dp = IntArray(n + 1) { it }
        for (i in 1..m) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..n) {
                val temp = dp[j]
                dp[j] = if (s1[i - 1] == s2[j - 1]) {
                    prev
                } else {
                    1 + minOf(prev, dp[j], dp[j - 1])
                }
                prev = temp
            }
        }
        return dp[n]
    }

    /**
     * Calculates a search relevance score >= 0.0 for an inventory item under the active [ShopVertical].
     * Returns 0.0 if the item does NOT match the query and should be filtered out.
     */
    fun calculateSearchRelevanceScore(
        query: String,
        itemWithVariants: InventoryItemWithVariants,
        vertical: ShopVertical = TradeTaxonomyRegistry.activeVerticalFlow.value
    ): Double {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return 1.0

        val lowerQuery = trimmed.lowercase()
        val displayName = itemWithVariants.item.displayName.trim()
        val lowerDisplayName = displayName.lowercase()

        // 1. Exact full display name match
        if (lowerDisplayName == lowerQuery) {
            return 10000.0
        }

        val queryWords = lowerQuery.split(Regex("""[^a-z0-9]+""")).filter { it.isNotBlank() }
        val nameWords = lowerDisplayName.split(Regex("""[^a-z0-9]+""")).filter { it.isNotBlank() }
        if (queryWords.isEmpty()) return 1.0

        var score = 0.0

        // 2. Exact word boundary / token match in item displayName
        val exactNameWordMatches = queryWords.count { q -> nameWords.contains(q) }
        if (exactNameWordMatches > 0) {
            val fraction = exactNameWordMatches.toDouble() / queryWords.size
            var wordScore = 4000.0 * fraction
            if (exactNameWordMatches == queryWords.size) {
                wordScore += 1500.0
            }
            if (nameWords.firstOrNull() == queryWords.firstOrNull()) {
                wordScore += 1000.0
            } else if (lowerDisplayName.startsWith(lowerQuery)) {
                wordScore += 800.0
            }
            score = max(score, wordScore)
        }

        // 3. Prefix match in item displayName
        val prefixMatches = queryWords.count { q ->
            nameWords.any { nw -> nw.startsWith(q) && nw != q }
        }
        if (prefixMatches > 0) {
            val fraction = prefixMatches.toDouble() / queryWords.size
            var prefixScore = 2500.0 * fraction
            if (nameWords.firstOrNull()?.startsWith(queryWords.first()) == true) {
                prefixScore += 500.0
            }
            score = max(score, prefixScore)
        }

        // 4. Substring match in item displayName
        if (score == 0.0 && lowerDisplayName.contains(lowerQuery)) {
            var subScore = 1500.0
            if (lowerDisplayName.startsWith(lowerQuery)) {
                subScore += 800.0
            }
            score = max(score, subScore)
        }

        // 5. Semantic & Trade Ontology Concepts
        val querySig = parseSignature(trimmed, vertical = vertical)
        val itemSig = parseSignature(itemWithVariants.item.displayName, vertical = vertical)

        var sizeMatched = false
        if (querySig.canonicalSize.isNotEmpty()) {
            val hasSize = itemWithVariants.variants.any {
                normalizeSizeString(it.sizeLabel, vertical).equals(querySig.canonicalSize, ignoreCase = true)
            } || itemSig.canonicalSize.equals(querySig.canonicalSize, ignoreCase = true)
            if (!hasSize) {
                return 0.0
            }
            sizeMatched = true
        }

        var materialMatched = false
        if (querySig.canonicalMaterial.isNotEmpty()) {
            val hasMat = itemWithVariants.variants.any {
                normalizeMaterialString(it.materialLabel, vertical).equals(querySig.canonicalMaterial, ignoreCase = true)
            } || itemSig.canonicalMaterial.equals(querySig.canonicalMaterial, ignoreCase = true)
            if (!hasMat) {
                return 0.0
            }
            materialMatched = true
        }

        val queryConcepts = querySig.baseConceptTokens.filter { it.startsWith("CONCEPT_") }.toSet()
        val itemConcepts = itemSig.baseConceptTokens.filter { it.startsWith("CONCEPT_") }.toSet()

        if (queryConcepts.isNotEmpty() && itemConcepts.isNotEmpty()) {
            val commonConcepts = queryConcepts.intersect(itemConcepts)
            if (commonConcepts.isNotEmpty()) {
                var conceptScore = 3500.0
                if (sizeMatched) conceptScore += 1200.0
                if (materialMatched) conceptScore += 500.0
                score = max(score, conceptScore)
            } else if (score == 0.0) {
                return 0.0
            }
        }

        if (sizeMatched && queryConcepts.isEmpty() && score == 0.0) {
            score = max(score, 1500.0)
        }
        if (materialMatched && queryConcepts.isEmpty() && score == 0.0) {
            score = max(score, 1500.0)
        }

        // 6. Variant Attribute Matching
        for (v in itemWithVariants.variants) {
            val vSize = v.sizeLabel.lowercase()
            val vMat = v.materialLabel.lowercase()
            val vColor = v.colorFinishLabel.lowercase()

            if (lowerQuery.length >= 2) {
                if (vSize == lowerQuery || vMat == lowerQuery || vColor == lowerQuery) {
                    score = max(score, 1200.0)
                } else if (vSize.contains(lowerQuery) || vMat.contains(lowerQuery) || vColor.contains(lowerQuery)) {
                    score = max(score, 700.0)
                }
            }
        }

        // 7. Typo tolerance using Levenshtein distance on discrete words
        if (score == 0.0 && queryWords.isNotEmpty()) {
            var typoCount = 0
            for (q in queryWords) {
                if (q.length < 3) continue
                val maxAllowedDist = if (q.length <= 4) 1 else 2
                val hasTypoMatch = nameWords.any { nw ->
                    nw.first() == q.first() && abs(nw.length - q.length) <= maxAllowedDist &&
                        levenshteinDistance(q, nw) <= maxAllowedDist
                }
                if (hasTypoMatch) typoCount++
            }
            if (typoCount > 0) {
                score = 2000.0 * (typoCount.toDouble() / queryWords.size)
            }
        }

        // 8. Category match (low weight)
        val lowerCat = itemWithVariants.item.category.lowercase()
        if (lowerCat.isNotBlank() && lowerQuery.length >= 3) {
            val catWords = lowerCat.split(Regex("""[^a-z0-9]+""")).filter { it.isNotBlank() }
            if (catWords.contains(lowerQuery)) {
                score = max(score, 300.0)
            } else if (lowerCat.contains(lowerQuery)) {
                score = max(score, 150.0)
            }
        }

        return score
    }

    /**
     * Evaluates whether the given item has a positive relevance score for the query.
     */
    fun matchesSearchQuery(
        query: String,
        itemWithVariants: InventoryItemWithVariants,
        vertical: ShopVertical = TradeTaxonomyRegistry.activeVerticalFlow.value
    ): Boolean {
        return calculateSearchRelevanceScore(query, itemWithVariants, vertical) > 0.0
    }

    fun calculateBaseConceptSimilarity(
        a: SemanticItemSignature,
        b: SemanticItemSignature
    ): Double {
        if (a.phoneticBaseTokens.isEmpty() || b.phoneticBaseTokens.isEmpty()) return 0.0

        val aConcepts = a.baseConceptTokens.filter { it.startsWith("CONCEPT_") }.toSet()
        val bConcepts = b.baseConceptTokens.filter { it.startsWith("CONCEPT_") }.toSet()

        if (aConcepts.isNotEmpty() && bConcepts.isNotEmpty() && aConcepts != bConcepts) {
            val intersection = aConcepts.intersect(bConcepts)
            if (intersection.isEmpty()) return 0.0
        }

        var totalScoreAtoB = 0.0
        for (tokenA in a.phoneticBaseTokens) {
            var bestTokenMatch = 0.0
            for (tokenB in b.phoneticBaseTokens) {
                val sim = if (tokenA == tokenB) {
                    1.0
                } else if (tokenA.startsWith("CONCEPT_") || tokenB.startsWith("CONCEPT_")) {
                    0.0
                } else {
                    val jw = PhoneticEncoder.jaroWinklerSimilarity(tokenA, tokenB)
                    if (jw >= 0.92) jw else 0.0
                }
                if (sim > bestTokenMatch) bestTokenMatch = sim
            }
            totalScoreAtoB += bestTokenMatch
        }

        var totalScoreBtoA = 0.0
        for (tokenB in b.phoneticBaseTokens) {
            var bestTokenMatch = 0.0
            for (tokenA in a.phoneticBaseTokens) {
                val sim = if (tokenB == tokenA) {
                    1.0
                } else if (tokenA.startsWith("CONCEPT_") || tokenB.startsWith("CONCEPT_")) {
                    0.0
                } else {
                    val jw = PhoneticEncoder.jaroWinklerSimilarity(tokenB, tokenA)
                    if (jw >= 0.92) jw else 0.0
                }
                if (sim > bestTokenMatch) bestTokenMatch = sim
            }
            totalScoreBtoA += bestTokenMatch
        }

        val avgA = totalScoreAtoB / a.phoneticBaseTokens.size.toDouble()
        val avgB = totalScoreBtoA / b.phoneticBaseTokens.size.toDouble()
        val diceSim = (2.0 * avgA * avgB) / max(0.0001, avgA + avgB)

        return if (aConcepts.isNotEmpty() && aConcepts == bConcepts && diceSim >= 0.55) {
            max(diceSim, 0.88)
        } else {
            diceSim
        }
    }

    fun normalizeSizeString(
        rawSize: String,
        vertical: ShopVertical = TradeTaxonomyRegistry.activeVerticalFlow.value
    ): String {
        val pack = TradeTaxonomyRegistry.getPack(vertical)
        val trimmed = rawSize.trim()
        if (trimmed.isEmpty()) return ""
        val lower = trimmed.lowercase()

        if (pack.nominalSizeEquivalents.values.any { it.equals(trimmed, ignoreCase = true) }) {
            return pack.nominalSizeEquivalents.values.first { it.equals(trimmed, ignoreCase = true) }
        }

        hindiFractionSizeRegex.find(lower)?.let { match ->
            val key = "${match.groupValues[1].lowercase()}_inch"
            pack.nominalSizeEquivalents[key]?.let { return it }
        }

        sizeRegex.find(lower)?.let { match ->
            return normalizeRegexSizeMatch(match.groupValues[1], match.groupValues[2], pack)
        }

        val num = lower.toDoubleOrNull()
        if (num != null && vertical == ShopVertical.HARDWARE) {
            val key = if (num >= 19) "${num.toInt()}mm" else "${trimmed}in"
            pack.nominalSizeEquivalents[key]?.let { return it }
        }

        return trimmed
    }

    private fun normalizeRegexSizeMatch(
        numPartRaw: String,
        unitRaw: String,
        pack: TradeVocabularyPack
    ): String {
        val numPart = numPartRaw.replace(" ", "")
        val unitLower = unitRaw.lowercase()
        val unitKey = when (unitLower) {
            "inch", "inches", "in", "\"", "''" -> "in"
            "mm" -> "mm"
            "cm" -> "cm"
            else -> unitLower
        }
        val lookupKey = "$numPart$unitKey"
        return pack.nominalSizeEquivalents[lookupKey] ?: "$numPart $unitKey"
    }

    fun normalizeMaterialString(
        rawMat: String,
        vertical: ShopVertical = TradeTaxonomyRegistry.activeVerticalFlow.value
    ): String {
        val pack = TradeTaxonomyRegistry.getPack(vertical)
        val trimmed = rawMat.trim()
        if (trimmed.isEmpty()) return ""
        val lower = trimmed.lowercase()
        pack.materialAliases[lower]?.let { return it }
        return trimmed
    }

    fun normalizeColorString(
        rawColor: String,
        vertical: ShopVertical = TradeTaxonomyRegistry.activeVerticalFlow.value
    ): String {
        val pack = TradeTaxonomyRegistry.getPack(vertical)
        val trimmed = rawColor.trim()
        if (trimmed.isEmpty()) return ""
        val lower = trimmed.lowercase()
        if (lower.contains("matt") && lower.contains("black")) return "Matt Black"
        if (lower.contains("rose") && lower.contains("gold")) return "Rose Gold"
        if (lower.contains("antique")) return "Antique Brass"
        pack.colorFinishAliases[lower]?.let { return it }
        return trimmed
    }

    private fun buildCleanBaseTitle(
        rawInput: String,
        conceptTokens: Set<String>,
        pack: TradeVocabularyPack
    ): String {
        for (token in conceptTokens) {
            pack.conceptDisplayNames[token]?.let { return it }
        }
        return rawInput.trim().split(Regex("""\s+""")).joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }
}
