// SPDX-License-Identifier: AGPL-3.0-or-later
package wund0r.naigre.reader.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import wund0r.naigre.reader.table.BookRecord
import wund0r.naigre.reader.table.LibraryItemKind

class LibraryPreviewKeyTest {
    private val book = BookRecord("id", "Book", uri = "content://book", color = 1,
        pageCount = 20, pdfBookmarks = emptyList(), sourceSize = 123, sourceRevisionToken = "r1")

    @Test fun presentationAndIndexHydrationDoNotChangePreviewIdentity() {
        assertEquals(LibraryPreviewLoader.key(book), LibraryPreviewLoader.key(book.copy(
            title = "Renamed", fileName = "Renamed.pdf", color = 2, tagIds = setOf("campaign"), indexLoaded = false)))
    }

    @Test fun sourceChangesAndDifferentBooksHaveDifferentPreviews() {
        for (changed in listOf(book.copy(id = "other"), book.copy(uri = "content://new"),
            book.copy(sourceSize = 456), book.copy(sourceLastModified = 42), book.copy(sourceRevisionToken = "r2"),
            book.copy(sourceFingerprint = "new"), book.copy(kind = LibraryItemKind.MARKDOWN))) {
            assertNotEquals(LibraryPreviewLoader.key(book), LibraryPreviewLoader.key(changed))
        }
    }
}
