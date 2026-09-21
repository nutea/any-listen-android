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
fun LibraryScreen(vm: AppViewModel, onOpenPlaylist: (Playlist) -> Unit, onSearch: () -> Unit,
    onArtists: () -> Unit = {}, onAlbums: () -> Unit = {}) {
    val state by vm.library.collectAsState()
    LibraryOverviewContent(state, vm::artworkUrl, onOpenPlaylist, onSearch, vm::playlistArtworkUrl, vm::editPlaylist, vm::clearPlaylistError, onArtists, onAlbums)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryOverviewContent(state: LibraryUiState, artwork: (Track?) -> String?,
    onOpenPlaylist: (Playlist) -> Unit, onSearch: () -> Unit,
    playlistArtwork: (Playlist) -> String? = { it.coverUrl },
    onEdit: (PlaylistEdit) -> Unit = {}, onBeginEdit: () -> Unit = {},
    onArtists: () -> Unit = {}, onAlbums: () -> Unit = {},
) {
    var editing by remember { mutableStateOf<Playlist?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Playlist?>(null) }
    var menu by remember { mutableStateOf<String?>(null) }
    var createId by remember { mutableStateOf("") }
    val editable = !state.snapshot.offline && !state.playlistBusy
    LaunchedEffect(state.playlistEditSuccess) {
        if (state.playlistEditSuccess > 0) { editing = null; creating = false; deleting = null }
    }

    val builtins = listOf("love", "last_played", "default")
    val custom = state.snapshot.playlists.filter { it.id !in builtins }
    LazyColumn(Modifier.fillMaxSize().testTag("library_overview"), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.library_title), Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                IconButton(onClick = {
                    onBeginEdit(); createId = java.util.UUID.randomUUID().toString(); creating = true
                }, enabled = editable) { Icon(Icons.Default.Add, stringResource(R.string.playlist_create)) }
                IconButton(onClick = onSearch) { Icon(Icons.Default.Search, stringResource(R.string.show_search)) }
            }
            if (state.snapshot.offline) Box(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) { Notice(stringResource(R.string.offline_banner)) }
            if (!creating && editing == null && deleting == null) state.playlistError?.let {
                Box(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) { Notice(it, error = true) }
            }
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
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(Triple(R.string.catalog_artists, Icons.Default.Person, onArtists), Triple(R.string.catalog_albums, Icons.Default.Album, onAlbums)).forEach { (label, icon, action) ->
                    Surface(onClick = action, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.weight(1f)) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(stringResource(label), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
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
                if (playlist.canManage) Box {
                    IconButton(onClick = { menu = playlist.id }, enabled = editable) {
                        Icon(Icons.Default.MoreVert, stringResource(R.string.playlist_options, playlist.name))
                    }

                } else Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (custom.isEmpty()) item { Text(stringResource(R.string.custom_playlists_empty), Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    custom.firstOrNull { it.id == menu }?.let { playlist ->
        ModalBottomSheet(onDismissRequest = { menu = null }, containerColor = MaterialTheme.colorScheme.surfaceContainer,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Artwork(playlistArtwork(playlist) ?: artwork(state.snapshot.tracksByPlaylist[playlist.id]?.firstOrNull()),
                        Modifier.size(56.dp), playlist.name, 12.dp, playlist = true)
                    Column(Modifier.weight(1f)) {
                        Text(playlist.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.track_count, playlist.trackCount), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    }
                    IconButton(onClick = { menu = null }) { Icon(Icons.Default.Close, stringResource(R.string.close_panel)) }
                }
                HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                PlaylistActionRow(Icons.Default.Edit, R.string.playlist_rename, editable) { menu = null; onBeginEdit(); editing = playlist }
                PlaylistActionRow(Icons.Default.ArrowUpward, R.string.playlist_move_up, editable && custom.indexOf(playlist) > 0) {
                    menu = null; onEdit(PlaylistEdit.Move(playlist.id, -1))
                }
                PlaylistActionRow(Icons.Default.ArrowDownward, R.string.playlist_move_down, editable && custom.indexOf(playlist) < custom.lastIndex) {
                    menu = null; onEdit(PlaylistEdit.Move(playlist.id, 1))
                }
                HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                PlaylistActionRow(Icons.Default.DeleteOutline, R.string.playlist_delete, editable, destructive = true) {
                    menu = null; onBeginEdit(); deleting = playlist
                }
            }
        }
    }
    if (creating || editing != null) {
        PlaylistNameDialog(editing?.name.orEmpty(), creating, state.playlistError, state.playlistBusy, editable,
            onDismiss = { creating = false; editing = null },
            onSave = { name -> onEdit(editing?.let { PlaylistEdit.Rename(it.id, name) } ?: PlaylistEdit.Create(createId, name)) })
    }
    deleting?.let { playlist ->
        AlertDialog(onDismissRequest = { if (!state.playlistBusy) deleting = null },
            icon = { Icon(Icons.Default.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            title = { Text(stringResource(R.string.playlist_delete)) },
            text = { Column {
                Text(stringResource(R.string.playlist_delete_confirm, playlist.name))
                state.playlistError?.let { Notice(it, error = true) }
            } },
            confirmButton = { TextButton(enabled = editable, onClick = { onEdit(PlaylistEdit.Delete(playlist.id)) }) {
                Text(stringResource(R.string.playlist_delete), color = MaterialTheme.colorScheme.error)
            } },
            dismissButton = { TextButton(enabled = !state.playlistBusy, onClick = { deleting = null }) { Text(stringResource(R.string.dialog_cancel)) } })
    }

}


@Composable
private fun PlaylistNameDialog(initial: String, creating: Boolean, error: String?, busy: Boolean, editable: Boolean,
    onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by androidx.compose.runtime.saveable.rememberSaveable(initial) { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        icon = { Icon(if (creating) Icons.Default.LibraryMusic else Icons.Default.Edit, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(stringResource(if (creating) R.string.playlist_create else R.string.playlist_rename)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (creating) Text(stringResource(R.string.playlist_create_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, enabled = !busy,
                label = { Text(stringResource(R.string.playlist_name)) }, modifier = Modifier.fillMaxWidth().testTag("playlist_name"), shape = RoundedCornerShape(14.dp),
                isError = name.trim().length > 100,
                supportingText = { Row(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.playlist_name_hint), Modifier.weight(1f))
                    Text("${name.trim().length}/100")
                } })
            error?.let { Notice(it, error = true) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        } },
        confirmButton = { Button(onClick = { onSave(name.trim()) }, enabled = editable && validPlaylistName(name)) {
            Text(stringResource(R.string.playlist_save))
        } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.dialog_cancel)) } })
}

@Composable
private fun PlaylistActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: Int,
    enabled: Boolean, destructive: Boolean = false, onClick: () -> Unit) {
    val color = (if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        .copy(alpha = if (enabled) 1f else .38f)
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 28.dp, vertical = 17.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = color)
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge, color = color)
    }
}
