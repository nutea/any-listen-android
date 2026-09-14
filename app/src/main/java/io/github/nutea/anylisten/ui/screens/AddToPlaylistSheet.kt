package io.github.nutea.anylisten.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nutea.anylisten.R
import io.github.nutea.anylisten.core.model.Playlist

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(playlists: List<Playlist>, selectedCount: Int,
    artwork: (Playlist) -> String?, onSelect: (Playlist) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).testTag("add_playlist_sheet")) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.add_to_playlist), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.add_playlist_selection, selectedCount),
                        Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, stringResource(R.string.dialog_cancel))
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
            LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
                if (playlists.isEmpty()) item {
                    Text(stringResource(R.string.no_playlist_targets), Modifier.padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(playlists, key = { it.id }) { playlist ->
                    Row(Modifier.fillMaxWidth().testTag("add_playlist_${playlist.id}")
                        .clickable { onSelect(playlist) }.padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Artwork(artwork(playlist), Modifier.size(56.dp).testTag("add_playlist_cover_${playlist.id}"), playlist.name, 12.dp, playlist = true)
                        Column(Modifier.weight(1f)) {
                            Text(playlistName(playlist), style = MaterialTheme.typography.titleMedium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(R.string.track_count, playlist.trackCount), Modifier.padding(top = 4.dp),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Default.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
