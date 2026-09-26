package io.github.nutea.anylisten.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal data class PlayerPalette(val top: Color, val bottom: Color) {
    companion object {
        val Fallback = PlayerPalette(Color(0xFF252936), Color(0xFF26374C))
    }
}

/** Sample a tiny software bitmap off the UI thread; prefer the lower cover's chromatic tones. */
internal fun samplePlayerPalette(file: File): PlayerPalette {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 64) sample *= 2
    val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: return PlayerPalette.Fallback
    try {
        val weights = FloatArray(24)
        val hues = FloatArray(24)
        val saturations = FloatArray(24)
        var red = 0L; var green = 0L; var blue = 0L; var count = 0
        val hsv = FloatArray(3)
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val pixel = bitmap.getPixel(x, y)
            if (android.graphics.Color.alpha(pixel) < 128) continue
            red += android.graphics.Color.red(pixel); green += android.graphics.Color.green(pixel)
            blue += android.graphics.Color.blue(pixel); count++
            android.graphics.Color.colorToHSV(pixel, hsv)
            if (hsv[1] < .18f || hsv[2] < .15f) continue
            val bin = (hsv[0] / 15f).toInt().coerceIn(0, 23)
            val locationWeight = when {
                y > bitmap.height * .85f -> 15f
                y > bitmap.height * .55f -> 1f
                else -> .1f
            }
            val weight = hsv[1] * hsv[1] * hsv[2] * locationWeight
            weights[bin] += weight; hues[bin] += hsv[0] * weight; saturations[bin] += hsv[1] * weight
        }
        if (count == 0) return PlayerPalette.Fallback
        val average = Color((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
        val best = weights.indices.maxByOrNull { weights[it] } ?: 0
        val bottom = if (weights[best] > .5f) Color.hsv(hues[best] / weights[best],
            (saturations[best] / weights[best]).coerceIn(.3f, .78f), .36f)
        else lerp(average, Color.Black, .65f)
        return PlayerPalette(lerp(average, Color.Black, .58f), bottom)
    } finally { bitmap.recycle() }
}

@Composable
internal fun rememberPlayerPalette(image: Any?): PlayerPalette {
    val request = image as? ImageRequest
    val palette by produceState(PlayerPalette.Fallback, request?.memoryCacheKey?.key) {
        value = withContext(Dispatchers.IO) {
            (request?.data as? File)?.let { runCatching { samplePlayerPalette(it) }.getOrNull() }
                ?: PlayerPalette.Fallback
        }
    }
    return palette
}

@Composable
internal fun PlayerBackdrop(image: Any?, palette: PlayerPalette, lyrics: Boolean, onArtworkError: (Boolean) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().testTag("immersive_background")
        .background(Brush.verticalGradient(0f to palette.bottom, .85f to palette.bottom,
            1f to lerp(palette.bottom, Color.Black, .25f)))) {
        val artHeight = minOf(maxWidth * 1.3f, maxHeight * .73f)
        Box(Modifier.fillMaxWidth().height(48.dp).background(palette.top))
        Box(Modifier.padding(top = 48.dp).fillMaxWidth().height(artHeight)) {
            if (image != null) AsyncImage(image, null, contentScale = ContentScale.Crop,
                onError = { onArtworkError(true) }, onSuccess = { onArtworkError(false) },
                modifier = Modifier.fillMaxSize().testTag("immersive_artwork"))
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .12f)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0f to palette.top, .2f to Color.Transparent, .48f to Color.Transparent, .9f to palette.bottom, 1f to palette.bottom)))
        }
        if (lyrics) Box(Modifier.fillMaxSize().background(palette.top.copy(alpha = .86f)))
    }
}
