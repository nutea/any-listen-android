package io.github.nutea.anylisten.core.playback

import androidx.media3.common.Player

/** Edits the actual Media3 timeline, keeping unrelated playback uninterrupted. */
fun Player.removeQueuedTrack(mediaId: String): Boolean {
    val index = (0 until mediaItemCount).firstOrNull { getMediaItemAt(it).mediaId == mediaId } ?: return false
    if (mediaItemCount == 1) {
        stop()
        clearMediaItems()
    } else {
        removeMediaItem(index)
    }
    return true
}

/** Read the engine's shuffle traversal, starting with the current track and wrapping once. */
fun Player.playbackOrderKeys(): List<String> {
    if (!shuffleModeEnabled) return (0 until mediaItemCount).map { getMediaItemAt(it).mediaId }
    val timeline = currentTimeline
    if (timeline.isEmpty) return emptyList()
    val indices = linkedSetOf<Int>()
    var index = currentMediaItemIndex.takeIf { it in 0 until timeline.windowCount }
        ?: timeline.getFirstWindowIndex(true)
    while (index != -1 && indices.add(index)) {
        index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_ALL, true)
    }
    return indices.map { getMediaItemAt(it).mediaId }
}
