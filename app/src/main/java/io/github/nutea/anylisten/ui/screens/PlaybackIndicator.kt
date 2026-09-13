package io.github.nutea.anylisten.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.nutea.anylisten.R
import io.github.nutea.anylisten.ui.rememberMotionEnabled
import kotlin.math.sin

@Composable
internal fun PlaybackIndicator(isPlaying: Boolean) {
    val animate = isPlaying && rememberMotionEnabled()
    val phase = if (animate) {
        val transition = rememberInfiniteTransition(label = "playing bars")
        val value by transition.animateFloat(0f, (Math.PI * 2).toFloat(),
            infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "bar phase")
        value
    } else 0f
    val color = MaterialTheme.colorScheme.primary
    val label = stringResource(R.string.queue_now_playing)
    val status = stringResource(if (isPlaying) R.string.indicator_playing else R.string.indicator_paused)
    Canvas(Modifier.size(18.dp).testTag("playback_indicator").semantics {
        contentDescription = label; stateDescription = status
    }) {
        val width = size.width / 7f
        repeat(4) { index ->
            val ratio = if (animate) .25f + .75f * ((sin(phase + index * 1.7f) + 1f) / 2f)
                else listOf(.35f, .7f, .5f, .3f)[index]
            val height = size.height * ratio
            drawRoundRect(color, Offset(index * width * 2, size.height - height), Size(width, height), CornerRadius(width / 2))
        }
    }
}
