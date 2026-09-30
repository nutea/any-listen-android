package io.github.nutea.anylisten.core.playback

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.github.nutea.anylisten.core.model.SleepTimerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Owned by the playback service, so leaving the Activity never cancels a timer. */
class SleepTimerController(private val player: ExoPlayer, private val scope: CoroutineScope) {
    private val mutableState = MutableStateFlow(SleepTimerState())
    val state = mutableState.asStateFlow()
    private var timerJob: Job? = null
    private var endTrack: String? = null
    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (endTrack != null && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) cancel()
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Explicitly selecting another song cancels the bound end-of-song timer.
            if (endTrack != null && mediaItem?.mediaId != endTrack) cancel()
        }
    }
    init { player.addListener(listener) }

    fun start(durationMs: Long) {
        if (durationMs !in 1..86_400_000 || player.mediaItemCount == 0) return
        cancel()
        val deadline = SystemClock.elapsedRealtime() + durationMs
        mutableState.value = SleepTimerState(remainingMs = durationMs)
        timerJob = scope.launch {
            while (true) {
                val remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)
                if (remaining == 0L) { player.pause(); cancel(); break }
                mutableState.value = SleepTimerState(remainingMs = remaining)
                delay(minOf(remaining, 1_000))
            }
        }
    }

    fun stopAfterTrack() {
        val key = player.currentMediaItem?.mediaId ?: return
        cancel()
        endTrack = key
        player.pauseAtEndOfMediaItems = true
        mutableState.value = SleepTimerState(endOfTrack = true)
    }

    fun cancel() {
        timerJob?.cancel(); timerJob = null
        endTrack = null
        player.pauseAtEndOfMediaItems = false
        mutableState.value = SleepTimerState()
    }

    fun release() { cancel(); player.removeListener(listener) }
}
