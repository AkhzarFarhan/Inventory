package com.shopledger.pos.core.scanner

import com.shopledger.pos.core.format.IndianCurrencyFormatter
import kotlin.math.abs
import kotlin.math.roundToLong

data class ParsedStockLine(
    val name: String,
    val qtyMilli: Long,
    val unit: String?,
    val ratePaise: Long?,
    val amountPaise: Long?,
    /** false when qty x rate disagrees with the printed amount by more than 1% */
    val consistent: Boolean,
    val rawLine: String
)

/** Parses a supplier bill / handwritten stock list (OCR text) into [ParsedStockLine]s. Pure Kotlin. */
object StockListParser {

    private val UNIT_MAP: Map<String, String> = buildMap {
        fun add(canon: String, vararg words: String) {
            put(canon, canon)
            words.forEach { put(it, canon) }
        }
        add("pcs", "pc", "piece", "pieces", "nos", "no")
        add("pkt", "packet", "packets", "pack", "pkts")
        add("box", "boxes")
        add("kg", "kgs", "kilo")
        add("g", "gm", "gms", "gram", "grams")
        add("ltr", "litre", "liter", "l", "ltrs", "litres", "liters")
        add("ml")
        add("mtr", "m", "meter", "meters")
        add("ft", "feet")
        add("bag", "bags")
        add("roll", "rolls")
        add("set", "sets")
        add("pair", "pairs")
        add("dozen", "dzn")
        add("bottle", "bottles")
        add("can", "cans")
        add("strip", "strips")
        add("tube", "tubes")
    }

    private fun normalizeUnit(word: String): String? = UNIT_MAP[word.lowercase().trimEnd('.')]

