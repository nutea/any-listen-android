package io.github.nutea.anylisten.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.nutea.anylisten.R
import io.github.nutea.anylisten.core.model.*
import io.github.nutea.anylisten.ui.AppViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaylistToolsSheet(vm: AppViewModel, dismiss: () -> Unit) {
    val state by vm.library.collectAsState()
    val playlist = state.selected ?: return
    val original = remember(playlist.id) { state.snapshot.tracksByPlaylist[playlist.id].orEmpty() }
    val successAtOpen = remember { state.playlistEditSuccess }
    LaunchedEffect(state.playlistEditSuccess) { if (state.playlistEditSuccess > successAtOpen) dismiss() }
    var copy by remember { mutableStateOf(false) }
    val copyId = remember { java.util.UUID.randomUUID().toString() }
    var name by remember { mutableStateOf(playlist.name) }
    ModalBottomSheet(onDismissRequest = { if (!state.playlistBusy) dismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        PlaylistOrderContent(original, vm::artworkUrl, state.playlistBusy || state.snapshot.offline, state.playlistError,
            { ordered -> vm.savePlaylistOrder(playlist.id, original, ordered) }, { copy = true })
    }
    if (copy) AlertDialog(onDismissRequest = { if (!state.playlistBusy) copy = false },
        title = { Text(stringResource(R.string.playlist_copy)) },
        text = { Column {
            OutlinedTextField(name, { name = it.take(100) }, label = { Text(stringResource(R.string.playlist_copy_name)) }, singleLine = true)
            state.playlistError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton({ vm.copySelectedPlaylist(name, copyId) }, enabled = validPlaylistName(name) && !state.playlistBusy && !state.snapshot.offline,
            modifier = Modifier.testTag("playlist_copy_confirm")) { Text(stringResource(R.string.playlist_copy)) } },
        dismissButton = { TextButton({ copy = false }, enabled = !state.playlistBusy) { Text(stringResource(R.string.dialog_cancel)) } })
}

@Composable
internal fun PlaylistOrderContent(original: List<Track>, artwork: (Track?) -> String?, disabled: Boolean,
    error: String?, save: (List<Track>) -> Unit, copy: () -> Unit) {
    var draft by remember(original) { mutableStateOf(original) }
    val list = rememberLazyListState()
    val drag = rememberQueueDrag(list, QueueSections(null, emptyList(), draft)) { from, to ->
        if (!disabled) QueueReorder.move(draft, null, emptyList(), from, to)?.let { draft = it.queue }
    }
    Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).testTag("playlist_tools_sheet")) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
            Text(stringResource(R.string.playlist_tools), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.playlist_order_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            if (disabled) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(copy, enabled = !disabled, modifier = Modifier.weight(1f).testTag("playlist_copy")) { Text(stringResource(R.string.playlist_copy)) }
                Button({ save(draft) }, enabled = !disabled && draft != original,
                    modifier = Modifier.weight(1f).testTag("playlist_order_save")) { Text(stringResource(R.string.playlist_order_save)) }
            }
        }
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f, fill = false), state = list, contentPadding = PaddingValues(bottom = 24.dp)) {
            items(draft, key = { it.cacheKey }) { track ->
                with(drag) {
                    Row(Modifier.fillMaxWidth().rowModifier(track.cacheKey).padding(start = 24.dp, end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Artwork(artwork(track), Modifier.size(44.dp), track.album)
                        TrackHeading(track.title, trackSubtitle(track), Modifier.weight(1f))
                        if (!disabled) QueueDragHandle(drag, track, draft)
                    }
                }
            }
        }
    }
}
