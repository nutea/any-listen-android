package io.github.nutea.anylisten.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.github.nutea.anylisten.R
import io.github.nutea.anylisten.core.model.PlaybackMode
import io.github.nutea.anylisten.core.model.Track
import io.github.nutea.anylisten.ui.AppViewModel
import io.github.nutea.anylisten.ui.PlayerUiState

@Composable
fun PlayerScreen(vm: AppViewModel) {
    val state by vm.player.collectAsState()
    val lyricIndex = vm.currentLyricIndex()
    val lyricState = rememberLazyListState()
    LaunchedEffect(lyricIndex) {
        if (lyricIndex >= 0) {
            runCatching { lyricState.animateScrollToItem(lyricIndex) }
        }
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.player_title), style = MaterialTheme.typography.headlineSmall)
        CoverArt(url = vm.artworkUrl(state.track), modifier = Modifier.fillMaxWidth(0.72f).align(Alignment.CenterHorizontally))
        Text(state.track?.title ?: "—", style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(state.track?.artist.orEmpty(), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!state.track?.album.isNullOrBlank()) {
            Text(state.track?.album.orEmpty(), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        PlaybackSlider(state, vm::seekTo)
        TransportRow(state, vm)
        val lines = state.lyrics?.lines.orEmpty()
        if (lines.isEmpty()) {
            Text(stringResource(R.string.lyrics_empty), style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = lyricState) {
                itemsIndexed(lines) { index, line ->
                    Text(
                        line.text.ifBlank { " " },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (index == lyricIndex) FontWeight.Bold else FontWeight.Normal,
                        color = if (index == lyricIndex) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
        }
        Text(stringResource(R.string.queue_count, state.queue.size), style = MaterialTheme.typography.labelLarge)
        LazyColumn(Modifier.weight(0.7f)) {
            itemsIndexed(state.queue, key = { _, track -> track.cacheKey }) { _, track ->
                QueueRow(track = track, selected = track.cacheKey == state.track?.cacheKey) {
                    vm.playQueueItem(track)
                }
            }
        }
    }
}

@Composable
fun MiniPlayer(vm: AppViewModel, onOpenPlayer: () -> Unit) {
    val state by vm.player.collectAsState()
    val library by vm.library.collectAsState()
    val track = state.track ?: return
    val favorite = vm.isFavorite(track)
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenPlayer)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CoverArt(url = vm.artworkUrl(track), modifier = Modifier.size(44.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = { vm.toggleFavorite(track) }) {
                Icon(
                    if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = stringResource(if (favorite) R.string.cd_unfavorite else R.string.cd_favorite),
                    tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = vm::togglePlayPause) {
                Icon(
                    if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(if (state.isPlaying) R.string.cd_pause else R.string.cd_play),
                )
            }
            IconButton(onClick = vm::skipNext) {
                Icon(Icons.Filled.SkipNext, contentDescription = stringResource(R.string.cd_next))
            }
            PlayModeButton(PlaybackMode.from(state.repeat, state.shuffled), vm::cyclePlayMode)
        }
    }
}

@Composable
private fun CoverArt(url: String?, modifier: Modifier = Modifier) {
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNullOrBlank()) {
            Text("♪", style = MaterialTheme.typography.headlineMedium)
        } else {
            AsyncImage(
                model = url,
                contentDescription = stringResource(R.string.cd_cover),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun PlaybackSlider(state: PlayerUiState, onSeek: (Long) -> Unit) {
    val duration = state.durationMs.coerceAtLeast(0L)
    var dragging by remember { mutableStateOf<Float?>(null) }
    val progress = when {
        dragging != null -> dragging!!
        duration <= 0L -> 0f
        else -> (state.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    }
    Column {
        Slider(
            value = progress,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                if (duration > 0L) {
                    onSeek(((dragging ?: progress) * duration).toLong())
                }
                dragging = null
            },
            enabled = duration > 0L,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatMs(if (dragging != null && duration > 0L) (dragging!! * duration).toLong() else state.positionMs))
            Text(formatMs(duration))
        }
    }
}

@Composable
private fun TransportRow(state: PlayerUiState, vm: AppViewModel) {
    val library by vm.library.collectAsState()
    val track = state.track
    val favorite = track != null && vm.isFavorite(track)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { track?.let(vm::toggleFavorite) }, enabled = track != null) {
            Icon(
                if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = stringResource(if (favorite) R.string.cd_unfavorite else R.string.cd_favorite),
                tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = vm::skipPrevious) {
            Icon(Icons.Filled.SkipPrevious, contentDescription = stringResource(R.string.cd_previous))
        }
        IconButton(onClick = vm::togglePlayPause, modifier = Modifier.size(64.dp)) {
            Icon(
                if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(if (state.isPlaying) R.string.cd_pause else R.string.cd_play),
                modifier = Modifier.size(40.dp),
            )
        }
        IconButton(onClick = vm::skipNext) {
            Icon(Icons.Filled.SkipNext, contentDescription = stringResource(R.string.cd_next))
        }
        PlayModeButton(PlaybackMode.from(state.repeat, state.shuffled), vm::cyclePlayMode)
    }
}

@Composable
private fun PlayModeButton(mode: PlaybackMode, onClick: () -> Unit) {
    val (icon, label) = when (mode) {
        PlaybackMode.LIST_LOOP -> Icons.Filled.Repeat to R.string.cd_play_mode_list_loop
        PlaybackMode.RANDOM -> Icons.Filled.Shuffle to R.string.cd_play_mode_random
        PlaybackMode.SEQUENCE -> Icons.AutoMirrored.Filled.PlaylistPlay to R.string.cd_play_mode_sequence
        PlaybackMode.SINGLE -> Icons.Filled.RepeatOne to R.string.cd_play_mode_single
    }
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = stringResource(label), tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun QueueRow(track: Track, selected: Boolean, onClick: () -> Unit) {
    Text(
        "${track.title} · ${track.artist}",
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
    )
}

internal fun formatMs(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    val minutes = total / 60L
    val seconds = total % 60L
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
