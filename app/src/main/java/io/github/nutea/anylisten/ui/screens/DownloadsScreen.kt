package io.github.nutea.anylisten.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nutea.anylisten.R
import io.github.nutea.anylisten.core.model.DownloadStatus
import io.github.nutea.anylisten.core.model.StorageSummary
import io.github.nutea.anylisten.ui.AppViewModel

@Composable
fun DownloadsScreen(vm: AppViewModel, onOpenPlayer: () -> Unit) {
    val items by vm.downloads.collectAsState()
    val storage by vm.storage.collectAsState()
    LaunchedEffect(Unit) { vm.resumeDownloads() }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.downloads_title), style = MaterialTheme.typography.headlineSmall)
        Text(storageLabel(storage), style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.fillMaxSize()) {
            items(items, key = { it.cacheKey }) { item ->
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Text("${item.artist} · ${statusLabel(item.status)}", style = MaterialTheme.typography.bodySmall)
                    item.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (item.status == DownloadStatus.DOWNLOADING || item.status == DownloadStatus.VERIFYING) {
                        val progress = item.bytesTotal?.takeIf { it > 0 }?.let { item.bytesDownloaded.toFloat() / it }
                        if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (item.status == DownloadStatus.COMPLETED) {
                            OutlinedButton(onClick = {
                                vm.playDownload(item)
                                onOpenPlayer()
                            }) { Text(stringResource(R.string.cd_play)) }
                            OutlinedButton(onClick = { vm.deleteDownload(item.cacheKey) }) { Text(stringResource(R.string.download_delete)) }
                        }
                        if (item.status == DownloadStatus.FAILED || item.status == DownloadStatus.CANCELLED) {
                            OutlinedButton(onClick = { vm.retryDownload(item.cacheKey) }) { Text(stringResource(R.string.download_retry)) }
                        }
                        if (item.status == DownloadStatus.DOWNLOADING || item.status == DownloadStatus.QUEUED) {
                            OutlinedButton(onClick = { vm.cancelDownload(item.cacheKey) }) { Text(stringResource(R.string.download_cancel)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun storageLabel(storage: StorageSummary): String = stringResource(
    R.string.storage_summary,
    storage.downloadBytes / 1024,
    storage.cacheBytes / 1024,
    storage.usableBytes / 1024 / 1024,
)

@Composable
private fun statusLabel(status: DownloadStatus): String = stringResource(
    when (status) {
        DownloadStatus.QUEUED -> R.string.download_status_queued
        DownloadStatus.DOWNLOADING -> R.string.download_status_downloading
        DownloadStatus.PAUSED -> R.string.download_status_paused
        DownloadStatus.VERIFYING -> R.string.download_status_verifying
        DownloadStatus.COMPLETED -> R.string.download_status_completed
        DownloadStatus.FAILED -> R.string.download_status_failed
        DownloadStatus.CANCELLED -> R.string.download_status_cancelled
    },
)
