package io.github.nutea.anylisten.core.data

import io.github.nutea.anylisten.core.data.repo.SingleFlight
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SingleFlightTest {
    @Test
    fun sharesInFlightWork() = runBlocking {
        val flight = SingleFlight<Int>()
        val runs = AtomicInteger()
        coroutineScope {
            val a = async {
                flight.join {
                    runs.incrementAndGet()
                    delay(30)
                    7
                }
            }
            val b = async {
                flight.join {
                    runs.incrementAndGet()
                    delay(30)
                    8
                }
            }
            assertEquals(7, a.await())
            assertEquals(7, b.await())
        }
        assertEquals(1, runs.get())
    }

    @Test
    fun failuresAreSharedButDoNotPoisonLaterAttempts() = runBlocking {
        val flight = SingleFlight<Int>()
        val runs = AtomicInteger()
        val failed = runCatching {
            flight.join {
                runs.incrementAndGet()
                error("boom")
            }
        }
        assertTrue(failed.isFailure)
        assertEquals(5, flight.join { runs.incrementAndGet(); 5 })
        assertEquals(2, runs.get())
    }

    /**
     * A timed-out session restore or media resolve used to leave the slot occupied by an
     * already-completed deferred, so every later call replayed the same stale failure forever.
     */
    @Test
    fun aTimedOutOwnerDoesNotBlockEveryLaterCall() = runBlocking {
        val flight = SingleFlight<Int>()
        val runs = AtomicInteger()
        val timedOut = withTimeoutOrNull(100) {
            flight.join {
                runs.incrementAndGet()
                delay(10_000)
                1
            }
        }
        assertNull(timedOut)
        assertEquals(2, withTimeout(5_000) { flight.join { runs.incrementAndGet(); 2 } })
        assertEquals(2, runs.get())
    }

    /** A joiner that was never cancelled must not inherit the owner's cancellation. */
    @Test
    fun aCancelledOwnerHandsTheWorkToAWaitingJoiner() = runBlocking {
        val flight = SingleFlight<Int>()
        val started = CompletableDeferred<Unit>()
        val runs = AtomicInteger()
        coroutineScope {
            val owner = async {
                flight.join {
                    runs.incrementAndGet()
                    started.complete(Unit)
                    delay(10_000)
                    1
                }
            }
            started.await()
            val joiner = async { flight.join { runs.incrementAndGet(); 2 } }
            delay(50)
            owner.cancel()
            assertEquals(2, withTimeout(5_000) { joiner.await() })
        }
        assertEquals(2, runs.get())
    }
}
