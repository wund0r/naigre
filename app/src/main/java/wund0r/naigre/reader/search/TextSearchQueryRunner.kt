// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.search

import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.Process
import android.os.SystemClock
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

data class TextSearchRequest(
    val requestId: Long,
    val sessionId: String,
    val query: String,
    val bookIds: List<String>,
    val pageCounts: Map<String, Int>,
    val sourceRevisions: Map<String, String>,
    val resultLimit: Int,
    val submittedAtElapsedMs: Long,
)

data class TextSearchCompletion(
    val request: TextSearchRequest,
    val snapshot: TextSearchSnapshot?,
    val failure: Throwable?,
    val cancelled: Boolean,
    val queueWaitMs: Long,
    val executionMs: Long,
    val rerunPending: Boolean,
)

/**
 * Runs one UI search at a time and gives every request a real SQLite cancellation signal.
 *
 * Explicit requests replace current work. Index-progress requests never starve a useful running
 * query: only their newest immutable snapshot is retained and run immediately afterwards.
 */
class TextSearchQueryRunner private constructor(
    private val search: (TextSearchRequest, CancellationSignal) -> TextSearchSnapshot,
    private val executor: Executor,
    private val dispatchCompletion: (Runnable) -> Unit,
    private val elapsedRealtime: () -> Long,
    private val shutdownExecutor: () -> Unit,
    private val listener: (TextSearchCompletion) -> Unit,
) : AutoCloseable {
    private data class ActiveRequest(
        val request: TextSearchRequest,
        val cancellationSignal: CancellationSignal,
    )

    companion object {
        fun create(
            repository: TextSearchIndexRepository,
            dispatchCompletion: (Runnable) -> Unit,
            listener: (TextSearchCompletion) -> Unit,
        ): TextSearchQueryRunner {
            val executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(
                    {
                        runCatching {
                            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                        }
                        runnable.run()
                    },
                    "naigre-text-query",
                )
            }
            return TextSearchQueryRunner(
                search = { request, cancellationSignal ->
                    repository.search(
                        query = request.query,
                        bookIds = request.bookIds,
                        pageCounts = request.pageCounts,
                        sourceRevisions = request.sourceRevisions,
                        resultLimit = request.resultLimit,
                        cancellationSignal = cancellationSignal,
                    )
                },
                executor = executor,
                dispatchCompletion = dispatchCompletion,
                elapsedRealtime = SystemClock::elapsedRealtime,
                shutdownExecutor = executor::shutdownNow,
                listener = listener,
            )
        }

        internal fun forTesting(
            search: (TextSearchRequest, CancellationSignal) -> TextSearchSnapshot,
            executor: Executor = Executor { it.run() },
            dispatchCompletion: (Runnable) -> Unit = { it.run() },
            elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
            shutdownExecutor: () -> Unit = {
                (executor as? ExecutorService)?.shutdownNow()
            },
            listener: (TextSearchCompletion) -> Unit,
        ): TextSearchQueryRunner = TextSearchQueryRunner(
            search,
            executor,
            dispatchCompletion,
            elapsedRealtime,
            shutdownExecutor,
            listener,
        )
    }

    private val lock = Any()
    private var active: ActiveRequest? = null
    private var pendingProgressRequest: TextSearchRequest? = null
    private var closed = false

    /** Replaces current work, or coalesces behind it when this is only an index-progress refresh. */
    fun submit(request: TextSearchRequest, progressOnly: Boolean = false) {
        var replacedSignal: CancellationSignal? = null
        val requestToStart = synchronized(lock) {
            if (closed) return
            if (progressOnly && active != null) {
                pendingProgressRequest = request
                return
            }
            pendingProgressRequest = null
            replacedSignal = active?.cancellationSignal
            ActiveRequest(request, CancellationSignal()).also { active = it }
        }
        replacedSignal?.cancel()
        execute(requestToStart)
    }

    /** Cancels running/queued work and discards a coalesced progress refresh. */
    fun cancel() {
        val signal = synchronized(lock) {
            pendingProgressRequest = null
            active?.cancellationSignal.also { active = null }
        }
        signal?.cancel()
    }

    override fun close() {
        val signal = synchronized(lock) {
            if (closed) return
            closed = true
            pendingProgressRequest = null
            active?.cancellationSignal.also { active = null }
        }
        signal?.cancel()
        shutdownExecutor()
    }

    private fun execute(activeRequest: ActiveRequest) {
        try {
            executor.execute { run(activeRequest) }
        } catch (failure: RejectedExecutionException) {
            finish(activeRequest, null, failure, cancelled = false, 0L, 0L)
        }
    }

    private fun run(activeRequest: ActiveRequest) {
        val startedAt = elapsedRealtime()
        val queueWaitMs = (startedAt - activeRequest.request.submittedAtElapsedMs).coerceAtLeast(0L)
        var snapshot: TextSearchSnapshot? = null
        var failure: Throwable? = null
        var cancelled = false
        try {
            activeRequest.cancellationSignal.throwIfCanceled()
            snapshot = search(activeRequest.request, activeRequest.cancellationSignal)
            activeRequest.cancellationSignal.throwIfCanceled()
        } catch (_: OperationCanceledException) {
            cancelled = true
        } catch (caught: Throwable) {
            failure = caught
        }
        val executionMs = (elapsedRealtime() - startedAt).coerceAtLeast(0L)
        finish(activeRequest, snapshot, failure, cancelled, queueWaitMs, executionMs)
    }

    private fun finish(
        activeRequest: ActiveRequest,
        snapshot: TextSearchSnapshot?,
        failure: Throwable?,
        cancelled: Boolean,
        queueWaitMs: Long,
        executionMs: Long,
    ) {
        dispatchCompletion(Runnable {
            val nextRequest = synchronized(lock) {
                if (closed || active !== activeRequest) return@Runnable
                active = null
                val next = if (failure == null && !cancelled) pendingProgressRequest else null
                pendingProgressRequest = null
                next
            }
            listener(
                TextSearchCompletion(
                    request = activeRequest.request,
                    snapshot = snapshot,
                    failure = failure,
                    cancelled = cancelled,
                    queueWaitMs = queueWaitMs,
                    executionMs = executionMs,
                    rerunPending = nextRequest != null,
                ),
            )
            if (nextRequest != null) startIfIdle(nextRequest)
        })
    }

    private fun startIfIdle(request: TextSearchRequest) {
        val requestToStart = synchronized(lock) {
            if (closed || active != null) return
            ActiveRequest(request, CancellationSignal()).also { active = it }
        }
        execute(requestToStart)
    }
}
