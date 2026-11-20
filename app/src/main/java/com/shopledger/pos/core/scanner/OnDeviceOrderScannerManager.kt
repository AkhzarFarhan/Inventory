package com.shopledger.pos.core.scanner

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import com.google.android.gms.tasks.Tasks
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.shopledger.pos.core.nlp.ShopVertical
import com.shopledger.pos.core.nlp.SmartItemMatcher
import com.shopledger.pos.core.sync.SyncManager
import com.shopledger.pos.data.local.entity.ItemVariantEntity
import com.shopledger.pos.data.model.CartLineItem
import com.shopledger.pos.data.model.InventoryItemWithVariants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

sealed interface LlmModelState {
    data object NotDownloaded : LlmModelState
    data class Downloading(
        val progress: Float,
        val bytesDownloaded: Long,
        val totalBytes: Long
    ) : LlmModelState
    data class Ready(
        val modelName: String,
        val sizeBytes: Long,
        val filePath: String
    ) : LlmModelState
    data class Error(val message: String) : LlmModelState
}

data class ParsedOrderLine(
    val itemQuery: String,
    val quantity: Double,
    val rawLine: String
)

data class OrderScanResult(
    val rawOcrText: String,
    val matchedCartLines: List<CartLineItem>,
    val unmatchedLines: List<String>,
    val usedOnDeviceLlm: Boolean
)

/**
 * Unbundled On-Device Receipt & Order List Scanner powered by:
 * 1. Google Play Services ML Kit Text Recognition (downloaded dynamically by GMS, zero APK bloat).
 * 2. Optional downloadable On-Device MediaPipe LLM model (stored strictly in app filesDir, never bundled).
 * 3. Deterministic + SmartItemMatcher bilingual POS parser for instant offline cart population.
 */
object OnDeviceOrderScannerManager {

    const val MODEL_DIR_NAME = "llm_models"
    const val DEFAULT_MODEL_FILENAME = "Qwen2.5-0.5B-Instruct-int4.task"
    const val DEFAULT_RECOMMENDED_MODEL_URL =
        "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"

    private val _modelState = MutableStateFlow<LlmModelState>(LlmModelState.NotDownloaded)
    val modelState: StateFlow<LlmModelState> = _modelState.asStateFlow()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    internal val KNOWN_UNITS = setOf(
        "pc", "pcs", "piece", "pieces",
        "pkt", "pkts", "packet", "packets", "pack",
        "kg", "kgs", "kilo", "g", "gm", "gms", "gram", "grams",
        "l", "ltr", "ltrs", "litre", "litres", "liter", "liters", "ml",
        "box", "boxes", "set", "sets", "pair", "pairs",
        "dzn", "dozen", "mtr", "m", "meter", "meters", "ft", "feet", "inch",
        "roll", "rolls", "bag", "bags", "bottle", "bottles",
        "strip", "strips", "tube", "tubes", "vial", "vials", "can", "cans"
    )

    private val NOISE_LINE_KEYWORDS = listOf(
        "grand total", "sub total", "subtotal", "total amount", "net payable",
        "tax invoice", "sale receipt", "estimate", "quotation", "thank you",
        "visit again", "gstin", "cgst", "sgst", "igst", "balance due",
        "amount paid", "payment mode", "bill no", "invoice no", "cash", "upi"
    )

    fun refreshModelState(context: Context): LlmModelState {
        val file = getDownloadedModelFile(context)
        val next = if (file != null && file.length() > 1024L * 1024L) {
            LlmModelState.Ready(
                modelName = file.name,
                sizeBytes = file.length(),
                filePath = file.absolutePath
            )
        } else {
            LlmModelState.NotDownloaded
        }
        _modelState.value = next
        return next
    }

    fun getDownloadedModelFile(context: Context): File? {
        val dir = File(context.filesDir, MODEL_DIR_NAME)
        if (!dir.exists()) return null
        return dir.listFiles()
            ?.firstOrNull {
                it.isFile &&
                    it.length() > 1024L * 1024L &&
                    (it.name.endsWith(".task", ignoreCase = true) || it.name.endsWith(".bin", ignoreCase = true))
            }
    }

