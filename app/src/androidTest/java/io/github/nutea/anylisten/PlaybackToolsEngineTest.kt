package io.github.nutea.anylisten

import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nutea.anylisten.core.model.*
import io.github.nutea.anylisten.core.playback.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PlaybackToolsEngineTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun countdownPausesAndCancellationKeepsPlaybackRunning() {
        val file = waveAudioFixture(instrumentation.targetContext, 4)
        var player: ExoPlayer? = null
        var timer: SleepTimerController? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(instrumentation.targetContext).build().apply {
                    volume = 0f
                    setMediaItem(MediaItem.Builder().setMediaId("current").setUri(file.toURI().toString()).build())
                    prepare(); play()
                }
                timer = SleepTimerController(player!!, scope).apply { start(100); cancel() }
            }
            Thread.sleep(200)
            instrumentation.runOnMainSync {
                assertTrue(player!!.playWhenReady)
                assertFalse(timer!!.state.value.active)
                timer!!.start(250)
                assertTrue(timer!!.state.value.active)
            }
            Thread.sleep(500)
            instrumentation.runOnMainSync {
                assertFalse(player!!.playWhenReady)
                assertFalse(timer!!.state.value.active)
            }
        } finally {
            instrumentation.runOnMainSync { timer?.release(); player?.release() }
            scope.cancel(); file.delete()
        }
    }

    @Test fun endOfTrackStopsBeforeAnotherSongOrRepeatInEveryMode() {
        val file = waveAudioFixture(instrumentation.targetContext, 1)
        try {
            for ((shuffle, repeat, withPriority) in listOf(Triple(false, Player.REPEAT_MODE_OFF, true),
                Triple(false, Player.REPEAT_MODE_ALL, true), Triple(true, Player.REPEAT_MODE_ALL, true),
                Triple(false, Player.REPEAT_MODE_ONE, true), Triple(false, Player.REPEAT_MODE_ONE, false))) {
                val stopped = CountDownLatch(1)
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                var player: ExoPlayer? = null
                var timer: SleepTimerController? = null
                instrumentation.runOnMainSync {
                    player = ExoPlayer.Builder(instrumentation.targetContext).build().apply {
                        volume = 0f
                        setMediaItems(listOf("current", "later", "rest").map {
                            MediaItem.Builder().setMediaId(it).setUri(file.toURI().toString()).build()
                        })
                        shuffleModeEnabled = shuffle
                        val priority = PriorityQueue(this)
                        priority.setRepeatMode(repeat)
                        if (withPriority) priority.restore(listOf("later"))
                        addListener(object : Player.Listener {
                            override fun onPlayWhenReadyChanged(ready: Boolean, reason: Int) {
                                if (!ready && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) stopped.countDown()
                            }
                        })
                        timer = SleepTimerController(this, scope).apply { stopAfterTrack() }
                        prepare(); play()
                    }
                }
                try {
                    assertTrue("Timer failed: shuffle=$shuffle repeat=$repeat", stopped.await(8, TimeUnit.SECONDS))
                    Thread.sleep(200)
                    instrumentation.runOnMainSync {
                        assertFalse(player!!.playWhenReady)
                        assertEquals("current", player!!.currentMediaItem!!.mediaId)
                        assertFalse(timer!!.state.value.active)
                        assertFalse(player!!.pauseAtEndOfMediaItems)
                    }
                } finally {
                    instrumentation.runOnMainSync { timer?.release(); player?.release() }; scope.cancel()
                }
            }
        } finally { file.delete() }
    }

    @Test fun reorderedQueuePreservesProgressAndRestoresRandomTraversal() {
        instrumentation.runOnMainSync {
            for (shuffle in listOf(false, true)) {
                val tracks = (0..4).map { Track(TrackIdentity("fixture", "$it"), "$it", "", "", 60000) }
                fun Track.item() = MediaItem.Builder().setMediaId(cacheKey).setUri("https://example.invalid/$cacheKey").build()
                val player = ExoPlayer.Builder(instrumentation.targetContext).build()
                val restored = ExoPlayer.Builder(instrumentation.targetContext).build()
                try {
                    player.setMediaItems(tracks.map { it.item() }, 0, 25000)
                    player.playWhenReady = true
                    player.shuffleModeEnabled = shuffle
                    val priority = PriorityQueue(player)
                    priority.setRepeatMode(Player.REPEAT_MODE_ALL)
                    priority.restore(listOf(tracks[1].cacheKey, tracks[2].cacheKey))
                    if (shuffle) { player.restorePlaybackOrder(tracks.map { it.cacheKey }); priority.refresh() }
                    val moved = QueueReorder.move(tracks, tracks[0].cacheKey, priority.laterKeys.toList(),
                        tracks[2].cacheKey, tracks[1].cacheKey)!!
                    priority.apply(moved.queue.map { it.item() }, moved.laterKeys)
                    assertEquals(tracks[0].cacheKey, player.currentMediaItem!!.mediaId)
                    assertEquals(25000L, player.currentPosition)
                    assertTrue(player.playWhenReady)
                    assertEquals(tracks[2].cacheKey, player.getMediaItemAt(player.nextMediaItemIndex).mediaId)
                    val ordinary = QueueReorder.move(moved.queue, tracks[0].cacheKey, moved.laterKeys,
                        tracks[4].cacheKey, tracks[3].cacheKey)!!
                    priority.apply(ordinary.queue.map { it.item() }, ordinary.laterKeys)
                    if (shuffle) {
                        player.restorePlaybackOrder(ordinary.queue.map { it.cacheKey }); priority.refresh()
                    }
                    assertEquals(25000L, player.currentPosition)
                    assertEquals(listOf(0, 2, 1, 4, 3).map { tracks[it].cacheKey }, player.playbackOrderKeys())
                    val order = player.playbackOrderKeys()
                    val saved = player.playbackSnapshot(priority.laterKeys.toList())!!
                    restored.setMediaItems(saved.cacheKeys.map { key -> tracks.first { it.cacheKey == key }.item() }, 0, saved.positionMs)
                    restored.shuffleModeEnabled = shuffle
                    val otherPriority = PriorityQueue(restored)
                    otherPriority.setRepeatMode(Player.REPEAT_MODE_ALL)
                    otherPriority.restore(saved.laterKeys)
                    if (shuffle) { restored.restorePlaybackOrder(order); otherPriority.refresh() }
                    assertEquals(order, restored.playbackOrderKeys())
                    assertEquals(25000L, restored.currentPosition)
                } finally { player.release(); restored.release() }
            }
        }
    }

    @Test fun missingDecoderValuesRemainUnknown() {
        val unknown = Format.Builder().build().audioInfo("key")
        assertNull(unknown.bitrate); assertNull(unknown.sampleRate); assertNull(unknown.channels)
        val known = Format.Builder().setSampleMimeType("audio/flac").setAverageBitrate(900000)
            .setSampleRate(96000).setChannelCount(2).build().audioInfo("key")
        assertEquals(900000, known.bitrate); assertEquals(96000, known.sampleRate); assertEquals(2, known.channels)
    }
}
