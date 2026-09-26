package io.github.nutea.anylisten.core.model

enum class PlayerStyle {
    CLASSIC, IMMERSIVE, COSMIC_DUST, PULSE_PARTICLES, CRYSTAL_WAVE;

    val hasWave: Boolean get() = this == COSMIC_DUST || this == PULSE_PARTICLES || this == CRYSTAL_WAVE
    val isDarkPlayer: Boolean get() = this != CLASSIC

    companion object {
        fun decode(value: String?): PlayerStyle = entries.firstOrNull { it.name == value } ?: CLASSIC
    }
}
