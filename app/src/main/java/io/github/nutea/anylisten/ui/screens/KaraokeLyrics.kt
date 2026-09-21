package io.github.nutea.anylisten.ui.screens

import android.os.SystemClock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.nutea.anylisten.core.model.LyricTiming
import io.github.nutea.anylisten.core.model.LyricLine
import io.github.nutea.anylisten.ui.PlayerUiState
import kotlinx.coroutines.isActive

/** Interpolate only the visible lyric view, anchored to Media3 samples; never advance while paused. */
@Composable
internal fun rememberLyricPlaybackPosition(state: PlayerUiState, animate: Boolean): State<Long> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latest by rememberUpdatedState(state)
    val position = remember(state.track?.cacheKey) { mutableLongStateOf(state.positionMs) }
    LaunchedEffect(state.positionMs, state.positionSampleTimeMs, state.isPlaying, animate) { position.longValue = state.positionMs }
    LaunchedEffect(lifecycle, animate, state.isPlaying, state.isBuffering, state.track?.cacheKey) {
        if (animate && state.isPlaying && !state.isBuffering) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) withFrameNanos {
                    val sample = latest
                    // Stop extrapolating if the next 400 ms sample is late, rather than drifting indefinitely.
                    position.longValue = LyricTiming.interpolate(sample.positionMs, sample.positionSampleTimeMs,
                        SystemClock.elapsedRealtime(), sample.playbackSpeed, sample.durationMs)
                }
            }
        }
    }
    return position
}

/** Clip a second text draw to the played fraction of each timed unit, preserving shaping and wrapping. */
@Composable
internal fun KaraokeLyricText(line: LyricLine, positionMs: Long, modifier: Modifier = Modifier) {
    var layout by remember(line.text) { mutableStateOf<TextLayoutResult?>(null) }
    val highlight = MaterialTheme.colorScheme.primary
    Text(line.text, fontSize = 26.sp, lineHeight = 39.sp, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .48f),
        onTextLayout = { layout = it },
        modifier = modifier.testTag("karaoke_line").drawWithContent {
            drawContent()
            val measured = layout ?: return@drawWithContent
            val path = Path()
            var offset = 0
            for (word in line.words) {
                val end = (offset + word.text.length).coerceAtMost(line.text.length)
                val boxes = (offset until end).map { measured.getBoundingBox(it) }
                var remaining = boxes.sumOf { it.width.toDouble() }.toFloat() * word.progressAt(positionMs)
                for ((index, box) in boxes.withIndex()) {
                    val width = remaining.coerceIn(0f, box.width.coerceAtLeast(0f))
                    if (width > 0) {
                        val rtl = measured.getBidiRunDirection(offset + index) == ResolvedTextDirection.Rtl
                        path.addRect(if (rtl) Rect(box.right - width, box.top, box.right, box.bottom)
                            else Rect(box.left, box.top, box.left + width, box.bottom))
                    }
                    remaining = (remaining - box.width).coerceAtLeast(0f)
                }
                offset = end
            }
            clipPath(path) { drawText(measured, color = highlight) }
        })
}
