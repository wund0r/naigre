// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.search

import android.content.Context
import android.graphics.Paint
import android.os.CancellationSignal
import android.os.OperationCanceledException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import wund0r.naigre.reader.pdf.PdfAnnotationInfo
import wund0r.naigre.reader.pdf.PdfDocument
import wund0r.naigre.reader.pdf.PdfOutlineEntry
import wund0r.naigre.reader.pdf.PdfRect
import wund0r.naigre.reader.pdf.MuPdfDocument
import wund0r.naigre.reader.pdf.RenderedPdfPage
import wund0r.naigre.reader.table.BookRecord
import wund0r.naigre.reader.table.LibraryItemKind
import wund0r.naigre.reader.table.sourceRevisionKey
import java.util.ArrayDeque
import java.io.File
import java.util.UUID
import java.util.concurrent.Executor

@RunWith(AndroidJUnit4::class)
class TextSearchIndexRepositoryTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var repository: TextSearchIndexRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "text-search-test-${UUID.randomUUID()}.db"
        repository = TextSearchIndexRepository(context, databaseName)
    }

    @After
    fun tearDown() {
        repository.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun cyrillicFullTextSupportsCaseFoldingPhrasesAndPrefixes() {
        val book = testBook("russian", 1)
        storeCompleteBook(book.id, book.sourceRevisionKey(), "ЁЖ сторожит БАШНЮ. Ёлочка у ворот.")
        for (query in listOf("ёж", "ЁЖ", "башню", "БАШНЮ", "башн", "ёлоч", "\"ёж сторожит\"")) {
            val result = searchBook(book, query)
            assertEquals(query, 1, result.totalMatches)
            assertEquals(book.id, result.hits.single().bookId)
        }
    }

    @Test
    fun cyrillicPdfRendersAndItsExtractedTextIsSearchable() {
        val file = File(context.cacheDir, "cyrillic-${UUID.randomUUID()}.pdf")
        try {
            val pdf = android.graphics.pdf.PdfDocument()
            try {
                val page = pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(480, 640, 1).create())
                page.canvas.drawText("Ёж сторожит башню", 24f, 80f, Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 26f })
                pdf.finishPage(page)
                file.outputStream().use(pdf::writeTo)
            } finally {
                pdf.close()
            }
            MuPdfDocument(file).use { pdf ->
                val rendered = pdf.renderPage(0, 480)
                assertEquals(480, rendered.bitmap.width)
                rendered.bitmap.recycle()
                val book = testBook("cyrillic-pdf", 1)
                storeCompleteBook(book.id, book.sourceRevisionKey(), pdf.textForSearch(0))
                for (query in listOf("ёж", "ЁЖ", "башн", "\"ёж сторожит\"")) {
                    assertEquals(query, 1, searchBook(book, query).totalMatches)
                }
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun repeatedBatchKeepsOneRowPerPageAndMonotonicProgress() {
        val progress = repository.prepareBook("book", 3, "revision")
        val pages = listOf(
            ExtractedTextPage(0, "amber dragon"),
            ExtractedTextPage(1, "blank follows"),
            ExtractedTextPage(2, ""),
        )

        val completed = repository.storePages(
            "book",
            3,
            "revision",
            progress.indexRunId,
            pages,
        )
        val repeated = repository.storePages(
            "book",
            3,
            "revision",
            progress.indexRunId,
            pages,
        )

        assertTrue(completed.complete)
        assertEquals(3, repeated.indexedPages)
        assertTrue(repeated.complete)
        assertEquals(1, search("amber").totalMatches)
        assertEquals(1, search("blank").totalMatches)
    }

    @Test
    fun forcedRebuildRejectsAnOlderRunWithTheSameSourceRevision() {
        val oldRun = repository.prepareBook("book", 1, "same-revision")
        repository.storePages(
            "book",
            1,
            "same-revision",
            oldRun.indexRunId,
            listOf(ExtractedTextPage(0, "obsolete dragon")),
        )

        val newRun = repository.prepareBook("book", 1, "same-revision", force = true)
        assertNotEquals(oldRun.indexRunId, newRun.indexRunId)
        val staleWrite = runCatching {
            repository.storePages(
                "book",
                1,
                "same-revision",
                oldRun.indexRunId,
                listOf(ExtractedTextPage(0, "stale result")),
            )
        }
        assertTrue(staleWrite.isFailure)

        repository.storePages(
            "book",
            1,
            "same-revision",
            newRun.indexRunId,
            listOf(ExtractedTextPage(0, "current phoenix")),
        )
        assertEquals(0, search("obsolete", 1, "same-revision").totalMatches)
        assertEquals(1, search("phoenix", 1, "same-revision").totalMatches)
    }

    @Test
    fun deletingOneBookLeavesAnotherBooksRowsAndProgress() {
        storeCompleteBook("first", "first-revision", "scarlet dragon")
        storeCompleteBook("second", "second-revision", "silver dragon")

        repository.deleteBook("first")

        assertNull(repository.progress("first"))
        assertNotNull(repository.progress("second"))
        val remaining = repository.search(
            query = "dragon",
            bookIds = listOf("first", "second"),
            pageCounts = mapOf("first" to 1, "second" to 1),
            sourceRevisions = mapOf("first" to "first-revision", "second" to "second-revision"),
        )
        assertEquals(1, remaining.totalMatches)
        assertEquals("second", remaining.hits.single().bookId)
    }

    @Test
    fun incompleteIndexResumesAfterRepositoryReopen() {
        val firstRun = repository.prepareBook("book", 2, "revision")
        repository.storePages(
            "book",
            2,
            "revision",
            firstRun.indexRunId,
            listOf(ExtractedTextPage(0, "first chamber")),
        )
        repository.close()
        repository = TextSearchIndexRepository(context, databaseName)

        val resumed = repository.prepareBook("book", 2, "revision")
        assertEquals(firstRun.indexRunId, resumed.indexRunId)
        assertEquals(1, resumed.indexedPages)
        val completed = repository.storePages(
            "book",
            2,
            "revision",
            resumed.indexRunId,
            listOf(ExtractedTextPage(1, "second chamber")),
        )

        assertTrue(completed.complete)
        assertEquals(2, search("chamber", 2).totalMatches)
    }

    @Test
    fun extractionFailurePreservesCommittedBatchesAndRequiresExplicitRetry() {
        var factoryCalls = 0
        val document = FakeTextDocument(24, failurePage = 13)
        val coordinator = TextIndexCoordinator.forTesting(
            repository = repository,
            documentFactory = {
                factoryCalls++
                document
            },
        )
        val book = testBook("coordinated", 24)

        coordinator.schedule(book)

        val partial = repository.progress(book.id)
        assertEquals(12, partial?.indexedPages)
        assertFalse(checkNotNull(partial).complete)
        assertEquals(13, coordinator.failure(book.id)?.pageIndex)
        assertEquals(1, factoryCalls)
        assertEquals(12, searchBook(book, "indexed").totalMatches)

        coordinator.schedule(book)
        assertEquals(1, factoryCalls)
        assertEquals(12, repository.progress(book.id)?.indexedPages)

        document.failurePage = null
        coordinator.schedule(book, force = true)
        assertEquals(2, factoryCalls)
        assertTrue(checkNotNull(repository.progress(book.id)).complete)
        assertNull(coordinator.failure(book.id))
        assertEquals(24, searchBook(book, "indexed").totalMatches)
    }

    @Test
    fun oneFailedBookDoesNotPreventAnotherBookFromFinishing() {
        val failing = testBook("failing", 13)
        val healthy = testBook("healthy", 2)
        val coordinator = TextIndexCoordinator.forTesting(
            repository = repository,
            documentFactory = { book ->
                if (book.id == failing.id) {
                    FakeTextDocument(book.pageCount, failurePage = 12)
                } else {
                    FakeTextDocument(book.pageCount, failurePage = null)
                }
            },
        )

        coordinator.schedule(failing)
        coordinator.schedule(healthy)

        assertNotNull(coordinator.failure(failing.id))
        assertFalse(checkNotNull(repository.progress(failing.id)).complete)
        assertNull(coordinator.failure(healthy.id))
        assertTrue(checkNotNull(repository.progress(healthy.id)).complete)
        assertEquals(2, searchBook(healthy, "indexed").totalMatches)
    }

    @Test
    fun supersededJobCleanupDoesNotUnregisterItsReplacement() {
        val executor = ManualExecutor()
        var factoryCalls = 0
        val coordinator = TextIndexCoordinator.forTesting(
            repository = repository,
            documentFactory = { book ->
                factoryCalls++
                FakeTextDocument(book.pageCount, failurePage = null)
            },
            executor = executor,
        )
        val book = testBook("replacement", 2)

        coordinator.schedule(book)
        coordinator.schedule(book, force = true)
        assertEquals(1, coordinator.activeJobCount())

        executor.runNext()
        assertEquals(1, coordinator.activeJobCount())
        assertEquals(0, factoryCalls)

        executor.runNext()
        assertEquals(0, coordinator.activeJobCount())
        assertEquals(1, factoryCalls)
        assertTrue(checkNotNull(repository.progress(book.id)).complete)
    }

    @Test
    fun cancelledSearchStopsWithoutReturningAResult() {
        storeCompleteBook("book", "revision", "amber dragon")
        val cancellationSignal = CancellationSignal().apply { cancel() }

        val result = runCatching {
            repository.search(
                query = "dragon",
                bookIds = listOf("book"),
                pageCounts = mapOf("book" to 1),
                sourceRevisions = mapOf("book" to "revision"),
                cancellationSignal = cancellationSignal,
            )
        }

        assertTrue(result.exceptionOrNull() is OperationCanceledException)
    }

    private fun storeCompleteBook(bookId: String, revision: String, text: String) {
        val progress = repository.prepareBook(bookId, 1, revision)
        repository.storePages(
            bookId,
            1,
            revision,
            progress.indexRunId,
            listOf(ExtractedTextPage(0, text)),
        )
    }

    private fun search(
        query: String,
        pageCount: Int = 3,
        revision: String = "revision",
    ): TextSearchSnapshot = repository.search(
        query = query,
        bookIds = listOf("book"),
        pageCounts = mapOf("book" to pageCount),
        sourceRevisions = mapOf("book" to revision),
    )

    private fun searchBook(book: BookRecord, query: String): TextSearchSnapshot = repository.search(
        query = query,
        bookIds = listOf(book.id),
        pageCounts = mapOf(book.id to book.pageCount),
        sourceRevisions = mapOf(book.id to book.sourceRevisionKey()),
    )

    private fun testBook(id: String, pageCount: Int) = BookRecord(
        id = id,
        title = id,
        fileName = "$id.pdf",
        uri = "content://test/$id",
        kind = LibraryItemKind.PDF,
        color = 0,
        pageCount = pageCount,
        pdfBookmarks = emptyList(),
        sourceRevisionToken = "test-revision",
    )

    private class FakeTextDocument(
        override val pageCount: Int,
        var failurePage: Int?,
    ) : PdfDocument {
        override fun renderPage(pageIndex: Int, targetWidthPx: Int): RenderedPdfPage =
            error("Rendering is not used by this test")

        override fun annotations(pageIndex: Int): List<PdfAnnotationInfo> = emptyList()

        override fun textInRect(pageIndex: Int, bounds: PdfRect): String? = null

        override fun textForSearch(pageIndex: Int): String {
            if (pageIndex == failurePage) error("damaged text layer")
            return "indexed page $pageIndex"
        }

        override fun searchText(
            pageIndex: Int,
            terms: List<String>,
            caseSensitive: Boolean,
        ): List<PdfRect> = emptyList()

        override fun outline(): List<PdfOutlineEntry> = emptyList()

        override fun close() = Unit
    }

    private class ManualExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun runNext() {
            check(tasks.isNotEmpty()) { "No queued task" }
            tasks.removeFirst().run()
        }
    }
}
