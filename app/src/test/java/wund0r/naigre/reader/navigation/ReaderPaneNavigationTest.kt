// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.navigation

import org.junit.Assert.*
import org.junit.Test

class ReaderPaneNavigationTest {
    private val first = ReaderTab.start("notes")
    private val second = ReaderTab("pdf", 12, "Pazuzu", "pdf|page:11", 11)
    private val third = ReaderTab.start("maps")
    private val reference = ReaderTab("pdf", 18, "Other location", "pdf|page:15", 15)

    @Test fun movingActiveTabSelectsItsNextNeighbour() {
        val result = ReaderPaneNavigation.moveToReference(listOf(first, second, third), 1, null, 1)!!
        assertEquals(listOf(first, third), result.tabs)
        assertSame(third, result.tabs[result.activeTabIndex])
        assertSame(second, result.reference)
        assertEquals(11, result.reference!!.originPageIndex)
        assertEquals(12, result.reference.pageIndex)
    }

    @Test fun movingEarlierBackgroundTabKeepsActiveSession() {
        val result = ReaderPaneNavigation.moveToReference(listOf(first, second, third), 2, null, 0)!!
        assertSame(third, result.tabs[result.activeTabIndex])
        assertEquals(1, result.activeTabIndex)
    }

    @Test fun swappingActiveTabUsesTheSameSlotForTheReference() {
        val result = ReaderPaneNavigation.moveToReference(listOf(first, second, third), 1, reference, 1)!!
        assertEquals(listOf(first, reference, third), result.tabs)
        assertSame(reference, result.tabs[result.activeTabIndex])
        assertSame(second, result.reference)
    }

    @Test fun swappingBackgroundTabKeepsActiveSession() {
        val result = ReaderPaneNavigation.moveToReference(listOf(first, second), 0, reference, 1)!!
        assertSame(first, result.tabs[result.activeTabIndex])
        assertSame(reference, result.tabs[1])
    }

    @Test fun lastRegularTabNeedsADocumentReferenceToSwap() {
        assertNull(ReaderPaneNavigation.moveToReference(listOf(first), 0, null, 0))
        val result = ReaderPaneNavigation.moveToReference(listOf(first), 0, reference, 0)!!
        assertSame(reference, result.tabs.single())
        assertSame(first, result.reference)
        assertNull(ReaderPaneNavigation.moveToReference(listOf(first), 0, reference, -1))
    }

    @Test fun promotionPreservesDistinctSessionsEvenAtTheSameDestination() {
        val duplicate = second.copy()
        val result = ReaderPaneNavigation.makeRegularTab(listOf(first, second), duplicate)
        assertEquals(3, result.tabs.size)
        assertSame(duplicate, result.tabs[result.activeTabIndex])
        assertSame(second, result.tabs[1])
        assertNull(result.reference)
    }
}
