package io.github.nutea.anylisten.core.model

data class ListeningStat(val trackKey: String, val listenedMs: Long = 0, val plays: Long = 0,
    val lastPlayedAt: Long = 0, val firstPlayedAt: Long = 0)
data class ListeningDelta(val trackKey: String, val listenedMs: Long, val plays: Long)

/** Wall-clock listening time, independent of seeks and playback speed. Only advances while audible. */
class ListeningAccumulator {
    private var key: String? = null
    private var last = 0L
    private var playing = false
    private var total = 0L
    private var counted = false
    private var threshold = 30_000L
    fun sample(now: Long, newKey: String?, audible: Boolean, durationMs: Long = 0,
        newOccurrence: Boolean = false): ListeningDelta? {
        if (newKey == key && durationMs > 0) threshold = minOf(30_000L, (durationMs / 2).coerceAtLeast(1000))
        val elapsed = if (playing) (now - last).coerceAtLeast(0) else 0L
        val oldKey = key
        total += elapsed
        val count = if (!counted && total >= threshold && oldKey != null) 1L else 0L
        if (count > 0) counted = true
        val delta = oldKey?.takeIf { elapsed > 0 || count > 0 }?.let { ListeningDelta(it, elapsed, count) }
        if (newKey != key || newOccurrence) {
            key = newKey; total = 0; counted = false
            threshold = if (durationMs > 0) minOf(30_000L, (durationMs / 2).coerceAtLeast(1000)) else 30_000L
        }
        last = now; playing = audible && newKey != null
        return delta
    }
}

enum class SmartLibrary { ALL, FREQUENT, REDISCOVER, RANDOM }
object SmartTracks {
    fun select(tracks: List<Track>, stats: List<ListeningStat>, mode: SmartLibrary,
        now: Long, randomSeed: Int = 0): List<Track> {
        val unique = tracks.distinctBy { it.cacheKey }
        val byKey = stats.associateBy { it.trackKey }
        return when (mode) {
            SmartLibrary.ALL -> unique
            SmartLibrary.FREQUENT -> unique.filter { (byKey[it.cacheKey]?.plays ?: 0) > 0 }
                .sortedWith(compareByDescending<Track> { byKey[it.cacheKey]?.plays ?: 0 }
                    .thenByDescending { byKey[it.cacheKey]?.listenedMs ?: 0 })
            SmartLibrary.REDISCOVER -> unique.filter { byKey[it.cacheKey]?.let { s -> s.plays > 0 && now - s.lastPlayedAt >= 30L * 86400000 } == true }
                .sortedBy { byKey[it.cacheKey]?.lastPlayedAt }
            SmartLibrary.RANDOM -> unique.shuffled(kotlin.random.Random(randomSeed)).take(30)
        }
    }
}
