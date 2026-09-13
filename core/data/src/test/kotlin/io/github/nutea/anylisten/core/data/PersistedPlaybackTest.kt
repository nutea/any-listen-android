package io.github.nutea.anylisten.core.data

import io.github.nutea.anylisten.core.data.session.PersistedPlayback
import io.github.nutea.anylisten.core.model.RepeatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistedPlaybackTest {
    @Test
    fun roundTripKeepsQueueAndPosition() {
        val saved = PersistedPlayback(
            cacheKeys = listOf("a", "b"),
            currentKey = "b",
            shuffled = true,
            repeat = RepeatMode.ONE.name,
            positionMs = 12_345,
        )
        val restored = PersistedPlayback.decode(saved.encode())
        assertEquals(saved, restored)
        assertEquals(RepeatMode.ONE, restored.repeatMode())
    }

    @Test
    fun blankOrInvalidDecodesEmpty() {
        assertTrue(PersistedPlayback.decode(null).cacheKeys.isEmpty())
        assertTrue(PersistedPlayback.decode("{").cacheKeys.isEmpty())
        assertEquals(RepeatMode.ALL, PersistedPlayback.decode("""{"repeat":"NOPE"}""").repeatMode())
    }
}
