// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.work

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ReaderWorkCoordinatorThreadTest {
    @Test
    fun speculativePrefetchRestoresSecondaryWorkerPriority() {
        val coordinator = ReaderWorkCoordinator.create()
        val completed = CountDownLatch(3)
        val initialPriority = AtomicInteger(Int.MIN_VALUE)
        val speculativePriority = AtomicInteger(Int.MIN_VALUE)
        val restoredPriority = AtomicInteger(Int.MIN_VALUE)
        try {
            coordinator.submitSecondary {
                initialPriority.set(Process.getThreadPriority(Process.myTid()))
                completed.countDown()
            }
            coordinator.submitSpeculative {
                speculativePriority.set(Process.getThreadPriority(Process.myTid()))
                completed.countDown()
            }
            coordinator.submitSecondary {
                restoredPriority.set(Process.getThreadPriority(Process.myTid()))
                completed.countDown()
            }

            assertTrue("worker tasks did not finish", completed.await(5, TimeUnit.SECONDS))
            assertEquals(Process.THREAD_PRIORITY_BACKGROUND, speculativePriority.get())
            assertEquals(initialPriority.get(), restoredPriority.get())
        } finally {
            coordinator.shutdown()
        }
    }
}