    private val UNIT_ALT = UNIT_MAP.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }

    private val SERIAL = Regex("""^\(?\d{1,3}\s*[.)\]:-]+\s+""")
    private val BULLET = Regex("""^[-•*✓☑➤]+\s*""")
    private val NOISE = Regex(
        """(?<![a-z])(?:total|sub total|subtotal|grand total|round off|cgst|sgst|igst|gst|tax|discount|invoice|bill no|date|gstin|mobile|phone|ph\.|thank|amount in words|rupees)(?![a-z])""",
        RegexOption.IGNORE_CASE
    )
    private val PHONE = Regex("""(?<!\d)\d{10}(?!\d)""")
    private val DATE = Regex("""\d{1,2}[/-]\d{1,2}[/-]\d{2,4}""")
    private const val SIZE_UNIT = "\"|''|inch|in|mm|cm|ft|kg|gm|g|ml|ltr|mtr|amp|l|m|w|v"
    private val SIZE = Regex("""(?<![\w.])(\d+(?:\.\d+)?)(\s?)($SIZE_UNIT)(?![A-Za-z])""", RegexOption.IGNORE_CASE)
    private val LEAD = Regex("""^(\d+(?:\.\d+)?)\s*(?:($UNIT_ALT)(?=\s))?\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val TOKEN = Regex("""(?:₹|rs\.?\s*)?\d[\d,]*(?:\.\d+)?|[^\s×*@=]+|[×*@=]""", RegexOption.IGNORE_CASE)
    private val NUMBER = Regex("""^(?:₹|rs\.?\s*)?(\d[\d,]*(?:\.\d+)?)$""", RegexOption.IGNORE_CASE)
    private val LETTER_AFTER = Regex("""^\s*[A-Za-z]""")
    private val QTY_UNIT_AFTER = Regex(
        """\d\s*(?:pcs|pc|pieces|piece|nos|pkts|pkt|packets|packet|packs|pack|box|boxes|bags|bag|rolls|roll|sets|set|pairs|pair|dozen|dzn|bottles|bottle|cans|can|strips|strip|tubes|tube)(?![A-Za-z])""",
        RegexOption.IGNORE_CASE
    )
    private val NUMBER_RUN = Regex("""(?<![\w.])\d+(?:\.\d+)?(?![\w])""")
    private val QTY_WORD = Regex("""^(?:qty|qnty|nos)\.?\s*[:.-]?\s*(?=\d)""", RegexOption.IGNORE_CASE)
    private val LEAD_TIMES = Regex("""^(\d+(?:\.\d+)?)\s*[x×*]\s+(?=[A-Za-z])""", RegexOption.IGNORE_CASE)
    private val CROSS = Regex(
        """(?<![\w.])(\d+(?:\.\d+)?)\s*[x×*]\s*(\d[\d,]*(?:\.\d+)?)(?![\w"'])(?!\s?(?:inch|in|mm|cm|ft))""",
        RegexOption.IGNORE_CASE
    )
    private val SLASH_DASH = Regex("""(\d)\s*/[-=](?=\s|$)""")
    private val TAX_WORD = Regex("""(?<![a-z])(?:gst|tax|cgst|sgst|igst|discount)(?![a-z])""")
    private val CURRENCY = Regex("""^(?:₹|rs)""", RegexOption.IGNORE_CASE)
    private val MASK_REF = Regex("""(\d+)""")
    private val OPS = setOf("x", "×", "*", "@", "=", "-", "–", ":")
    private val LONG_SIZE_UNITS = setOf("inch", "in", "mm", "cm", "\"", "''")

    /**
     * Protects size tokens ("6\"", "1kg", "4in", "4 in SS 12") so they stay in the name. Attached tokens are always
     * protected. A spaced token is protected only when a word follows it and another number comes later, and a spaced
     * token at the very start of the line is only protected for length-type units (so "5 kg Sugar" stays a quantity).
     * A spaced token directly followed by numbers ("2.5 kg 180 450") is a quantity+unit, not a size.
     */
    private fun mask(s: String, store: MutableList<String>): String {
        val sb = StringBuilder()
        var last = 0
        for (m in SIZE.findAll(s)) {
            val unit = m.groupValues[3].lowercase()
            val attached = m.groupValues[2].isEmpty()
            val atStart = m.range.first == 0
            val after = s.substring(m.range.last + 1)
            val protect = when {
                attached -> true
                atStart -> unit in LONG_SIZE_UNITS
                else -> (LETTER_AFTER.containsMatchIn(after) && after.any { it.isDigit() }) ||
                    QTY_UNIT_AFTER.containsMatchIn(after) || NUMBER_RUN.findAll(after).count() >= 3
            }
            if (protect) {
                sb.append(s, last, m.range.first)
                sb.append('').append(store.size).append('')
                store.add(m.value)
                last = m.range.last + 1
            }
        }
        sb.append(s, last, s.length)
        return sb.toString()
    }

    private fun unmask(s: String, store: List<String>): String =
        MASK_REF.replace(s) { store[it.groupValues[1].toInt()] }

    private fun num(s: String): Double? =
        NUMBER.matchEntire(s.trim())?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()

    private fun paise(rupees: Double): Long = IndianCurrencyFormatter.parseRupeesToPaise(rupees.toString())

    private fun cleanLine(raw: String): String? {
        var t = raw.trim()
        t = SERIAL.replace(t, "")
        t = BULLET.replace(t, "").trim()
        if (t.length < 2 || t.none { it.isLetter() }) return null
        return t
    }

    private fun isNoise(t: String): Boolean =
        NOISE.containsMatchIn(t) || PHONE.containsMatchIn(t) || DATE.containsMatchIn(t)

    fun parse(text: String): List<ParsedStockLine> =
        text.split('\n', '\r').mapNotNull { raw ->
            val t = cleanLine(raw) ?: return@mapNotNull null
            if (isNoise(t)) return@mapNotNull null
            parseLine(t, raw.trim())
        }

    private fun parseLine(t: String, raw: String): ParsedStockLine? {
        if (t.contains('|')) parsePipe(t, raw)?.let { return it }

        val store = ArrayList<String>()
        var pre = SLASH_DASH.replace(t, "$1")
        pre = QTY_WORD.replace(pre, "")
        pre = LEAD_TIMES.replace(pre, "$1 ")
        pre = CROSS.replace(pre, "$1 x $2")
        var s = mask(pre, store).trim()

        var qty: Double? = null
        var unit: String? = null
        var hasLeadQty = false
        LEAD.find(s)?.let { m ->
            val rest = m.groupValues[3]
            if (rest.any { it.isLetter() }) {
                qty = m.groupValues[1].toDoubleOrNull()
                unit = m.groupValues[2].takeIf { it.isNotEmpty() }?.let { normalizeUnit(it) }
                hasLeadQty = qty != null
                s = rest.trim()
            }
        }

        val tokens = TOKEN.findAll(s).toList()
        var startIdx = -1
        var i = tokens.size - 1
        while (i >= 0) {
            val v = tokens[i].value
            if (num(v) != null) startIdx = i
            else if (v.lowercase() !in OPS && normalizeUnit(v) == null) break
            i--
        }
        val nameEnd = if (startIdx >= 0) tokens[startIdx].range.first else s.length
        var name = s.substring(0, nameEnd)
        name = name.replace(Regex("""\s[xX]$"""), "").replace(Regex("""[\s×*@=:,–-]+$"""), "").trim()
        name = unmask(name, store).replace(Regex("""\s+"""), " ").trim()
        if (name.none { it.isLetter() } || normalizeUnit(name) != null) return null

        var rate: Double? = null
        var amount: Double? = null
        val plain = ArrayList<Double>()
        if (startIdx >= 0) {
            var prevOp: String? = null
            var seenNum = false
            for (k in startIdx until tokens.size) {
                val v = tokens[k].value
                val n = num(v)
                if (n != null) {
                    when (prevOp) {
                        "@" -> rate = n
                        "=" -> amount = n
                        "x", "×", "*" -> if (seenNum && rate == null && plain.isNotEmpty()) rate = n else plain.add(n)
                        else -> if (seenNum && amount == null && CURRENCY.containsMatchIn(v)) amount = n else plain.add(n)
                    }
                    seenNum = true
                    prevOp = null
                } else if (v.lowercase() in OPS) {
                    prevOp = v.lowercase()
                } else if (unit == null && seenNum) {
                    unit = normalizeUnit(v)
                }
            }
        }
        if (hasLeadQty) {
            when (plain.size) {
                0 -> {}
                1 -> if (rate == null) rate = plain[0]
                else -> {
                    if (rate == null) rate = plain[0]
                    if (amount == null) amount = plain.last()
                }
            }
        } else {
            when {
                plain.isEmpty() -> {}
                plain.size == 1 -> qty = plain[0]
                plain.size == 2 -> {
                    qty = plain[0]
                    if (rate == null) rate = plain[1]
                }
                else -> {
                    qty = plain[0]
                    if (rate == null) rate = plain[1]
                    if (amount == null) amount = plain.last()
                }
            }
        }
        return build(name, qty, unit, rate?.let { paise(it) }, amount?.let { paise(it) }, raw)
    }

    private fun parsePipe(t: String, raw: String): ParsedStockLine? {
        val p = t.split('|').map { it.trim() }
        if (p.size < 2 || p[0].none { it.isLetter() }) return null
        fun field(i: Int) = p.getOrNull(i)?.takeIf { it.isNotEmpty() && it != "-" }
        val qty = field(1)?.let { num(it) }
        val unit = field(2)?.let { normalizeUnit(it) }
        val rate = field(3)?.let { num(it) }?.let { paise(it) }
        val amount = field(4)?.let { num(it) }?.let { paise(it) }
        return build(p[0], qty, unit, rate, amount, raw)
    }

    private fun build(
        name: String, qty: Double?, unit: String?, rate: Long?, amount: Long?, raw: String
    ): ParsedStockLine {
        val qtyMilli = qty?.let { IndianCurrencyFormatter.doubleToMilliUnits(it) }?.takeIf { it > 0L } ?: 1000L
        val finalRate = rate ?: amount?.let { (it * 1000.0 / qtyMilli).roundToLong() }
        val consistent = if (amount == null || rate == null) true else {
            val expected = qtyMilli * rate / 1000.0
            abs(expected - amount) <= maxOf(amount * 0.01, 100.0)
        }
        return ParsedStockLine(name, qtyMilli, unit, finalRate, amount, consistent, raw)
    }

    /** Value of a "grand total"/"total" line printed on the bill, or null when there is none. */
    fun detectPrintedTotalPaise(text: String): Long? {
        var grand: Long? = null
        var plainTotal: Long? = null
        val totalWord = Regex("""(?<![a-z])total(?![a-z])""")
        val numberRe = Regex("""\d[\d,]*(?:\.\d+)?""")
        for (raw in text.split('\n', '\r')) {
            val t = cleanLine(raw)?.lowercase() ?: continue
            if (!totalWord.containsMatchIn(t)) continue
            if (TAX_WORD.containsMatchIn(t)) continue
            if (t.contains("sub total") || t.contains("subtotal") || t.contains("qty") || t.contains("items")) continue
            val v = numberRe.findAll(t).lastOrNull()?.value?.replace(",", "")?.toDoubleOrNull() ?: continue
            if (t.contains("grand total")) grand = paise(v) else plainTotal = paise(v)
        }
        return grand ?: plainTotal
    }
}
