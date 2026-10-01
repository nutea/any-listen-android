package io.github.nutea.anylisten.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
fun LibrarySearchScreen(vm: AppViewModel, onBack: () -> Unit, onOpenPlayer: () -> Unit, playlistId: String? = null) {
    val state by vm.library.collectAsState()
    val player by vm.player.collectAsState()
    val downloadedKeys = downloadedTrackKeys(vm)
    val history by vm.searchHistory.collectAsState()
    val stats by vm.listeningStats.collectAsState()
    LibrarySearchContent(state.snapshot, player.track?.cacheKey, vm::artworkUrl, vm::isFavorite, vm::isAvailableOffline, onBack,
        { tracks, track -> if (track.cacheKey == player.track?.cacheKey) onOpenPlayer() else vm.play(tracks, track) }, downloaded = { it.cacheKey in downloadedKeys }, isPlaying = player.isPlaying,
        history = history, stats = stats, downloadedKeys = downloadedKeys, rememberSearch = vm::rememberSearch,
        clearHistory = vm::clearSearchHistory, playlistId = playlistId) { track, action ->
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
    onPlay: (List<Track>, Track) -> Unit, downloaded: (Track) -> Boolean = { false }, isPlaying: Boolean = false,
    history: List<String> = emptyList(), stats: List<ListeningStat> = emptyList(), downloadedKeys: Set<String> = emptySet(),
    rememberSearch: (String) -> Unit = {}, clearHistory: () -> Unit = {}, playlistId: String? = null,
    onAction: (Track, TrackAction) -> Unit) {
    val scopePlaylist = snapshot.playlists.firstOrNull { it.id == playlistId }
    val scopeUnavailable = playlistId != null && scopePlaylist == null
    var query by rememberSaveable(playlistId) { mutableStateOf("") }
    var menuTrack by remember(playlistId) { mutableStateOf<Track?>(null) }
    var onlyDownloaded by rememberSaveable(playlistId) { mutableStateOf(false) }
    var smart by rememberSaveable(playlistId) { mutableStateOf(SmartLibrary.ALL) }
    var randomSeed by rememberSaveable(playlistId) { mutableIntStateOf(0) }
    val index by key(playlistId) { produceState<LibrarySearch.Index?>(null, snapshot) {
        value = withContext(Dispatchers.Default) { LibrarySearch.index(snapshot, playlistId) }
    } }
    val results by key(playlistId) { produceState<List<LibrarySearchHit>>(emptyList(), index, query, smart, randomSeed, stats, onlyDownloaded, downloadedKeys) {
        value = withContext(Dispatchers.Default) {
            val base = if (query.isBlank()) index?.all.orEmpty() else index?.find(query).orEmpty()
            val eligible = base.filter { !onlyDownloaded || it.track.cacheKey in downloadedKeys }
            val selected = SmartTracks.select(eligible.map { it.track }, stats, smart, System.currentTimeMillis(), randomSeed)
            val hits = base.associateBy { it.track.cacheKey }
            selected.mapNotNull { hits[it.cacheKey] }
        }
    } }
    val keyboard = LocalSoftwareKeyboardController.current
    Column(Modifier.fillMaxSize().testTag("library_search")) {
        Row(Modifier.padding(end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { keyboard?.hide(); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back_to_library)) }
            OutlinedTextField(query, { query = it }, singleLine = true, enabled = !scopeUnavailable,
                placeholder = { Text(stringResource(if (playlistId == null) R.string.library_search_hint else R.string.library_search_playlist_hint), style = MaterialTheme.typography.bodyMedium) },
                trailingIcon = { if(query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, stringResource(R.string.clear_search)) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { rememberSearch(query); keyboard?.hide() }), modifier = Modifier.weight(1f).testTag("library_search_input"))
        }
        Text(when {
            scopeUnavailable -> stringResource(R.string.library_search_playlist_missing)
            scopePlaylist != null -> stringResource(R.string.library_search_playlist_scope, playlistName(scopePlaylist))
            else -> stringResource(R.string.library_search_scope)
        }, Modifier.padding(horizontal = 20.dp, vertical = 8.dp).testTag("library_search_scope"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (snapshot.offline) Text(stringResource(R.string.library_search_offline), Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(onlyDownloaded, { onlyDownloaded = !onlyDownloaded }, enabled = !scopeUnavailable, label = { Text(stringResource(R.string.search_downloaded)) }, modifier = Modifier.testTag("search_downloaded"))
            SmartLibrary.entries.forEach { mode ->
                FilterChip(smart == mode, { smart = mode; if (mode == SmartLibrary.RANDOM) randomSeed++ }, enabled = !scopeUnavailable, label = { Text(stringResource(when(mode) {
                    SmartLibrary.ALL -> R.string.smart_all; SmartLibrary.FREQUENT -> R.string.smart_frequent
                    SmartLibrary.REDISCOVER -> R.string.smart_rediscover; SmartLibrary.RANDOM -> R.string.smart_random
                })) }, modifier = Modifier.testTag("smart_${mode.name}"))
            }
        }
        val keys = remember(index) { index?.all.orEmpty().map { it.track.cacheKey }.toSet() }
        val libraryStats = stats.filter { it.trackKey in keys }
        Text(stringResource(R.string.listening_summary, libraryStats.sumOf { it.plays }, libraryStats.sumOf { it.listenedMs } / 60000),
            Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (scopeUnavailable) {
            Spacer(Modifier.weight(1f))
        } else if (query.isBlank() && smart == SmartLibrary.ALL && !onlyDownloaded) {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.search_history), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        TextButton(clearHistory, enabled = history.isNotEmpty(), modifier = Modifier.testTag("search_history_clear")) { Text(stringResource(R.string.clear_search)) }
                    }
                    Text(stringResource(R.string.listening_since_install), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(history, key = { it }) { previous ->
                    ListItem(headlineContent = { Text(previous) }, leadingContent = { Icon(Icons.Default.History, null) },
                        modifier = Modifier.fillMaxWidth().clickable { query = previous; rememberSearch(previous) }.testTag("search_history_$previous"))
                }
                item { Text(stringResource(R.string.smart_rediscover_hint), Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        } else {
            Text(stringResource(R.string.library_search_results, results.size), Modifier.padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.titleSmall)
            if (results.isEmpty()) Text(stringResource(R.string.search_empty_detail), Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            key(query) { LazyColumn(Modifier.weight(1f)) {
                items(results, key = { it.track.cacheKey }) { hit ->
                    val track = hit.track
                    val origins = hit.playlistIds.mapNotNull { id -> snapshot.playlists.firstOrNull { it.id == id } }.map { playlistName(it) }.joinToString(" · ")
                    Row(Modifier.fillMaxWidth().clickable { rememberSearch(query); keyboard?.hide(); onPlay(results.map { it.track }, track) }.padding(start = 20.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
