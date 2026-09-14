package io.github.nutea.anylisten.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nutea.anylisten.R
import io.github.nutea.anylisten.core.model.*
import io.github.nutea.anylisten.ui.*

@Composable
fun LibraryScreen(vm: AppViewModel, onOpenPlaylist: (Playlist) -> Unit, onSearch: () -> Unit) {
    val state by vm.library.collectAsState()
    LibraryOverviewContent(state, vm::artworkUrl, onOpenPlaylist, onSearch, vm::playlistArtworkUrl)
}

@Composable
fun LibraryOverviewContent(state: LibraryUiState, artwork: (Track?) -> String?,
    onOpenPlaylist: (Playlist) -> Unit, onSearch: () -> Unit,
    playlistArtwork: (Playlist) -> String? = { it.coverUrl }) {
    val builtins = listOf("love", "last_played", "default")
    val custom = state.snapshot.playlists.filter { it.id !in builtins }
    LazyColumn(Modifier.fillMaxSize().testTag("library_overview"), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.library_title), Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                IconButton(onClick = onSearch) { Icon(Icons.Default.Search, stringResource(R.string.show_search)) }
            }
            if (state.snapshot.offline) Box(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) { Notice(stringResource(R.string.offline_banner)) }
            state.error?.let { Box(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) { Notice(it, error = true) } }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                builtins.forEachIndexed { index, id ->
                    val playlist = state.snapshot.playlists.firstOrNull { it.id == id }
                    val label = stringResource(when (id) { "love" -> R.string.list_love; "last_played" -> R.string.list_last_played; else -> R.string.list_default })
                    val color = when(index) { 0 -> MaterialTheme.colorScheme.primaryContainer; 1 -> MaterialTheme.colorScheme.secondaryContainer; else -> MaterialTheme.colorScheme.tertiaryContainer }
                    val ink = when(index) { 0 -> MaterialTheme.colorScheme.onPrimaryContainer; 1 -> MaterialTheme.colorScheme.onSecondaryContainer; else -> MaterialTheme.colorScheme.onTertiaryContainer }
                    Column(Modifier.weight(1f).testTag("playlist_$id").clickable(enabled = playlist != null) { playlist?.let(onOpenPlaylist) }.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(shape = RoundedCornerShape(24.dp), color = color, modifier = Modifier.size(80.dp)) {
                            Box(contentAlignment = Alignment.Center) { Icon(when(index) { 0 -> Icons.Default.Favorite; 1 -> Icons.Default.History; else -> Icons.Default.LibraryMusic }, null, Modifier.size(34.dp), tint = ink) }
                        }
                        Text(label, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.track_count, playlist?.trackCount ?: 0), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.custom_playlists), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text(custom.size.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(custom, key = { it.id }) { playlist ->
            Row(Modifier.fillMaxWidth().testTag("playlist_${playlist.id}").clickable { onOpenPlaylist(playlist) }.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Artwork(playlistArtwork(playlist) ?: artwork(state.snapshot.tracksByPlaylist[playlist.id]?.firstOrNull()), Modifier.size(58.dp), playlist.name, 12.dp, playlist = true)
                Column(Modifier.weight(1f)) {
                    Text(playlist.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.track_count, playlist.trackCount), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (custom.isEmpty()) item { Text(stringResource(R.string.custom_playlists_empty), Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
