package io.github.nutea.anylisten.core.data

import androidx.room.Room
import io.github.nutea.anylisten.core.data.local.*
import io.github.nutea.anylisten.core.data.download.*
import io.github.nutea.anylisten.core.data.gateway.*
import io.github.nutea.anylisten.core.model.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DownloadRecoveryTest {
    @Test fun unicodeNamesDownloadDistinctBytesAndDeleteIndependently() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).build()
        val folder = kotlin.io.path.createTempDirectory("unicode-downloads").toFile()
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                    MockResponse().setBody(if (request.path == "/one") "first-song" else "second-song")
            }
            server.start()
            val tracks = listOf("七里香", "兰亭序").map { name ->
                Track(TrackIdentity("server", "/music/歌手 - $name.mp3"), name, "歌手", "", 1000,
                    fingerprint = "/music/歌手 - $name.mp3")
            }
            val gateway = object : AnyListenGateway by MockAnyListenGateway() {
                override suspend fun resolveMedia(track: Track, refresh: Boolean) =
                    MediaResource(server.loopbackUrl(if (track == tracks[0]) "/one" else "/two"))
            }
            val c = DownloadCoordinator(db.downloadDao(),db.libraryDao(),gateway,FileDownloader(OkHttpClient()),folder,folder,{Long.MAX_VALUE})
            c.enqueue(tracks)
            withTimeout(5000) { while (db.downloadDao().all().count { it.status == "COMPLETED" } != 2) delay(20) }
            assertEquals("first-song",c.completedFile(tracks[0].cacheKey)!!.readText())
            assertEquals("second-song",c.completedFile(tracks[1].cacheKey)!!.readText())
            assertNotEquals(c.completedFile(tracks[0].cacheKey),c.completedFile(tracks[1].cacheKey))
            c.deleteLocal(tracks[0].cacheKey)
            assertEquals("second-song",c.completedFile(tracks[1].cacheKey)!!.readText())
        }
        db.close();folder.deleteRecursively();Unit
    }

    @Test fun legacySharedFilesAreInvalidatedAndUniqueDownloadsPreserved() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).build()
        val folder = kotlin.io.path.createTempDirectory("legacy-collision").toFile()
        val tracks = listOf("七里香", "兰亭序", "unique").map { Track(TrackIdentity("server", it),it,"artist","",1000) }
        for (t in tracks) {
            val file = File(folder,t.cacheKey.replace(Regex("[^A-Za-z0-9._-]"),"_")).apply { writeText("audio") }
            db.downloadDao().upsert(DownloadEntity.from(DownloadRecord(t.cacheKey,t.identity,t.title,t.artist,
                DownloadStatus.COMPLETED,bytesDownloaded = 5,bytesTotal = 5,filePath = file.absolutePath)))
        }
        val c = DownloadCoordinator(db.downloadDao(),db.libraryDao(),MockAnyListenGateway(),FileDownloader(OkHttpClient()),folder,folder,{Long.MAX_VALUE})
        // Playback itself must trigger migration before returning a possibly shared file.
        assertNull(c.completedFile(tracks[0].cacheKey))
        assertNull(c.completedFile(tracks[1].cacheKey))
        assertEquals("PAUSED",db.downloadDao().find(tracks[0].cacheKey)!!.status)
        assertEquals("audio",c.completedFile(tracks[2].cacheKey)!!.readText())
        assertEquals(downloadFileName(tracks[2].cacheKey),c.completedFile(tracks[2].cacheKey)!!.name)
        c.migrateLegacyFiles()
        assertEquals("audio",c.completedFile(tracks[2].cacheKey)!!.readText())
        assertEquals(3,db.downloadDao().all().size)
        db.close();folder.deleteRecursively();Unit
    }

    @Test fun retryRepairsLegacySessionIdentityAndPreservesLocalMetadata() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).build()
        val folder = kotlin.io.path.createTempDirectory("download-recovery").toFile()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("valid-audio")); server.start()
            val track = Track(TrackIdentity("server", "/music/小情歌.mp3"), "小情歌", "苏打绿", "小宇宙", 273000,
                fingerprint = "/music/小情歌.mp3", rawJson = "{\"isLocal\":true,\"meta\":{\"filePath\":\"/music/小情歌.mp3\"}}")
            db.libraryDao().upsertTracks(listOf(TrackEntity.from(track)))
            val legacy = Track(TrackIdentity("session", track.cacheKey), track.title, track.artist, "", null)
            db.downloadDao().upsert(DownloadEntity.from(DownloadRecord(legacy.cacheKey, legacy.identity, legacy.title, legacy.artist, DownloadStatus.FAILED)))
            val gateway = object : AnyListenGateway by MockAnyListenGateway() {
                override suspend fun resolveMedia(candidate: Track, refresh: Boolean): MediaResource {
                    assertEquals(track.identity, candidate.identity)
                    assertEquals(track.rawJson, candidate.rawJson)
                    return MediaResource(server.loopbackUrl("/audio"))
                }
            }
            val coordinator = DownloadCoordinator(db.downloadDao(), db.libraryDao(), gateway, FileDownloader(OkHttpClient()),
                folder, folder, { Long.MAX_VALUE })
            coordinator.retry(legacy.cacheKey)
            withTimeout(5000) { while (db.downloadDao().find(track.cacheKey)?.status != "COMPLETED") delay(20) }
            assertNull(db.downloadDao().find(legacy.cacheKey))
            assertEquals("valid-audio", File(db.downloadDao().find(track.cacheKey)!!.filePath!!).readText())
            coordinator.removeTask(track.cacheKey)
            assertNotNull(db.downloadDao().find(track.cacheKey))
        }
        db.close(); folder.deleteRecursively(); Unit
    }

    @Test fun removeFailedTaskClearsPartialFilesAndKeepsOtherDownloads() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).build()
        val folder = kotlin.io.path.createTempDirectory("download-removal").toFile()
        val key = "session:path/track:unknown"
        db.downloadDao().upsert(DownloadEntity.from(DownloadRecord(key, TrackIdentity("session", "path/track"), "Failed", "Artist", DownloadStatus.FAILED)))
        val partial = File(folder, "session_path_track_unknown.part").apply { writeText("partial") }
        val other = File(folder, "other").apply { writeText("saved") }
        val coordinator = DownloadCoordinator(db.downloadDao(), db.libraryDao(), MockAnyListenGateway(), FileDownloader(OkHttpClient()),
            folder, folder, { Long.MAX_VALUE })
        coordinator.removeTask(key)
        assertNull(db.downloadDao().find(key)); assertFalse(partial.exists()); assertTrue(other.exists())
        db.close(); folder.deleteRecursively(); Unit
    }
}
