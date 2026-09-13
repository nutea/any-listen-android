package io.github.nutea.anylisten.core.data.session

import io.github.nutea.anylisten.core.model.RepeatMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PersistedPlayback(
    val cacheKeys: List<String> = emptyList(),
    val currentKey: String? = null,
    val shuffled: Boolean = false,
    val repeat: String = RepeatMode.ALL.name,
    val positionMs: Long = 0L,
    val laterKeys: List<String> = emptyList(),
) {
    fun repeatMode(): RepeatMode = runCatching { RepeatMode.valueOf(repeat) }.getOrDefault(RepeatMode.ALL)

    fun encode(): String = format.encodeToString(serializer(), this)

    companion object {
        private val format = Json { ignoreUnknownKeys = true }

        fun decode(raw: String?): PersistedPlayback {
            if (raw.isNullOrBlank()) return PersistedPlayback()
            return runCatching { format.decodeFromString(serializer(), raw) }.getOrDefault(PersistedPlayback())
        }
    }
}
