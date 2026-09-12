// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.search

import android.os.CancellationSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TextSearchQueryRunnerTest {
    @Test
    fun explicitRequestCancelsHeldQueryAndPublishesOnlyReplacement() {
        val firstStarted = CountDownLatch(1)
        val firstCancelled = CountDownLatch(1)
        val replacementFinished = CountDownLatch(1)
        val published = Collections.synchronizedList(mutableListOf<Long>())
        val executor = Executors.newSingleThreadExecutor()
        val runner = TextSearchQueryRunner.forTesting(
            executor = executor,
            shutdownExecutor = executor::shutdownNow,
            search = { request, cancellationSignal ->
                if (request.requestId == 1L) {
                    cancellationSignal.setOnCancelListener { firstCancelled.countDown() }
                    firstStarted.countDown()
                    firstCancelled.await(5, TimeUnit.SECONDS)
                    cancellationSignal.throwIfCanceled()
                }
                emptySnapshot()
            },
            listener = { completion ->
                published += completion.request.requestId
                if (completion.request.requestId == 2L) replacementFinished.countDown()
            },
        )
        try {
            runner.submit(request(1L))
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS))

            runner.submit(request(2L))

            assertTrue(firstCancelled.await(2, TimeUnit.SECONDS))
            assertTrue(replacementFinished.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(2L), published.toList())
        } finally {
            runner.close()
        }
    }

    @Test
    fun progressRefreshesKeepCurrentQueryAndCoalesceToNewestRequest() {
        val executor = ManualExecutor()
        val executed = mutableListOf<Long>()
        val completions = mutableListOf<TextSearchCompletion>()
        val runner = TextSearchQueryRunner.forTesting(
            executor = executor,
            search = { request, _ ->
                executed += request.requestId
                emptySnapshot()
            },
            listener = completions::add,
        )

        runner.submit(request(1L))
        runner.submit(request(2L), progressOnly = true)
        runner.submit(request(3L), progressOnly = true)

        executor.runNext()
        assertEquals(listOf(1L), executed)
        assertTrue(completions.single().rerunPending)

        executor.runNext()
        assertEquals(listOf(1L, 3L), executed)
        assertEquals(listOf(1L, 3L), completions.map { it.request.requestId })
        assertFalse(completions.last().rerunPending)
    }

    @Test
    fun closingRunnerCancelsQueuedRequestWithoutPublishing() {
        val executor = ManualExecutor()
        val completions = mutableListOf<TextSearchCompletion>()
        val runner = TextSearchQueryRunner.forTesting(
            executor = executor,
            search = { _, cancellationSignal ->
                cancellationSignal.throwIfCanceled()
                emptySnapshot()
            },
            listener = completions::add,
        )

        runner.submit(request(1L))
        runner.close()
        executor.runNext()

        assertTrue(completions.isEmpty())
    }

    private fun request(id: Long) = TextSearchRequest(
        requestId = id,
        sessionId = "session",
        query = "dragon",
        bookIds = listOf("book"),
        pageCounts = mapOf("book" to 1),
        sourceRevisions = mapOf("book" to "revision"),
        resultLimit = 160,
        submittedAtElapsedMs = 0L,
    )

    private fun emptySnapshot() = TextSearchSnapshot(
        hits = emptyList(),
        totalMatches = 0,
        indexedPages = 1,
        totalPages = 1,
        readyBooks = 1,
    )

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
