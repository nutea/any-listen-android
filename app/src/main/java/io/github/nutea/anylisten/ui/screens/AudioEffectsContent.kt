package io.github.nutea.anylisten.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.nutea.anylisten.R
import io.github.nutea.anylisten.core.model.*
import kotlin.math.roundToInt

@Composable
internal fun AudioEffectsContent(config: AudioEffectsSettings, state: AudioEffectsState,
    update: (AudioEffectsSettings) -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)
        .testTag("audio_effects_sheet")) {
        Text(stringResource(R.string.audio_effects_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.audio_effects_local), Modifier.padding(top = 6.dp, bottom = 20.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.playback_speed), style = MaterialTheme.typography.titleMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                FilterChip(config.speed == speed, { update(config.copy(speed = speed)) },
                    label = { Text("${speed}×") }, modifier = Modifier.testTag("speed_$speed"))
            }
        }
        Text(stringResource(R.string.speed_pitch_preserved), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.padding(vertical = 20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.equalizer), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(config.enabled, { update(config.copy(enabled = it)) }, enabled = state.available,
                modifier = Modifier.testTag("equalizer_enable"))
        }
        if (!state.available) Text(stringResource(R.string.equalizer_unavailable), Modifier.padding(vertical = 12.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AudioEffectsSettings.PRESETS.forEach { preset ->
                FilterChip(config.preset == preset, { update(config.copy(preset = preset)) },
                    enabled = state.available, label = { Text(stringResource(when (preset) {
                        "pop" -> R.string.eq_pop; "rock" -> R.string.eq_rock; "bass" -> R.string.eq_bass
                        "vocal" -> R.string.eq_vocal; "custom" -> R.string.eq_custom; else -> R.string.eq_flat
                    })) }, modifier = Modifier.testTag("eq_preset_$preset"))
            }
        }
        state.bands.forEach { band ->
            val gain = if (config.preset == "custom") config.gains[band.frequencyHz] ?: 0 else presetGain(config.preset, band.frequencyHz)
            var draft by remember(gain, band.frequencyHz) { mutableFloatStateOf(gain.coerceIn(state.minGain, state.maxGain).toFloat()) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (band.frequencyHz < 1000) "${band.frequencyHz} Hz" else "${band.frequencyHz / 1000f} kHz",
                    Modifier.width(72.dp), style = MaterialTheme.typography.labelMedium)
                Slider(draft, { draft = it }, Modifier.weight(1f).testTag("eq_band_${band.frequencyHz}"),
                    enabled = state.available && config.enabled && state.minGain < state.maxGain,
                    valueRange = state.minGain.toFloat()..state.maxGain.toFloat(),
                    onValueChangeFinished = {
                        val gains = state.bands.associate { it.frequencyHz to (if (config.preset == "custom") config.gains[it.frequencyHz] ?: 0 else presetGain(config.preset, it.frequencyHz)) }.toMutableMap()
                        gains[band.frequencyHz] = draft.roundToInt()
                        update(config.copy(preset = "custom", gains = gains))
                    })
                Text("%.1f dB".format(draft / 100), Modifier.width(58.dp), style = MaterialTheme.typography.labelSmall)
            }
        }
        TextButton({ update(AudioEffectsSettings()) }, modifier = Modifier.testTag("effects_reset")) {
            Text(stringResource(R.string.effects_reset))
        }
        Spacer(Modifier.height(24.dp))
    }
}
