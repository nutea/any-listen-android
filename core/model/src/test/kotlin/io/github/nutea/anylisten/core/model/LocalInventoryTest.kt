package io.github.nutea.anylisten.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalInventoryTest {
    private val track = Track(TrackIdentity("server", "song"), "Song", "Artist", "Album", 120_000)
    private val catalog = OfflineCatalogEntry.from(track)

    @Test
    fun cachedListRequiresCompleteAudioAndKnownMetadata() {
        val orphan = OfflineCatalogEntry("orphan:key:x", "Should not appear", "?", "?")
        val listed = LocalInventory.cached(
            catalog = listOf(catalog, orphan),
            inspect = { key ->
                if (key == track.cacheKey) AssetCompleteness(true, SidecarState.NONE, SidecarState.FAILED)
                else AssetCompleteness(false)
            },
            completedDownloadKeys = emptySet(),
        )
        assertEquals(listOf(track.cacheKey), listed.map { it.cacheKey })
        assertEquals(SidecarState.NONE, listed[0].completeness.lyrics)
        assertEquals(SidecarState.FAILED, listed[0].completeness.cover)
        assertEquals(LocalOrigin.CACHE, listed[0].origin)
    }

    @Test
    fun downloadedItemsAreNotAlsoListedAsCache() {
        val listed = LocalInventory.cached(
            catalog = listOf(catalog),
            inspect = { AssetCompleteness(true, SidecarState.READY, SidecarState.READY) },
            completedDownloadKeys = setOf(track.cacheKey),
        )
        assertTrue(listed.isEmpty())
    }

    @Test
    fun downloadedUsesLibraryMetadataAndFileReadiness() {
        val record = DownloadRecord(track.cacheKey, track.identity, "Old title", "Old artist", DownloadStatus.COMPLETED)
        val listed = LocalInventory.downloaded(
            records = listOf(record),
            inspect = { AssetCompleteness(false, SidecarState.NONE, SidecarState.READY) },
            fileReady = { true },
            libraryTracks = mapOf(track.cacheKey to track),
        )
        assertEquals("Song", listed.single().title)
        assertTrue(listed.single().completeness.audioReady)
        assertEquals(SidecarState.NONE, listed.single().completeness.lyrics)
        assertEquals(SidecarState.READY, listed.single().completeness.cover)
    }

    @Test
    fun incompleteDownloadFileIsNotPlayableOffline() {
        val record = DownloadRecord(track.cacheKey, track.identity, track.title, track.artist, DownloadStatus.COMPLETED)
        val listed = LocalInventory.downloaded(
            records = listOf(record),
            inspect = { AssetCompleteness(false, SidecarState.READY, SidecarState.READY) },
            fileReady = { false },
        )
        assertTrue(listed.single().completeness.cover == SidecarState.READY)
        assertTrue(!listed.single().completeness.audioReady)
    }
}
