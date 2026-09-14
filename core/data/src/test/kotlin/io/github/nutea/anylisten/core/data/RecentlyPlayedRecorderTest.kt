package io.github.nutea.anylisten.core.data

import androidx.room.Room
import io.github.nutea.anylisten.core.data.gateway.MockAnyListenGateway
import io.github.nutea.anylisten.core.data.local.AppDatabase
import io.github.nutea.anylisten.core.data.repo.LibraryRepository
import io.github.nutea.anylisten.core.data.repo.RecentlyPlayedRecorder
import io.github.nutea.anylisten.core.model.ProtocolConstants
import io.github.nutea.anylisten.core.model.Track
import io.github.nutea.anylisten.core.model.TrackIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RecentlyPlayedRecorderTest {
    private fun track(id: String, title: String) =
        Track(TrackIdentity("server", id), title, "Artist", "Album", 1000L, fingerprint = id, playlistId = "love")

    @Test
    fun recordsNewestFirstAndSkipsLastPlayedSource() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).build()
        val gateway = MockAnyListenGateway().apply { setOnline(true) }
        val library = LibraryRepository(db.libraryDao(), gateway)
        val older = track("old", "AAA")
        val newer = track("new", "ZZZ")
        library.recordPlay(older, "love")
        library.recordPlay(newer, "love")
        val listed = library.cached().tracksByPlaylist[ProtocolConstants.LIST_LAST_PLAYED].orEmpty()
        assertEquals(listOf("new", "old"), listed.map { it.identity.remoteTrackId })
        library.recordPlay(older, ProtocolConstants.LIST_LAST_PLAYED)
        val unchanged = library.cached().tracksByPlaylist[ProtocolConstants.LIST_LAST_PLAYED].orEmpty()
        assertEquals(listOf("new", "old"), unchanged.map { it.identity.remoteTrackId })
        val playlist = library.cached().playlists.find { it.id == ProtocolConstants.LIST_LAST_PLAYED }
        assertEquals(2, playlist?.trackCount)
        db.close()
    }

    @Test
    fun recorderDedupesTheSameTrackAndReplaysMoveItToFront() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).build()
        val gateway = MockAnyListenGateway().apply { setOnline(true) }
        val library = LibraryRepository(db.libraryDao(), gateway)
        val recorder = RecentlyPlayedRecorder(library)
        val first = track("a", "First")
        val second = track("b", "Second")
        recorder.onTrackStarted(first, "love")
        recorder.onTrackStarted(first, "love")
        recorder.onTrackStarted(second, "love")
        recorder.onTrackStarted(first, "love")
        val listed = library.cached().tracksByPlaylist[ProtocolConstants.LIST_LAST_PLAYED].orEmpty()
        assertEquals(listOf("a", "b"), listed.map { it.identity.remoteTrackId })
        db.close()
    }

    @Test
    fun offlineDoesNotCreateALocalOnlyHistory() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).build()
        val gateway = MockAnyListenGateway()
        val library = LibraryRepository(db.libraryDao(), gateway)
        library.recordPlay(track("a", "Song"), "love")
        assertTrue(library.cached().tracksByPlaylist[ProtocolConstants.LIST_LAST_PLAYED].isNullOrEmpty())
        db.close()
    }
}
