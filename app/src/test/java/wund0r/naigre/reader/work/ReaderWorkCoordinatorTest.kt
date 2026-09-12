// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.Executor

class ReaderWorkCoordinatorTest {
    @Test
    fun queuedMaintenanceCannotDelayInteractiveSecondaryWork() {
        val primary = ManualExecutor()
        val secondary = ManualExecutor()
        val maintenance = ManualExecutor()
        val coordinator = ReaderWorkCoordinator.forTesting(primary, secondary, maintenance)
        var scanFinished = false
        var referenceStarted = false

        coordinator.submitMaintenance { scanFinished = true }
        coordinator.submitSecondary { referenceStarted = true }

        assertEquals(1, maintenance.pendingCount)
        assertEquals(1, secondary.pendingCount)
        secondary.runNext()

        assertTrue(referenceStarted)
        assertFalse(scanFinished)
        maintenance.runNext()
        assertTrue(scanFinished)
    }

    @Test
    fun eachLaneRetainsItsOwnFifoOrdering() {
        val primary = ManualExecutor()
        val secondary = ManualExecutor()
        val maintenance = ManualExecutor()
        val coordinator = ReaderWorkCoordinator.forTesting(primary, secondary, maintenance)
        val events = mutableListOf<String>()

        coordinator.submitMaintenance { events += "scan-a" }
        coordinator.submitMaintenance { events += "scan-b" }
        coordinator.submitSecondary { events += "reference" }

        maintenance.runNext()
        secondary.runNext()
        maintenance.runNext()

        assertEquals(listOf("scan-a", "reference", "scan-b"), events)
    }

    private class ManualExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()
        val pendingCount: Int get() = tasks.size

        override fun execute(command: Runnable) {
            tasks += command
        }

        fun runNext() {
            tasks.removeFirst().run()
        }
    }
}
