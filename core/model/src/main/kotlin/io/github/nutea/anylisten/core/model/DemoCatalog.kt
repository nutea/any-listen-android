package io.github.nutea.anylisten.core.model

object DemoCatalog {
    const val PROFILE_ID = "demo"

    fun library(): LibrarySnapshot {
        val playlist = Playlist(
            id = "demo-local",
            name = "Demo offline list",
            type = "demo",
            trackCount = 3,
            canMutateOnline = false,
        )
        val tracks = listOf(
            demoTrack("1", "Night Bus", "Local Sample", "Demo"),
            demoTrack("2", "Harbor Lights", "Local Sample", "Demo"),
            demoTrack("3", "Quiet Platform", "Local Sample", "Demo"),
        )
        return LibrarySnapshot(
            playlists = listOf(playlist),
            tracksByPlaylist = mapOf(playlist.id to tracks),
            refreshedAtEpochMs = 0L,
            offline = true,
        )
    }

    private fun demoTrack(id: String, title: String, artist: String, album: String) = Track(
        identity = TrackIdentity(PROFILE_ID, id),
        title = title,
        artist = artist,
        album = album,
        durationMs = 180_000,
        fingerprint = "demo-$id",
        playlistId = "demo-local",
    )
}
