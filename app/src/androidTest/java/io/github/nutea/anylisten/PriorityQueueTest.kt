package io.github.nutea.anylisten

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nutea.anylisten.core.playback.playbackOrderKeys
import androidx.media3.exoplayer.source.ShuffleOrder
import io.github.nutea.anylisten.core.playback.PriorityQueue
import io.github.nutea.anylisten.core.playback.playbackSnapshot
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PriorityQueueTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun displayedShuffleOrderMatchesEngineAfterEveryQueueEdit() {
        instrumentation.runOnMainSync {
            val player = ExoPlayer.Builder(instrumentation.targetContext).build()
            try {
                val items = listOf("a", "b", "c", "d", "e").map {
                    MediaItem.Builder().setMediaId(it).setUri("https://example.invalid/$it").build()
                }
                player.setMediaItems(items, 0, 0)
                player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(intArrayOf(2, 4, 0, 3, 1), 0L))
                player.shuffleModeEnabled = true
                player.repeatMode = Player.REPEAT_MODE_ALL
                assertEquals(listOf("a", "d", "b", "c", "e"), player.playbackOrderKeys())
                fun verifyNext() {
                    val shown = player.playbackOrderKeys()
                    assertEquals(player.mediaItemCount, shown.distinct().size)
                    assertEquals(player.currentMediaItem!!.mediaId, shown.first())
                    assertEquals(player.getMediaItemAt(player.nextMediaItemIndex).mediaId, shown[1])
                }
                repeat(6) { verifyNext(); player.seekToNextMediaItem() }
                val priority = PriorityQueue(player)
                priority.restore(listOf("c", "e"))
                verifyNext()
                player.seekToNextMediaItem()
                verifyNext()
                val removeIndex = (0 until player.mediaItemCount).first { player.getMediaItemAt(it).mediaId != player.currentMediaItem!!.mediaId }
                player.removeMediaItem(removeIndex)
                verifyNext()
                player.seekTo(0, 0)
                verifyNext()
                player.shuffleModeEnabled = false
                assertEquals((0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }, player.playbackOrderKeys())
            } finally { player.release() }
        }
    }

    @Test fun priorityEditsPreservePositionAndShuffleNextAcrossRestore() {
        instrumentation.runOnMainSync {
            val player = ExoPlayer.Builder(instrumentation.targetContext).build()
            try {
                val items = listOf("a", "b", "c", "d").map {
                    MediaItem.Builder().setMediaId(it).setUri("https://example.invalid/$it").build()
                }
                player.setMediaItems(items, 1, 25000)
                player.shuffleModeEnabled = true
                val priority = PriorityQueue(player)
                priority.setRepeatMode(Player.REPEAT_MODE_ONE)
                var transitions = 0
                player.addListener(object : Player.Listener {
                    override fun onMediaItemTransition(item: MediaItem?, reason: Int) { transitions++ }
                })
                priority.apply(listOf(items[0], items[1], items[3], items[2]), listOf("d", "c"))
                assertEquals("b", player.currentMediaItem?.mediaId)
                assertEquals(25000, player.currentPosition)
                assertEquals(0, transitions)
                assertEquals("d", player.getMediaItemAt(player.nextMediaItemIndex).mediaId)
                val saved = player.playbackSnapshot(priority.laterKeys.toList())!!
                val restored = ExoPlayer.Builder(instrumentation.targetContext).build()
                try {
                    val restoredItems = saved.cacheKeys.map { key -> items.first { it.mediaId == key } }
                    restored.setMediaItems(restoredItems, saved.cacheKeys.indexOf(saved.currentKey), saved.positionMs)
                    restored.shuffleModeEnabled = true
                    val restoredPriority = PriorityQueue(restored)
                    restoredPriority.setRepeatMode(priority.repeatMode)
                    restoredPriority.restore(saved.laterKeys)
                    assertEquals(listOf("d", "c"), restoredPriority.laterKeys.toList())
                    assertEquals(25000, restored.currentPosition)
                    restored.seekToNextMediaItem()
                    assertEquals("d", restored.currentMediaItem?.mediaId)
                    restored.seekToNextMediaItem()
                    assertEquals("c", restored.currentMediaItem?.mediaId)
                    assertTrue(restoredPriority.laterKeys.isEmpty())
                    assertEquals(Player.REPEAT_MODE_ONE, restored.repeatMode)
                } finally { restored.release() }
            } finally { player.release() }
        }
    }

    @Test fun naturalCompletionHonorsPriorityInEveryPlaybackMode() {
        val file = File.createTempFile("priority-test-", ".wav", instrumentation.targetContext.cacheDir)
        // 250 ms of local silence: no server, no audio focus and no audible output.
        val size = 4000
        val wave = ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN)
        wave.put("RIFF".toByteArray()).putInt(36 + size).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(size).put(ByteArray(size))
        file.writeBytes(wave.array())
        try {
            for ((shuffle, repeat) in listOf(false to Player.REPEAT_MODE_OFF, false to Player.REPEAT_MODE_ALL,
                true to Player.REPEAT_MODE_ALL, false to Player.REPEAT_MODE_ONE)) {
                val done = CountDownLatch(1)
                val visited = mutableListOf<String>()
                var player: ExoPlayer? = null
                instrumentation.runOnMainSync {
                    val exo = ExoPlayer.Builder(instrumentation.targetContext).build()
                    player = exo
                    val items = listOf("current", "later1", "later2", "ordinary").map {
                        MediaItem.Builder().setMediaId(it).setUri(android.net.Uri.fromFile(file)).build()
                    }
                    exo.volume = 0f
                    exo.setMediaItems(items)
                    exo.shuffleModeEnabled = shuffle
                    val priority = PriorityQueue(exo)
                    priority.setRepeatMode(repeat)
                    priority.restore(listOf("later1", "later2"))
                    exo.addListener(object : Player.Listener {
                        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                                visited.add(item?.mediaId.orEmpty())
                                if (visited.size >= 2) { exo.pause(); done.countDown() }
                            }
                        }
                    })
                    exo.prepare()
                    exo.play()
                }
                try {
                    assertTrue("Automatic transitions timed out: shuffle=$shuffle repeat=$repeat", done.await(15, TimeUnit.SECONDS))
                    instrumentation.runOnMainSync { assertEquals(listOf("later1", "later2"), visited) }
                } finally { instrumentation.runOnMainSync { player?.release() } }
            }
        } finally { file.delete() }
    }
}
