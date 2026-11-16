package com.shopledger.pos.core.draft

import android.content.Context
import com.shopledger.pos.ui.screens.inventory.VariantDraft
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SavedItemDraft(
    val itemName: String = "",
    val category: String = "",
    val variants: List<VariantDraft> = emptyList(),
    val activeDraftIndex: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
) {
    val hasContent: Boolean
        get() = itemName.isNotBlank() || variants.any { v ->
            v.sizeLabel.isNotBlank() ||
                v.materialLabel.isNotBlank() ||
                v.colorFinishLabel.isNotBlank() ||
                v.barcode.isNotBlank() ||
                v.purchasePriceInput.isNotBlank() ||
                v.sellingPriceInput.isNotBlank()
        }
}

/**
 * Persistent Item Addition Draft store backed by SharedPreferences and reactive StateFlow.
 * Prevents accidental data loss when sliding down or closing the Add Product sheet.
 */
object ItemDraftManager {

    private const val PREFS_NAME = "shopledger_item_draft_prefs"
    private const val KEY_DRAFT_JSON = "active_item_draft_json"

    private val _draftFlow = MutableStateFlow<SavedItemDraft?>(null)
    val draftFlow: StateFlow<SavedItemDraft?> = _draftFlow.asStateFlow()

    fun init(context: Context) {
        runCatching {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_DRAFT_JSON, null)
            _draftFlow.value = raw?.let(::deserializeDraftFromJson)?.takeIf { it.hasContent }
        }
    }

    fun saveDraft(context: Context, draft: SavedItemDraft) {
        if (!draft.hasContent) {
            clearDraft(context)
            return
        }
        val cleanVariants = draft.variants.map { it.copy() }
        val cleanDraft = draft.copy(
            variants = cleanVariants,
            activeDraftIndex = draft.activeDraftIndex.coerceIn(0, (cleanVariants.size - 1).coerceAtLeast(0)),
            updatedAt = System.currentTimeMillis()
        )
        _draftFlow.value = cleanDraft
        runCatching {
            val json = serializeDraftToJson(cleanDraft)
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_DRAFT_JSON, json)
                .apply()
        }
    }

    fun clearDraft(context: Context) {
        _draftFlow.value = null
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_DRAFT_JSON)
                .apply()
        }
    }

    private fun escapeJson(raw: String): String = buildString(raw.length + 8) {
        for (ch in raw) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
    }

    /**
     * Pure-Kotlin JSON serializer (works identically on Android & JVM unit tests).
     */
    fun serializeDraftToJson(draft: SavedItemDraft): String {
        val sb = StringBuilder(256)
        sb.append('{')
        sb.append("\"itemName\":\"").append(escapeJson(draft.itemName)).append("\",")
        sb.append("\"category\":\"").append(escapeJson(draft.category)).append("\",")
        sb.append("\"activeDraftIndex\":").append(draft.activeDraftIndex).append(',')
        sb.append("\"updatedAt\":").append(draft.updatedAt).append(',')
        sb.append("\"variants\":[")
        draft.variants.forEachIndexed { idx, v ->
            if (idx > 0) sb.append(',')
            sb.append('{')
            sb.append("\"id\":\"").append(escapeJson(v.id)).append("\",")
            sb.append("\"sizeLabel\":\"").append(escapeJson(v.sizeLabel)).append("\",")
            sb.append("\"materialLabel\":\"").append(escapeJson(v.materialLabel)).append("\",")
            sb.append("\"colorFinishLabel\":\"").append(escapeJson(v.colorFinishLabel)).append("\",")
            sb.append("\"barcode\":\"").append(escapeJson(v.barcode)).append("\",")
            sb.append("\"unit\":\"").append(escapeJson(v.unit)).append("\",")
            sb.append("\"purchasePriceInput\":\"").append(escapeJson(v.purchasePriceInput)).append("\",")
            sb.append("\"sellingPriceInput\":\"").append(escapeJson(v.sellingPriceInput)).append("\",")
            sb.append("\"stockQtyInput\":\"").append(escapeJson(v.stockQtyInput)).append("\",")
            sb.append("\"lowStockInput\":\"").append(escapeJson(v.lowStockInput)).append("\"")
            sb.append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    fun deserializeDraftFromJson(json: String): SavedItemDraft? {
        val trimmed = json.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) return null

        return runCatching {
            val topMap = parseFlatJsonObject(trimmed)
            val variantsSection = extractJsonArrayObjects(trimmed, "variants")
            val variants = variantsSection.map { objStr ->
                val m = parseFlatJsonObject(objStr)
                VariantDraft(
                    id = m["id"] ?: java.util.UUID.randomUUID().toString(),
                    sizeLabel = m["sizeLabel"].orEmpty(),
                    materialLabel = m["materialLabel"].orEmpty(),
                    colorFinishLabel = m["colorFinishLabel"].orEmpty(),
                    barcode = m["barcode"].orEmpty(),
                    unit = m["unit"].orEmpty().ifBlank { "pcs" },
                    purchasePriceInput = m["purchasePriceInput"].orEmpty(),
                    sellingPriceInput = m["sellingPriceInput"].orEmpty(),
                    stockQtyInput = m["stockQtyInput"].orEmpty().ifBlank { "10" },
                    lowStockInput = m["lowStockInput"].orEmpty().ifBlank { "5" }
                )
            }
            SavedItemDraft(
                itemName = topMap["itemName"].orEmpty(),
                category = topMap["category"].orEmpty(),
                variants = variants,
                activeDraftIndex = (topMap["activeDraftIndex"]?.toIntOrNull() ?: 0)
                    .coerceIn(0, (variants.size - 1).coerceAtLeast(0)),
                updatedAt = topMap["updatedAt"]?.toLongOrNull() ?: System.currentTimeMillis()
            )
        }.getOrNull()
    }

    private fun parseFlatJsonObject(objText: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        var i = 0
        val n = objText.length
        while (i < n) {
            if (objText[i] == '"') {
                val (key, afterKey) = readJsonString(objText, i)
                i = afterKey
                while (i < n && objText[i].isWhitespace()) i++
                if (i < n && objText[i] == ':') {
                    i++
                    while (i < n && objText[i].isWhitespace()) i++
                    if (i < n) {
                        when (objText[i]) {
                            '"' -> {
                                val (valStr, afterVal) = readJsonString(objText, i)
                                map[key] = valStr
                                i = afterVal
                                continue
                            }
                            '[', '{' -> {
                                i = skipComposite(objText, i)
                                continue
                            }
                            else -> {
                                val start = i
                                while (i < n && objText[i] != ',' && objText[i] != '}') i++
                                map[key] = objText.substring(start, i).trim()
                                continue
                            }
                        }
                    }
                }
            }
            i++
        }
        return map
    }

    private fun extractJsonArrayObjects(jsonText: String, arrayKey: String): List<String> {
        val keyToken = "\"$arrayKey\""
        val keyIdx = jsonText.indexOf(keyToken)
        if (keyIdx < 0) return emptyList()
        val bracketStart = jsonText.indexOf('[', keyIdx + keyToken.length)
        if (bracketStart < 0) return emptyList()

        val objects = ArrayList<String>()
        var i = bracketStart + 1
        val n = jsonText.length
        while (i < n && jsonText[i] != ']') {
            val ch = jsonText[i]
            if (ch == '"') {
                val (_, nextIdx) = readJsonString(jsonText, i)
                i = nextIdx
                continue
            }
            if (ch == '{') {
                val endIdx = skipComposite(jsonText, i)
                objects.add(jsonText.substring(i, endIdx))
                i = endIdx
                continue
            }
            i++
        }
        return objects
    }

    private fun readJsonString(text: String, startQuoteIdx: Int): Pair<String, Int> {
        val sb = StringBuilder()
        var i = startQuoteIdx + 1
        val n = text.length
        while (i < n) {
            val ch = text[i]
            if (ch == '\\' && i + 1 < n) {
                when (val esc = text[i + 1]) {
                    '\\' -> sb.append('\\')
                    '"' -> sb.append('"')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    else -> sb.append(esc)
                }
                i += 2
            } else if (ch == '"') {
                return sb.toString() to (i + 1)
            } else {
                sb.append(ch)
                i++
            }
        }
        return sb.toString() to n
    }

    private fun skipComposite(text: String, startIdx: Int): Int {
        var depth = 0
        var i = startIdx
        val n = text.length
        while (i < n) {
            val ch = text[i]
            if (ch == '"') {
                i = readJsonString(text, i).second
                continue
            }
            if (ch == '{' || ch == '[') depth++
            else if (ch == '}' || ch == ']') {
                depth--
                if (depth <= 0) return i + 1
            }
            i++
        }
        return n
    }
}
