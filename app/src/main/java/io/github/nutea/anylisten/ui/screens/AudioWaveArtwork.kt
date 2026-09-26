package io.github.nutea.anylisten.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import coil.compose.AsyncImage
import io.github.nutea.anylisten.R
import io.github.nutea.anylisten.core.model.PlayerStyle
import io.github.nutea.anylisten.core.playback.AudioWaveSignal
import kotlinx.coroutines.isActive
import kotlin.math.*

internal fun playerStyleName(style: PlayerStyle): Int = when (style) {
    PlayerStyle.CLASSIC -> R.string.player_style_classic
    PlayerStyle.IMMERSIVE -> R.string.player_style_immersive
    PlayerStyle.COSMIC_DUST -> R.string.player_style_dust
    PlayerStyle.PULSE_PARTICLES -> R.string.player_style_particles
    PlayerStyle.CRYSTAL_WAVE -> R.string.player_style_crystal
}

internal fun playerStyleDetail(style: PlayerStyle): Int = when (style) {
    PlayerStyle.CLASSIC -> R.string.player_style_classic_detail
    PlayerStyle.IMMERSIVE -> R.string.player_style_immersive_detail
    PlayerStyle.COSMIC_DUST -> R.string.player_style_dust_detail
    PlayerStyle.PULSE_PARTICLES -> R.string.player_style_particles_detail
    PlayerStyle.CRYSTAL_WAVE -> R.string.player_style_crystal_detail
}

private data class WaveFrame(val seconds: Float = 0f, val level: Float = 0f, val peak: Float = 0f)

@Composable
private fun rememberWaveFrame(active: Boolean, signal: () -> AudioWaveSignal.Sample): State<WaveFrame> {
    val frame = remember { mutableStateOf(WaveFrame()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestSignal by rememberUpdatedState(signal)
    LaunchedEffect(active, lifecycle) {
        if (!active) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            AudioWaveSignal.acquire()
            try {
                var previous = 0L
                while (isActive) withFrameNanos { now ->
                    if (previous == 0L) previous = now
                    if (now - previous >= 32_000_000L) {
                        val delta = ((now - previous) / 1_000_000_000f).coerceAtMost(.1f)
                        previous = now
                        val sample = latestSignal()
                        val before = frame.value
                        val smoothing = 1f - exp(-delta * if (sample.level > before.level) 16f else 5f)
                        frame.value = WaveFrame((before.seconds + delta) % 3600f,
                            before.level + (sample.level - before.level) * smoothing,
                            before.peak + (sample.peak - before.peak) * smoothing)
                    }
                }
            } finally { AudioWaveSignal.release() }
        }
    }
    return frame
}

/** All frame reads are in draw/layer blocks, so animation does not recompose the player. */
@Composable
internal fun AudioWaveArtwork(image: Any?, style: PlayerStyle, palette: PlayerPalette, active: Boolean,
    modifier: Modifier = Modifier, signal: () -> AudioWaveSignal.Sample = { AudioWaveSignal.current() }) {
    val frame = rememberWaveFrame(active, signal)
    val dust = remember {
        val random = kotlin.random.Random(8471)
        List(1800) { floatArrayOf(random.nextFloat() * (2f * PI.toFloat()), random.nextFloat(), random.nextFloat()) }
    }
    val glow = lerp(palette.bottom, Color.White, .7f)
    Box(modifier.testTag("audio_wave_${style.name}"), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val f = frame.value
            val unit = size.minDimension
            val radius = unit * .35f
            fun point(angle: Float, distance: Float) = center + Offset(cos(angle) * distance, sin(angle) * distance)
            fun wave(angle: Float) = (sin(angle * 5f + f.seconds * 1.7f) * .5f +
                sin(angle * 9f - f.seconds * 2.3f) * .3f + sin(angle * 3f + f.seconds) * .2f).absoluteValue
            drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = .13f + f.level * .12f), Color.Transparent),
                center, unit * .49f), unit * .49f)
            drawCircle(glow.copy(alpha = .18f), radius + unit * .012f, style = Stroke(unit * .003f))
            when (style) {
                PlayerStyle.COSMIC_DUST -> dust.forEach { seed ->
                    val angle = seed[0] + f.seconds * (.035f + seed[2] * .025f)
                    val spread = seed[1].pow(2) * (.055f + f.level * .07f)
                    val distance = radius + unit * (.016f + spread + wave(angle) * f.level * .015f)
                    val twinkle = .55f + .45f * sin(f.seconds * 1.4f + seed[0] * 7f + seed[2] * 30f).absoluteValue
                    drawCircle(glow.copy(alpha = ((1f - seed[1]) * twinkle * .75f).coerceIn(0f, 1f)),
                        unit * (.0008f + seed[2] * .0017f), point(angle, distance))
                }
                PlayerStyle.PULSE_PARTICLES -> {
                    for (i in 0 until 144) {
                        val angle = i * (2f * PI.toFloat() / 144f)
                        val distance = radius + unit * (.025f + wave(angle) * f.level * .07f)
                        drawCircle(glow.copy(alpha = .65f + f.level * .3f), unit * (.0025f + f.level * .0015f), point(angle, distance))
                    }
                    dust.take(90).forEach { seed ->
                        val travel = (f.seconds * .5f + seed[1]) % 1f
                        val angle = seed[0]
                        drawCircle(glow.copy(alpha = (1f - travel) * f.peak * .55f),
                            unit * .002f, point(angle, radius + unit * (.03f + travel * .11f)))
                    }
                }
                PlayerStyle.CRYSTAL_WAVE -> {
                    repeat(2) { layer ->
                        val path = Path()
                        for (i in 0..128) {
                            val angle = i % 128 * (2f * PI.toFloat() / 128f)
                            val teeth = if (i % 2 == 0) 1f else .16f
                            val distance = radius + unit * (.025f + wave(angle + layer) * teeth * (.012f + f.level * .08f))
                            val p = point(angle + layer * .026f, distance)
                            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                        }
                        path.close()
                        drawPath(path, glow.copy(alpha = if (layer == 0) .7f else .3f), style = Stroke(unit * .002f))
                    }
                    dust.take(22).forEach { seed ->
                        val angle = seed[0] + f.seconds * .045f
                        val distance = radius + unit * (.065f + seed[1] * .065f + f.level * .025f)
                        val p = point(angle, distance)
                        val edge = unit * (.014f + seed[2] * .018f)
                        val path = Path()
                        repeat(3) { j ->
                            val a = angle + j * (2f * PI.toFloat() / 3f) + f.seconds * .14f
                            val vertex = p + Offset(cos(a), sin(a)) * edge
                            if (j == 0) path.moveTo(vertex.x, vertex.y) else path.lineTo(vertex.x, vertex.y)
                        }
                        path.close()
                        drawPath(path, glow.copy(alpha = .07f + f.level * .19f), style = Stroke(unit * .0015f))
                    }
                }
                else -> Unit
            }
        }
        Box(Modifier.fillMaxSize(.68f).graphicsLayer { rotationZ = frame.value.seconds * 7f }
            .clip(CircleShape).background(palette.top)) {
            Image(painterResource(R.drawable.cover_default_track), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (image != null) AsyncImage(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}
