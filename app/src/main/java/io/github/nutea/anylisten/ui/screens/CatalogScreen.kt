package io.github.nutea.anylisten.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nutea.anylisten.R
import io.github.nutea.anylisten.core.model.*

@Composable
fun CatalogIndexContent(index: MusicCatalog.Index, artists: Boolean, artwork: (Track?) -> String?, onBack: () -> Unit,
    onArtist: (MusicCatalog.ArtistKey) -> Unit, onAlbum: (MusicCatalog.AlbumKey) -> Unit) {
    var query by rememberSaveable(artists) { mutableStateOf("") }
    val keyword = query.trim()
    val visibleArtists = remember(index, keyword) { index.artists.filter { it.name.contains(keyword, ignoreCase = true) } }
    val visibleAlbums = remember(index, keyword) { index.albums.filter { it.name.contains(keyword, ignoreCase = true) || it.artist.contains(keyword, ignoreCase = true) } }
    Column(Modifier.fillMaxSize()) {
        CatalogToolbar(stringResource(if (artists) R.string.catalog_artists else R.string.catalog_albums), onBack)
        OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text(stringResource(if (artists) R.string.catalog_search_artists else R.string.catalog_search_albums)) },
            leadingIcon = { Icon(Icons.Default.Search, null) }, shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp).testTag("catalog_search"))
        LazyColumn(Modifier.testTag("catalog_index"), contentPadding = PaddingValues(bottom = 20.dp)) {
            item { Text(stringResource(R.string.catalog_scope), Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (artists) items(visibleArtists) { artist ->
                Row(Modifier.fillMaxWidth().clickable { onArtist(artist.key) }.padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    ArtistGlyph(Modifier.size(52.dp))
                    Column(Modifier.weight(1f)) {
                        Text(artist.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.catalog_artist_count, artist.tracks.size, artist.albums.size),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else items(visibleAlbums) { album ->
                Row(Modifier.fillMaxWidth().clickable { onAlbum(album.key) }.padding(horizontal = 24.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Artwork(artwork(album.tracks.firstOrNull()), Modifier.size(60.dp), album.name, 12.dp)
                    Column(Modifier.weight(1f)) {
                        Text(album.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(album.artist.ifBlank { stringResource(R.string.artist_unknown) }, style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.track_count, album.tracks.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (if (artists) visibleArtists.isEmpty() else visibleAlbums.isEmpty()) item {
                Text(stringResource(R.string.catalog_empty), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun CatalogDetailContent(artist: MusicCatalog.Artist? = null, album: MusicCatalog.Album? = null,
    artwork: (Track?) -> String?, currentKey: String?, isPlaying: Boolean, offline: Boolean,
    downloaded: (Track) -> Boolean, availableOffline: (Track) -> Boolean,
    onBack: () -> Unit, onArtist: (MusicCatalog.ArtistKey) -> Unit, onAlbum: (MusicCatalog.AlbumKey) -> Unit,
    onPlay: (List<Track>, Track) -> Unit, onDownload: (List<Track>) -> Unit,
    artistPage: Boolean = artist != null, error: String? = null,
) {
    val tracks = artist?.tracks ?: album?.tracks.orEmpty()
    val title = artist?.name ?: album?.name.orEmpty()
    val pending = tracks.filterNot(downloaded)
    val playable = if (offline) tracks.filter(availableOffline) else tracks
    Column(Modifier.fillMaxSize()) {
        CatalogToolbar(stringResource(if (artistPage) R.string.catalog_artist else R.string.catalog_album), onBack)
        LazyColumn(Modifier.weight(1f).testTag("catalog_detail"), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (artist != null) ArtistGlyph(Modifier.size(88.dp))
                        else Artwork(artwork(tracks.firstOrNull()), Modifier.size(152.dp), title, 18.dp)
                        Text(title.ifBlank { stringResource(R.string.catalog_unavailable) }, style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.padding(top = 16.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        if (album != null && album.artist.isNotBlank()) TextButton(onClick = {
                            onArtist(MusicCatalog.ArtistKey(album.key.serverId, album.key.artist))
                        }, modifier = Modifier.testTag("catalog_album_artist")) {
                            Text(album.artist, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp))
                        }
                        Text(if (artist != null) stringResource(R.string.catalog_artist_count, tracks.size, artist.albums.size)
                            else stringResource(R.string.track_count, tracks.size), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    }
                }
                Text(stringResource(R.string.catalog_scope), Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Box(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) { Notice(it, error = true) } }
                if (offline) Text(stringResource(R.string.catalog_offline), Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { playable.firstOrNull()?.let { onPlay(playable, it) } }, enabled = playable.isNotEmpty(), modifier = Modifier.weight(1f).testTag("catalog_play_all"), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)) {
                        Icon(Icons.Default.PlayArrow, null, Modifier.size(20.dp))
                        Text(stringResource(R.string.catalog_play_all), Modifier.padding(start = 4.dp))
                    }
                    FilledTonalButton(onClick = { onDownload(pending) }, enabled = !offline && pending.isNotEmpty(),
                        modifier = Modifier.weight(1f).testTag("catalog_download_all"), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)) {
                        Icon(Icons.Default.Download, null, Modifier.size(20.dp))
                        Text(stringResource(R.string.download_all), Modifier.padding(start = 4.dp))
                    }
                }
            }
            if (!artist?.albums.isNullOrEmpty()) item {
                Text(stringResource(R.string.catalog_albums), Modifier.padding(horizontal = 24.dp, vertical = 12.dp), style = MaterialTheme.typography.titleMedium)
                LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    itemsIndexed(artist!!.albums) { albumIndex, entry ->
                        Column(Modifier.width(132.dp).testTag("catalog_album_$albumIndex").clickable { onAlbum(entry.key) }) {
                            Artwork(artwork(entry.tracks.firstOrNull()), Modifier.size(132.dp), entry.name, 16.dp)
                            Text(entry.name, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(R.string.track_count, entry.tracks.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item { Text(stringResource(R.string.catalog_tracks), Modifier.padding(horizontal = 24.dp, vertical = 16.dp), style = MaterialTheme.typography.titleMedium) }
            itemsIndexed(tracks, key = { _, track -> track.identity.serverProfileId + ":" + track.identity.remoteTrackId }) { number, track ->
                val enabled = !offline || availableOffline(track)
                Row(Modifier.fillMaxWidth().testTag("catalog_track_$number").clickable(enabled = enabled) { onPlay(playable, track) }
                    .padding(start = 24.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                        if (track.cacheKey == currentKey) PlaybackIndicator(isPlaying)
                        else Text((number + 1).toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(track.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .4f))
                        Text(trackSubtitle(track), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    }
                    IconButton(onClick = { onDownload(listOf(track)) }, enabled = !offline && !downloaded(track)) {
                        Icon(if (downloaded(track)) Icons.Default.DownloadDone else Icons.Default.Download,
                            stringResource(if (downloaded(track)) R.string.cd_downloaded else R.string.cd_download), Modifier.size(20.dp))
                    }
                }
            }
            if (tracks.isEmpty()) item { Text(stringResource(R.string.catalog_empty), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun CatalogToolbar(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.catalog_back)) }
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun ArtistGlyph(modifier: Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.Person, null, Modifier.fillMaxSize(.45f), tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}