    fun deleteDownloadedModel(context: Context) {
        val dir = File(context.filesDir, MODEL_DIR_NAME)
        if (dir.exists()) {
            dir.listFiles()?.forEach { it.delete() }
        }
        _modelState.value = LlmModelState.NotDownloaded
    }

    suspend fun downloadModel(
        context: Context,
        modelUrl: String = DEFAULT_RECOMMENDED_MODEL_URL
    ): Result<File> = withContext(Dispatchers.IO) {
        val cleanUrl = modelUrl.trim()
        if (cleanUrl.isBlank()) {
            val err = LlmModelState.Error("Enter a valid model download URL (.task / .bin)")
            _modelState.value = err
            return@withContext Result.failure(IllegalArgumentException(err.message))
        }

        val dir = File(context.filesDir, MODEL_DIR_NAME).apply { mkdirs() }
        val fileName = Uri.parse(cleanUrl).lastPathSegment
            ?.takeIf { it.endsWith(".task", true) || it.endsWith(".bin", true) }
            ?: DEFAULT_MODEL_FILENAME
        val tempFile = File(dir, "$fileName.part")
        val targetFile = File(dir, fileName)

        try {
            _modelState.value = LlmModelState.Downloading(0f, 0L, 0L)
            val request = Request.Builder()
                .url(cleanUrl)
                .header("User-Agent", "ShopLedger-POS-Android")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val msg = "Download failed: HTTP ${response.code}"
                    _modelState.value = LlmModelState.Error(msg)
                    return@withContext Result.failure(IllegalStateException(msg))
                }
                val body = response.body ?: throw IllegalStateException("Empty download response")
                val totalBytes = body.contentLength()
                var downloaded = 0L

                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            val progress = if (totalBytes > 0L) downloaded.toFloat() / totalBytes else 0f
                            _modelState.value = LlmModelState.Downloading(progress, downloaded, totalBytes)
                        }
                        output.flush()
                    }
                }
            }

            if (tempFile.length() < 1024L * 1024L) {
                tempFile.delete()
                val msg = "Downloaded file is too small or invalid."
                _modelState.value = LlmModelState.Error(msg)
                return@withContext Result.failure(IllegalStateException(msg))
            }

            dir.listFiles()?.forEach { if (it != tempFile) it.delete() }
            if (targetFile.exists()) targetFile.delete()
            tempFile.renameTo(targetFile)

            _modelState.value = LlmModelState.Ready(
                modelName = targetFile.name,
                sizeBytes = targetFile.length(),
                filePath = targetFile.absolutePath
            )
            Result.success(targetFile)
        } catch (e: Exception) {
            tempFile.delete()
            val msg = e.localizedMessage ?: "Model download failed"
            _modelState.value = LlmModelState.Error(msg)
            Result.failure(e)
        }
    }

    suspend fun importLocalModelUri(context: Context, uri: Uri): Result<File> = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.filesDir, MODEL_DIR_NAME).apply { mkdirs() }
            val resolvedName = resolveFileName(context, uri)
                ?.takeIf { it.endsWith(".task", true) || it.endsWith(".bin", true) }
                ?: DEFAULT_MODEL_FILENAME
            val targetFile = File(dir, resolvedName)
            val tempFile = File(dir, "$resolvedName.part")

            _modelState.value = LlmModelState.Downloading(0f, 0L, 0L)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var copied = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        copied += read
                        _modelState.value = LlmModelState.Downloading(0f, copied, 0L)
                    }
                    output.flush()
                }
            } ?: throw IllegalStateException("Could not open selected model file")

            if (tempFile.length() < 1024L * 1024L) {
                tempFile.delete()
                throw IllegalStateException("Selected file is not a valid MediaPipe LLM model (.task / .bin)")
            }

            dir.listFiles()?.forEach { if (it != tempFile) it.delete() }
            if (targetFile.exists()) targetFile.delete()
            tempFile.renameTo(targetFile)

            _modelState.value = LlmModelState.Ready(
                modelName = targetFile.name,
                sizeBytes = targetFile.length(),
                filePath = targetFile.absolutePath
            )
            Result.success(targetFile)
        } catch (e: Exception) {
            val msg = e.localizedMessage ?: "Model import failed"
            _modelState.value = LlmModelState.Error(msg)
            Result.failure(e)
        }
    }

    private fun resolveFileName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: uri.lastPathSegment
        } catch (_: Exception) {
            uri.lastPathSegment
        }
    }

    suspend fun extractTextFromBitmap(bitmap: Bitmap): Result<String> = withContext(Dispatchers.Default) {
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val visionText = Tasks.await(recognizer.process(image))
            recognizer.close()
            Result.success(reconstructSpatialOrderLines(visionText))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun extractTextFromUri(context: Context, uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        try {
            val image = InputImage.fromFilePath(context, uri)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val visionText = Tasks.await(recognizer.process(image))
            recognizer.close()
            Result.success(reconstructSpatialOrderLines(visionText))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Reconstructs tabular rows from ML Kit bounding boxes by grouping [Text.Line] elements
     * sharing the same horizontal band and ordering them left-to-right.
     */
    private fun reconstructSpatialOrderLines(visionText: Text): String {
        val allLines = visionText.textBlocks.flatMap { it.lines }
        if (allLines.isEmpty()) return visionText.text

        val sortedByY = allLines.sortedBy { it.boundingBox?.centerY() ?: 0 }
        val rows = ArrayList<MutableList<Text.Line>>()

        for (line in sortedByY) {
            val box = line.boundingBox
            if (box == null) {
                rows.add(mutableListOf(line))
                continue
            }
            val centerY = box.centerY().toFloat()
            val height = (box.height().coerceAtLeast(16)).toFloat()
            val matchingRow = rows.lastOrNull { row ->
                val rowBoxes = row.mapNotNull { it.boundingBox }
                if (rowBoxes.isEmpty()) false
                else {
                    val avgY = rowBoxes.map { it.centerY() }.average().toFloat()
                    val avgH = rowBoxes.map { it.height().coerceAtLeast(16) }.average().toFloat()
                    abs(centerY - avgY) <= maxOf(height, avgH) * 0.65f
                }
            }
            if (matchingRow != null) {
                matchingRow.add(line)
            } else {
                rows.add(mutableListOf(line))
            }
        }

        return rows.joinToString("\n") { row ->
            row.sortedBy { it.boundingBox?.left ?: 0 }
                .joinToString(" ") { it.text.trim() }
                .trim()
        }.trim()
    }

    /**
     * Parses a scanned receipt or order list text into cart-ready [CartLineItem] entries.
     * Uses the downloaded on-device MediaPipe LLM when present (falling back cleanly to deterministic parsing),
     * then matches every extracted item against [inventory] via [SmartItemMatcher].
     */
    suspend fun parseOrderListToCart(
        rawText: String,
        inventory: List<InventoryItemWithVariants>,
        context: Context? = null,
        vertical: ShopVertical = SyncManager.getActiveVertical()
    ): OrderScanResult = withContext(Dispatchers.Default) {
        var textForParsing = rawText
        var usedLlm = false

        if (context != null) {
            val modelFile = getDownloadedModelFile(context)
            if (modelFile != null) {
                val llmExtracted = runOnDeviceLlmExtraction(context, modelFile, rawText)
                if (!llmExtracted.isNullOrBlank()) {
                    textForParsing = llmExtracted
                    usedLlm = true
                }
            }
        }

        val parsedLines = extractOrderLines(textForParsing)
        val mergedByVariant = LinkedHashMap<String, CartLineItem>()
        val unmatched = ArrayList<String>()

        for (parsed in parsedLines) {
            val match = matchLineToInventory(parsed, inventory, vertical)
            if (match == null) {
                unmatched.add(parsed.rawLine)
            } else {
                val (parent, variant) = match
                val existing = mergedByVariant[variant.id]
                if (existing != null) {
                    mergedByVariant[variant.id] = existing.copy(
                        quantity = existing.quantity + parsed.quantity
                    )
                } else {
                    mergedByVariant[variant.id] = CartLineItem(
                        itemId = parent.item.id,
                        variantId = variant.id,
                        itemName = parent.item.displayName,
                        variantSummary = variant.buildVariantSummary(),
                        unit = variant.unit,
                        availableStock = variant.stockQuantity,
                        unitPurchasePricePaise = variant.purchasePricePaise,
                        defaultSellingPricePaise = variant.sellingPricePaise,
                        customSellingPricePaise = variant.sellingPricePaise,
                        skuCode = variant.skuCode,
                        barcode = variant.barcode,
                        gstRateBps = variant.gstRateBps,
                        quantity = parsed.quantity
                    )
                }
            }
        }

        OrderScanResult(
            rawOcrText = rawText,
            matchedCartLines = mergedByVariant.values.toList(),
            unmatchedLines = unmatched,
            usedOnDeviceLlm = usedLlm
        )
    }

    /**
     * Stock-in variant of the optional on-device LLM clean-up: one line per item as
     * `ITEM | QTY | UNIT | RATE | AMOUNT` (`-` when unknown). Returns null when no model is installed or it
     * fails, so callers fall back to the raw OCR text.
     */
    suspend fun extractStockLines(context: Context, rawText: String): String? = withContext(Dispatchers.Default) {
        val modelFile = getDownloadedModelFile(context) ?: return@withContext null
        try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(768)
                .build()
            val inference = LlmInference.createFromOptions(context, options)
            try {
                val prompt = buildString {
                    appendLine("Extract each product received from this supplier bill or handwritten stock list.")
                    appendLine("Output one item per line strictly in format: ITEM | QTY | UNIT | RATE | AMOUNT")
                    appendLine("Use - for any value that is not written. Keep size and brand words in ITEM.")
                    appendLine("Ignore totals, taxes, dates, bill numbers, phone numbers, headers and shop names.")
                    appendLine("---")
                    appendLine(rawText.take(3000))
                }
                inference.generateResponse(prompt)?.trim()?.takeIf { it.contains('|') }
            } finally {
                inference.close()
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun runOnDeviceLlmExtraction(
        context: Context,
        modelFile: File,
        rawText: String
    ): String? {
        return try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(512)
                .build()
            val inference = LlmInference.createFromOptions(context, options)
            try {
                val prompt = buildString {
                    appendLine("Extract each ordered product and quantity from this shop receipt/order list.")
                    appendLine("Output one item per line strictly in format: ITEM_NAME | VARIANT | QUANTITY | UNIT")
                    appendLine("Ignore totals, dates, headers, phone numbers, and shop names.")
                    appendLine("---")
                    appendLine(rawText.take(2500))
                }
                inference.generateResponse(prompt)?.trim()
            } finally {
                inference.close()
            }
        } catch (_: Throwable) {
            null
        }
    }

    internal fun extractOrderLines(rawText: String): List<ParsedOrderLine> {
        val rawRows = rawText
            .split('\n', ';')
            .map { it.trim() }
            .filter { it.length >= 2 }

        val results = ArrayList<ParsedOrderLine>()
        for (rawLine in rawRows) {
            // 1. Check pipe-delimited LLM output format: ITEM | VARIANT | QTY | UNIT
            if (rawLine.contains('|')) {
                val parts = rawLine.split('|').map { it.trim() }
                if (parts.size >= 3) {
                    val qty = parts[2].replace(",", "").toDoubleOrNull()
                    if (qty != null && qty > 0.0) {
                        val query = listOf(parts[0], parts.getOrNull(1).orEmpty(), parts.getOrNull(3).orEmpty())
                            .filter { it.isNotBlank() && !it.equals("none", true) && !it.equals("n/a", true) }
                            .joinToString(" ")
                        if (query.isNotBlank()) {
                            results.add(ParsedOrderLine(query, qty, rawLine))
                            continue
                        }
                    }
                }
            }

            // Strip leading bullets / serial numbering ("1.", "2)", "(3)", "-", "•")
            var cleaned = rawLine
                .replace(Regex("""^\s*(?:\(?\d{1,3}\s*[.)\]:-]+|[-•*☑✓➤]+)\s*"""), "")
                .trim()
            if (cleaned.length < 2) continue

            val lower = cleaned.lowercase(Locale.ENGLISH)
            if (NOISE_LINE_KEYWORDS.any { lower == it || lower.startsWith("$it ") || lower.startsWith("$it:") }) {
                continue
            }
            // Skip pure currency / price totals like "Rs. 1,250.00"
            if (Regex("""^(?:rs\.?|inr|₹)?\s*\d[\d,]*(?:\.\d+)?\s*$""", RegexOption.IGNORE_CASE).matches(cleaned)) {
                continue
            }

            // Strip trailing currency price annotations if accompanied by quantity earlier (e.g. "x 5 = ₹250")
            cleaned = cleaned.replace(Regex("""\s*[=@]?\s*(?:rs\.?|inr|₹)\s*\d[\d,]*(?:\.\d+)?\s*$""", RegexOption.IGNORE_CASE), "").trim()

            var extractedQty: Double? = null
            var queryPart = cleaned

            // Pattern A: explicit multiplier at end: "4 inch SS Kabza x 12", "White Cement * 3 pkt"
            val multMatch = Regex("""^(.*?)\s*[x×*]\s*(\d+(?:\.\d+)?)(?:\s+([a-zA-Z]+))?\s*$""", RegexOption.IGNORE_CASE)
                .find(cleaned)
            if (multMatch != null) {
                val candidateQuery = multMatch.groupValues[1].trim()
                val qtyVal = multMatch.groupValues[2].toDoubleOrNull()
                if (candidateQuery.isNotBlank() && qtyVal != null && qtyVal > 0.0) {
                    extractedQty = qtyVal
                    queryPart = candidateQuery
                }
            }

            // Pattern B: trailing "- 5 pkt" / ": 2.5 kg" / "Qty: 4" / "5 pkt"
            if (extractedQty == null) {
                val trailUnitMatch = Regex("""^(.*?)(?:\s*[-:,]?\s*(?:qty|qnty|quantity)?\s*[:=-]?\s*)(\d+(?:\.\d+)?)\s*([a-zA-Z]+)\s*$""", RegexOption.IGNORE_CASE)
                    .find(cleaned)
                if (trailUnitMatch != null) {
                    val left = trailUnitMatch.groupValues[1].trim()
                    val qtyVal = trailUnitMatch.groupValues[2].toDoubleOrNull()
                    val unitWord = trailUnitMatch.groupValues[3].lowercase(Locale.ENGLISH)
                    if (left.isNotBlank() && qtyVal != null && qtyVal > 0.0 && unitWord in KNOWN_UNITS && unitWord != "inch") {
                        extractedQty = qtyVal
                        queryPart = left.trimEnd('-', ':', ',', ' ')
                    }
                }
            }

            // Pattern C: leading "5 pkt White Cement" or "2.5 kg Sugar"
            if (extractedQty == null) {
                val leadUnitMatch = Regex("""^(\d+(?:\.\d+)?)\s*([a-zA-Z]+)?\s+(.+)$""").find(cleaned)
                if (leadUnitMatch != null) {
                    val qtyVal = leadUnitMatch.groupValues[1].toDoubleOrNull()
                    val maybeUnit = leadUnitMatch.groupValues[2].lowercase(Locale.ENGLISH)
                    val rest = leadUnitMatch.groupValues[3].trim()
                    if (qtyVal != null && qtyVal > 0.0 && rest.length >= 2) {
                        if (maybeUnit in KNOWN_UNITS && maybeUnit != "inch") {
                            extractedQty = qtyVal
                            queryPart = rest
                        } else if (maybeUnit.isEmpty()) {
                            extractedQty = qtyVal
                            queryPart = rest
                        }
                    }
                }
            }

            // Pattern D: trailing standalone qty "Fevicol SH 2" or "White Cement - 5"
            if (extractedQty == null) {
                val trailNumMatch = Regex("""^(.*?)(?:\s*[-:,]\s*|\s+)(\d+(?:\.\d+)?)\s*$""").find(cleaned)
                if (trailNumMatch != null) {
                    val left = trailNumMatch.groupValues[1].trim()
                    val qtyVal = trailNumMatch.groupValues[2].toDoubleOrNull()
                    if (left.length >= 2 && qtyVal != null && qtyVal > 0.0 && qtyVal <= 1000.0) {
                        extractedQty = qtyVal
                        queryPart = left.trimEnd('-', ':', ',', ' ')
                    }
                }
            }

            // Clean trailing stray unit tokens if any remained
            val normalizedQuery = queryPart
                .replace(Regex("""\s+"""), " ")
                .trim()
            if (normalizedQuery.isNotBlank()) {
                results.add(
                    ParsedOrderLine(
                        itemQuery = normalizedQuery,
                        quantity = extractedQty ?: 1.0,
                        rawLine = rawLine
                    )
                )
            }
        }
        return results
    }

    private fun matchLineToInventory(
        parsed: ParsedOrderLine,
        inventory: List<InventoryItemWithVariants>,
        vertical: ShopVertical
    ): Pair<InventoryItemWithVariants, ItemVariantEntity>? {
        val candidateQueries = LinkedHashSet<String>()
        candidateQueries.add(parsed.itemQuery)

        // Fallback query with unit suffixes stripped
        val tokens = parsed.itemQuery.split(' ').filter { token ->
            token.isNotBlank() && token.lowercase(Locale.ENGLISH) !in KNOWN_UNITS
        }
        if (tokens.isNotEmpty()) {
            candidateQueries.add(tokens.joinToString(" "))
        }

        var bestItem: InventoryItemWithVariants? = null
        var bestScore = 0.0

        for (query in candidateQueries) {
            for (item in inventory) {
                if (item.variants.isEmpty()) continue
                val score = SmartItemMatcher.calculateSearchRelevanceScore(query, item, vertical)
                if (score > bestScore) {
                    bestScore = score
                    bestItem = item
                }
            }
            if (bestScore >= 1200.0) break
        }

        // If full phrase failed due to extra adjectives in an order line, try bigrams / core tokens
        if (bestItem == null && tokens.size >= 2) {
            for (item in inventory) {
                if (item.variants.isEmpty()) continue
                val matchedTokens = countTokenMatches(tokens, item, vertical)
                if (matchedTokens > 0) {
                    val tokenScore = matchedTokens * 600.0
                    if (tokenScore > bestScore) {
                        bestScore = tokenScore
                        bestItem = item
                    }
                }
            }
        }

        val resolvedItem = bestItem ?: return null
        val bestVariant = pickBestVariant(resolvedItem, parsed.itemQuery) ?: return null

        return resolvedItem to bestVariant
    }

    /** Number of [tokens] (length >= 3) that each score >= 500 against [item]; shared with the stock-in matcher. */
    internal fun countTokenMatches(
        tokens: List<String>,
        item: InventoryItemWithVariants,
        vertical: ShopVertical
    ): Int = tokens.count { tok ->
        tok.length >= 3 && SmartItemMatcher.calculateSearchRelevanceScore(tok, item, vertical) >= 500.0
    }

    /**
     * Picks the variant of [resolvedItem] that best fits the free-text [itemQuery] (barcode / SKU /
     * size / material / finish words). Shared by the order scanner and the stock-in matcher.
     */
    internal fun pickBestVariant(
        resolvedItem: InventoryItemWithVariants,
        itemQuery: String
    ): ItemVariantEntity? {
        val lowerQuery = itemQuery.lowercase(Locale.ENGLISH)
        return resolvedItem.variants.maxByOrNull { v ->
            var vScore = 0
            if (v.barcode.isNotBlank() && lowerQuery.contains(v.barcode.lowercase(Locale.ENGLISH))) vScore += 100
            if (v.skuCode.isNotBlank() && lowerQuery.contains(v.skuCode.lowercase(Locale.ENGLISH))) vScore += 90
            if (v.sizeLabel.isNotBlank()) {
                v.sizeLabel.lowercase(Locale.ENGLISH).split(' ').filter { it.length >= 2 }.forEach { part ->
                    if (lowerQuery.contains(part)) vScore += 20
                }
            }
            if (v.materialLabel.isNotBlank()) {
                v.materialLabel.lowercase(Locale.ENGLISH).split(' ').filter { it.length >= 2 }.forEach { part ->
                    if (lowerQuery.contains(part)) vScore += 15
                }
            }
            if (v.colorFinishLabel.isNotBlank()) {
                v.colorFinishLabel.lowercase(Locale.ENGLISH).split(' ').filter { it.length >= 2 }.forEach { part ->
                    if (lowerQuery.contains(part)) vScore += 10
                }
            }
            vScore
        } ?: resolvedItem.variants.firstOrNull()
    }
}
