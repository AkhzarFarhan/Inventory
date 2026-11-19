package com.shopledger.pos.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class StringParityTest {

    private class Res(val strings: Map<String, String>, val plurals: Map<String, String>)

    private fun parse(xml: String): Res = parse(xml.byteInputStream())

    private fun parse(stream: java.io.InputStream): Res {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stream)
        val strings = LinkedHashMap<String, String>()
        val plurals = LinkedHashMap<String, String>()
        val sn = doc.getElementsByTagName("string")
        for (i in 0 until sn.length) {
            val e = sn.item(i) as Element
            if (e.getAttribute("translatable") == "false") continue
            strings[e.getAttribute("name")] = e.textContent
        }
        val pn = doc.getElementsByTagName("plurals")
        for (i in 0 until pn.length) {
            val e = pn.item(i) as Element
            if (e.getAttribute("translatable") == "false") continue
            val items = e.getElementsByTagName("item")
            for (j in 0 until items.length) {
                val item = items.item(j) as Element
                plurals["${e.getAttribute("name")}#${item.getAttribute("quantity")}"] = item.textContent
            }
        }
        return Res(strings, plurals)
    }

    private fun load(dir: String): Res {
        val rel = "src/main/res/$dir/strings.xml"
        val base = System.getProperty("user.dir")
        val file = listOf(File(base, rel), File(base, "app/$rel")).firstOrNull { it.exists() }
        assertTrue("Missing $rel under $base", file != null)
        return parse(file!!.inputStream())
    }

    private val formatRegex = Regex("""(?<!%)%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z]""")

    /** Format specifiers (sorted), with %% removed and positional index dropped for a single argument. */
    private fun formats(value: String): List<String> {
        val found = formatRegex.findAll(value.replace("%%", "")).map { it.value }.toList()
        val norm = if (found.size == 1) listOf(found[0].replace(Regex("""^%\d+\$"""), "%")) else found
        return norm.sorted()
    }

    private fun formatMismatches(en: Map<String, String>, hi: Map<String, String>): List<String> =
        en.mapNotNull { (k, v) ->
            val h = hi[k] ?: return@mapNotNull null
            if (formats(v) != formats(h)) "$k: ${formats(v)} vs ${formats(h)}" else null
        }

    private fun missing(en: Res, hi: Res): Map<String, Set<String>> = mapOf(
        "strings missing in Hindi" to en.strings.keys - hi.strings.keys,
        "strings missing in English" to hi.strings.keys - en.strings.keys,
        "plurals missing in Hindi" to en.plurals.keys - hi.plurals.keys,
        "plurals missing in English" to hi.plurals.keys - en.plurals.keys,
    ).filterValues { it.isNotEmpty() }

    @Test
    fun everyKeyExistsInBothLanguages() {
        assertEquals(emptyMap<String, Set<String>>(), missing(load("values"), load("values-hi")))
    }

    @Test
    fun noHindiValueIsEmpty() {
        val hi = load("values-hi")
        val empty = (hi.strings + hi.plurals).filterValues { it.isBlank() }.keys
        assertEquals(emptySet<String>(), empty)
    }

    @Test
    fun formatArgumentsMatch() {
        val en = load("values")
        val hi = load("values-hi")
        assertEquals(emptyList<String>(), formatMismatches(en.strings, hi.strings))
        assertEquals(emptyList<String>(), formatMismatches(en.plurals, hi.plurals))
    }

    // ---- self-tests of the comparison logic ----

    @Test
    fun selfTestPluralQuantityMismatchIsCaught() {
        val en = parse("<resources><plurals name=\"p\"><item quantity=\"one\">%d a</item><item quantity=\"other\">%d b</item></plurals></resources>")
        val hi = parse("<resources><plurals name=\"p\"><item quantity=\"other\">%d b</item></plurals></resources>")
        assertEquals(setOf("p#one"), missing(en, hi)["plurals missing in Hindi"])
    }

    @Test
    fun selfTestNonTranslatableSkipped() {
        val en = parse("<resources><string name=\"a\" translatable=\"false\">x</string><string name=\"b\">y</string></resources>")
        assertEquals(setOf("b"), en.strings.keys)
    }

    @Test
    fun selfTestFormatSpecifiers() {
        assertEquals(listOf("%.2f"), formats("Total %.2f"))
        assertEquals(emptyList<String>(), formats("100%% done"))
        assertEquals(emptyList<String>(), formats("%%d"))
        assertEquals(formats("%s items"), formats("%1\$s आइटम"))
        assertEquals(1, formatMismatches(mapOf("k" to "%d"), mapOf("k" to "%.2f")).size)
    }
}
