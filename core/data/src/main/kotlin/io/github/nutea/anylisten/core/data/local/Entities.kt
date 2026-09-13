package io.github.nutea.anylisten.core.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.github.nutea.anylisten.core.model.DownloadRecord
import io.github.nutea.anylisten.core.model.DownloadStatus
import io.github.nutea.anylisten.core.model.IntegrityKind
import io.github.nutea.anylisten.core.model.Playlist
import io.github.nutea.anylisten.core.model.Track
import io.github.nutea.anylisten.core.model.TrackIdentity

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val cacheKey: String,
    val serverProfileId: String,
    val remoteTrackId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long?,
    val coverUrl: String?,
    val fingerprint: String?,
    val playlistId: String?,
    val rawJson: String?,
    val sizeLabel: String?,
    val extension: String?,
) {
    fun toModel() = Track(
        identity = TrackIdentity(serverProfileId, remoteTrackId),
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        coverUrl = coverUrl,
        fingerprint = fingerprint,
        playlistId = playlistId,
        rawJson = rawJson,
        sizeLabel = sizeLabel,
        extension = extension,
    )

    companion object {
        fun from(track: Track) = TrackEntity(
            cacheKey = track.cacheKey,
            serverProfileId = track.identity.serverProfileId,
            remoteTrackId = track.identity.remoteTrackId,
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationMs = track.durationMs,
            coverUrl = track.coverUrl,
            fingerprint = track.fingerprint,
            playlistId = track.playlistId,
            rawJson = track.rawJson,
            sizeLabel = track.sizeLabel,
            extension = track.extension,
        )
    }
}

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val trackCount: Int,
    val coverUrl: String?,
    val canMutateOnline: Boolean,
    val refreshedAtEpochMs: Long,
) {
    fun toModel() = Playlist(id, name, type, trackCount, coverUrl, canMutateOnline = canMutateOnline)

    companion object {
        fun from(item: Playlist, refreshedAt: Long) = PlaylistEntity(
            id = item.id,
            name = item.name,
            type = item.type,
            trackCount = item.trackCount,
            coverUrl = item.coverUrl,
            canMutateOnline = item.canMutateOnline,
            refreshedAtEpochMs = refreshedAt,
        )
    }
}

@Entity(tableName = "playlist_entries", primaryKeys = ["playlistId", "entryId"])
data class PlaylistEntryEntity(
    val playlistId: String,
    val entryId: String,
    val trackCacheKey: String,
    val position: Int,
)

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val cacheKey: String,
    val serverProfileId: String,
    val remoteTrackId: String,
    val title: String,
    val artist: String,
    val status: String,
    val bytesDownloaded: Long,
    val bytesTotal: Long?,
    val filePath: String?,
    val error: String?,
    val integrity: String?,
    val isolated: Boolean,
    val fingerprint: String?,
) {
    fun toModel() = DownloadRecord(
        cacheKey = cacheKey,
        identity = TrackIdentity(serverProfileId, remoteTrackId),
        title = title,
        artist = artist,
        status = DownloadStatus.valueOf(status),
        bytesDownloaded = bytesDownloaded,
        bytesTotal = bytesTotal,
        filePath = filePath,
        error = error,
        integrity = integrity?.let { IntegrityKind.valueOf(it) },
        isolated = isolated,
        fingerprint = fingerprint,
    )

    companion object {
        fun from(record: DownloadRecord) = DownloadEntity(
            cacheKey = record.cacheKey,
            serverProfileId = record.identity.serverProfileId,
            remoteTrackId = record.identity.remoteTrackId,
            title = record.title,
            artist = record.artist,
            status = record.status.name,
            bytesDownloaded = record.bytesDownloaded,
            bytesTotal = record.bytesTotal,
            filePath = record.filePath,
            error = record.error,
            integrity = record.integrity?.name,
            isolated = record.isolated,
            fingerprint = record.fingerprint,
        )
    }
}
