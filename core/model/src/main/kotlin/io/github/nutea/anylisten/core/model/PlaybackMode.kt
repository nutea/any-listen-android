package io.github.nutea.anylisten.core.model

enum class PlaybackMode {
    LIST_LOOP,
    RANDOM,
    SEQUENCE,
    SINGLE,
    ;

    fun next(): PlaybackMode = when (this) {
        LIST_LOOP -> RANDOM
        RANDOM -> SEQUENCE
        SEQUENCE -> SINGLE
        SINGLE -> LIST_LOOP
    }

    val shuffled: Boolean get() = this == RANDOM

    val repeat: RepeatMode
        get() = when (this) {
            LIST_LOOP, RANDOM -> RepeatMode.ALL
            SEQUENCE -> RepeatMode.OFF
            SINGLE -> RepeatMode.ONE
        }

    companion object {
        fun from(repeat: RepeatMode, shuffled: Boolean): PlaybackMode = when {
            repeat == RepeatMode.ONE -> SINGLE
            shuffled -> RANDOM
            repeat == RepeatMode.ALL -> LIST_LOOP
            else -> SEQUENCE
        }
    }
}
