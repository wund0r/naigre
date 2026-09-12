// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.table

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.Executor

class BookLibraryStorageTest {
    @Test
    fun failedCatalogLoadBlocksWritesUntilRetrySucceeds() {
        val executor = ManualExecutor()
        val store = FakeStore().apply {
            catalogReads += BookCatalogState.Failed(
                CatalogLoadFailure(CatalogFailureKind.INVALID, "malformed"),
            )
            catalogReads += BookCatalogState.Loaded(emptyLibrary())
        }
        val failures = mutableListOf<BookStorageFailure>()
        val storage = BookLibraryStorage.forTesting(store, executor).also {
            it.addListener(failures::add)
        }

        storage.loadCatalog {}
        executor.runNext()
        storage.saveCatalog(library("blocked"))
        executor.runNext()

        assertTrue(store.operations.none { it.startsWith("save-catalog") })
        assertEquals(BookStorageOperation.SAVE_CATALOG, failures.single().operation)

        storage.loadCatalog {}
        executor.runNext()
        storage.saveCatalog(library("accepted"))
        executor.runNext()

        assertEquals(listOf("read-catalog", "read-catalog", "save-catalog:accepted"), store.operations)
    }

    @Test
    fun orderedSnapshotsLeaveNewestCatalogDurableAcrossClients() {
        val executor = ManualExecutor()
        val store = FakeStore().apply { catalogReads += BookCatalogState.Missing }
        val storage = BookLibraryStorage.forTesting(store, executor)
        storage.loadCatalog {}
        executor.runNext()

        val mutableBooks = mutableListOf(book("A"))
        storage.saveCatalog(
            BookLibraryState(mutableBooks, linkedSetOf("book"), emptyList(), emptyList()),
        )
        mutableBooks[0] = book("mutated-after-submit")
        storage.saveCatalog(library("B"))
        storage.saveIndex(book("B"))
        storage.deleteIndex("book")
        var reloaded: BookCatalogState? = null
        storage.loadCatalog { reloaded = it }

        executor.runAll()

        assertEquals(
            listOf(
                "read-catalog",
                "save-catalog:A",
                "save-catalog:B",
                "save-index:B",
                "delete-index:book",
                "read-catalog",
            ),
            store.operations,
        )
        assertEquals("B", (reloaded as BookCatalogState.Loaded).library.books.single().title)
    }

    private class ManualExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            tasks += command
        }

        fun runNext() {
            tasks.removeFirst().run()
        }

        fun runAll() {
            while (tasks.isNotEmpty()) runNext()
        }
    }

    private class FakeStore : BookLibraryStore {
        val catalogReads = ArrayDeque<BookCatalogState>()
        val operations = mutableListOf<String>()
        private var durable = emptyLibrary()

        override fun readCatalog(): BookCatalogState {
            operations += "read-catalog"
            return if (catalogReads.isNotEmpty()) catalogReads.removeFirst() else {
                BookCatalogState.Loaded(durable)
            }
        }

        override fun readIndex(book: BookRecord): BookIndexLoadResult =
            BookIndexLoadResult.Loaded(book)

        override fun saveCatalog(library: BookLibraryState) {
            operations += "save-catalog:${library.books.singleOrNull()?.title ?: "empty"}"
            durable = library
        }

        override fun saveIndex(book: BookRecord) {
            operations += "save-index:${book.title}"
        }

        override fun deleteIndex(bookId: String) {
            operations += "delete-index:$bookId"
        }
    }

    private companion object {
        fun emptyLibrary() = BookLibraryState(emptyList(), linkedSetOf(), emptyList(), emptyList())

        fun library(title: String) = BookLibraryState(
            books = listOf(book(title)),
            selectedBookIds = linkedSetOf("book"),
            folders = emptyList(),
            tags = emptyList(),
        )

        fun book(title: String) = BookRecord(
            id = "book",
            title = title,
            uri = "content://book",
            color = 0x112233,
            pageCount = 10,
            pdfBookmarks = emptyList(),
        )
    }
}
