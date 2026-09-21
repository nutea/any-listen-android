package io.github.nutea.anylisten.core.model

/** Positive offset delays the lyrics; negative offset displays them earlier. */
object LyricTiming {
    const val LIMIT_MS = 60_000L
    fun position(playbackMs: Long, offsetMs: Long): Long = playbackMs - offsetMs
    fun seek(lineMs: Long, offsetMs: Long): Long = (lineMs + offsetMs).coerceAtLeast(0)
    fun interpolate(positionMs: Long, sampledAtMs: Long, nowMs: Long, speed: Float, durationMs: Long): Long {
        val elapsed = if (sampledAtMs > 0) (nowMs - sampledAtMs).coerceIn(0, 500) else 0L
        return (positionMs + (elapsed * speed).toLong()).coerceAtMost(durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
    }
    fun clamp(offsetMs: Long): Long = offsetMs.coerceIn(-LIMIT_MS, LIMIT_MS)
}
