// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Checks the source catalogs without an Android device or a translation service. */
class LocalizationCatalogTest {
    private val resources = listOf(File("src/main/res"), File("app/src/main/res"))
        .first { File(it, "values/strings.xml").isFile }

    private fun parse(file: File): Element = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder().parse(file).documentElement

    private fun children(element: Element): List<Element> = (0 until element.childNodes.length)
        .mapNotNull { element.childNodes.item(it) as? Element }

    private fun catalog(directory: String): Map<String, Element> {
        val entries = children(parse(File(resources, "$directory/strings.xml")))
            .filter { it.getAttribute("translatable") != "false" }
        val keyed = entries.associateBy { it.getAttribute("name") }
        assertEquals("Duplicate resource names in $directory", entries.size, keyed.size)
        return keyed
    }

    private fun signature(text: String): Map<Int, String> =
        Regex("%([1-9][0-9]*)\\\$([sd])").findAll(text)
            .associate { it.groupValues[1].toInt() to it.groupValues[2] }

    @Test
    fun russianCatalogCoversEveryTranslatableResource() {
        assertEquals(catalog("values").keys, catalog("values-ru").keys)
    }

    @Test
    fun translationsPreservePlaceholderPositionsAndTypes() {
        val russian = catalog("values-ru")
        catalog("values").forEach { (name, english) ->
            val translated = checkNotNull(russian[name]) { "Missing translation: $name" }
            assertEquals(name, english.tagName, translated.tagName)
            val defaultText = if (english.tagName == "plurals") {
                children(english).single { it.getAttribute("quantity") == "other" }.textContent
            } else english.textContent
            val translatedTexts = if (translated.tagName == "plurals") children(translated) else listOf(translated)
            translatedTexts.forEach {
                assertTrue("Empty translation: $name", it.textContent.isNotBlank())
                assertEquals("Placeholder mismatch: $name", signature(defaultText), signature(it.textContent))
            }
        }
    }

    @Test
    fun russianPluralsIncludeAllRequiredForms() {
        catalog("values-ru").filterValues { it.tagName == "plurals" }.forEach { (name, element) ->
            assertEquals(name, setOf("one", "few", "many", "other"),
                children(element).map { it.getAttribute("quantity") }.toSet())
        }
    }

    @Test
    fun manifestAdvertisesOnlyEnglishAndRussian() {
        val android = "http://schemas.android.com/apk/res/android"
        val config = parse(File(resources, "xml/locale_config.xml"))
        assertEquals(listOf("en", "ru"), children(config).map { it.getAttributeNS(android, "name") })
        val manifest = parse(File(resources.parentFile, "AndroidManifest.xml"))
        val application = children(manifest).single { it.tagName == "application" }
        assertEquals("@xml/locale_config", application.getAttributeNS(android, "localeConfig"))
    }
}
