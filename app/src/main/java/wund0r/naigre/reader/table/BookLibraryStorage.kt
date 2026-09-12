// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.table

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executor
import java.util.concurrent.Executors

enum class BookStorageOperation(val label: String) {
    LOAD_CATALOG("Load library"),
    LOAD_INDEX("Load item index"),
    SAVE_CATALOG("Save library"),
    SAVE_INDEX("Save item index"),
    DELETE_INDEX("Delete item index"),
}

data class BookStorageFailure(
    val operation: BookStorageOperation,
    val message: String,
    val bookId: String? = null,
)

/**
 * Process-owned FIFO for every app-private library/index read and write.
 *
 * Activity recreation must not create competing writers. Callers submit immutable snapshots and
 * receive results on the main thread; the small process-kill window of asynchronous saves remains.
 */
class BookLibraryStorage private constructor(
    private val store: BookLibraryStore,
    private val executor: Executor,
    private val dispatchResult: (Runnable) -> Unit,
) {
    fun interface Listener {
        fun onBookStorageFailure(failure: BookStorageFailure)
    }

    companion object {
        @Volatile private var sharedInstance: BookLibraryStorage? = null

        fun shared(context: Context): BookLibraryStorage =
            sharedInstance ?: synchronized(this) {
                sharedInstance ?: create(context.applicationContext).also { sharedInstance = it }
            }

        private fun create(context: Context): BookLibraryStorage {
            val handler = Handler(Looper.getMainLooper())
            return BookLibraryStorage(
                store = BookLibraryRepository(context),
                executor = Executors.newSingleThreadExecutor { runnable ->
                    Thread(
                        {
                            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
                            runnable.run()
                        },
                        "naigre-library-storage",
                    )
                },
                dispatchResult = { handler.post(it) },
            )
        }

        internal fun forTesting(
            store: BookLibraryStore,
            executor: Executor = Executor { it.run() },
            dispatchResult: (Runnable) -> Unit = { it.run() },
        ): BookLibraryStorage = BookLibraryStorage(store, executor, dispatchResult)
    }

    private val listeners = CopyOnWriteArraySet<Listener>()
    @Volatile private var catalogState: BookCatalogState = BookCatalogState.Loading
    @Volatile private var latestFailure: BookStorageFailure? = null

    fun addListener(listener: Listener) {
        listeners += listener
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    fun currentCatalogState(): BookCatalogState = catalogState

    fun lastFailure(): BookStorageFailure? = latestFailure

    fun loadCatalog(onResult: (BookCatalogState) -> Unit) {
        executor.execute {
            catalogState = BookCatalogState.Loading
            val result = try {
                store.readCatalog()
            } catch (failure: Throwable) {
                BookCatalogState.Failed(
                    CatalogLoadFailure(
                        CatalogFailureKind.READ,
                        failure.message ?: failure.javaClass.simpleName,
                        failure,
                    ),
                )
            }
            require(result !is BookCatalogState.Loading) { "A catalog read cannot finish as Loading" }
            catalogState = result
            if (result is BookCatalogState.Failed) {
                recordFailure(
                    BookStorageFailure(
                        BookStorageOperation.LOAD_CATALOG,
                        result.failure.message,
                    ),
                    notify = false,
                )
            }
            dispatchResult(Runnable { onResult(result) })
        }
    }

    fun loadIndex(book: BookRecord, onResult: (BookIndexLoadResult) -> Unit) {
        val snapshot = immutableBook(book)
        executor.execute {
            val result = if (catalogReady()) {
                try {
                    store.readIndex(snapshot)
                } catch (failure: Throwable) {
                    BookIndexLoadResult.Failed(
                        snapshot,
                        failure.message ?: failure.javaClass.simpleName,
                        failure,
                    )
                }
            } else {
                BookIndexLoadResult.Failed(snapshot, "Library catalog is not ready")
            }
            if (result is BookIndexLoadResult.Failed) {
                recordFailure(
                    BookStorageFailure(
                        BookStorageOperation.LOAD_INDEX,
                        result.message,
                        snapshot.id,
                    ),
                    notify = false,
                )
            }
            dispatchResult(Runnable { onResult(result) })
        }
    }

    fun loadIndexes(books: List<BookRecord>, onResult: (List<BookIndexLoadResult>) -> Unit) {
        val snapshots = books.map(::immutableBook)
        executor.execute {
            val results = snapshots.map { snapshot ->
                if (catalogReady()) {
                    try {
                        store.readIndex(snapshot)
                    } catch (failure: Throwable) {
                        BookIndexLoadResult.Failed(
                            snapshot,
                            failure.message ?: failure.javaClass.simpleName,
                            failure,
                        )
                    }
                } else {
                    BookIndexLoadResult.Failed(snapshot, "Library catalog is not ready")
                }
            }
            results.filterIsInstance<BookIndexLoadResult.Failed>().forEach { failure ->
                recordFailure(
                    BookStorageFailure(
                        BookStorageOperation.LOAD_INDEX,
                        failure.message,
                        failure.book.id,
                    ),
                    notify = false,
                )
            }
            dispatchResult(Runnable { onResult(results) })
        }
    }

    fun saveCatalog(library: BookLibraryState) {
        val snapshot = immutableLibrary(library)
        executor.execute {
            if (!catalogReady()) {
                recordFailure(
                    BookStorageFailure(
                        BookStorageOperation.SAVE_CATALOG,
                        "Library load has not completed successfully; the existing catalog was not changed",
                    ),
                )
                return@execute
            }
            try {
                store.saveCatalog(snapshot)
                catalogState = BookCatalogState.Loaded(snapshot)
            } catch (failure: Throwable) {
                recordFailure(
                    BookStorageFailure(
                        BookStorageOperation.SAVE_CATALOG,
                        failure.message ?: failure.javaClass.simpleName,
                    ),
                )
            }
        }
    }

    fun saveIndex(book: BookRecord) {
        val snapshot = immutableBook(book)
        executor.execute {
            if (!catalogReady()) {
                recordFailure(
                    BookStorageFailure(
                        BookStorageOperation.SAVE_INDEX,
                        "Library load has not completed successfully; the item index was not changed",
                        snapshot.id,
                    ),
                )
                return@execute
            }
            try {
                store.saveIndex(snapshot)
            } catch (failure: Throwable) {
                recordFailure(
                    BookStorageFailure(
                        BookStorageOperation.SAVE_INDEX,
                        failure.message ?: failure.javaClass.simpleName,
                        snapshot.id,
                    ),
                )
            }
        }
    }

    fun deleteIndex(bookId: String) {
        executor.execute {
            if (!catalogReady()) {
                recordFailure(
                    BookStorageFailure(
                        BookStorageOperation.DELETE_INDEX,
                        "Library load has not completed successfully; the item index was not deleted",
                        bookId,
                    ),
                )
                return@execute
            }
            try {
                store.deleteIndex(bookId)
            } catch (failure: Throwable) {
                recordFailure(
                    BookStorageFailure(
                        BookStorageOperation.DELETE_INDEX,
                        failure.message ?: failure.javaClass.simpleName,
                        bookId,
                    ),
                )
            }
        }
    }

    private fun catalogReady(): Boolean =
        catalogState is BookCatalogState.Loaded || catalogState is BookCatalogState.Missing

    private fun recordFailure(failure: BookStorageFailure, notify: Boolean = true) {
        latestFailure = failure
        if (notify) dispatchResult(Runnable {
            listeners.forEach { it.onBookStorageFailure(failure) }
        })
    }

    private fun immutableLibrary(library: BookLibraryState): BookLibraryState = BookLibraryState(
        books = library.books.map(::immutableBook),
        selectedBookIds = LinkedHashSet(library.selectedBookIds),
        folders = library.folders.toList(),
        tags = library.tags.toList(),
    )

    // BookRecord and its entry records are treated as immutable; only the small tag set needs a
    // defensive copy. Avoid copying potentially huge bookmark/image lists on the UI thread.
    private fun immutableBook(book: BookRecord): BookRecord = book.copy(tagIds = book.tagIds.toSet())
}
