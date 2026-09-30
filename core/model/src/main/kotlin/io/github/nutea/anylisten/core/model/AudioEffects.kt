package io.github.nutea.anylisten.core.model

import kotlinx.serialization.Serializable

@Serializable
data class AudioEffectsSettings(val speed: Float = 1f, val enabled: Boolean = false,
    val preset: String = "flat", val gains: Map<Int, Int> = emptyMap()) {
    fun sanitized() = copy(speed = if (speed.isFinite()) speed.coerceIn(.5f, 2f) else 1f,
        preset = preset.takeIf { it in PRESETS } ?: "flat", gains = gains.filterKeys { it > 0 }
            .mapValues { it.value.coerceIn(-1500, 1500) })
    companion object { val PRESETS = listOf("flat", "pop", "rock", "vocal", "bass", "custom") }
}

data class EqualizerBand(val frequencyHz: Int, val gain: Int)
data class AudioEffectsState(val settings: AudioEffectsSettings = AudioEffectsSettings(),
    val bands: List<EqualizerBand> = emptyList(), val minGain: Int = -1500, val maxGain: Int = 1500,
    val available: Boolean = false)

fun presetGain(preset: String, hz: Int): Int = when (preset) {
    "bass" -> if (hz < 250) 500 else if (hz < 1000) 100 else 0
    "vocal" -> if (hz in 500..4000) 300 else -100
    "rock" -> if (hz < 250 || hz > 4000) 400 else -150
    "pop" -> if (hz in 250..4000) 250 else 100
    else -> 0
}
