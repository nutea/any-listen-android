package io.github.nutea.anylisten.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import io.github.nutea.anylisten.ui.rememberMotionEnabled
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nutea.anylisten.R
import io.github.nutea.anylisten.core.model.*
import io.github.nutea.anylisten.ui.AppViewModel
import io.github.nutea.anylisten.ui.LibraryUiState
import io.github.nutea.anylisten.ui.confirmHaptic
import io.github.nutea.anylisten.ui.tickHaptic

enum class TrackAction { PLAY, FAVORITE, ADD, REMOVE, DOWNLOAD, PLAY_LATER }

@Composable
fun PlaylistScreen(vm: AppViewModel, onBack: () -> Unit, onSearch: () -> Unit, onOpenPlayer: () -> Unit, canInteract: () -> Boolean = { true }) {
    val state by vm.library.collectAsState()
    val player by vm.player.collectAsState()
    val downloadedKeys = downloadedTrackKeys(vm)
    LibraryContent(state, player.track?.cacheKey, vm::artworkUrl, vm::isFavorite, vm::selectPlaylist,
        vm::updateQuery, vm::refresh, { if (canInteract()) vm.play(state.filtered) }, { if (canInteract()) vm.requestDownload(state.filtered.filterNot { it.cacheKey in downloadedKeys }) },
        vm::isAvailableOffline, vm::setSort,
        onBack = onBack, onSearch = onSearch, isPlaying = player.isPlaying, downloaded = { it.cacheKey in downloadedKeys },
        onBatch = { tracks, action ->
            if (canInteract()) when (action) {
                TrackAction.ADD -> vm.startAddToPlaylist(tracks)
                TrackAction.REMOVE -> vm.removeFromSelected(tracks)
                TrackAction.DOWNLOAD -> vm.requestDownload(tracks)
                TrackAction.PLAY_LATER -> vm.playLater(tracks)
                else -> Unit
            }
        },
    ) { track, action ->
        if (canInteract()) when (action) {
            TrackAction.PLAY -> if (player.track?.cacheKey == track.cacheKey) onOpenPlayer() else vm.play(state.filtered, track)
            TrackAction.FAVORITE -> vm.toggleFavorite(track)
            TrackAction.ADD -> vm.startAddToPlaylist(track)
            TrackAction.REMOVE -> vm.removeFromSelected(track)
            TrackAction.DOWNLOAD -> vm.download(listOf(track))
            TrackAction.PLAY_LATER -> vm.playLater(listOf(track))
        }
    }
}

