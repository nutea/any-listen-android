package io.github.nutea.anylisten.core.data

import io.github.nutea.anylisten.core.data.repo.SingleFlight
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
}
