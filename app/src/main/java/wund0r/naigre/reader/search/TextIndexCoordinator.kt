// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.search

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import wund0r.naigre.reader.markdown.MarkdownDocument
import wund0r.naigre.reader.markdown.MarkdownEngine
import wund0r.naigre.reader.pdf.MuPdfDocument
import wund0r.naigre.reader.pdf.PdfDocument
import wund0r.naigre.reader.table.BookRecord
import wund0r.naigre.reader.table.LibraryItemKind
import wund0r.naigre.reader.table.sourceRevisionKey
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executor
import java.util.concurrent.Executors

data class TextIndexFailure(
    val bookId: String,
    val sourceRevision: String,
    val pageIndex: Int?,
    val message: String,
)

enum class TextIndexEvent {
    PROGRESS,
    FINISHED,
    FAILED,
    DELETED,
}

/**
 * Process-owned text extraction queue.
 *
 * Indexing must outlive Activity recreation: an Activity-local worker can overlap its replacement
 * and write stale data after a forced rebuild. The repository's persisted run ID is the final
 * database guard, while this coordinator prevents duplicate extraction work in the normal case.
 */
class TextIndexCoordinator private constructor(
    private val repository: TextSearchIndexRepository,
    private val documentFactory: (BookRecord) -> PdfDocument,
    private val executor: Executor,
    private val dispatchEvent: (Runnable) -> Unit,
) {
    fun interface Listener {
        fun onTextIndexEvent(bookId: String, event: TextIndexEvent)
    }

    private sealed interface Operation {
        val id: String
        val bookId: String
    }

    private data class IndexOperation(
        override val id: String,
        override val bookId: String,
        val book: BookRecord,
        val sourceRevision: String,
        val force: Boolean,
    ) : Operation

    private data class DeleteOperation(
        override val id: String,
        override val bookId: String,
    ) : Operation

    companion object {
        @Volatile private var sharedInstance: TextIndexCoordinator? = null

        fun shared(context: Context): TextIndexCoordinator =
            sharedInstance ?: synchronized(this) {
                sharedInstance ?: create(context.applicationContext).also { sharedInstance = it }
            }

        private fun create(context: Context): TextIndexCoordinator {
            val resolver = context.contentResolver
            val markdownEngine = MarkdownEngine(context)
            val handler = Handler(Looper.getMainLooper())
            return TextIndexCoordinator(
                repository = TextSearchIndexRepository.shared(context),
                documentFactory = { book ->
                    when (book.kind) {
                        LibraryItemKind.PDF -> {
                            val descriptor = requireNotNull(
                                resolver.openFileDescriptor(Uri.parse(book.uri), "r"),
                            ) { "Could not open PDF" }
                            MuPdfDocument(descriptor)
                        }
                        LibraryItemKind.MARKDOWN ->
                            MarkdownDocument(resolver, Uri.parse(book.uri), markdownEngine)
                        LibraryItemKind.IMAGE_COLLECTION ->
                            error("Image albums do not have searchable text")
                    }
                },
                executor = Executors.newSingleThreadExecutor { runnable ->
                    Thread(
                        {
                            runCatching {
                                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                            }
                            runnable.run()
                        },
                        "naigre-text-index",
                    )
                },
                dispatchEvent = { handler.post(it) },
            )
        }

        internal fun forTesting(
            repository: TextSearchIndexRepository,
            documentFactory: (BookRecord) -> PdfDocument,
            executor: Executor = Executor { it.run() },
            dispatchEvent: (Runnable) -> Unit = { it.run() },
        ): TextIndexCoordinator = TextIndexCoordinator(
            repository,
            documentFactory,
            executor,
            dispatchEvent,
        )
    }

    private val operationLock = Any()
    private val operations = HashMap<String, Operation>()
    private val failures = ConcurrentHashMap<String, TextIndexFailure>()
    private val listeners = CopyOnWriteArraySet<Listener>()

    fun addListener(listener: Listener) {
        listeners += listener
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    fun schedule(book: BookRecord, force: Boolean = false) {
        if (book.kind == LibraryItemKind.IMAGE_COLLECTION) return
        val sourceRevision = book.sourceRevisionKey()
        val existingFailure = failures[book.id]
        if (!force && existingFailure?.sourceRevision == sourceRevision) {
            notify(book.id, TextIndexEvent.FAILED)
            return
        }

        val operation = IndexOperation(
            id = UUID.randomUUID().toString(),
            bookId = book.id,
            book = book,
            sourceRevision = sourceRevision,
            force = force,
        )
        val accepted = synchronized(operationLock) {
            val existing = operations[book.id]
            if (
                !force && existing is IndexOperation &&
                existing.sourceRevision == sourceRevision
            ) {
                false
            } else {
                operations[book.id] = operation
                true
            }
        }
        if (!accepted) return
        failures.remove(book.id)
        executor.execute { runIndex(operation) }
    }

    /** Stops extraction but preserves already committed index pages. */
    fun cancel(bookId: String) {
        synchronized(operationLock) {
            if (operations[bookId] is IndexOperation) operations.remove(bookId)
        }
    }

    /** Supersedes extraction and removes this book's derived full-text data. */
    fun delete(bookId: String) {
        val operation = DeleteOperation(UUID.randomUUID().toString(), bookId)
        synchronized(operationLock) { operations[bookId] = operation }
        failures.remove(bookId)
        executor.execute { runDelete(operation) }
    }

    fun failure(bookId: String): TextIndexFailure? = failures[bookId]

    fun failures(): List<TextIndexFailure> = failures.values.sortedBy { it.bookId }

    fun activeJobCount(): Int = synchronized(operationLock) {
        operations.values.count { it is IndexOperation }
    }

    private fun runIndex(operation: IndexOperation) {
        var document: PdfDocument? = null
        var terminalEvent = TextIndexEvent.FINISHED
        try {
            if (!isCurrent(operation)) return
            var progress = repository.prepareBook(
                operation.bookId,
                operation.book.pageCount,
                operation.sourceRevision,
                operation.force,
            )
            if (progress.complete) return
            notify(operation.bookId, TextIndexEvent.PROGRESS)

            val openedDocument = documentFactory(operation.book)
            document = openedDocument
            require(openedDocument.pageCount == operation.book.pageCount) {
                "Document structure changed; refresh ${operation.book.title}"
            }
            var nextPage = progress.indexedPages
            while (nextPage < openedDocument.pageCount && isCurrent(operation)) {
                val endExclusive = minOf(nextPage + 12, openedDocument.pageCount)
                val batch = ArrayList<ExtractedTextPage>(endExclusive - nextPage)
                for (pageIndex in nextPage until endExclusive) {
                    if (!isCurrent(operation)) return
                    val text = try {
                        openedDocument.textForSearch(pageIndex)
                    } catch (failure: Throwable) {
                        throw PageExtractionException(pageIndex, failure)
                    }
                    batch += ExtractedTextPage(pageIndex, text)
                }
                if (!isCurrent(operation)) return
                progress = repository.storePages(
                    operation.bookId,
                    openedDocument.pageCount,
                    operation.sourceRevision,
                    progress.indexRunId,
                    batch,
                )
                nextPage = progress.indexedPages
                if (progress.complete || nextPage % 60 < batch.size) {
                    notify(operation.bookId, TextIndexEvent.PROGRESS)
                }
            }
        } catch (failure: Throwable) {
            if (isCurrent(operation)) {
                val extraction = failure as? PageExtractionException
                val cause = extraction?.cause ?: failure
                failures[operation.bookId] = TextIndexFailure(
                    bookId = operation.bookId,
                    sourceRevision = operation.sourceRevision,
                    pageIndex = extraction?.pageIndex,
                    message = cause.message ?: cause.javaClass.simpleName,
                )
                terminalEvent = TextIndexEvent.FAILED
            }
        } finally {
            runCatching { document?.close() }
            if (finish(operation)) notify(operation.bookId, terminalEvent)
        }
    }

    private fun runDelete(operation: DeleteOperation) {
        var terminalEvent = TextIndexEvent.DELETED
        try {
            if (!isCurrent(operation)) return
            repository.deleteBook(operation.bookId)
        } catch (failure: Throwable) {
            if (isCurrent(operation)) {
                failures[operation.bookId] = TextIndexFailure(
                    bookId = operation.bookId,
                    sourceRevision = "",
                    pageIndex = null,
                    message = failure.message ?: failure.javaClass.simpleName,
                )
                terminalEvent = TextIndexEvent.FAILED
            }
        } finally {
            if (finish(operation)) notify(operation.bookId, terminalEvent)
        }
    }

    private fun isCurrent(operation: Operation): Boolean = synchronized(operationLock) {
        operations[operation.bookId]?.id == operation.id
    }

    private fun finish(operation: Operation): Boolean = synchronized(operationLock) {
        if (operations[operation.bookId]?.id != operation.id) {
            false
        } else {
            operations.remove(operation.bookId)
            true
        }
    }

    private fun notify(bookId: String, event: TextIndexEvent) {
        dispatchEvent(Runnable {
            listeners.forEach { it.onTextIndexEvent(bookId, event) }
        })
    }

    private class PageExtractionException(
        val pageIndex: Int,
        cause: Throwable,
    ) : RuntimeException(cause)
}
