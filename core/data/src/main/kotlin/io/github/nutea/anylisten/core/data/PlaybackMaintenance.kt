package io.github.nutea.anylisten.core.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Maintenance yields immediately when playback starts and resumes only while idle. */
class PlaybackMaintenance {
    private val playing = MutableStateFlow(false)
    private val serial = Mutex()
    val active: Boolean get() = playing.value
    fun setActive(value: Boolean) { playing.value = value }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun run(block: suspend () -> Unit) = serial.withLock {
        playing.mapLatest { active ->
            if (active) false else { block(); true }
        }.first { it }
    }
}
