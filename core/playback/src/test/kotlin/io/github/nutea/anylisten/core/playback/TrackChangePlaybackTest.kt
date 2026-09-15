package io.github.nutea.anylisten.core.playback

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackChangePlaybackTest {
    @Test
    fun pausedSeekStartsPlayback() {
        assertTrue(
            TrackChangePlayback.shouldPlayAfterTransition(
                Player.MEDIA_ITEM_TRANSITION_REASON_SEEK,
                playWhenReady = false,
            ),
        )
    }

    @Test
    fun playingSeekKeepsPlaying() {
        assertFalse(
            TrackChangePlayback.shouldPlayAfterTransition(
                Player.MEDIA_ITEM_TRANSITION_REASON_SEEK,
                playWhenReady = true,
            ),
        )
    }

    @Test
    fun autoAdvancePlaylistChangeAndRepeatDoNotUnpause() {
        assertFalse(
            TrackChangePlayback.shouldPlayAfterTransition(
                Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
                playWhenReady = false,
            ),
        )
        assertFalse(
            TrackChangePlayback.shouldPlayAfterTransition(
                Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED,
                playWhenReady = false,
            ),
        )
        assertFalse(
            TrackChangePlayback.shouldPlayAfterTransition(
                Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT,
                playWhenReady = false,
            ),
        )
    }
}
