package io.github.nutea.anylisten.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.nutea.anylisten.R
import io.github.nutea.anylisten.core.model.*
import io.github.nutea.anylisten.ui.AppViewModel

enum class LocalBatchAction { PLAY, LATER, DELETE, RETRY, CANCEL, REMOVE }
private data class PendingLocalBatch(val tab: Int, val keys: List<String>, val action: LocalBatchAction)

@Composable
fun DownloadsScreen(vm: AppViewModel, onOpenPlayer: () -> Unit) {
    val records by vm.downloads.collectAsState()
    val player by vm.player.collectAsState()
    val downloaded by vm.downloadedAssets.collectAsState()
    val cached by vm.cachedAssets.collectAsState()
    val storage by vm.storage.collectAsState()
    LaunchedEffect(Unit) {
        vm.resumeDownloads()
        vm.refreshLocal()
    }
    DownloadsContent(
        downloaded = downloaded,
        cached = cached,
        tasks = LocalInventory.tasks(records),
        storage = storage,
        artwork = { vm.artworkUrl(it.track) },
        onPlay = { vm.playLocal(it); onOpenPlayer() },
        onDeleteDownload = vm::deleteDownload,
        onDeleteCache = vm::deleteCache,
        onRetry = vm::retryDownload,
        onCancel = vm::cancelDownload,
        currentKey = player.track?.cacheKey,
        isPlaying = player.isPlaying,
        onRemoveTask = vm::removeDownloadTask,
        onBatch = { tab, keys, action -> vm.batchLocal(tab, keys, action) },
    )
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DownloadsContent(
    downloaded: List<LocalAssetItem>,
    cached: List<LocalAssetItem>,
    tasks: List<DownloadRecord>,
    storage: StorageSummary,
    artwork: (LocalAssetItem) -> String? = { null },
    onPlay: (LocalAssetItem) -> Unit,
    onDeleteDownload: (String) -> Unit,
    onDeleteCache: (String) -> Unit,
    onRetry: (String) -> Unit,
    onCancel: (String) -> Unit,
    currentKey: String? = null,
    isPlaying: Boolean = false,
    onRemoveTask: (String) -> Unit = {},
    onBatch: (Int, List<String>, LocalBatchAction) -> Unit = { _, _, _ -> },
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var deletingDownload by remember { mutableStateOf<LocalAssetItem?>(null) }
    var deletingCache by remember { mutableStateOf<LocalAssetItem?>(null) }
    var menuItem by remember { mutableStateOf<LocalAssetItem?>(null) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selectedKeys by rememberSaveable { mutableStateOf(setOf<String>()) }
    var pendingBatch by remember { mutableStateOf<PendingLocalBatch?>(null) }
    val visibleKeys = when (tab) { 0 -> downloaded.map { it.cacheKey }; 1 -> cached.map { it.cacheKey }; else -> tasks.map { it.cacheKey } }
    val selected = visibleKeys.filter { it in selectedKeys }
    fun exitSelection() { selecting = false; selectedKeys = emptySet() }
    fun toggle(key: String) { selectedKeys = if (key in selectedKeys) selectedKeys - key else selectedKeys + key }
    fun begin(key: String) { selecting = true; selectedKeys = setOf(key) }
    BackHandler(enabled = selecting) { exitSelection() }
    fun eligible(action: LocalBatchAction): List<String> = when (action) {
        LocalBatchAction.RETRY, LocalBatchAction.REMOVE -> tasks.filter { it.cacheKey in selected && it.status in setOf(DownloadStatus.FAILED, DownloadStatus.CANCELLED, DownloadStatus.PAUSED) }.map { it.cacheKey }
        LocalBatchAction.CANCEL -> tasks.filter { it.cacheKey in selected && it.status in setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING) }.map { it.cacheKey }
        LocalBatchAction.PLAY, LocalBatchAction.LATER -> (if (tab == 0) downloaded else cached).filter { it.cacheKey in selected && it.completeness.audioReady }.map { it.cacheKey }
        else -> selected
    }
    fun batch(action: LocalBatchAction) {
        val keys = eligible(action)
        if (keys.isEmpty()) return
        if (action in setOf(LocalBatchAction.DELETE, LocalBatchAction.REMOVE, LocalBatchAction.CANCEL)) pendingBatch = PendingLocalBatch(tab, keys, action)
        else { onBatch(tab, keys, action); exitSelection() }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.downloads_title), Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                if (!selecting) TextButton(onClick = { selecting = true }, enabled = visibleKeys.isNotEmpty()) { Text(stringResource(R.string.local_multiselect)) }
            }
            Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Storage, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(stringResource(R.string.storage_card_title), style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf(R.string.storage_downloads to storage.downloadBytes, R.string.storage_cache to storage.cacheBytes,
                            R.string.storage_available to storage.usableBytes).forEach { (label, bytes) ->
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(humanBytes(bytes), style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(stringResource(label), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .7f))
                            }
                        }
                    }
                }
            }
        }
        stickyHeader {
            Surface(color = MaterialTheme.colorScheme.background) {
                Column {
                TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
                    listOf(stringResource(R.string.downloaded_tab, downloaded.size), stringResource(R.string.cached_tab, cached.size),
                        stringResource(R.string.tasks_tab, tasks.size)).forEachIndexed { index, label ->
                        Tab(selected = tab == index, onClick = { if (tab != index) { exitSelection(); tab = index } }, text = {
                            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        })
                    }
                }
                if (selecting) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.selection_count, selected.size, visibleKeys.size), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                        TextButton(onClick = { selectedKeys = if (selected.size == visibleKeys.size) emptySet() else visibleKeys.toSet() }) {
                            Text(stringResource(if (selected.isNotEmpty() && selected.size == visibleKeys.size) R.string.local_unselect_all else R.string.local_select_all))
                        }
                        IconButton(onClick = { exitSelection() }) { Icon(Icons.Default.Close, stringResource(R.string.clear_selection)) }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        val actions = if (tab == 2) listOf(LocalBatchAction.RETRY, LocalBatchAction.CANCEL, LocalBatchAction.REMOVE)
                            else listOf(LocalBatchAction.PLAY, LocalBatchAction.LATER, LocalBatchAction.DELETE)
                        actions.forEach { action ->
                            TextButton(onClick = { batch(action) }, enabled = eligible(action).isNotEmpty()) {
                                Text(stringResource(when (action) {
                                    LocalBatchAction.PLAY -> R.string.cd_play; LocalBatchAction.LATER -> R.string.play_later
                                    LocalBatchAction.DELETE -> R.string.local_delete_selected; LocalBatchAction.RETRY -> R.string.download_retry
                                    LocalBatchAction.CANCEL -> R.string.download_cancel; LocalBatchAction.REMOVE -> R.string.remove_download_task
                                }))
                            }
                        }
                    }
                }
                }
            }
        }
        when (tab) {
            0 -> {
                if (downloaded.isEmpty()) item {
                    EmptyContent(Icons.Default.DownloadDone, stringResource(R.string.downloads_empty), stringResource(R.string.downloads_empty_detail))
                }
                items(downloaded, key = { it.cacheKey }) { item ->
                    LocalAssetRow(item, artwork(item), current = item.cacheKey == currentKey, isPlaying = isPlaying, onPlay = { onPlay(item) }, onMore = { menuItem = item },
                        selecting = selecting, checked = item.cacheKey in selected, onToggle = { toggle(item.cacheKey) }, onLongClick = { begin(item.cacheKey) })
                }
            }
            1 -> {
                if (cached.isEmpty()) item {
                    EmptyContent(Icons.Default.Cached, stringResource(R.string.cached_empty), stringResource(R.string.cached_empty_detail))
                }
                items(cached, key = { it.cacheKey }) { item ->
                    LocalAssetRow(item, artwork(item), current = item.cacheKey == currentKey, isPlaying = isPlaying, onPlay = { onPlay(item) }, onMore = { menuItem = item },
                        selecting = selecting, checked = item.cacheKey in selected, onToggle = { toggle(item.cacheKey) }, onLongClick = { begin(item.cacheKey) })
                }
            }
            else -> {
                if (tasks.isEmpty()) item {
                    EmptyContent(Icons.Default.DownloadDone, stringResource(R.string.tasks_empty), stringResource(R.string.downloads_empty_detail))
                }
                items(tasks, key = { it.cacheKey }) { record ->
                    TaskRow(record, onRetry = { onRetry(record.cacheKey) }, onCancel = { onCancel(record.cacheKey) }, onRemove = { onRemoveTask(record.cacheKey) },
                        selecting = selecting, checked = record.cacheKey in selected, onToggle = { toggle(record.cacheKey) }, onLongClick = { begin(record.cacheKey) })
                }
            }
        }
    }
    pendingBatch?.let { pending ->
        val available = pending.keys.filter { it in visibleKeys }
        AlertDialog(onDismissRequest = { pendingBatch = null },
            title = { Text(stringResource(when (pending.action) { LocalBatchAction.CANCEL -> R.string.download_cancel; LocalBatchAction.REMOVE -> R.string.remove_download_task; else -> R.string.local_delete_selected })) },
            text = { Text(stringResource(if (pending.tab == 2) R.string.local_batch_tasks_confirm else R.string.local_batch_delete_confirm, available.size)) },
            confirmButton = { TextButton(onClick = { onBatch(pending.tab, available, pending.action); pendingBatch = null; exitSelection() }, enabled = available.isNotEmpty()) { Text(stringResource(R.string.local_confirm)) } },
            dismissButton = { TextButton(onClick = { pendingBatch = null }) { Text(stringResource(R.string.dialog_cancel)) } })
    }
    menuItem?.let { item ->
        ModalBottomSheet(onDismissRequest = { menuItem = null }) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Artwork(artwork(item), Modifier.size(46.dp), seed = item.album.ifBlank { item.title })
                TrackHeading(item.title, listOf(item.artist, item.album).filter { it.isNotBlank() }.joinToString(" · "), Modifier.weight(1f))
            }
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(humanBytes(item.bytes), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(if (item.completeness.audioReady) R.string.asset_playable else R.string.asset_audio_incomplete), style = MaterialTheme.typography.bodySmall)
                sidecarChip(item.completeness.lyrics, R.string.asset_lyrics_none, R.string.asset_lyrics_failed)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                sidecarChip(item.completeness.cover, R.string.asset_cover_none, R.string.asset_cover_failed)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                item.error?.let { Notice(it, error = true) }
            }
            ActionLine(stringResource(R.string.cd_play), Icons.Default.PlayArrow,
                { menuItem = null; onPlay(item) }, enabled = item.completeness.audioReady)
            ActionLine(stringResource(if (item.origin == LocalOrigin.DOWNLOAD) R.string.download_delete else R.string.delete_cache),
                Icons.Default.DeleteOutline, {
                    menuItem = null
                    if (item.origin == LocalOrigin.DOWNLOAD) deletingDownload = item else deletingCache = item
                }, destructive = true)
            Spacer(Modifier.height(16.dp))
        }
    }
    deletingDownload?.let { item ->
        AlertDialog(onDismissRequest = { deletingDownload = null }, title = { Text(stringResource(R.string.download_delete)) },
            text = { Text(stringResource(R.string.delete_download_confirm, item.title)) },
            confirmButton = { TextButton(onClick = { deletingDownload = null; onDeleteDownload(item.cacheKey) }) { Text(stringResource(R.string.confirm_remove)) } },
            dismissButton = { TextButton(onClick = { deletingDownload = null }) { Text(stringResource(R.string.dialog_cancel)) } })
    }
    deletingCache?.let { item ->
        AlertDialog(onDismissRequest = { deletingCache = null }, title = { Text(stringResource(R.string.delete_cache)) },
            text = { Text(stringResource(R.string.delete_cache_confirm, item.title)) },
            confirmButton = { TextButton(onClick = { deletingCache = null; onDeleteCache(item.cacheKey) }) { Text(stringResource(R.string.confirm_remove)) } },
            dismissButton = { TextButton(onClick = { deletingCache = null }) { Text(stringResource(R.string.dialog_cancel)) } })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LocalAssetRow(item: LocalAssetItem, cover: String?, current: Boolean, isPlaying: Boolean, onPlay: () -> Unit, onMore: () -> Unit, selecting: Boolean, checked: Boolean, onToggle: () -> Unit, onLongClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("local_${item.cacheKey}")
        .combinedClickable(onClick = { if (selecting) onToggle() else if (item.completeness.audioReady) onPlay() }, onLongClick = onLongClick).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (selecting) Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Artwork(cover, Modifier.size(46.dp), seed = item.album.ifBlank { item.title }, radius = 8.dp)
        Column(Modifier.weight(1f)) {
            Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall,
                color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            Text(listOf(item.artist, item.album).filter { it.isNotBlank() }.joinToString(" · "), maxLines = 1,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (current) PlaybackIndicator(isPlaying)
        if (!selecting) IconButton(onClick = onMore) { Icon(Icons.Default.MoreHoriz, stringResource(R.string.track_options, item.title)) }
    }
}

@Composable
private fun sidecarChip(state: SidecarState, none: Int, failed: Int): String? = when (state) {
    SidecarState.NONE -> stringResource(none)
    SidecarState.FAILED -> stringResource(failed)
    SidecarState.UNKNOWN, SidecarState.READY -> null
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskRow(record: DownloadRecord, onRetry: () -> Unit, onCancel: () -> Unit, onRemove: () -> Unit, selecting: Boolean, checked: Boolean, onToggle: () -> Unit, onLongClick: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("task_${record.cacheKey}")
            .combinedClickable(onClick = { if (selecting) onToggle() }, onLongClick = onLongClick).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (selecting) Checkbox(checked = checked, onCheckedChange = { onToggle() })
            Artwork(null, Modifier.size(46.dp), seed = record.title, radius = 8.dp)
            Column(Modifier.weight(1f)) {
                Text(record.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOf(record.artist, statusLabel(record.status), humanBytes(record.bytesDownloaded)).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!selecting) when (record.status) {
                DownloadStatus.FAILED, DownloadStatus.CANCELLED, DownloadStatus.PAUSED ->
                    Row {
                        IconButton(onClick = onRetry) { Icon(Icons.Default.Refresh, stringResource(R.string.download_retry)) }
                        IconButton(onClick = onRemove) { Icon(Icons.Default.DeleteOutline, stringResource(R.string.remove_download_task)) }
                    }
                DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING ->
                    IconButton(onClick = onCancel) { Icon(Icons.Default.Close, stringResource(R.string.download_cancel)) }
                else -> Spacer(Modifier.width(48.dp))
            }
        }
        record.error?.let { Text(it, Modifier.padding(start = 72.dp, end = 16.dp, bottom = 8.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        if (record.status == DownloadStatus.DOWNLOADING || record.status == DownloadStatus.VERIFYING) {
            val fraction = record.bytesTotal?.takeIf { it > 0 }?.let { (record.bytesDownloaded.toFloat() / it).coerceIn(0f, 1f) }
            val modifier = Modifier.fillMaxWidth().padding(start = 72.dp, end = 16.dp, bottom = 6.dp).height(2.dp)
            if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = modifier)
            else LinearProgressIndicator(modifier)
        }
    }
}

internal fun humanBytes(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0).toDouble()
    return when {
        safe >= 1024 * 1024 * 1024 -> String.format(java.util.Locale.getDefault(), "%.1f GB", safe / (1024 * 1024 * 1024))
        safe >= 1024 * 1024 -> String.format(java.util.Locale.getDefault(), "%.1f MB", safe / (1024 * 1024))
        else -> String.format(java.util.Locale.getDefault(), "%.0f KB", safe / 1024)
    }
}

@Composable
private fun statusLabel(status: DownloadStatus): String = stringResource(when (status) {
    DownloadStatus.QUEUED -> R.string.download_status_queued
    DownloadStatus.DOWNLOADING -> R.string.download_status_downloading
    DownloadStatus.PAUSED -> R.string.download_status_paused
    DownloadStatus.VERIFYING -> R.string.download_status_verifying
    DownloadStatus.COMPLETED -> R.string.download_status_completed
    DownloadStatus.FAILED -> R.string.download_status_failed
    DownloadStatus.CANCELLED -> R.string.download_status_cancelled
})
