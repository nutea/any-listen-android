package io.github.nutea.anylisten.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nutea.anylisten.R
import io.github.nutea.anylisten.core.model.*
import io.github.nutea.anylisten.ui.AppViewModel

@Composable
fun LibrarySearchScreen(vm: AppViewModel, onBack: () -> Unit, onOpenPlayer: () -> Unit) {
    val state by vm.library.collectAsState()
    val player by vm.player.collectAsState()
    val downloadedKeys = downloadedTrackKeys(vm)
    LibrarySearchContent(state.snapshot, player.track?.cacheKey, vm::artworkUrl, vm::isFavorite, vm::isAvailableOffline, onBack,
        { tracks, track -> if (track.cacheKey == player.track?.cacheKey) onOpenPlayer() else vm.play(tracks, track) }, downloaded = { it.cacheKey in downloadedKeys }, isPlaying = player.isPlaying) { track, action ->
        when(action) {
            TrackAction.FAVORITE -> vm.toggleFavorite(track)
            TrackAction.ADD -> vm.startAddToPlaylist(track)
            TrackAction.DOWNLOAD -> vm.download(listOf(track))
            TrackAction.PLAY_LATER -> vm.playLater(listOf(track))
            else -> Unit
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrarySearchContent(snapshot: LibrarySnapshot, currentKey: String?, artwork: (Track?) -> String?,
    favorite: (Track) -> Boolean, offlineReady: (Track) -> Boolean, onBack: () -> Unit,
    onPlay: (List<Track>, Track) -> Unit, downloaded: (Track) -> Boolean = { false }, isPlaying: Boolean = false, onAction: (Track, TrackAction) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var menuTrack by remember { mutableStateOf<Track?>(null) }
    val results = remember(snapshot, query) { LibrarySearch.find(snapshot, query) }
    val keyboard = LocalSoftwareKeyboardController.current
    Column(Modifier.fillMaxSize().testTag("library_search")) {
        Row(Modifier.padding(end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { keyboard?.hide(); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back_to_library)) }
            OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true,
                placeholder = { Text(stringResource(R.string.library_search_hint), style = MaterialTheme.typography.bodyMedium) },
                trailingIcon = { if(query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, stringResource(R.string.clear_search)) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }))
        }
        Text(stringResource(if (snapshot.offline) R.string.library_search_offline else R.string.library_search_scope), Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (query.isBlank()) {
            EmptyContent(Icons.Default.Search, stringResource(R.string.library_search_start), stringResource(R.string.library_search_hint))
        } else {
            Text(stringResource(R.string.library_search_results, results.size), Modifier.padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.titleSmall)
            if (results.isEmpty()) Text(stringResource(R.string.search_empty_detail), Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            key(query) { LazyColumn(Modifier.weight(1f)) {
                items(results, key = { it.track.cacheKey }) { hit ->
                    val track = hit.track
                    val origins = hit.playlistIds.mapNotNull { id -> snapshot.playlists.firstOrNull { it.id == id } }.map { playlistName(it) }.joinToString(" · ")
                    Row(Modifier.fillMaxWidth().clickable { keyboard?.hide(); onPlay(results.map { it.track }, track) }.padding(start = 20.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Artwork(artwork(track), Modifier.size(48.dp), track.album.ifBlank { track.title })
                        Column(Modifier.weight(1f)) {
                            Text(track.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (currentKey == track.cacheKey) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            Text(trackSubtitle(track), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(R.string.library_search_origins, origins), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.primary)
                        }
                        if (currentKey == track.cacheKey) PlaybackIndicator(isPlaying)
                        IconButton(onClick = { keyboard?.hide(); menuTrack = track }) { Icon(Icons.Default.MoreHoriz, stringResource(R.string.track_options, track.title)) }
                    }
                }
            } }
        }
    }
    menuTrack?.let { track ->
        ModalBottomSheet(onDismissRequest = { menuTrack = null }) {
            TrackHeading(track.title, trackSubtitle(track), Modifier.padding(20.dp))
            ActionLine(stringResource(if (favorite(track)) R.string.cd_unfavorite else R.string.cd_favorite), Icons.Default.Favorite,
                { menuTrack = null; onAction(track, TrackAction.FAVORITE) }, enabled = !snapshot.offline)
            ActionLine(stringResource(R.string.play_later), Icons.AutoMirrored.Filled.QueueMusic, { menuTrack = null; onAction(track, TrackAction.PLAY_LATER) })
            ActionLine(stringResource(if (downloaded(track)) R.string.cd_downloaded else R.string.cd_download),
                if (downloaded(track)) Icons.Default.DownloadDone else Icons.Default.Download,
                { menuTrack = null; onAction(track, TrackAction.DOWNLOAD) }, enabled = !downloaded(track))
            ActionLine(stringResource(R.string.cd_add_to_playlist), Icons.AutoMirrored.Filled.PlaylistAdd, { menuTrack = null; onAction(track, TrackAction.ADD) }, enabled = !snapshot.offline)
            Spacer(Modifier.height(16.dp))
        }
    }
}