@Composable
fun LibraryDialogs(vm: AppViewModel) {
    val state by vm.library.collectAsState()
    state.pendingAdd?.let { tracks ->
        AddToPlaylistSheet(vm.addTargets(), tracks.size,
            artwork = { playlist -> vm.playlistArtworkUrl(playlist)
                ?: vm.artworkUrl(state.snapshot.tracksByPlaylist[playlist.id]?.firstOrNull()) },
            onSelect = vm::confirmAddToPlaylist, onDismiss = vm::cancelAddToPlaylist)
    }
    state.pendingDownload?.let { tracks ->
        AlertDialog(onDismissRequest = vm::cancelPendingDownload,
            title = { Text(stringResource(R.string.download_confirm_title)) },
            text = { Text(stringResource(R.string.download_confirm_body, tracks.size)) },
            confirmButton = { TextButton(onClick = vm::confirmPendingDownload) { Text(stringResource(R.string.download_all)) } },
            dismissButton = { TextButton(onClick = vm::cancelPendingDownload) { Text(stringResource(R.string.dialog_cancel)) } })
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryContent(
    state: LibraryUiState, currentKey: String?, artwork: (Track?) -> String?, favorite: (Track) -> Boolean,
    onSelect: (Playlist) -> Unit, onQuery: (String) -> Unit, onRefresh: () -> Unit,
    onPlayAll: () -> Unit, onDownloadAll: () -> Unit,
    offlineReady: (Track) -> Boolean = { false },
    onSort: (TrackSortField) -> Unit = {},
    onBatch: (List<Track>, TrackAction) -> Unit = { _, _ -> },
    onBack: (() -> Unit)? = null,
    onSearch: () -> Unit = {},
    downloaded: (Track) -> Boolean = { false },
    isPlaying: Boolean = false,
    onAction: (Track, TrackAction) -> Unit,
) {
    var menuTrack by remember { mutableStateOf<Track?>(null) }
    var removeTrack by remember { mutableStateOf<Track?>(null) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selectedKeys by rememberSaveable { mutableStateOf(setOf<String>()) }
    var sortMenu by remember { mutableStateOf(false) }
    var batchRemove by remember { mutableStateOf(false) }
    val hapticView = LocalView.current
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val scope = rememberCoroutineScope()
    val motion = rememberMotionEnabled()
    var locatedKey by remember { mutableStateOf<String?>(null) }
    var locateJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val currentIndex = remember(state.filtered, currentKey) { state.filtered.indexOfFirst { it.cacheKey == currentKey } }
    val selectedTracks = remember(state.filtered, selectedKeys) {
        if (selectedKeys.isEmpty()) emptyList() else state.filtered.filter { it.cacheKey in selectedKeys }
    }
    fun exitSelect() {
        selecting = false
        selectedKeys = emptySet()
    }
    BackHandler(enabled = selecting) { exitSelect() }
    LaunchedEffect(state.selected?.id) { exitSelect() }
    LazyColumn(Modifier.fillMaxSize().testTag("song_list"), state = listState,
        contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onBack != null) IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back_to_library))
                    }
                    Text(state.selected?.let { playlistName(it) }.orEmpty(), Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = onSearch) {
                        Icon(Icons.Default.Search, stringResource(R.string.show_search))
                    }
                    IconButton(onClick = onRefresh, enabled = !state.refreshing) {
                        Icon(Icons.Default.Refresh, stringResource(R.string.library_refresh))
                    }
                }
                if (state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(end = 8.dp, top = 4.dp))
                if (state.snapshot.offline) Box(Modifier.padding(top = 8.dp, end = 8.dp)) { Notice(stringResource(R.string.offline_banner)) }
                state.error?.let { Box(Modifier.padding(top = 8.dp, end = 8.dp)) { Notice(it, error = true) } }
            }
        }
        if (state.snapshot.playlists.isEmpty()) {
            item { EmptyContent(Icons.Default.LibraryMusic, stringResource(R.string.library_empty_title), stringResource(R.string.library_empty)) }
        } else {
            stickyHeader {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)) {
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (selecting) stringResource(R.string.selection_count, selectedTracks.size, state.filtered.size)
                                    else state.selected?.let { playlistName(it) }.orEmpty(),
                                    style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    if (selecting) stringResource(R.string.selection_scope)
                                    else stringResource(R.string.track_count, state.filtered.size),
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (!selecting) IconButton(onClick = {
                                if (currentIndex >= 0) {
                                    locateJob?.cancel()
                                    locateJob = scope.launch {
                                        hapticView.tickHaptic()
                                        val offset = -listState.layoutInfo.viewportSize.height / 3
                                        // The title and pinned toolbar precede the track rows.
                                        if (motion) listState.animateScrollToItem(currentIndex + 2, offset)
                                        else listState.scrollToItem(currentIndex + 2, offset)
                                        locatedKey = currentKey
                                        delay(1600)
                                        locatedKey = null
                                    }
                                }
                            }, enabled = currentIndex >= 0) {
                                Icon(Icons.Default.MyLocation, stringResource(R.string.locate_current_track))
                            }
                            Box {
                                IconButton(onClick = { sortMenu = true }) {
                                    Icon(Icons.AutoMirrored.Filled.Sort, stringResource(R.string.sort_tracks))
                                }
                                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                    Text(stringResource(R.string.sort_local_only), style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                                    TrackSortField.entries.forEach { field ->
                                        DropdownMenuItem(
                                            text = { Text(sortLabel(field, state.sort == field, state.sortAscending)) },
                                            onClick = { onSort(field); sortMenu = false },
                                        )
                                    }
                                }
                            }
                            if (selecting) {
                                val pending = selectedTracks.filterNot(downloaded)
                                val allDownloaded = selectedTracks.isNotEmpty() && pending.isEmpty()
                                IconButton(onClick = { onBatch(pending, TrackAction.DOWNLOAD); exitSelect() }, enabled = pending.isNotEmpty()) {
                                    Icon(if (allDownloaded) Icons.Default.DownloadDone else Icons.Default.Download,
                                        stringResource(if (allDownloaded) R.string.cd_downloaded else R.string.cd_download))
                                }
                                IconButton(onClick = { exitSelect() }) {
                                    Icon(Icons.Default.Close, stringResource(R.string.clear_selection))
                                }
                            } else {
                                val allDownloaded = state.filtered.isNotEmpty() && state.filtered.all(downloaded)
                                IconButton(onClick = onDownloadAll, enabled = state.filtered.isNotEmpty() && !allDownloaded) {
                                    Icon(if (allDownloaded) Icons.Default.DownloadDone else Icons.Default.Download,
                                        stringResource(if (allDownloaded) R.string.cd_downloaded else R.string.download_all))
                                }
                                IconButton(onClick = onPlayAll, enabled = state.filtered.isNotEmpty()) {
                                    Icon(Icons.Default.PlayArrow, stringResource(R.string.play_all), tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                        if (selecting) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { onBatch(selectedTracks, TrackAction.ADD); exitSelect() },
                                    enabled = selectedTracks.isNotEmpty() && !state.snapshot.offline) {
                                    Text(stringResource(R.string.cd_add_to_playlist))
                                }
                                TextButton(onClick = { batchRemove = true },
                                    enabled = selectedTracks.isNotEmpty() && state.selected?.canMutateOnline == true && !state.snapshot.offline) {
                                    Text(stringResource(R.string.cd_remove))
                                }
                                TextButton(onClick = {
                                    onBatch(selectedTracks, TrackAction.PLAY_LATER)
                                    exitSelect()
                                }, enabled = selectedTracks.isNotEmpty()) {
                                    Text(stringResource(R.string.play_later))
                                }
                            }
                        }
                    }
                }
            }
            if (state.filtered.isEmpty()) item {
                EmptyContent(Icons.Default.SearchOff, stringResource(R.string.no_tracks_title),
                    stringResource(if (state.query.isBlank()) R.string.playlist_empty_detail else R.string.search_empty_detail))
            }
            items(state.filtered, key = { it.cacheKey }, contentType = { "track" }) { track ->
                val selected = track.cacheKey == currentKey
                val checked = track.cacheKey in selectedKeys
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("song_${track.cacheKey}")
                    .background(if (track.cacheKey == locatedKey) MaterialTheme.colorScheme.primaryContainer
                        else if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .25f) else Color.Transparent)
                    .combinedClickable(
                        onClick = {
                            if (selecting) {
                                hapticView.tickHaptic()
                                selectedKeys = if (checked) selectedKeys - track.cacheKey else selectedKeys + track.cacheKey
                            } else onAction(track, TrackAction.PLAY)
                        },
                        onLongClick = {
                            hapticView.confirmHaptic()
                            selecting = true
                            selectedKeys = selectedKeys + track.cacheKey
                        },
                    )
                    .padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (selecting) {
                        Checkbox(checked = checked, onCheckedChange = {
                            selectedKeys = if (it) selectedKeys + track.cacheKey else selectedKeys - track.cacheKey
                        })
                    }
                    Artwork(artwork(track), Modifier.size(46.dp), seed = track.album.ifBlank { track.title }, radius = 8.dp)
                    Column(Modifier.weight(1f)) {
                        Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        Text(trackSubtitle(track), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (selected) PlaybackIndicator(isPlaying)
                    if (!selecting) IconButton(onClick = { menuTrack = track }) { Icon(Icons.Default.MoreHoriz, stringResource(R.string.track_options, track.title)) }
                }
            }
        }
    }
    menuTrack?.let { track ->
        ModalBottomSheet(onDismissRequest = { menuTrack = null }) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Artwork(artwork(track), Modifier.size(48.dp), seed = track.album)
                TrackHeading(track.title, trackSubtitle(track), Modifier.weight(1f))
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ActionLine(stringResource(if (favorite(track)) R.string.cd_unfavorite else R.string.cd_favorite),
                if (favorite(track)) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                { menuTrack = null; onAction(track, TrackAction.FAVORITE) }, enabled = !state.snapshot.offline)
            ActionLine(stringResource(R.string.play_later), Icons.AutoMirrored.Filled.QueueMusic,
                { menuTrack = null; onAction(track, TrackAction.PLAY_LATER) })
            ActionLine(stringResource(if (downloaded(track)) R.string.cd_downloaded else R.string.cd_download),
                if (downloaded(track)) Icons.Default.DownloadDone else Icons.Default.Download,
                { menuTrack = null; onAction(track, TrackAction.DOWNLOAD) }, enabled = !downloaded(track))
            ActionLine(stringResource(R.string.cd_add_to_playlist), Icons.AutoMirrored.Filled.PlaylistAdd,
                { menuTrack = null; onAction(track, TrackAction.ADD) }, enabled = !state.snapshot.offline)
            if (state.snapshot.offline) {
                Text(stringResource(R.string.error_offline_write), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
            if (state.selected?.canMutateOnline == true) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                ActionLine(stringResource(R.string.cd_remove), Icons.Default.RemoveCircleOutline,
                    { menuTrack = null; removeTrack = track }, enabled = !state.snapshot.offline, destructive = true)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    removeTrack?.let { track ->
        AlertDialog(onDismissRequest = { removeTrack = null }, title = { Text(stringResource(R.string.cd_remove)) },
            text = { Text(stringResource(R.string.remove_track_confirm, track.title)) },
            confirmButton = { TextButton(onClick = { removeTrack = null; onAction(track, TrackAction.REMOVE) }) { Text(stringResource(R.string.confirm_remove)) } },
            dismissButton = { TextButton(onClick = { removeTrack = null }) { Text(stringResource(R.string.dialog_cancel)) } })
    }
    if (batchRemove) {
        AlertDialog(onDismissRequest = { batchRemove = false }, title = { Text(stringResource(R.string.cd_remove)) },
            text = { Text(stringResource(R.string.batch_remove_confirm, selectedTracks.size, state.selected?.let { playlistName(it) }.orEmpty())) },
            confirmButton = {
                TextButton(onClick = {
                    batchRemove = false
                    onBatch(selectedTracks, TrackAction.REMOVE)
                    exitSelect()
                }) { Text(stringResource(R.string.confirm_remove)) }
            },
            dismissButton = { TextButton(onClick = { batchRemove = false }) { Text(stringResource(R.string.dialog_cancel)) } })
    }
}

@Composable
private fun sortLabel(field: TrackSortField, selected: Boolean, ascending: Boolean): String {
    val name = stringResource(when (field) {
        TrackSortField.TITLE -> R.string.sort_title
        TrackSortField.ARTIST -> R.string.sort_artist
        TrackSortField.ALBUM -> R.string.sort_album
    })
    if (!selected) return name
    return name + if (ascending) " ↑" else " ↓"
}
