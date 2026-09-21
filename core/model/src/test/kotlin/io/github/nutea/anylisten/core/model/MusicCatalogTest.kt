package io.github.nutea.anylisten.core.model

import org.junit.Assert.*
import org.junit.Test

class MusicCatalogTest {
    private fun song(id: String, artist: String = "Artist", album: String = "Album", server: String = "s") =
        Track(TrackIdentity(server, id), id, artist, album, 60000)
    private fun catalog(vararg tracks: Track) = MusicCatalog.build(LibrarySnapshot(emptyList(), mapOf("a" to tracks.toList()), 0, false))

    @Test fun duplicatePlaylistCopiesCountOnce() {
        val one = song("1")
        val index = MusicCatalog.build(LibrarySnapshot(emptyList(), mapOf("a" to listOf(one, song("2")), "b" to listOf(one.copy(fingerprint = "updated"))), 0, false))
        assertEquals(listOf("1", "2"), index.artists.single().tracks.map { it.title })
        assertEquals(2, index.albums.single().tracks.size)
    }
    @Test fun sameAlbumTitleDoesNotMergeDifferentArtistsOrServers() {
        val index = catalog(song("1"), song("2", artist = "Other"), song("1", server = "second"))
        assertEquals(3, index.albums.size)
        assertEquals(3, index.artists.size)
        assertEquals(1, index.artist(MusicCatalog.artistKey(song("1"))!!)!!.tracks.size)
    }
    @Test fun trimsAndNormalizesButDoesNotSubstringMatchOrSplitCredits() {
        val index = catalog(song("1", "  Artist  "), song("2", "artist"), song("3", "Artist / Other"), song("4", "Artists"))
        assertEquals(3, index.artists.size)
        assertEquals(2, index.artist(MusicCatalog.artistKey(song("1"))!!)!!.tracks.size)
        assertNotNull(index.artists.find { it.name == "Artist / Other" })
    }
    @Test fun albumUsesDiscAndTrackNumbersAndFallbackIsIndependentOfPlaylistOrder() {
        fun numbered(id: String, disc: Int, number: Int) = song(id).copy(rawJson = """{"meta":{"discNo":$disc,"trackNo":$number}}""")
        val first = numbered("z", 1, 1)
        val second = numbered("a", 1, 2)
        val third = numbered("b", 2, 1)
        assertEquals(listOf(first, second, third), catalog(third, second, first).albums.single().tracks)
        assertEquals(catalog(song("z"), song("a")).albums, catalog(song("a"), song("z")).albums)
    }
    @Test fun missingMetadataDoesNotCreateBogusArtistOrAlbum() {
        val index = catalog(song("1", "", ""), song("2", "Artist", ""), song("3", "", "Album"))
        assertEquals(1, index.artists.size)
        assertEquals(1, index.albums.size)
        assertTrue(index.artists.single().albums.isEmpty())
        assertNull(MusicCatalog.artistKey(song("1", " ")))
    }
}
