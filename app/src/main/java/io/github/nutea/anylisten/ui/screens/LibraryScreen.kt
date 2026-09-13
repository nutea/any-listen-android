package io.github.nutea.anylisten.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.github.nutea.anylisten.R
import io.github.nutea.anylisten.ui.AppViewModel

@Composable
fun LibraryScreen(vm: AppViewModel, onOpenPlayer: () -> Unit) {
    val state by vm.library.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.library_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = vm::refresh, enabled = !state.refreshing) { Text(stringResource(R.string.library_refresh)) }
        }
        Text(stringResource(R.string.last_refresh, vm.lastRefreshLabel()), style = MaterialTheme.typography.bodySmall)
        if (state.snapshot.offline) {
            Text(stringResource(R.string.offline_banner), color = MaterialTheme.colorScheme.secondary)
        }
        if (state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.status?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }
        if (state.snapshot.playlists.isEmpty()) {
            Text(stringResource(R.string.library_empty))
            return
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.snapshot.playlists.forEach { playlist ->
                FilterChip(
                    selected = playlist.id == state.selected?.id,
                    onClick = { vm.selectPlaylist(playlist) },
                    label = {
                        Text(
                            "${vm.playlistLabel(playlist)} (${playlist.trackCount})",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }
        OutlinedTextField(
            value = state.query,
            onValueChange = vm::updateQuery,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.playlist_search)) },
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    vm.play(state.filtered)
                    onOpenPlayer()
                },
                enabled = state.filtered.isNotEmpty(),
            ) { Text(stringResource(R.string.play_all)) }
            OutlinedButton(
                onClick = { vm.requestDownload(state.filtered) },
                enabled = state.filtered.isNotEmpty(),
            ) { Text(stringResource(R.string.download_all)) }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(state.filtered, key = { it.cacheKey }) { track ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        vm.play(state.filtered, track)
                        onOpenPlayer()
                    }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val cover = vm.artworkUrl(track)
                    if (!cover.isNullOrBlank()) {
                        AsyncImage(
                            model = cover,
                            contentDescription = stringResource(R.string.cd_cover),
                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)).padding(end = 0.dp),
                            contentScale = ContentScale.Crop,
                        )
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                        Text("${track.artist} · ${track.album}", maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { vm.play(state.filtered, track); onOpenPlayer() }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.cd_play))
                    }
                    IconButton(onClick = { vm.toggleFavorite(track) }) {
                        val fav = vm.isFavorite(track)
                        Icon(
                            if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = stringResource(if (fav) R.string.cd_unfavorite else R.string.cd_favorite),
                        )
                    }
                    IconButton(onClick = { vm.startAddToPlaylist(track) }) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.cd_add_to_playlist))
                    }
                    if (state.selected?.canMutateOnline == true) {
                        IconButton(onClick = { vm.removeFromSelected(track) }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.cd_remove))
                        }
                    }
                    TextButton(onClick = { vm.download(listOf(track)) }, modifier = Modifier.semantics { contentDescription = "Download" }) {
                        Text(stringResource(R.string.cd_download))
                    }
                }
            }
        }
    }
    val addTrack = state.addTrack
    if (addTrack != null) {
        AlertDialog(
            onDismissRequest = vm::cancelAddToPlaylist,
            title = { Text(stringResource(R.string.add_to_playlist)) },
            text = {
                Column {
                    vm.addTargets().forEach { playlist ->
                        Text(
                            vm.playlistLabel(playlist),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { vm.confirmAddToPlaylist(playlist) }
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = vm::cancelAddToPlaylist) { Text(stringResource(R.string.dialog_cancel)) }
            },
        )
    }
    val pending = state.pendingDownload
    if (pending != null) {
        AlertDialog(
            onDismissRequest = vm::cancelPendingDownload,
            title = { Text(stringResource(R.string.download_confirm_title)) },
            text = { Text(stringResource(R.string.download_confirm_body, pending.size)) },
            confirmButton = {
                TextButton(onClick = vm::confirmPendingDownload) { Text(stringResource(R.string.download_all)) }
            },
            dismissButton = {
                TextButton(onClick = vm::cancelPendingDownload) { Text(stringResource(R.string.dialog_cancel)) }
            },
        )
    }
}
