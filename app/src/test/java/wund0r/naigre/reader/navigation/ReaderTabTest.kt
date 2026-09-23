// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderTabTest {
    @Test
    fun generatedStartDoesNotStoreEnglish() {
        val tab = ReaderTab.start("book")
        assertEquals(TabLabelKind.START, tab.labelKind)
        assertEquals("", tab.label)
        assertEquals(0, tab.originPageIndex)
    }

    @Test
    fun documentTitlesAreNeverRecognizedByTheirWording() {
        for (title in listOf("Start", "Page 42", "Page turning", "Начало")) {
            assertEquals(TabLabelKind.TEXT, ReaderTab("book", 0, title).labelKind)
        }
    }

    @Test
    fun scrollingDoesNotChangeTheTitleDestination() {
        val tab = ReaderTab("book", 41, "").apply {
            setTitle(TabTitle("Cavern", TabLabelKind.TEXT_WITH_PAGE, 41))
        }
        tab.pageIndex = 80
        assertEquals(41, tab.labelPageIndex)
        assertEquals(41, tab.originPageIndex)
        assertEquals("Cavern", tab.label)
    }

    @Test
    fun openingBookmarkReplacesGeneratedTitleSemantics() {
        val tab = ReaderTab.start("book")
        tab.setTitle(TabTitle(kind = TabLabelKind.PAGE, pageIndex = 41))
        tab.setTextTitle("Page turning")
        assertEquals(TabLabelKind.TEXT, tab.labelKind)
        assertEquals("Page turning", tab.label)
    }
}
