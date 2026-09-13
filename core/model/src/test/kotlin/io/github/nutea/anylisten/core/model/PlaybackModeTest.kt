package io.github.nutea.anylisten.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackModeTest {
    @Test
    fun cyclesWebOrder() {
        assertEquals(PlaybackMode.RANDOM, PlaybackMode.LIST_LOOP.next())
        assertEquals(PlaybackMode.SEQUENCE, PlaybackMode.RANDOM.next())
        assertEquals(PlaybackMode.SINGLE, PlaybackMode.SEQUENCE.next())
        assertEquals(PlaybackMode.LIST_LOOP, PlaybackMode.SINGLE.next())
    }

    @Test
    fun mapsRepeatAndShuffle() {
        assertFalse(PlaybackMode.LIST_LOOP.shuffled)
        assertEquals(RepeatMode.ALL, PlaybackMode.LIST_LOOP.repeat)
        assertTrue(PlaybackMode.RANDOM.shuffled)
        assertEquals(RepeatMode.ALL, PlaybackMode.RANDOM.repeat)
        assertFalse(PlaybackMode.SEQUENCE.shuffled)
        assertEquals(RepeatMode.OFF, PlaybackMode.SEQUENCE.repeat)
        assertFalse(PlaybackMode.SINGLE.shuffled)
        assertEquals(RepeatMode.ONE, PlaybackMode.SINGLE.repeat)
    }

    @Test
    fun restoresFromFlags() {
        assertEquals(PlaybackMode.LIST_LOOP, PlaybackMode.from(RepeatMode.ALL, false))
        assertEquals(PlaybackMode.RANDOM, PlaybackMode.from(RepeatMode.ALL, true))
        assertEquals(PlaybackMode.SEQUENCE, PlaybackMode.from(RepeatMode.OFF, false))
        assertEquals(PlaybackMode.SINGLE, PlaybackMode.from(RepeatMode.ONE, false))
        assertEquals(PlaybackMode.SINGLE, PlaybackMode.from(RepeatMode.ONE, true))
    }
}
