// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.table

import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class BookLibraryStorageThreadTest {
    @Test
    fun catalogIoRunsOffMainAndCompletionReturnsToMain() {
        val ioThread = AtomicReference<Thread>()
        val callbackThread = AtomicReference<Thread>()
        val completed = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val handler = Handler(Looper.getMainLooper())
        val storage = BookLibraryStorage.forTesting(
            store = object : BookLibraryStore {
                override fun readCatalog(): BookCatalogState {
                    ioThread.set(Thread.currentThread())
                    return BookCatalogState.Missing
                }

                override fun readIndex(book: BookRecord) = BookIndexLoadResult.Loaded(book)
                override fun saveCatalog(library: BookLibraryState) = Unit
                override fun saveIndex(book: BookRecord) = Unit
                override fun deleteIndex(bookId: String) = Unit
            },
            executor = executor,
            dispatchResult = { handler.post(it) },
        )

        try {
            storage.loadCatalog {
                callbackThread.set(Thread.currentThread())
                completed.countDown()
            }

            assertTrue(completed.await(5, TimeUnit.SECONDS))
            assertNotEquals(Looper.getMainLooper().thread, ioThread.get())
            assertTrue(callbackThread.get() === Looper.getMainLooper().thread)
        } finally {
            executor.shutdownNow()
        }
    }
}
