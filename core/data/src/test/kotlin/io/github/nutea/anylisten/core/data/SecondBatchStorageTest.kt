package io.github.nutea.anylisten.core.data

import androidx.room.Room
import io.github.nutea.anylisten.core.data.gateway.*
import io.github.nutea.anylisten.core.data.download.FileDownloader
import io.github.nutea.anylisten.core.data.local.AppDatabase
import io.github.nutea.anylisten.core.data.session.AppSettingsStore
import io.github.nutea.anylisten.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SecondBatchStorageTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun localOverrideSurvivesAutomaticRefreshOfflineRestartAndRestore() = runBlocking {
        var online = true
        var calls = 0
        val track = Track(TrackIdentity("s", "song"), "Title", "Artist", "Album", 60000)
        val original = LrcParser.parse("[00:01]Server")
        val local = LrcParser.parse("[00:01]Local", "[00:01]Translation", "[00:01]Romanization", "[00:01]<0,500>Local")
        val gateway = object : AnyListenGateway by MockAnyListenGateway() {
            override fun isOnline() = online
            override suspend fun resolveLyrics(track: Track): Lyrics { calls++; return original }
        }
        val http = OkHttpClient(); val folder = temp.newFolder()
        fun assets() = OfflineAssets(folder, gateway, FileDownloader(http), ArtworkStore(temp.newFolder(), http, { online })) { "https://example.invalid" }
        val first = assets()
        first.storeServerLyrics(track, original)
        first.setLocalLyrics(track, local)
        assertEquals(local, first.lyrics(track, force = true)); assertEquals(0, calls)
        online = false
        val restarted = assets()
        assertEquals(local, restarted.lyrics(track)); assertEquals(local, restarted.cachedLyrics(track))
        restarted.setLocalLyrics(track, null)
        assertEquals(original, restarted.lyrics(track))
        val otherServer = track.copy(identity = TrackIdentity("other", "song"))
        assertNull(restarted.localLyrics(otherServer))
    }
    @Test fun statsUpdateAtomicallyAndDoNotExistForPreexistingLibrary() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).build()
        try {
            assertTrue(db.listeningStatDao().observe().first().isEmpty())
            coroutineScope { List(20) { launch(Dispatchers.IO) { db.listeningStatDao().record("s:track", 1000, 1, 5000) } }.joinAll() }
            val stats = db.listeningStatDao().observe().first().single()
            assertEquals(20000L, stats.listenedMs); assertEquals(20L, stats.plays)
            assertEquals(5000L, stats.firstPlayedAt)
        } finally { db.close() }
    }
    @Test fun copyUsesFreshServerOrderAndKeepsCreatedListVisibleWhenAppendFails() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).build()
        val a = Track(TrackIdentity("s", "a"), "A", "", "", 1000)
        val b = Track(TrackIdentity("s", "b"), "B", "", "", 1000)
        var remote = LibrarySnapshot(listOf(Playlist("source", "Source", "general", 2)), mapOf("source" to listOf(b, a)), 10, false)
        var failAppend = false
        var creates = 0
        val gateway = object : AnyListenGateway by MockAnyListenGateway() {
            override fun isOnline() = true
            override suspend fun refreshLibrary() = remote
            override suspend fun editPlaylist(edit: PlaylistEdit) {
                val create = edit as PlaylistEdit.Create
                if (remote.playlists.none { it.id == create.id }) {
                    creates++
                    remote = remote.copy(playlists = remote.playlists + Playlist(create.id, create.name, "general", 0),
                        tracksByPlaylist = remote.tracksByPlaylist + (create.id to emptyList()))
                }
            }
            override suspend fun appendPlaylistTracks(listId: String, tracks: List<Track>) {
                if (failAppend) throw java.io.IOException("Append failed")
                remote = remote.copy(tracksByPlaylist = remote.tracksByPlaylist + (listId to tracks))
            }
        }
        try {
            val repository = io.github.nutea.anylisten.core.data.repo.LibraryRepository(db.libraryDao(), gateway)
            repository.copyPlaylist("source", "copy", "Copy")
            assertEquals(listOf("b", "a"), repository.cached().tracksByPlaylist.getValue("copy").map { it.identity.remoteTrackId })
            failAppend = true
            try { repository.copyPlaylist("source", "partial", "Partial"); fail() } catch (_: java.io.IOException) { }
            assertTrue(repository.cached().playlists.any { it.id == "partial" })
            failAppend = false
            repository.copyPlaylist("source", "partial", "Partial")
            assertEquals(2, creates)
            assertEquals(2, repository.cached().tracksByPlaylist.getValue("partial").size)
        } finally { db.close() }
    }
    @Test fun effectsAndSearchHistoryPersistWithBoundedDeduplication() = runBlocking {
        val settings = AppSettingsStore(RuntimeEnvironment.getApplication())
        settings.clearSearchHistory()
        settings.rememberSearch("  Hello  "); settings.rememberSearch("HELLO")
        assertEquals(listOf("HELLO"), settings.searchHistory.first())
        repeat(25) { settings.rememberSearch("query$it") }
        assertEquals(20, settings.searchHistory.first().size)
        settings.clearSearchHistory(); assertTrue(settings.searchHistory.first().isEmpty())
        val config = AudioEffectsSettings(1.5f, true, "custom", mapOf(60 to 300, 1000 to -200))
        settings.setAudioEffects(config)
        assertEquals(config, settings.audioEffects.first())
        settings.setAudioEffects(AudioEffectsSettings())
    }
}
