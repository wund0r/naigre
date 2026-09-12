// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.table

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import wund0r.naigre.reader.navigation.BookmarkEntry
import wund0r.naigre.reader.navigation.BookmarkSource
import wund0r.naigre.reader.navigation.ReaderTab

class TableSessionControllerTest {
    @Test
    fun transitionPreservesSurvivingTabInstancesAndActiveTab() {
        val first = ReaderTab("first", 3, "First")
        val second = ReaderTab("second", 7, "Second")
        val controller = TableSessionController(listOf("first", "second"))

        val transition = controller.transitionTo(
            desiredBookIds = listOf("first", "second", "third"),
            availableBookIds = listOf("first", "second", "third"),
            tabs = listOf(first, second),
            activeTabIndex = 1,
            referenceBookId = "first",
        )

        assertSame(first, transition.survivingTabs[0])
        assertSame(second, transition.survivingTabs[1])
        assertEquals(1, transition.activeTabIndex)
        assertEquals(setOf("third"), transition.addedBookIds)
        assertFalse(transition.closeReference)
        assertNull(transition.fallbackBookId)
    }

    @Test
    fun removingActiveBookClosesItsReferenceAndSelectsASurvivingTab() {
        val first = ReaderTab("first", 3, "First")
        val second = ReaderTab("second", 7, "Second")
        val controller = TableSessionController(listOf("first", "second"))

        val transition = controller.transitionTo(
            desiredBookIds = listOf("second"),
            availableBookIds = listOf("first", "second"),
            tabs = listOf(first, second),
            activeTabIndex = 0,
            referenceBookId = "first",
        )

        assertEquals(listOf(second), transition.survivingTabs)
        assertEquals(0, transition.activeTabIndex)
        assertEquals(setOf("first"), transition.removedBookIds)
        assertTrue(transition.closeReference)
    }

    @Test
    fun removingMainBookKeepsAnUnrelatedReferenceOpen() {
        val main = ReaderTab("main", 31, "Main")
        val controller = TableSessionController(listOf("main", "reference"))

        val transition = controller.transitionTo(
            desiredBookIds = listOf("reference"),
            availableBookIds = listOf("main", "reference"),
            tabs = listOf(main),
            activeTabIndex = 0,
            referenceBookId = "reference",
        )

        assertTrue(transition.survivingTabs.isEmpty())
        assertFalse(transition.closeReference)
        assertEquals("reference", transition.fallbackBookId)
    }

    @Test
    fun laterIndividualRemovalInvalidatesOnlyThatBooksBulkHydrationIntent() {
        val controller = TableSessionController()
        controller.transitionTo(listOf("first", "second"), listOf("first", "second"), emptyList(), 0, null)
        val firstVersion = controller.selectionVersion("first")
        val secondVersion = controller.selectionVersion("second")

        controller.transitionTo(listOf("first"), listOf("first", "second"), emptyList(), 0, null)

        assertTrue(controller.selectionStillCurrent("first", firstVersion, selected = true))
        assertFalse(controller.selectionStillCurrent("second", secondVersion, selected = true))
    }

    @Test
    fun laterIndividualAdditionKeepsExistingBulkHydrationIntentsValid() {
        val controller = TableSessionController()
        controller.transitionTo(listOf("first", "second"), listOf("first", "second", "third"), emptyList(), 0, null)
        val firstVersion = controller.selectionVersion("first")
        val secondVersion = controller.selectionVersion("second")

        controller.transitionTo(
            listOf("first", "second", "third"),
            listOf("first", "second", "third"),
            emptyList(),
            0,
            null,
        )

        assertTrue(controller.selectionStillCurrent("first", firstVersion, selected = true))
        assertTrue(controller.selectionStillCurrent("second", secondVersion, selected = true))
        assertTrue("third" in controller.selectedBookIds)
    }

    @Test
    fun passiveContentResultCannotClaimAfterANewerOperation() {
        val controller = TableSessionController(listOf("book"))
        val scanSnapshot = controller.captureContentOperation("book")

        val relink = controller.beginContentOperation("book")

        assertNull(controller.claimContentOperation(scanSnapshot))
        assertTrue(controller.isContentOperationCurrent(relink))
    }

    @Test
    fun forgettingInvalidatesPendingSelectionAndContentWork() {
        val controller = TableSessionController(listOf("book"))
        val selectionVersion = controller.selectionVersion("book")
        val contentToken = controller.captureContentOperation("book")

        val deletion = controller.invalidateForgottenBook("book")

        assertFalse(controller.selectionStillCurrent("book", selectionVersion, selected = true))
        assertFalse(controller.isContentOperationCurrent(contentToken))
        assertTrue(controller.isContentOperationCurrent(deletion))
    }

    @Test
    fun sourceResultKeepsCurrentUserOwnedFields() {
        val current = book(
            title = "Old source",
            color = 0x112233,
            tagIds = setOf("campaign", "rules"),
        )
        val scanned = book(
            title = "Updated source",
            color = 0x445566,
            tagIds = emptySet(),
            pageCount = 42,
        )

        val merged = mergeSourceBookResult(current, scanned)

        assertEquals("Updated source", merged.title)
        assertEquals(42, merged.pageCount)
        assertEquals(current.color, merged.color)
        assertEquals(current.tagIds, merged.tagIds)
    }

    @Test
    fun hydrationAppliesOnlyIndexPayload() {
        val current = book(
            title = "Current title",
            color = 0x112233,
            tagIds = setOf("campaign"),
            pageCount = 15,
        ).copy(indexLoaded = false, storedBookmarkCount = 1)
        val outline = listOf(
            BookmarkEntry(
                bookId = "book",
                title = "Hydrated heading",
                pageIndex = 8,
                source = BookmarkSource.PDF_OUTLINE,
            ),
        )
        val hydratedSnapshot = book(
            title = "Stale title",
            color = 0x445566,
            tagIds = emptySet(),
            pageCount = 99,
        ).copy(pdfBookmarks = outline)

        val merged = mergeHydratedBookIndex(current, hydratedSnapshot)

        assertEquals("Current title", merged.title)
        assertEquals(15, merged.pageCount)
        assertEquals(current.color, merged.color)
        assertEquals(current.tagIds, merged.tagIds)
        assertEquals(outline, merged.pdfBookmarks)
        assertTrue(merged.indexLoaded)
    }

    private fun book(
        title: String,
        color: Int,
        tagIds: Set<String>,
        pageCount: Int = 10,
    ) = BookRecord(
        id = "book",
        title = title,
        uri = "content://book",
        color = color,
        pageCount = pageCount,
        pdfBookmarks = emptyList(),
        tagIds = tagIds,
    )
}
