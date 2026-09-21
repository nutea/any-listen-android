package io.github.nutea.anylisten.core.data

import androidx.room.Room
import io.github.nutea.anylisten.core.data.gateway.MockAnyListenGateway
import io.github.nutea.anylisten.core.data.local.AppDatabase
import io.github.nutea.anylisten.core.data.repo.LibraryRepository
import io.github.nutea.anylisten.core.data.session.AppSettingsStore
import io.github.nutea.anylisten.core.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PlaylistRepositoryTest {
    @Test fun serverOrderSurvivesCacheAndRenameAndDeletePreservesTracks() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).build()
        try {
            val gateway = MockAnyListenGateway().apply { setOnline(true) }
            val repository = LibraryRepository(db.libraryDao(), gateway)
            repository.editPlaylist(PlaylistEdit.Create("new-z", "Z"))
            repository.editPlaylist(PlaylistEdit.Create("new-a", "A"))
            fun ids(snapshot: LibrarySnapshot) = snapshot.playlists.filter { it.id.startsWith("new-") }.map { it.id }
            assertEquals(listOf("new-z", "new-a"), ids(repository.cached()))
            repository.editPlaylist(PlaylistEdit.Move("new-a", -1))
            repository.editPlaylist(PlaylistEdit.Rename("new-a", "ZZZ"))
            assertEquals(listOf("new-a", "new-z"), ids(repository.cached()))
            val track = Track(TrackIdentity("test", "song"), "Song", "Artist", "Album", 1000)
            repository.addToPlaylist("new-a", track)
            repository.addToPlaylist("new-z", track)
            repository.editPlaylist(PlaylistEdit.Delete("new-a"))
            gateway.setOnline(false)
            val offline = LibraryRepository(db.libraryDao(), gateway).cached()
            assertTrue(offline.offline)
            assertEquals(listOf("new-z"), ids(offline))
            assertEquals(track.cacheKey, offline.tracksByPlaylist.getValue("new-z").single().cacheKey)
            assertNotNull(repository.cachedTrack(track.cacheKey))
        } finally { db.close() }
    }

    @Test fun lyricPreferencesPersistPerServerTrackAndResetIndependently() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val settings = AppSettingsStore(context)
        val a = TrackIdentity("server-a", "song").cacheKey(null)
        val b = TrackIdentity("server-b", "song").cacheKey(null)
        settings.setLyricOffset(a, 300)
        settings.setLyricOffset(b, -200)
        settings.setShowTranslation(false)
        settings.setShowRomanization(false)
        settings.setKaraokeEnabled(false)
        val reloaded = AppSettingsStore(context).lyricSettings.first()
        assertEquals(300L, reloaded.offsets[a])
        assertEquals(-200L, reloaded.offsets[b])
        assertFalse(reloaded.showTranslation)
        assertFalse(reloaded.showRomanization)
        assertFalse(reloaded.karaokeEnabled)
        settings.setLyricOffset(a, 0)
        assertNull(settings.lyricSettings.first().offsets[a])
        assertEquals(-200L, settings.lyricSettings.first().offsets[b])
    }
}
