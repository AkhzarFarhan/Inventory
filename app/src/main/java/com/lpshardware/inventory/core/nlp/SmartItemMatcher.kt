package com.lpshardware.inventory.core.nlp

import androidx.compose.runtime.Immutable
import com.lpshardware.inventory.data.model.InventoryItemWithVariants
import kotlin.math.max

/**
 * Structured semantic decomposition of a hardware item name and its variant attributes.
 */
@Immutable
data class SemanticItemSignature(
    val originalName: String,
    /** Core product concept tokens (stripped of size, color, and material attributes) */
    val baseConceptTokens: Set<String>,
    /** Phonetic keys of the core product tokens */
    val phoneticBaseTokens: Set<String>,
    /** Extracted or explicitly provided canonical Size (e.g. `"4 inch (100mm)"`) */
    val canonicalSize: String,
    /** Extracted or explicitly provided canonical Material (e.g. `"SS"`, `"Brass"`) */
    val canonicalMaterial: String,
    /** Extracted or explicitly provided canonical Color/Finish (e.g. `"Matt Black"`, `"Antique Brass"`) */
    val canonicalColorFinish: String,
    /** Suggested clean parent item name (with variant attributes separated out) */
    val suggestedCleanBaseName: String
)

enum class DuplicateDecisionType {
    /** Exact same base item AND same variant attributes (Size, Material, Color) */
    EXACT_DUPLICATE,
    /** Same base item concept, but different Size, Material, or Color/Finish */
    NEW_VARIANT_OF_EXISTING,
    /** Close semantic similarity — warn seller gently */
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
        pattern = """(\d+(?:\.\d+)?|\d+\s*/\s*\d+)\s*(inch|inches|in|"|''|mm|cm|ft|feet|mtr|meter|g|gm|kg|ml|ltr)\b""",
        option = RegexOption.IGNORE_CASE
    )

    private val hindiFractionSizeRegex = Regex(
        pattern = """\b(aadha|half|pauna|sawa|dedh|dhai)\s*(inch|in|"|ft|kg)?\b""",
        option = RegexOption.IGNORE_CASE
    )

    /**
     * Parses any hardware item name + optional explicit variant inputs into a [SemanticItemSignature].
     * Automatically understands Hinglish terms, material codes (`SS`, `MS`, `GI`, `Brass`),
     * color/finishes (`Antique`, `Matt Black`, `Rose Gold`), and metric/imperial sizes (`4"` == `100mm`).
     */
    fun parseSignature(
        rawItemName: String,
        explicitSize: String = "",
        explicitMaterial: String = "",
        explicitColorFinish: String = ""
    ): SemanticItemSignature {
        var workingText = rawItemName.lowercase().trim()

        // 1. Extract Size from explicit field or item name
        var detectedSize = normalizeSizeString(explicitSize)
        val sizeRangesToRemove = mutableListOf<IntRange>()

        hindiFractionSizeRegex.findAll(workingText).forEach { match ->
            val word = match.groupValues[1].lowercase()
            val key = "${word}_inch"
            val mapped = HardwareOntology.nominalSizeEquivalents[key]
            if (mapped != null) {
                if (detectedSize.isEmpty()) detectedSize = mapped
                sizeRangesToRemove.add(match.range)
            }
        }

        sizeRegex.findAll(workingText).forEach { match ->
            val norm = normalizeRegexSizeMatch(match.groupValues[1], match.groupValues[2])
            if (detectedSize.isEmpty()) detectedSize = norm
            sizeRangesToRemove.add(match.range)
        }

        // Remove extracted size substrings from working text so base product concept stays pure
        for (range in sizeRangesToRemove.sortedByDescending { it.first }) {
            workingText = workingText.removeRange(range)
        }

        // 2. Replace multi-word hardware phrases with canonical concept tokens
        val conceptTokens = LinkedHashSet<String>()
        for ((phrase, canonicalConcept) in HardwareOntology.phraseSynonyms) {
            if (workingText.contains(phrase)) {
                conceptTokens.add(canonicalConcept)
                workingText = workingText.replace(phrase, " ")
            }
        }

        // 3. Tokenize words and separate Materials, Colors/Finishes, and Base Concepts
        val rawTokens = workingText
            .replace(Regex("""[^a-z0-9./]+"""), " ")
            .trim()
            .split(Regex("""\s+"""))
            .filter { it.isNotBlank() }

        var detectedMaterial = normalizeMaterialString(explicitMaterial)
        val detectedColorParts = LinkedHashSet<String>()
        if (explicitColorFinish.isNotBlank()) {
            detectedColorParts.add(normalizeColorString(explicitColorFinish))
        }

        val remainingWords = mutableListOf<String>()

        for (token in rawTokens) {
            if (token in HardwareOntology.stopWords) continue

            val materialMatch = HardwareOntology.materialAliases[token]
            if (materialMatch != null) {
                if (detectedMaterial.isEmpty()) {
                    detectedMaterial = materialMatch
                } else if (materialMatch.startsWith("SS ") && detectedMaterial == "SS") {
                    detectedMaterial = materialMatch
                }
                continue
            }

            val colorMatch = HardwareOntology.colorFinishAliases[token]
            if (colorMatch != null) {
                detectedColorParts.add(colorMatch)
                continue
            }

            val wordConcept = HardwareOntology.wordSynonyms[token]
            if (wordConcept != null) {
                conceptTokens.add(wordConcept)
            } else {
                remainingWords.add(token)
            }
        }

        // Normalize composite colors like "Matt" + "Black" -> "Matt Black"
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

        // Fallback if input was purely attributes (e.g. "SS 304")
        if (conceptTokens.isEmpty() && rawItemName.isNotBlank()) {
            val fallback = PhoneticEncoder.encodeWord(rawItemName)
            conceptTokens.add(rawItemName.lowercase().trim())
            phoneticSet.add(fallback)
        }

        // Build clean suggested parent title
        val cleanBaseTitle = buildCleanBaseTitle(rawItemName, conceptTokens)

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
     * Checks a candidate new item against the shop's existing inventory using semantic & phonetic
     * concept matching (not naive string equality).
     */
    fun checkDuplicateOrVariant(
        candidateName: String,
        candidateSize: String,
        candidateMaterial: String,
        candidateColorFinish: String,
        existingInventory: List<InventoryItemWithVariants>,
        excludeItemId: String? = null
    ): DuplicateCheckResult {
        val candidateSig = parseSignature(
            rawItemName = candidateName,
            explicitSize = candidateSize,
            explicitMaterial = candidateMaterial,
            explicitColorFinish = candidateColorFinish
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
            val existingSig = parseSignature(existing.item.displayName)
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

        // If base concept matches strongly (>= 0.80), compare variant attributes (Size, Material, Color)
        if (bestScore >= 0.80) {
            val candSize = candidateSig.canonicalSize
            val candMat = candidateSig.canonicalMaterial.ifEmpty { bestExistingSig?.canonicalMaterial.orEmpty() }
            val candColor = candidateSig.canonicalColorFinish.ifEmpty { bestExistingSig?.canonicalColorFinish.orEmpty() }

            // Check if any existing variant of this parent item has the same Size + Material + Color
            val matchingVariant = matched.variants.firstOrNull { variant ->
                val vSize = normalizeSizeString(variant.sizeLabel)
                val vMat = normalizeMaterialString(variant.materialLabel)
                val vColor = normalizeColorString(variant.colorFinishLabel)

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

        // Moderate similarity (0.65 .. 0.79)
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
     * Calculates a search relevance score >= 0.0 for an inventory item.
     * Returns 0.0 if the item does NOT match the query and should be filtered out.
     *
     * Higher score indicates higher search relevance:
     * - Exact item display name match: 10,000
     * - Direct word token match in item display name: 4,000 to 6,500
     * - Prefix match of words in item display name: 2,500 to 3,500
     * - Domain ontology concept match (e.g. "kabza" -> Hinge, "pech" -> Screw): 3,500 to 5,500
     * - Substring match in item display name: 1,500 to 2,300
     * - Match in variant attributes (size, material, finish): 700 to 1,500
     * - Typo tolerance with Levenshtein distance on discrete words: 2,000
     * - Category match: 150 to 300 (intentionally low so category matches never rank above name matches)
     */
    fun calculateSearchRelevanceScore(
        query: String,
        itemWithVariants: InventoryItemWithVariants
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
                wordScore += 1500.0 // All query words match in title!
            }
            if (nameWords.firstOrNull() == queryWords.firstOrNull()) {
                wordScore += 1000.0
            } else if (lowerDisplayName.startsWith(lowerQuery)) {
                wordScore += 800.0
            }
            score = max(score, wordScore)
        }

        // 3. Prefix match in item displayName (e.g. "cemen" matches "cement", "hing" matches "hinge")
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

        // 5. Semantic & Hardware Domain Ontology Concepts (Hinglish trade synonyms like "kabza" -> Hinge)
        val querySig = parseSignature(trimmed)
        val itemSig = parseSignature(itemWithVariants.item.displayName)

        // Check size requirement if specified in query
        var sizeMatched = false
        if (querySig.canonicalSize.isNotEmpty()) {
            val hasSize = itemWithVariants.variants.any {
                normalizeSizeString(it.sizeLabel).equals(querySig.canonicalSize, ignoreCase = true)
            } || itemSig.canonicalSize.equals(querySig.canonicalSize, ignoreCase = true)
            if (!hasSize) {
                return 0.0
            }
            sizeMatched = true
        }

        // Check material requirement if specified in query
        var materialMatched = false
        if (querySig.canonicalMaterial.isNotEmpty()) {
            val hasMat = itemWithVariants.variants.any {
                normalizeMaterialString(it.materialLabel).equals(querySig.canonicalMaterial, ignoreCase = true)
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

        // Standalone size or material queries (e.g. query is "4 inch" or "SS 304")
        if (sizeMatched && queryConcepts.isEmpty() && score == 0.0) {
            score = max(score, 1500.0)
        }
        if (materialMatched && queryConcepts.isEmpty() && score == 0.0) {
            score = max(score, 1500.0)
        }

        // 6. Variant Attribute Matching (Size, Material, Color/Finish)
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

        // 7. Typo tolerance using Levenshtein distance on discrete words (e.g. "cemnt" for "cement")
        if (score == 0.0 && queryWords.isNotEmpty()) {
            var typoCount = 0
            for (q in queryWords) {
                if (q.length < 3) continue
                val maxAllowedDist = if (q.length <= 4) 1 else 2
                val hasTypoMatch = nameWords.any { nw ->
                    nw.first() == q.first() && Math.abs(nw.length - q.length) <= maxAllowedDist &&
                        levenshteinDistance(q, nw) <= maxAllowedDist
                }
                if (hasTypoMatch) typoCount++
            }
            if (typoCount > 0) {
                score = 2000.0 * (typoCount.toDouble() / queryWords.size)
            }
        }

        // 8. Category match (low weight so category matches never rank above name matches)
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
     * Semantic search matcher used by the Inventory search bar and search history recording.
     * Evaluates whether the given item has a positive relevance score for the query.
     */
    fun matchesSearchQuery(
        query: String,
        itemWithVariants: InventoryItemWithVariants
    ): Boolean {
        return calculateSearchRelevanceScore(query, itemWithVariants) > 0.0
    }

    /**
     * Computes semantic similarity between two [SemanticItemSignature] base concepts in `[0.0, 1.0]`.
     */
    fun calculateBaseConceptSimilarity(
        a: SemanticItemSignature,
        b: SemanticItemSignature
    ): Double {
        if (a.phoneticBaseTokens.isEmpty() || b.phoneticBaseTokens.isEmpty()) return 0.0

        // Exact canonical concept overlap (e.g. both have CONCEPT_HINGE)
        val aConcepts = a.baseConceptTokens.filter { it.startsWith("CONCEPT_") }.toSet()
        val bConcepts = b.baseConceptTokens.filter { it.startsWith("CONCEPT_") }.toSet()

        if (aConcepts.isNotEmpty() && bConcepts.isNotEmpty() && aConcepts != bConcepts) {
            // Two different known hardware concepts (e.g. CONCEPT_HINGE vs CONCEPT_SCREW)
            val intersection = aConcepts.intersect(bConcepts)
            if (intersection.isEmpty()) return 0.0
        }

        // Soft token matching using phonetic skeleton + Jaro-Winkler
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

        // If both share the exact same canonical hardware concept and have no conflicting brand words, boost
        return if (aConcepts.isNotEmpty() && aConcepts == bConcepts && diceSim >= 0.55) {
            max(diceSim, 0.88)
        } else {
            diceSim
        }
    }

    fun normalizeSizeString(rawSize: String): String {
        val trimmed = rawSize.trim()
        if (trimmed.isEmpty()) return ""
        val lower = trimmed.lowercase()

        // Already normalized format like "4 inch (100mm)"
        if (HardwareOntology.nominalSizeEquivalents.values.any { it.equals(trimmed, ignoreCase = true) }) {
            return HardwareOntology.nominalSizeEquivalents.values.first { it.equals(trimmed, ignoreCase = true) }
        }

        hindiFractionSizeRegex.find(lower)?.let { match ->
            val key = "${match.groupValues[1].lowercase()}_inch"
            HardwareOntology.nominalSizeEquivalents[key]?.let { return it }
        }

        sizeRegex.find(lower)?.let { match ->
            return normalizeRegexSizeMatch(match.groupValues[1], match.groupValues[2])
        }

        // Plain number (assume inches if 1..18, or mm if >= 20)
        val num = lower.toDoubleOrNull()
        if (num != null) {
            val key = if (num >= 19) "${num.toInt()}mm" else "${trimmed}in"
            HardwareOntology.nominalSizeEquivalents[key]?.let { return it }
        }

        return trimmed
    }

    private fun normalizeRegexSizeMatch(numPartRaw: String, unitRaw: String): String {
        val numPart = numPartRaw.replace(" ", "")
        val unitLower = unitRaw.lowercase()
        val unitKey = when (unitLower) {
            "inch", "inches", "in", "\"", "''" -> "in"
            "mm" -> "mm"
            "cm" -> "cm"
            else -> unitLower
        }
        val lookupKey = "$numPart$unitKey"
        return HardwareOntology.nominalSizeEquivalents[lookupKey] ?: "$numPart $unitKey"
    }

    fun normalizeMaterialString(rawMat: String): String {
        val trimmed = rawMat.trim()
        if (trimmed.isEmpty()) return ""
        val lower = trimmed.lowercase()
        HardwareOntology.materialAliases[lower]?.let { return it }
        for ((alias, canonical) in HardwareOntology.materialAliases) {
            if (lower == alias) return canonical
        }
        return trimmed
    }

    fun normalizeColorString(rawColor: String): String {
        val trimmed = rawColor.trim()
        if (trimmed.isEmpty()) return ""
        val lower = trimmed.lowercase()
        if (lower.contains("matt") && lower.contains("black")) return "Matt Black"
        if (lower.contains("rose") && lower.contains("gold")) return "Rose Gold"
        if (lower.contains("antique")) return "Antique Brass"
        HardwareOntology.colorFinishAliases[lower]?.let { return it }
        return trimmed
    }

    private fun buildCleanBaseTitle(rawInput: String, conceptTokens: Set<String>): String {
        val conceptDisplayMap = mapOf(
            "CONCEPT_HINGE" to "Butt Hinge (Kabza)",
            "CONCEPT_PARLIAMENT_HINGE" to "Parliament Hinge",
            "CONCEPT_AUTO_HINGE" to "Hydraulic Auto Hinge",
            "CONCEPT_TOWER_BOLT" to "Tower Bolt (Chatkani)",
            "CONCEPT_ALDROP" to "Door Aldrop",
            "CONCEPT_MORTISE_LOCK" to "Mortise Handle Lock",
            "CONCEPT_PADLOCK" to "Padlock (Tala)",
            "CONCEPT_WOOD_SCREW" to "Wood Screw",
            "CONCEPT_DRYWALL_SCREW" to "Drywall Screw (Black)",
            "CONCEPT_SELF_TAPPING_SCREW" to "Self Tapping Screw",
            "CONCEPT_SCREW" to "Screw (Pech)",
            "CONCEPT_NAIL" to "Wire Nail (Kila)",
            "CONCEPT_WALL_PLUG" to "Wall Plug (Gitti)",
            "CONCEPT_BIB_COCK" to "Bib Cock (Tap)",
            "CONCEPT_ANGLE_VALVE" to "Angle Valve",
            "CONCEPT_BALL_VALVE" to "Ball Valve",
            "CONCEPT_ELBOW" to "Pipe Elbow",
            "CONCEPT_TEE" to "Pipe Tee",
            "CONCEPT_SOCKET" to "Pipe Socket",
            "CONCEPT_TEFLON_TAPE" to "Teflon Tape",
            "CONCEPT_SANDPAPER" to "Emery Sandpaper (Regmal)",
            "CONCEPT_ADHESIVE" to "Wood Adhesive (Fevicol)",
            "CONCEPT_DRAWER_CHANNEL" to "Telescopic Drawer Channel"
        )
        for (token in conceptTokens) {
            conceptDisplayMap[token]?.let { return it }
        }
        return rawInput.trim().split(Regex("""\s+""")).joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }
}
