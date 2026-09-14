package io.github.nutea.anylisten.core.playback

import androidx.media3.common.Player

/**
 * Playback-side recovery policy.
 *
 * Deciding *whether the session must be rebuilt* is no longer here: that belongs to
 * `ConnectionPlanner`, which owns network identity and socket generations. What remains is the
 * player's own question — given a session that just came back, does the timeline need another
 * `prepare()`, and has a buffer stalled long enough to be worth escalating?
 */
object PlaybackReconnect {
    const val BUFFERING_STALL_MS = 8_000L
    const val NETWORK_CHANGE_WINDOW_MS = 60_000L

    /**
     * `prepare()` keeps position and `playWhenReady`, so it is safe to replay a failed, idle or
     * actively-buffering timeline, and wrong to replay one the user deliberately paused or that
     * is already playing.
     */
    fun shouldReplayTimeline(
        hasError: Boolean,
        playbackState: Int,
        playWhenReady: Boolean,
    ): Boolean {
        if (hasError || playbackState == Player.STATE_IDLE) return true
        return playWhenReady && playbackState == Player.STATE_BUFFERING
    }

    /**
     * A buffer that has not moved for [BUFFERING_STALL_MS] is only a connection problem when the
     * session is down, or when a handover happened recently enough to explain it. Otherwise it is
     * an ordinary slow network and must be left alone.
     */
    fun shouldRecoverStalledBuffer(
        stillBuffering: Boolean,
        playWhenReady: Boolean,
        gatewayOnline: Boolean,
        msSinceNetworkChange: Long,
    ): Boolean {
        if (!stillBuffering || !playWhenReady) return false
        return !gatewayOnline || msSinceNetworkChange in 0 until NETWORK_CHANGE_WINDOW_MS
    }
}
