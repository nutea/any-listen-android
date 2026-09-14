package io.github.nutea.anylisten.core.data

import io.github.nutea.anylisten.core.data.repo.LibraryAutoSync
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryAutoSyncTest {
    @Test fun foregroundIsThrottledAndPeriodicReconciliationRecoversMissedPush() = runTest {
        val calls = mutableListOf<Set<String>?>()
        val sync = LibraryAutoSync(backgroundScope, { true }, { testScheduler.currentTime }) { calls.add(it) }
        repeat(10) { sync.onForeground() }
        runCurrent(); advanceTimeBy(251); runCurrent()
        assertEquals(listOf<Set<String>?>(null), calls)
        repeat(10) { sync.onForeground() }
        advanceTimeBy(59_000); runCurrent()
        assertEquals(1, calls.size)
        advanceTimeBy(1_001); sync.onForeground(); runCurrent(); advanceTimeBy(251); runCurrent()
        assertEquals(2, calls.size)
        // No more notifications or foreground events: the timer must eventually recover missed changes.
        advanceTimeBy(600_000); runCurrent()
        assertEquals(3, calls.size)
        assertTrue(calls.all { it == null })
    }

    @Test fun partialRequestsUnionAndFullReconciliationDominates() = runTest {
        val calls = mutableListOf<Set<String>?>()
        val sync = LibraryAutoSync(backgroundScope, { true }) { calls.add(it) }
        sync.request(io.github.nutea.anylisten.core.data.gateway.LibraryChange(setOf("a")))
        sync.request(io.github.nutea.anylisten.core.data.gateway.LibraryChange(setOf("b")))
        runCurrent(); advanceTimeBy(251); runCurrent()
        assertEquals(setOf("a", "b"), calls.single())
        sync.request(io.github.nutea.anylisten.core.data.gateway.LibraryChange(setOf("c")))
        sync.request()
        runCurrent(); advanceTimeBy(251); runCurrent()
        assertNull(calls.last())
    }

    @Test fun burstsCoalesceButChangesDuringFetchAreNotLost() = runTest {
        var calls = 0
        val first = CompletableDeferred<Unit>()
        val sync = LibraryAutoSync(backgroundScope, { true }) {
            calls++
            if (calls == 1) first.await()
        }
        repeat(20) { sync.request() }
        runCurrent(); advanceTimeBy(251); runCurrent()
        assertEquals(1, calls)
        repeat(20) { sync.request() }
        first.complete(Unit)
        runCurrent(); advanceTimeBy(251); runCurrent()
        assertEquals(2, calls)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(2, calls)
    }

    @Test fun transientFailureRetriesAndOfflineWaitsForReconnect() = runTest {
        var online = true
        var calls = 0
        val sync = LibraryAutoSync(backgroundScope, { online }) {
            calls++
            if (calls == 1) throw java.io.IOException("temporary")
        }
        sync.request(); runCurrent(); advanceTimeBy(251); runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(1_001); runCurrent()
        assertEquals(2, calls)
        online = false
        sync.request(); runCurrent(); advanceTimeBy(60_000); runCurrent()
        assertEquals(2, calls)
        online = true
        sync.request(); runCurrent(); advanceTimeBy(251); runCurrent()
        assertEquals(3, calls)
    }
}
