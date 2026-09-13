package io.github.nutea.anylisten.core.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder

/** Keeps priority tracks in the engine's actual traversal order, including automatic transitions. */
class PriorityQueue(private val player: ExoPlayer, val laterKeys: MutableSet<String> = linkedSetOf()) {
    var repeatMode: Int = player.repeatMode
        private set
    private var updating = false

    init {
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                if (updating) return
                item?.mediaId?.let(laterKeys::remove)
                refresh()
            }
            override fun onShuffleModeEnabledChanged(enabled: Boolean) { refresh() }
            override fun onRepeatModeChanged(mode: Int) {
                if (!updating) { repeatMode = mode; refresh() }
            }
        })
    }

    fun setRepeatMode(mode: Int) { repeatMode = mode; refresh() }

    fun restore(keys: List<String>) {
        laterKeys.clear()
        laterKeys.addAll(keys)
        refresh()
    }

    fun refresh() {
        if (updating) return
        updating = true
        try {
            val ids = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
            laterKeys.retainAll(ids.toSet())
            laterKeys.remove(player.currentMediaItem?.mediaId)
            // A pending priority track takes precedence even over single-track repeat.
            player.repeatMode = if (laterKeys.isNotEmpty() && repeatMode == Player.REPEAT_MODE_ONE)
                Player.REPEAT_MODE_ALL else repeatMode
            if (player.shuffleModeEnabled && laterKeys.isNotEmpty()) {
                val timeline = player.currentTimeline
                val order = mutableListOf<Int>()
                var index = timeline.getFirstWindowIndex(true)
                while (index != -1 && order.size < ids.size) {
                    order.add(index)
                    index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
                }
                val current = player.currentMediaItemIndex
                val priority = laterKeys.mapNotNull { key -> ids.indexOf(key).takeIf { it >= 0 } }
                val rest = order.filter { it != current && it !in priority }
                val traversal = listOf(current) + priority + rest
                if (traversal.size == ids.size && traversal.distinct().size == ids.size)
                    player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(traversal.toIntArray(), 0L))
            }
        } finally { updating = false }
    }

    /** Edit the existing timeline without replacing/re-preparing the current source. */
    fun apply(items: List<MediaItem>, keys: List<String>) {
        updating = true
        try {
            items.forEachIndexed { target, item ->
                val existing = (target until player.mediaItemCount).firstOrNull {
                    player.getMediaItemAt(it).mediaId == item.mediaId
                }
                if (existing == null) player.addMediaItem(target, item)
                else if (existing != target) player.moveMediaItem(existing, target)
            }
            if (player.mediaItemCount > items.size) player.removeMediaItems(items.size, player.mediaItemCount)
            laterKeys.clear()
            laterKeys.addAll(keys)
        } finally { updating = false }
        refresh()
    }
}
