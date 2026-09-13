package io.github.nutea.anylisten.core.model

data class ServerProfile(
    val id: String,
    val baseUrl: String,
    val serverId: String = "",
    val serverName: String = "",
    val reportedVersion: String = "",
    val lastRefreshEpochMs: Long? = null,
)

data class TrackIdentity(
    val serverProfileId: String,
    val remoteTrackId: String,
) {
    fun cacheKey(fingerprint: String?): String {
        val suffix = fingerprint?.takeIf { it.isNotBlank() } ?: "unknown"
        return listOf(serverProfileId, remoteTrackId, suffix).joinToString(":")
    }
}

data class Track(
    val identity: TrackIdentity,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long?,
    val coverUrl: String? = null,
    val fingerprint: String? = null,
    val isLocalOnServer: Boolean = false,
    val playlistId: String? = null,
    val rawJson: String? = null,
    val sizeLabel: String? = null,
    val extension: String? = null,
) {
    val cacheKey: String get() = identity.cacheKey(fingerprint)
}

data class Playlist(
    val id: String,
    val name: String,
    val type: String,
    val trackCount: Int,
    val coverUrl: String? = null,
    val revision: String? = null,
    val canMutateOnline: Boolean = true,
)

data class PlaylistEntry(
    val playlistId: String,
    val entryId: String,
    val track: Track,
    val position: Int,
)

data class LyricLine(
    val timeMs: Long,
    val text: String,
)

data class Lyrics(
    val lines: List<LyricLine>,
    val raw: String,
) {
    fun lineAt(positionMs: Long): String? {
        if (lines.isEmpty()) return null
        return lines.lastOrNull { it.timeMs <= positionMs }?.text?.takeIf { it.isNotBlank() }
    }

    fun sessionPayload(): String = toTimedLrc()

    fun toTimedLrc(): String {
        val trimmed = raw.trim()
        if (TIMESTAMP.matches(trimmed)) return trimmed
        if (lines.isEmpty()) return trimmed
        return lines.joinToString("\n") { line ->
            val minutes = line.timeMs / 60_000
            val seconds = (line.timeMs % 60_000) / 1000
            val millis = line.timeMs % 1000
            "[%02d:%02d.%03d]%s".format(minutes, seconds, millis, line.text)
        }
    }

    private companion object {
        val TIMESTAMP = Regex("""(?s).*\[\d{1,2}:\d{2}(?:[.:]\d{1,3})?\].*""")
    }
}

data class MediaResource(
    val url: String,
    val quality: String = "",
    val requiresSameOriginCookie: Boolean = false,
)

enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    VERIFYING,
    COMPLETED,
    PAUSED,
    FAILED,
    CANCELLED,
}

enum class IntegrityKind {
    REMOTE_DIGEST,
    LENGTH_AND_DECODE,
    NO_REMOTE_CHECKSUM,
}

data class DownloadRecord(
    val cacheKey: String,
    val identity: TrackIdentity,
    val title: String,
    val artist: String,
    val status: DownloadStatus,
    val bytesDownloaded: Long = 0,
    val bytesTotal: Long? = null,
    val filePath: String? = null,
    val error: String? = null,
    val integrity: IntegrityKind? = null,
    val isolated: Boolean = false,
    val fingerprint: String? = null,
)

enum class RepeatMode { OFF, ALL, ONE }

data class PlaybackSnapshot(
    val queueIds: List<String>,
    val currentIndex: Int,
    val positionMs: Long,
    val repeat: RepeatMode,
    val shuffled: Boolean,
)

data class LibrarySnapshot(
    val playlists: List<Playlist>,
    val tracksByPlaylist: Map<String, List<Track>>,
    val refreshedAtEpochMs: Long,
    val offline: Boolean,
)

data class StorageSummary(
    val downloadBytes: Long,
    val cacheBytes: Long,
    val usableBytes: Long,
)

object IntervalParser {
    fun toMillis(interval: String?): Long? {
        if (interval.isNullOrBlank()) return null
        val parts = interval.trim().split(":")
        if (parts.size !in 2..3) return null
        return try {
            val values = parts.map { it.toLong() }
            when (values.size) {
                2 -> (values[0] * 60 + values[1]) * 1000
                else -> (values[0] * 3600 + values[1] * 60 + values[2]) * 1000
            }
        } catch (_: NumberFormatException) {
            null
        }
    }
}
