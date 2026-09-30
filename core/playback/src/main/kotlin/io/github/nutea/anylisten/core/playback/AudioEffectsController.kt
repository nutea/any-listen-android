package io.github.nutea.anylisten.core.playback

import android.media.audiofx.Equalizer
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import io.github.nutea.anylisten.core.model.*

/** Owns the effect for this player's session only. Device failures leave normal playback intact. */
class AudioEffectsController(private val player: ExoPlayer,
    private val publish: (AudioEffectsState) -> Unit) {
    private var effect: Equalizer? = null
    private var sessionId = 0
    private var settings = AudioEffectsSettings()
    fun attach(id: Int) {
        if (id == sessionId && effect != null) return
        releaseEffect(); sessionId = id
        if (id > 0) {
            try {
                effect = Equalizer(0, id).also { eq ->
                    eq.setControlStatusListener { _, _ -> apply() }
                }
            } catch (_: Exception) { effect = null }
        }
        apply()
    }
    fun update(value: AudioEffectsSettings) { settings = value.sanitized(); apply() }
    private fun apply() {
        player.playbackParameters = PlaybackParameters(settings.speed, 1f)
        val eq = effect
        try {
            if (eq == null || !eq.hasControl()) { publish(AudioEffectsState(settings)); return }
            val range = eq.bandLevelRange
            val bands = (0 until eq.numberOfBands.toInt()).map { index ->
                val band = index.toShort()
                val hz = eq.getCenterFreq(band) / 1000
                val gain = (if (settings.preset == "custom") settings.gains[hz] ?: 0
                    else presetGain(settings.preset, hz)).coerceIn(range[0].toInt(), range[1].toInt())
                eq.setBandLevel(band, gain.toShort())
                EqualizerBand(hz, gain)
            }
            check(eq.setEnabled(settings.enabled) == android.media.audiofx.AudioEffect.SUCCESS)
            publish(AudioEffectsState(settings, bands, range[0].toInt(), range[1].toInt(), bands.isNotEmpty()))
        } catch (_: Exception) { releaseEffect(); publish(AudioEffectsState(settings)) }
    }
    private fun releaseEffect() { effect?.let { runCatching { it.release() } }; effect = null }
    fun release() { releaseEffect(); publish(AudioEffectsState(settings)) }
}
