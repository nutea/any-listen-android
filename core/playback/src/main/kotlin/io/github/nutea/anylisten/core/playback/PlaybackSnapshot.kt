package io.github.nutea.anylisten.core.playback

import androidx.media3.common.Player
import io.github.nutea.anylisten.core.data.session.PersistedPlayback
import io.github.nutea.anylisten.core.model.RepeatMode

/** An empty, newly connected player must never overwrite the saved resume point. */
fun Player.playbackSnapshot(laterKeys: List<String> = emptyList()): PersistedPlayback? {
    if (mediaItemCount == 0) return null
    return PersistedPlayback(
        cacheKeys = (0 until mediaItemCount).map { getMediaItemAt(it).mediaId },
        currentKey = currentMediaItem?.mediaId,
        positionMs = currentPosition.coerceAtLeast(0L),
        shuffled = shuffleModeEnabled,
        repeat = when (repeatMode) {
            Player.REPEAT_MODE_ONE -> RepeatMode.ONE
            Player.REPEAT_MODE_OFF -> RepeatMode.OFF
            else -> RepeatMode.ALL
        }.name,
        laterKeys = laterKeys,
    )
}
