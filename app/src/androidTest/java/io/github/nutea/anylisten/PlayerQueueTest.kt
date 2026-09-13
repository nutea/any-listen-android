package io.github.nutea.anylisten

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nutea.anylisten.core.playback.removeQueuedTrack
import io.github.nutea.anylisten.core.playback.playbackSnapshot
import org.junit.Assert.*
import org.junit.Test

class PlayerQueueTest {
    @Test fun removingOtherTracksPreservesCurrentPositionAndFinalRemovalClearsTimeline() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val player = ExoPlayer.Builder(instrumentation.targetContext).build()
            try {
                player.setMediaItems(listOf("one", "two", "three").map {
                    MediaItem.Builder().setMediaId(it).setUri("https://example.invalid/$it").build()
                }, 1, 25_000)
                val saved = player.playbackSnapshot()!!
                assertEquals(25_000L, saved.positionMs)
                assertEquals("two", saved.currentKey)
                assertEquals(listOf("one", "two", "three"), saved.cacheKeys)
                // No prepare/play: this test uses a real timeline without network or audio.
                assertTrue(player.removeQueuedTrack("one"))
                assertEquals("two", player.currentMediaItem?.mediaId)
                assertEquals(25_000L, player.currentPosition)
                assertEquals(2, player.mediaItemCount)
                assertFalse(player.removeQueuedTrack("missing"))
                assertTrue(player.removeQueuedTrack("two"))
                assertEquals("three", player.currentMediaItem?.mediaId)
                assertEquals(0L, player.currentPosition)
                assertTrue(player.removeQueuedTrack("three"))
                assertEquals(0, player.mediaItemCount)
                assertNull(player.currentMediaItem)
                assertNull(player.playbackSnapshot())
                player.setMediaItems(saved.cacheKeys.map {
                    MediaItem.Builder().setMediaId(it).setUri("https://example.invalid/$it").build()
                }, saved.cacheKeys.indexOf(saved.currentKey), saved.positionMs)
                assertEquals(saved, player.playbackSnapshot())
            } finally { player.release() }
        }
    }
}
