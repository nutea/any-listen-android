package io.github.nutea.anylisten.core.playback

import androidx.media3.common.Player

/**
 * User-initiated track changes should start playback even if the previous track was paused.
 *
 * Media3 keeps `playWhenReady` across next/prev and queue seeks, which leaves the new song
 * paused. Automatic advances, playlist edits, and restores are not user skips and must keep
 * the current pause/play intent.
 */
object TrackChangePlayback {
    fun shouldPlayAfterTransition(reason: Int, playWhenReady: Boolean): Boolean {
        return !playWhenReady && reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
    }
}
