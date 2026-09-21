package io.github.nutea.anylisten.core.data.session

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class LyricSettings(
    val showTranslation: Boolean = true,
    val showRomanization: Boolean = true,
    val karaokeEnabled: Boolean = true,
    val offsets: Map<String, Long> = emptyMap(),
) {
    fun encode(): String = Json.encodeToString(serializer(), this)
    companion object {
        fun decode(raw: String?): LyricSettings = raw?.let {
            runCatching { Json.decodeFromString(serializer(), it) }.getOrNull()
        } ?: LyricSettings()
    }
}
