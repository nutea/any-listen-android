package io.github.nutea.anylisten.core.playback

import androidx.media3.common.Format
import io.github.nutea.anylisten.core.model.AudioInfo
import io.github.nutea.anylisten.core.model.Track

data class PlaybackSurfaceState(
    val track: Track?, val isPlaying: Boolean, val playWhenReady: Boolean,
    val favorite: Boolean, val favoriteAvailable: Boolean, val artworkPath: String?,
)

fun Format.audioInfo(trackKey: String): AudioInfo = AudioInfo(
    trackKey = trackKey, mimeType = sampleMimeType,
    bitrate = averageBitrate.takeIf { it > 0 } ?: peakBitrate.takeIf { it > 0 },
    sampleRate = sampleRate.takeIf { it > 0 }, channels = channelCount.takeIf { it > 0 },
)
