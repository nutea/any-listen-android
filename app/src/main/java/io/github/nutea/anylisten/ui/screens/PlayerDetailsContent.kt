package io.github.nutea.anylisten.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nutea.anylisten.R
import io.github.nutea.anylisten.core.model.AudioEffectsSettings
import io.github.nutea.anylisten.ui.PlayerUiState

@Composable
internal fun PlayerFavorite(favorite: Boolean, offline: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = !offline) {
        Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            stringResource(if (favorite) R.string.cd_unfavorite else R.string.cd_favorite),
            Modifier.size(28.dp), tint = if (favorite && !offline) MaterialTheme.colorScheme.primary else LocalContentColor.current)
    }
}

@Composable
internal fun PlayerDetailsContent(state: PlayerUiState, cover: String?, downloaded: Boolean,
    offline: Boolean, effects: AudioEffectsSettings, open: (String) -> Unit,
    addToPlaylist: () -> Unit, artist: () -> Unit, album: () -> Unit) {
    val track = state.track ?: return
    Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).verticalScroll(rememberScrollState())
        .testTag("player_details_sheet").padding(bottom = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Artwork(cover, Modifier.size(60.dp), seed = track.album.ifBlank { track.title }, radius = 14.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(track.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(stringResource(when {
                    downloaded -> R.string.player_details_downloaded
                    state.availableOffline -> R.string.player_details_cached
                    else -> R.string.audio_offline_unavailable
                }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DetailShortcut(Icons.Default.Bedtime, stringResource(R.string.sleep_timer), "sleep_timer_button",
                Modifier.weight(1f)) { open("sleep") }
            DetailShortcut(Icons.AutoMirrored.Filled.PlaylistAdd, stringResource(R.string.cd_add_to_playlist), "player_add_to_playlist",
                Modifier.weight(1f), enabled = !offline, onClick = addToPlaylist)
            DetailShortcut(Icons.Default.Palette, stringResource(R.string.player_style), "player_style_button",
                Modifier.weight(1f)) { open("style") }
        }
        HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
        DetailRow(Icons.Default.Album, stringResource(R.string.catalog_album),
            track.album.ifBlank { stringResource(R.string.player_metadata_missing) }, "player_album",
            enabled = track.album.isNotBlank(), onClick = album)
        DetailRow(Icons.Default.PersonOutline, stringResource(R.string.catalog_artist),
            track.artist.ifBlank { stringResource(R.string.artist_unknown) }, "player_details_artist",
            enabled = track.artist.isNotBlank(), onClick = artist)
        DetailRow(Icons.Default.GraphicEq, stringResource(R.string.audio_effects_title),
            stringResource(R.string.player_effects_summary, effects.speed), "audio_effects_button") { open("effects") }
        DetailRow(Icons.Default.Info, stringResource(R.string.audio_info),
            stringResource(R.string.player_audio_summary), "audio_info_button") { open("audio") }
        DetailRow(Icons.Default.Lyrics, stringResource(R.string.lyric_settings),
            stringResource(R.string.player_lyrics_summary), "lyrics_settings_button") { open("lyrics") }
    }
}

@Composable
private fun DetailShortcut(icon: ImageVector, label: String, tag: String, modifier: Modifier,
    enabled: Boolean = true, onClick: () -> Unit) {
    val color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)
    Column(modifier.testTag(tag).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
            Box(Modifier.size(58.dp), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(26.dp), tint = color) }
        }
        Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis, color = color)
    }
}

@Composable
private fun DetailRow(icon: ImageVector, label: String, value: String, tag: String,
    enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().testTag(tag).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .heightIn(min = 76.dp).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (enabled) Icon(Icons.Default.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
