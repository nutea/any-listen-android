package io.github.nutea.anylisten.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import io.github.nutea.anylisten.ui.AppViewModel
import io.github.nutea.anylisten.core.model.DownloadStatus
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import io.github.nutea.anylisten.core.playback.ContainerHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.github.nutea.anylisten.R
import io.github.nutea.anylisten.core.model.Playlist
import io.github.nutea.anylisten.core.model.Track
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@Composable
internal fun Artwork(url: String?, modifier: Modifier = Modifier, seed: String = "", radius: Dp = 8.dp) {
    val storedImage = rememberArtwork(url)
    val palettes = listOf(0xFF3D54C4 to 0xFF9AA8E6, 0xFF8A6150 to 0xFFE4C79A, 0xFF35664B to 0xFFA7C9B4, 0xFF5C4A78 to 0xFFC9B8D8)
    val colors = palettes[(seed.hashCode() and Int.MAX_VALUE) % palettes.size]
    val glyph = seed.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "♪"
    Box(modifier.aspectRatio(1f).clip(RoundedCornerShape(radius))
        .background(Color(colors.first)), contentAlignment = Alignment.Center) {
        Text(glyph, color = Color.White.copy(alpha = .85f), fontSize = 16.sp)
        if (storedImage != null) AsyncImage(model = storedImage, contentDescription = null,
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
internal fun rememberArtwork(url: String?, store: io.github.nutea.anylisten.core.data.ArtworkStore? = null): Any? {
    val context = LocalContext.current
    val application = context.applicationContext as ContainerHolder
    val artwork = store ?: application.container.artwork
    val revision by remember(url, artwork) { artwork.updatesFor(url) }.collectAsState(0L)
    var storedImage by remember(artwork) { mutableStateOf<Any?>(null) }
    LaunchedEffect(url, revision) {
        if (url.isNullOrBlank()) { storedImage = null; return@LaunchedEffect }
        var retryDelay = 2_000L
        while (true) {
            try {
                val (file, contentKey) = withContext(Dispatchers.IO) {
                    val file = if (url.startsWith("file:")) File(android.net.Uri.parse(url).path!!) else artwork.get(url)
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    file.inputStream().use { input ->
                        val buffer = ByteArray(16384)
                        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                    }
                    file to "artwork:${digest.digest().joinToString("") { "%02x".format(it) }}"
                }
                // A notification or a different URL is not itself a new image.
                val previous = storedImage as? coil.request.ImageRequest
                if (previous?.memoryCacheKey?.key != contentKey) {
                    storedImage = coil.request.ImageRequest.Builder(context).data(file)
                        .placeholderMemoryCacheKey(previous?.memoryCacheKey?.key)
                        .crossfade(false).memoryCacheKey(contentKey).diskCacheKey(contentKey).build()
                }
                break
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                delay(retryDelay)
                retryDelay = (retryDelay * 2).coerceAtMost(30_000L)
            }
        }
    }
    return storedImage
}

@Composable
internal fun rememberCoverAccent(url: String?, dark: Boolean): Color {
    val fallback = MaterialTheme.colorScheme.surface
    val application = LocalContext.current.applicationContext as? ContainerHolder ?: return fallback
    val revision by remember(url) { application.container.artwork.updatesFor(url) }.collectAsState(0L)
    val accent by produceState(fallback, url, dark, revision) {
        if (url.isNullOrBlank()) {
            value = fallback
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            runCatching {
                val file = if (url.startsWith("file:")) File(android.net.Uri.parse(url).path!!)
                else application.container.artwork.get(url)
                CoverAccent.wash(file, dark)
            }.getOrDefault(fallback)
        }
    }
    return accent
}

internal object CoverAccent {
    private val cache = ConcurrentHashMap<String, FloatArray>()

    fun wash(file: File, dark: Boolean): Color {
        val hsv = cache.getOrPut("${file.absolutePath}:${file.lastModified()}:${file.length()}") { sample(file) }
        return if (dark) Color.hsv(hsv[0], (hsv[1] * 0.28f).coerceAtMost(0.32f), 0.16f)
        else Color.hsv(hsv[0], (hsv[1] * 0.14f).coerceAtMost(0.18f), 0.94f)
    }

    private fun sample(file: File): FloatArray {
        val fallback = floatArrayOf(228f, 0.32f, 0.45f)
        val options = BitmapFactory.Options().apply { inSampleSize = 16 }
        val bitmap = BitmapFactory.decodeFile(file.path, options) ?: return fallback
        try {
            var red = 0L
            var green = 0L
            var blue = 0L
            var count = 0
            var y = 0
            while (y < bitmap.height) {
                var x = 0
                while (x < bitmap.width) {
                    val pixel = bitmap.getPixel(x, y)
                    if (android.graphics.Color.alpha(pixel) > 120) {
                        red += android.graphics.Color.red(pixel)
                        green += android.graphics.Color.green(pixel)
                        blue += android.graphics.Color.blue(pixel)
                        count++
                    }
                    x += 2
                }
                y += 2
            }
            if (count == 0) return fallback
            val hsv = FloatArray(3)
            android.graphics.Color.RGBToHSV((red / count).toInt(), (green / count).toInt(), (blue / count).toInt(), hsv)
            return hsv
        } finally {
            bitmap.recycle()
        }
    }
}

@Composable
internal fun PageHeading(title: String, subtitle: String = "", action: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
        action()
    }
}

@Composable
internal fun Notice(text: String, error: Boolean = false) {
    Surface(color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (error) Icons.Default.ErrorOutline else Icons.Default.Info, null, Modifier.size(18.dp))
            Text(text, style = MaterialTheme.typography.bodySmall,
                color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun EmptyContent(icon: ImageVector, title: String, detail: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
internal fun playlistName(playlist: Playlist): String = when (playlist.id) {
    "default" -> stringResource(R.string.list_default)
    "love" -> stringResource(R.string.list_love)
    "last_played" -> stringResource(R.string.list_last_played)
    else -> playlist.name
}

@Composable
internal fun trackSubtitle(track: Track): String {
    val parts = listOf(track.artist, track.album).filter { it.isNotBlank() }
    return parts.joinToString(" · ").ifBlank { stringResource(R.string.artist_unknown) }
}

@Composable
internal fun ActionLine(label: String, icon: ImageVector, onClick: () -> Unit, enabled: Boolean = true, destructive: Boolean = false) {
    val tint = if (!enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)
        else if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint)
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}

@Composable
internal fun TrackHeading(title: String, artist: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
        if (artist.isNotBlank()) Text(artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
    }
}

/** Completion alone is insufficient when the local file has been removed. */
@Composable
internal fun downloadedTrackKeys(vm: AppViewModel): Set<String> {
    val records by vm.downloads.collectAsState()
    val keys by produceState<Set<String>>(emptySet(), records) {
        value = withContext(Dispatchers.IO) {
            records.filter { it.status == DownloadStatus.COMPLETED && it.filePath?.let(::File)?.isFile == true }
                .mapTo(mutableSetOf()) { it.cacheKey }
        }
    }
    return keys
}
