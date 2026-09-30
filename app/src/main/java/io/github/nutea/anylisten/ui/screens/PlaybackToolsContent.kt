package io.github.nutea.anylisten.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.nutea.anylisten.R
import io.github.nutea.anylisten.core.model.AudioLocation
import io.github.nutea.anylisten.core.model.SleepTimerState
import io.github.nutea.anylisten.ui.PlayerUiState
import java.util.Locale

@Composable
internal fun sleepTimerLabel(timer: SleepTimerState, compact: Boolean = false): String = when {
    timer.endOfTrack -> stringResource(if (compact) R.string.sleep_after_track_short else R.string.sleep_after_track)
    timer.remainingMs != null -> if (compact) formatMs(timer.remainingMs ?: 0)
        else stringResource(R.string.sleep_remaining, formatMs(timer.remainingMs ?: 0))
    else -> stringResource(R.string.sleep_timer)
}

@Composable
internal fun SleepTimerContent(state: PlayerUiState, actions: PlayerActions, dismiss: () -> Unit) {
    var custom by rememberSaveable { mutableStateOf("30") }
    val minutes = custom.toLongOrNull()?.takeIf { it in 1..1440 }
    val ready = state.queueReady || state.isPlaying || state.playWhenReady
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp).testTag("sleep_timer_sheet"),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.sleep_timer), style = MaterialTheme.typography.titleLarge)
        Text(if (!ready) stringResource(R.string.sleep_start_playback) else if (state.sleepTimer.active)
            sleepTimerLabel(state.sleepTimer) else stringResource(R.string.sleep_timer_detail),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(15L, 30L, 60L).forEach { value ->
                OutlinedButton(onClick = { actions.sleepStart(value * 60_000); dismiss() }, enabled = ready,
                    modifier = Modifier.weight(1f).testTag("sleep_preset_$value"), contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text(stringResource(R.string.sleep_minutes, value.toInt()))
                }
            }
        }
        OutlinedTextField(custom, { if (it.length <= 4 && it.all(Char::isDigit)) custom = it },
            label = { Text(stringResource(R.string.sleep_custom_minutes)) },
            supportingText = { Text(stringResource(R.string.sleep_custom_range)) },
            isError = minutes == null, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().testTag("sleep_custom_input"))
        Button(onClick = { minutes?.let { actions.sleepStart(it * 60_000); dismiss() } },
            enabled = ready && minutes != null, modifier = Modifier.fillMaxWidth().testTag("sleep_custom_start")) {
            Text(stringResource(R.string.sleep_start))
        }
        OutlinedButton(onClick = { actions.sleepAfterTrack(); dismiss() }, enabled = ready,
            modifier = Modifier.fillMaxWidth().testTag("sleep_after_track")) { Text(stringResource(R.string.sleep_after_track)) }
        if (state.sleepTimer.active) TextButton(onClick = { actions.sleepCancel(); dismiss() },
            modifier = Modifier.fillMaxWidth().testTag("sleep_cancel")) { Text(stringResource(R.string.sleep_cancel)) }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
internal fun AudioInfoContent(state: PlayerUiState, downloaded: Boolean) {
    val info = state.audioInfo.takeIf { it.trackKey == state.track?.cacheKey }
    val track = state.track
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp).testTag("audio_info_sheet"),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.audio_info), style = MaterialTheme.typography.titleLarge)
        track?.let { Text(it.title, style = MaterialTheme.typography.titleMedium) }
        val unknown = stringResource(R.string.audio_unavailable)
        val codec = info?.mimeType?.let { when (it) {
            "audio/raw" -> "PCM"; "audio/flac" -> "FLAC"; "audio/mpeg" -> "MP3"; "audio/mp4a-latm" -> "AAC"
            "audio/opus" -> "Opus"; "audio/vorbis" -> "Vorbis"; "audio/alac" -> "ALAC"; else -> it.removePrefix("audio/")
        } }
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                AudioInfoRow(R.string.audio_file_format, track?.extension?.takeIf(String::isNotBlank)?.uppercase(Locale.ROOT) ?: unknown)
                AudioInfoRow(R.string.audio_codec, codec ?: unknown)
                AudioInfoRow(R.string.audio_bitrate, info?.bitrate?.let { stringResource(R.string.audio_kbps, it / 1000) } ?: unknown)
                AudioInfoRow(R.string.audio_sample_rate, info?.sampleRate?.let { stringResource(R.string.audio_hz, it) } ?: unknown)
                AudioInfoRow(R.string.audio_channels, info?.channels?.let { pluralStringResource(R.plurals.audio_channel_count, it, it) } ?: unknown)
            }
        }
        AudioInfoRow(R.string.audio_source, stringResource(when (info?.location) {
            AudioLocation.DOWNLOAD -> R.string.audio_source_download
            AudioLocation.CACHE -> R.string.audio_source_cache
            AudioLocation.SERVER -> R.string.audio_source_server
            else -> R.string.audio_unavailable
        }))
        AudioInfoRow(R.string.audio_offline, stringResource(if (downloaded || state.availableOffline) R.string.audio_offline_available else R.string.audio_offline_unavailable))
        Text(stringResource(R.string.audio_info_detail), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun AudioInfoRow(label: Int, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}
