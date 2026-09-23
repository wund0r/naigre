// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class RussianResourcesTest {
    // A scoped resource context: never change the tablet's system or app language.
    private fun context(language: String): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        return base.createConfigurationContext(config)
    }

    @Test
    fun russianCountsUseOneFewAndManyIncludingTeenExceptions() {
        val resources = context("ru").resources
        val expected = mapOf(
            0 to "совпадений", 1 to "совпадение", 2 to "совпадения", 5 to "совпадений",
            11 to "совпадений", 12 to "совпадений", 14 to "совпадений",
            21 to "совпадение", 22 to "совпадения", 25 to "совпадений", 111 to "совпадений",
        )
        expected.forEach { (count, noun) ->
            assertEquals("$count $noun", resources.getQuantityString(R.plurals.match_count, count, count))
        }
        assertEquals("Поиск в 21 документе…", resources.getQuantityString(R.plurals.searching_documents, 21, 21))
        assertEquals("Поиск в 22 документах…", resources.getQuantityString(R.plurals.searching_documents, 22, 22))
        assertEquals("Совпадения: 160 из 221", resources.getQuantityString(R.plurals.matches_shown, 221, 160, 221))
    }

    @Test
    fun formattingPreservesUserTextAndSummaryWhitespace() {
        val russian = context("ru")
        assertEquals("Каверна · с. 42", russian.getString(R.string.tab_text_with_page, "Каверна", 42))
        assertEquals("Страница 42", russian.getString(R.string.tab_page, 42))
        assertEquals("Начало", russian.getString(R.string.tab_start))
        assertEquals("На стол и в библиотеку добавлен материал: 100% «Cavern»",
            russian.getString(R.string.added_to_library_table, "100% «Cavern»"))
        assertEquals(" · TXT: book.txt", russian.getString(R.string.toc_source_suffix, "book.txt"))
        assertEquals("\nДобавлено: 2 · обновлено: 3 · без изменений: 4",
            russian.getString(R.string.scan_change_summary, 2, 3, 4))
        assertTrue(russian.getString(R.string.forget_item_message).contains("Исходные файлы не будут удалены."))
        assertTrue(russian.resources.getQuantityString(R.plurals.forget_items_message, 22, 22)
            .contains("Исходные файлы не будут удалены."))
    }

    @Test
    fun localeSelectionAndEnglishFallbackDoNotChangeBranding() {
        val english = context("en")
        assertEquals("Navigate", english.getString(R.string.navigate))
        assertEquals("Закладки", context("ru").getString(R.string.navigate))
        assertEquals("Закладки", context("ru-RU").getString(R.string.navigate))
        assertEquals("Navigate", context("fr").getString(R.string.navigate))
        assertEquals(english.getString(R.string.app_name), context("ru").getString(R.string.app_name))
        assertEquals("Aa", context("ru").getString(R.string.smartcase_marker))
        // Resource contexts are independent; creating a Russian context must not alter English.
        assertEquals("Start", english.getString(R.string.tab_start))
    }
}
