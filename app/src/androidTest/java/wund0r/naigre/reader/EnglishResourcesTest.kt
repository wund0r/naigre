// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class EnglishResourcesTest {
    private fun context(): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val configuration = Configuration(base.resources.configuration).apply { setLocale(Locale.ENGLISH) }
        return base.createConfigurationContext(configuration)
    }

    @Test
    fun countsUseCompleteEnglishQuantityPhrases() {
        val resources = context().resources
        for (count in listOf(0, 1, 2, 5, 11, 21, 22, 25)) {
            val expected = if (count == 1) "$count match" else "$count matches"
            assertEquals(expected, resources.getQuantityString(R.plurals.match_count, count, count))
        }
        assertEquals("Searching 1 document…", resources.getQuantityString(R.plurals.searching_documents, 1, 1))
        assertEquals("Searching 2 documents…", resources.getQuantityString(R.plurals.searching_documents, 2, 2))
    }

    @Test
    fun formattingPreservesUserTextWhitespaceAndPunctuation() {
        val context = context()
        assertEquals("Cavern · p42", context.getString(R.string.tab_text_with_page, "Cavern", 42))
        assertEquals("Page 42", context.getString(R.string.tab_page, 42))
        assertEquals("100% «Подземелье» added to the library and table",
            context.getString(R.string.added_to_library_table, "100% «Подземелье»"))
        assertEquals(" · TXT: book.txt", context.getString(R.string.toc_source_suffix, "book.txt"))
        assertEquals("\n2 added · 3 refreshed · 4 unchanged", context.getString(R.string.scan_change_summary, 2, 3, 4))
        assertEquals("Source files are not deleted, but NaIgre will permanently remove this item's tabs, metadata, color, and visit history.",
            context.getString(R.string.forget_item_message))
    }
}
