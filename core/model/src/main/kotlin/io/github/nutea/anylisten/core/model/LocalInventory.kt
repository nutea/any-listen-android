package io.github.nutea.anylisten.core.model

enum class SidecarState { READY, NONE, FAILED, UNKNOWN }

enum class LocalOrigin { DOWNLOAD, CACHE }

data class OfflineCatalogEntry(
    val cacheKey: String,
    val title: String,
    val artist: String,
    val album: String,
    val coverUrl: String? = null,
    val serverProfileId: String = "",
    val remoteTrackId: String = "",
    val fingerprint: String? = null,
) {
    fun toTrack(): Track = Track(
        identity = TrackIdentity(
            serverProfileId = serverProfileId.ifBlank { "offline" },
            remoteTrackId = remoteTrackId.ifBlank { cacheKey },
        ),
        title = title,
        artist = artist,
        album = album,
        durationMs = null,
        coverUrl = coverUrl,
        fingerprint = fingerprint,
    )

    companion object {
        fun from(track: Track) = OfflineCatalogEntry(
            cacheKey = track.cacheKey,
            title = track.title,
            artist = track.artist,
            album = track.album,
            coverUrl = track.coverUrl,
            serverProfileId = track.identity.serverProfileId,
            remoteTrackId = track.identity.remoteTrackId,
            fingerprint = track.fingerprint,
        )
    }
}

data class AssetCompleteness(
    val audioReady: Boolean,
    val lyrics: SidecarState = SidecarState.UNKNOWN,
    val cover: SidecarState = SidecarState.UNKNOWN,
)

data class LocalAssetItem(
    val cacheKey: String,
    val title: String,
    val artist: String,
    val album: String,
    val track: Track,
    val origin: LocalOrigin,
    val completeness: AssetCompleteness,
    val bytes: Long = 0,
    val error: String? = null,
)

object LocalInventory {
    fun cached(
        catalog: List<OfflineCatalogEntry>,
        inspect: (String) -> AssetCompleteness,
        completedDownloadKeys: Set<String>,
        libraryTracks: Map<String, Track> = emptyMap(),
        audioBytes: (String) -> Long = { 0 },
    ): List<LocalAssetItem> = catalog.mapNotNull { entry ->
        val completeness = inspect(entry.cacheKey)
        if (!completeness.audioReady || entry.cacheKey in completedDownloadKeys) return@mapNotNull null
        val track = libraryTracks[entry.cacheKey] ?: entry.toTrack()
        LocalAssetItem(
            cacheKey = entry.cacheKey,
            title = track.title,
            artist = track.artist,
            album = track.album,
            track = track,
            origin = LocalOrigin.CACHE,
            completeness = completeness,
            bytes = audioBytes(entry.cacheKey),
        )
    }

    fun downloaded(
        records: List<DownloadRecord>,
        inspect: (String) -> AssetCompleteness,
        fileReady: (DownloadRecord) -> Boolean,
        libraryTracks: Map<String, Track> = emptyMap(),
    ): List<LocalAssetItem> = records.filter { it.status == DownloadStatus.COMPLETED }.map { record ->
        val sidecar = inspect(record.cacheKey)
        val track = libraryTracks[record.cacheKey] ?: Track(
            identity = record.identity,
            title = record.title,
            artist = record.artist,
            album = "",
            durationMs = null,
            fingerprint = record.fingerprint,
        )
        LocalAssetItem(
            cacheKey = record.cacheKey,
            title = track.title,
            artist = track.artist,
            album = track.album,
            track = track,
            origin = LocalOrigin.DOWNLOAD,
            completeness = sidecar.copy(audioReady = fileReady(record) || sidecar.audioReady),
            bytes = record.bytesDownloaded,
            error = record.error,
        )
    }

    fun tasks(records: List<DownloadRecord>): List<DownloadRecord> =
        records.filter { it.status != DownloadStatus.COMPLETED }
}
