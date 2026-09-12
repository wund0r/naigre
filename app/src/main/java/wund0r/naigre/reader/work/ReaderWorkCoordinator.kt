// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.work

import android.os.Process
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Activity-scoped document work lanes.
 *
 * Primary and secondary work remain independent so a maintenance scan cannot delay an
 * interactive render. Each document handle must be opened, used, and closed on one lane only.
 */
class ReaderWorkCoordinator private constructor(
    private val primaryExecutor: Executor,
    private val secondaryExecutor: Executor,
    private val maintenanceExecutor: Executor,
    private val shutdownExecutors: () -> Unit,
) {
    companion object {
        fun create(): ReaderWorkCoordinator {
            val primary = singleWorker("naigre-primary-render", Process.THREAD_PRIORITY_DEFAULT)
            val secondary = singleWorker("naigre-secondary-render", Process.THREAD_PRIORITY_DEFAULT)
            val maintenance = singleWorker("naigre-maintenance", Process.THREAD_PRIORITY_BACKGROUND)
            return ReaderWorkCoordinator(primary, secondary, maintenance) {
                primary.shutdown()
                secondary.shutdown()
                maintenance.shutdown()
            }
        }

        internal fun forTesting(
            primaryExecutor: Executor,
            secondaryExecutor: Executor,
            maintenanceExecutor: Executor,
        ): ReaderWorkCoordinator = ReaderWorkCoordinator(
            primaryExecutor,
            secondaryExecutor,
            maintenanceExecutor,
            shutdownExecutors = {},
        )

        private fun singleWorker(name: String, priority: Int): ExecutorService =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(
                    {
                        runCatching { Process.setThreadPriority(priority) }
                        runnable.run()
                    },
                    name,
                )
            }
    }

    @Volatile private var closed = false

    fun submitPrimary(block: () -> Unit) = submit(primaryExecutor, block)

    fun submitSecondary(block: () -> Unit) = submit(secondaryExecutor, block)

    fun submitMaintenance(block: () -> Unit) = submit(maintenanceExecutor, block)

    /** Speculative work yields CPU to interactive work, then restores this lane's priority. */
    fun submitSpeculative(block: () -> Unit) = submit(secondaryExecutor) {
        withTemporaryPriority(Process.THREAD_PRIORITY_BACKGROUND, block)
    }

    fun shutdown() {
        if (closed) return
        closed = true
        shutdownExecutors()
    }

    private fun submit(executor: Executor, block: () -> Unit): Boolean {
        if (closed) return false
        return try {
            executor.execute(block)
            true
        } catch (_: RejectedExecutionException) {
            // Activity teardown can race a queued UI callback; dropping late work is correct.
            false
        }
    }

    private fun withTemporaryPriority(priority: Int, block: () -> Unit) {
        val threadId = Process.myTid()
        val previous = runCatching { Process.getThreadPriority(threadId) }
            .getOrDefault(Process.THREAD_PRIORITY_DEFAULT)
        try {
            runCatching { Process.setThreadPriority(priority) }
            block()
        } finally {
            runCatching { Process.setThreadPriority(previous) }
        }
    }
}
