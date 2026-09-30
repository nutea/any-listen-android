package io.github.nutea.anylisten.core.model

data class SleepTimerState(val remainingMs: Long? = null, val endOfTrack: Boolean = false) {
    val active get() = remainingMs != null || endOfTrack
}

enum class AudioLocation { UNKNOWN, SERVER, DOWNLOAD, CACHE }

/** Actual decoder input format; unavailable values stay absent, never inferred from quality labels. */
data class AudioInfo(
    val trackKey: String? = null,
    val mimeType: String? = null,
    val bitrate: Int? = null,
    val sampleRate: Int? = null,
    val channels: Int? = null,
    val location: AudioLocation = AudioLocation.UNKNOWN,
)

/** Reorder within a queue section, preserving the current slot and priority membership. */
object QueueReorder {
    fun move(queue: List<Track>, currentKey: String?, laterKeys: List<String>, from: String, to: String): PlayLater.Result? {
        if (from == to || from == currentKey || to == currentKey) return null
        val section = QueueSections.from(queue, currentKey, laterKeys)
        val priority = section.later.any { it.cacheKey == from }
        val group = if (priority) section.later else section.rest
        val source = group.indexOfFirst { it.cacheKey == from }
        val target = group.indexOfFirst { it.cacheKey == to }
        if (source < 0 || target < 0) return null
        val moved = group.toMutableList().apply { add(target, removeAt(source)) }
        val keys = group.map { it.cacheKey }.toSet()
        val iterator = moved.iterator()
        return PlayLater.Result(queue.map { if (it.cacheKey in keys) iterator.next() else it },
            if (priority) moved.map { it.cacheKey } else laterKeys)
    }
}
