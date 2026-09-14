package io.github.nutea.anylisten.core.playback

import androidx.media3.common.Player

/** Policy for retrying the timeline after a network change or stalled buffer. */
object PlaybackReconnect {
    const val BUFFERING_STALL_MS = 8_000L
    const val NETWORK_CHANGE_WINDOW_MS = 60_000L

    fun shouldReplayTimeline(
        hasError: Boolean,
        playbackState: Int,
        playWhenReady: Boolean,
    ): Boolean {
        if (hasError || playbackState == Player.STATE_IDLE) return true
        return playWhenReady && playbackState == Player.STATE_BUFFERING
    }

    fun shouldRecoverStalledBuffer(
        stillBuffering: Boolean,
        playWhenReady: Boolean,
        gatewayOnline: Boolean,
        msSinceNetworkChange: Long,
    ): Boolean {
        if (!stillBuffering || !playWhenReady) return false
        return !gatewayOnline || msSinceNetworkChange in 0 until NETWORK_CHANGE_WINDOW_MS
    }

    /**
     * Tear down and rebuild the IPC session only when the socket is already dead,
     * or after a real default-network change (half-open link). The first
     * ConnectivityManager callback after login/restore must not reconnect —
     * that races Message2Call destroy/onMessage on a healthy socket and can
     * kill the process right after a successful connect.
     */
    fun shouldRebuildSession(gatewayOnline: Boolean, networkChanged: Boolean): Boolean {
        if (!gatewayOnline) return true
        return networkChanged
    }
}
