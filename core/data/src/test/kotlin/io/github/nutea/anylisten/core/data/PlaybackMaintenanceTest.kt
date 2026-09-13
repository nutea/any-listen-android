package io.github.nutea.anylisten.core.data

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackMaintenanceTest {
    @Test fun playingDefersAndCancelsMaintenanceThenResumesWhenIdle() = runTest {
        val gate = PlaybackMaintenance()
        gate.setActive(true)
        var starts = 0
        var cancellations = 0
        var finished = false
        val job = launch { gate.run {
            starts++
            try { delay(10_000); finished = true } finally { if (!finished) cancellations++ }
        } }
        runCurrent(); assertEquals(0, starts)
        gate.setActive(false); runCurrent(); assertEquals(1, starts)
        gate.setActive(true); runCurrent(); assertEquals(1, cancellations); assertFalse(finished)
        gate.setActive(false); advanceUntilIdle()
        assertEquals(2, starts); assertTrue(finished); assertTrue(job.isCompleted)
    }
}
