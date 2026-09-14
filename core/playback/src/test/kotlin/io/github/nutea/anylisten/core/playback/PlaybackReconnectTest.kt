package io.github.nutea.anylisten.core.playback

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackReconnectTest {
    @Test
    fun replaysErrorIdleAndBufferingButNotPausedReady() {
        assertTrue(PlaybackReconnect.shouldReplayTimeline(true, Player.STATE_READY, false))
        assertTrue(PlaybackReconnect.shouldReplayTimeline(false, Player.STATE_IDLE, false))
        assertTrue(PlaybackReconnect.shouldReplayTimeline(false, Player.STATE_BUFFERING, true))
        assertFalse(PlaybackReconnect.shouldReplayTimeline(false, Player.STATE_BUFFERING, false))
        assertFalse(PlaybackReconnect.shouldReplayTimeline(false, Player.STATE_READY, true))
        assertFalse(PlaybackReconnect.shouldReplayTimeline(false, Player.STATE_ENDED, true))
    }

    @Test
    fun stalledBufferRecoversOnlyAfterNetworkLossOrDeadSocket() {
        assertTrue(
            PlaybackReconnect.shouldRecoverStalledBuffer(
                stillBuffering = true,
                playWhenReady = true,
                gatewayOnline = false,
                msSinceNetworkChange = Long.MAX_VALUE,
            ),
        )
        assertTrue(
            PlaybackReconnect.shouldRecoverStalledBuffer(
                stillBuffering = true,
                playWhenReady = true,
                gatewayOnline = true,
                msSinceNetworkChange = 1_000L,
            ),
        )
        assertFalse(
            PlaybackReconnect.shouldRecoverStalledBuffer(
                stillBuffering = true,
                playWhenReady = true,
                gatewayOnline = true,
                msSinceNetworkChange = PlaybackReconnect.NETWORK_CHANGE_WINDOW_MS,
            ),
        )
        assertFalse(
            PlaybackReconnect.shouldRecoverStalledBuffer(
                stillBuffering = true,
                playWhenReady = false,
                gatewayOnline = false,
                msSinceNetworkChange = 0L,
            ),
        )
        assertFalse(
            PlaybackReconnect.shouldRecoverStalledBuffer(
                stillBuffering = false,
                playWhenReady = true,
                gatewayOnline = false,
                msSinceNetworkChange = 0L,
            ),
        )
    }
}
