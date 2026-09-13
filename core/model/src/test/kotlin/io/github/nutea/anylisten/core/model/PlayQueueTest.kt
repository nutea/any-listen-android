package io.github.nutea.anylisten.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayQueueTest {
    private val tracks = (1..4).map {
        Track(
            identity = TrackIdentity("p", "$it"),
            title = "T$it",
            artist = "A",
            album = "B",
            durationMs = 1000L * it,
        )
    }

    @Test
    fun nextWrapsInAllMode() {
        val queue = PlayQueue().withTracks(tracks).copy(repeat = RepeatMode.ALL, currentIndex = 2)
        assertEquals("4", queue.next().current?.identity?.remoteTrackId)
        val fromLast = queue.copy(currentIndex = queue.order.lastIndex).next(userRequested = true)
        assertEquals(tracks.first().identity, fromLast.current?.identity)
    }

    @Test
    fun oneModeIgnoresAutoAdvance() {
        val queue = PlayQueue().withTracks(tracks).copy(repeat = RepeatMode.ONE)
        assertEquals(queue.current?.identity, queue.next(userRequested = false).current?.identity)
        assertNotEquals(queue.current?.identity, queue.next(userRequested = true).current?.identity)
    }

    @Test
    fun shuffleKeepsCurrentFirst() {
        val queue = PlayQueue().withTracks(tracks).copy(currentIndex = 2).toggleShuffle()
        assertTrue(queue.shuffled)
        assertEquals(tracks[2].identity, queue.current?.identity)
        assertEquals(tracks.size, queue.order.distinct().size)
    }

    @Test
    fun offModeStopsAtEnd() {
        val queue = PlayQueue().withTracks(tracks).copy(repeat = RepeatMode.OFF, currentIndex = tracks.lastIndex)
        assertEquals(queue.current?.identity, queue.next().current?.identity)
    }
}
