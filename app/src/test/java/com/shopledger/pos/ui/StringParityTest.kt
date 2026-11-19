package com.shopledger.pos.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class StringParityTest {

    private fun load(dir: String): Map<String, String> {
        val file = File(System.getProperty("user.dir"), "src/main/res/$dir/strings.xml")
        assertTrue("Missing ${file.path}", file.exists())
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val out = LinkedHashMap<String, String>()
        for (tag in listOf("string", "plurals")) {
            val nodes = doc.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as Element
                out[e.getAttribute("name")] = e.textContent
            }
        }
        return out
    }

    private val formatRegex = Regex("%(\\d+\\$)?[sdfx]")

    private fun formats(value: String) = formatRegex.findAll(value).map { it.value }.sorted().toList()

    @Test
    fun everyKeyExistsInBothLanguages() {
        val en = load("values")
        val hi = load("values-hi")
        assertEquals("Missing in Hindi", emptySet<String>(), en.keys - hi.keys)
        assertEquals("Missing in English", emptySet<String>(), hi.keys - en.keys)
    }

    @Test
    fun noHindiValueIsEmpty() {
        val empty = load("values-hi").filterValues { it.isBlank() }.keys
        assertEquals(emptySet<String>(), empty)
    }

    @Test
    fun formatArgumentsMatch() {
        val en = load("values")
        val hi = load("values-hi")
        for ((k, v) in en) {
            val h = hi[k] ?: continue
            assertEquals("Format args differ for $k", formats(v), formats(h))
        }
    }
}
