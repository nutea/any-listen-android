package io.github.nutea.anylisten.core.data

import android.graphics.Bitmap
import androidx.room.Room
import io.github.nutea.anylisten.core.data.download.DownloadCoordinator
import io.github.nutea.anylisten.core.data.download.FileDownloader
import io.github.nutea.anylisten.core.data.gateway.AnyListenGateway
import io.github.nutea.anylisten.core.data.gateway.MockAnyListenGateway
import io.github.nutea.anylisten.core.data.local.AppDatabase
import io.github.nutea.anylisten.core.data.local.TrackEntity
import io.github.nutea.anylisten.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OfflineAssetsTest {
    @get:Rule val temp = TemporaryFolder()
    private val track = Track(TrackIdentity("server", "song"), "Song", "Artist", "Album", 120_000)

    @Test fun visibleMissingCoverResolvesWithoutPlaybackOrLyricsAndCoalescesRequests() = runBlocking {
        val png = ByteArrayOutputStream()
        Bitmap.createBitmap(4,4,Bitmap.Config.ARGB_8888).apply { compress(Bitmap.CompressFormat.PNG,100,png); recycle() }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(png.toByteArray())))
            server.start()
            var online = true
            val calls = java.util.concurrent.atomic.AtomicInteger()
            val release = CompletableDeferred<Unit>()
            val gateway = object : AnyListenGateway by MockAnyListenGateway() {
                override fun isOnline() = online
                override suspend fun resolveCover(track: Track): String {
                    calls.incrementAndGet(); release.await(); return server.loopbackUrl("/cover")
                }
                override suspend fun resolveMedia(track: Track, refresh: Boolean): MediaResource = error("No audio request expected")
                override suspend fun resolveLyrics(track: Track): Lyrics = error("No lyric request expected")
            }
            val http = OkHttpClient()
            val artwork = ArtworkStore(temp.newFolder(),http,{online})
            val assets = OfflineAssets(temp.newFolder(),gateway,FileDownloader(http),artwork) { server.loopbackUrl("/") }
            val pending = List(10) { assets.requestCover(track) }
            assertEquals(1, pending.filterNotNull().distinct().size)
            release.complete(Unit)
            pending.filterNotNull().joinAll()
            assertEquals(1,calls.get())
            assertEquals(server.loopbackUrl("/cover"), assets.savedCoverUrl(track.cacheKey))
            assertNotNull(artwork.cached(assets.savedCoverUrl(track.cacheKey)!!))
            assertNull(assets.audioFile(track.cacheKey)); assertNull(assets.cachedLyrics(track))
            assertTrue(assets.catalog().isEmpty())
            assertNull(assets.requestCover(track))
            online = false
            assertNull(assets.requestCover(track.copy(identity = TrackIdentity("server", "other"))))
            assertEquals(1, calls.get())
        }
    }

    @Test fun existingLyricsRefreshOnlineAndFallbackOfflineOrOnFailure() = runBlocking {
        var text = "[00:01.00]Original"
        var online = true
        var fail = false
        var calls = 0
        val gateway = object : AnyListenGateway by MockAnyListenGateway() {
            override fun isOnline() = online
            override suspend fun resolveLyrics(track: Track): Lyrics { calls++;check(!fail);return LrcParser.parse(text) }
        }
        val http = OkHttpClient()
        val assets = OfflineAssets(temp.newFolder(),gateway,FileDownloader(http),ArtworkStore(temp.newFolder(),http,{online})) { "https://example.test" }
        assertEquals("Original",assets.lyrics(track).lineAt(1000))
        text = "[00:01.00]Updated"
        assertEquals("Updated",assets.lyrics(track,force = true).lineAt(1000))
        fail = true
        assertEquals("Updated",assets.lyrics(track,force = true).lineAt(1000))
        val before = calls;online = false
        assertEquals("Updated",assets.lyrics(track,force = true).lineAt(1000));assertEquals(before,calls)
    }

    @Test fun playRevalidatesCachedLyricsWhenServerCopyChanges() = runBlocking {
        var clock = 1_000_000L
        var text = "[00:01.00]Original"
        var calls = 0
        val gateway = object : AnyListenGateway by MockAnyListenGateway() {
            override fun isOnline() = true
            override suspend fun resolveLyrics(track: Track): Lyrics { calls++; return LrcParser.parse(text) }
        }
        val http = OkHttpClient()
        val assets = OfflineAssets(
            temp.newFolder(), gateway, FileDownloader(http), ArtworkStore(temp.newFolder(), http),
            { clock }, { "https://example.test" },
        )
        assertEquals("Original", assets.lyrics(track).lineAt(1000))
        assertEquals(1, calls)
        text = "[00:01.00]Updated on server"
        repeat(4) { assertEquals("Original", assets.lyrics(track).lineAt(1000)) }
        delay(50)
        assertEquals(1, calls)
        assertEquals("Original", assets.cachedLyrics(track)!!.lineAt(1000))
        clock += LYRIC_PLAY_REVALIDATE_MS
        val notified = assets.updates.value
        assertEquals("Original", assets.lyrics(track).lineAt(1000))
        withTimeout(5_000) {
            while (assets.cachedLyrics(track)?.lineAt(1000) != "Updated on server") delay(10)
        }
        assertEquals("Updated on server", assets.cachedLyrics(track)!!.lineAt(1000))
        assertTrue(assets.updates.value > notified)
        assertEquals(2, calls)
        assertEquals("Updated on server", assets.lyrics(track).lineAt(1000))
        delay(50)
        assertEquals(2, calls)
    }

    @Test fun playRevalidatesMissingLyricsWhenServerAddsThem() = runBlocking {
        var clock = 1_000_000L
        var available = false
        var calls = 0
        val gateway = object : AnyListenGateway by MockAnyListenGateway() {
            override fun isOnline() = true
            override suspend fun resolveLyrics(track: Track): Lyrics {
                calls++
                return LrcParser.parse(if (available) "[00:01.00]Added later" else "")
            }
        }
        val http = OkHttpClient()
        val assets = OfflineAssets(
            temp.newFolder(), gateway, FileDownloader(http), ArtworkStore(temp.newFolder(), http),
            { clock }, { "https://example.test" },
        )
        assertTrue(assets.lyrics(track).lines.isEmpty())
        assertEquals(1, calls)
        available = true
        clock += LYRIC_PLAY_REVALIDATE_MS
        assertTrue(assets.lyrics(track).lines.isEmpty())
        withTimeout(5_000) {
            while (assets.cachedLyrics(track)?.lineAt(1000) != "Added later") delay(10)
        }
        assertEquals("Added later", assets.cachedLyrics(track)!!.lineAt(1000))
        assertEquals(2, calls)
    }

    @Test fun retainedAudioRefreshesInPlaceAndKeepsPlayableBytesOnFailureAndOffline() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("original audio"))
            server.enqueue(MockResponse().setBody("updated audio bytes"))
            server.enqueue(MockResponse().setResponseCode(503))
            server.start()
            var online = true
            val gateway = object : AnyListenGateway by MockAnyListenGateway() {
                override fun isOnline() = online
                override suspend fun resolveMedia(track: Track, refresh: Boolean) = MediaResource(server.loopbackUrl("/audio"))
            }
            val http = OkHttpClient()
            val assets = OfflineAssets(temp.newFolder(), gateway, FileDownloader(http), ArtworkStore(temp.newFolder(), http, { online })) { "https://example.test" }
            val file = assets.cacheAudio(track)
            assertEquals("original audio", file.readText())
            assertEquals(file, assets.cacheAudio(track, force = true))
            assertEquals("updated audio bytes", file.readText())
            assertEquals(file, assets.cacheAudio(track, force = true))
            assertEquals("updated audio bytes", file.readText())
            online = false
            assertEquals(file, assets.cacheAudio(track, force = true))
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun missingLyricsCanAppearLaterAndThenWorkOffline() = runBlocking {
        var available = false
        var online = true
        var calls = 0
        val gateway = object : AnyListenGateway by MockAnyListenGateway() {
            override fun isOnline() = online
            override suspend fun resolveLyrics(track: Track): Lyrics {
                calls++
                check(online)
                return LrcParser.parse(if (available) "[00:01.00]New server lyric" else "")
            }
        }
        val http = OkHttpClient()
        val assets = OfflineAssets(temp.newFolder(),gateway,FileDownloader(http),ArtworkStore(temp.newFolder(),http)) { "https://example.test" }
        assertTrue(assets.lyrics(track).lines.isEmpty())
        online = false
        assertTrue(assets.lyrics(track).lines.isEmpty())
        assertEquals(1,calls)
        online = true; available = true
        assertEquals("New server lyric",assets.lyrics(track, force = true).lineAt(1000))
        online = false
        assertEquals("New server lyric",assets.lyrics(track).lineAt(1000))
        assertEquals(2,calls)
    }

    @Test fun missingSidecarsAndFailuresDoNotHammerGateway() = runBlocking {
        var lyricCalls = 0; var coverCalls = 0; var fail = false
        val gateway = object : AnyListenGateway by MockAnyListenGateway() {
            override fun isOnline() = true
            override suspend fun resolveLyrics(track: Track): Lyrics { lyricCalls++; check(!fail); return Lyrics(emptyList(), "") }
            override suspend fun resolveCover(track: Track): String? { coverCalls++; check(!fail); return null }
        }
        val http = OkHttpClient()
        val assets = OfflineAssets(temp.newFolder(), gateway, FileDownloader(http), ArtworkStore(temp.newFolder(), http)) { "https://example.test" }
        repeat(8) { assertTrue(assets.cacheExtras(track)) }
        assertEquals(1, lyricCalls); assertEquals(1, coverCalls)
        fail = true
        val other = track.copy(identity = TrackIdentity("server", "failed"))
        repeat(8) { assertFalse(assets.cacheExtras(other)) }
        assertEquals(2, lyricCalls); assertEquals(2, coverCalls)
        fail = false
        assertTrue(assets.cacheExtras(other, force = true))
        assertEquals(3, lyricCalls); assertEquals(3, coverCalls)
    }

    @Test fun alternateResolvedCoverUrlAndUnchangedLyricsKeepFilesStable() = runBlocking {
        val png = ByteArrayOutputStream()
        Bitmap.createBitmap(4,4,Bitmap.Config.ARGB_8888).apply { compress(Bitmap.CompressFormat.PNG,100,png); recycle() }
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setBody(Buffer().write(png.toByteArray()))) }
            server.start()
            var coverCalls = 0
            val gateway = object : AnyListenGateway by MockAnyListenGateway() {
                override fun isOnline() = true
                override suspend fun resolveCover(track: Track): String { coverCalls++; return server.loopbackUrl("/resolved") }
                override suspend fun resolveLyrics(track: Track) = LrcParser.parse("[00:01.00]Stable")
            }
            val http = OkHttpClient(); val dir = temp.newFolder()
            val artwork = ArtworkStore(temp.newFolder(),http)
            val assets = OfflineAssets(dir,gateway,FileDownloader(http),artwork) { "https://example.test" }
            // The library URL can differ from the URL resolved by getMusicPic.
            val input = track.copy(coverUrl = "https://example.test/list-cover")
            assets.cacheExtras(input)
            val files = dir.listFiles()!!.filter { it.name.endsWith(".cover") || it.name.endsWith(".lrc") }
            files.forEach { assertTrue(it.setLastModified(1000L)) }
            repeat(8) { assets.cacheCover(input); assets.cacheCover(input.copy(coverUrl = assets.savedCoverUrl(input.cacheKey))) }
            assertEquals(1,coverCalls); assertEquals(1,server.requestCount)
            val revision = artwork.updates.value
            assets.cacheExtras(input,force = true)
            assertEquals(2,coverCalls); assertEquals(2,server.requestCount)
            assertEquals(revision,artwork.updates.value)
            files.forEach { assertEquals(1000L,it.lastModified()) }
        }
    }

    @Test fun themePreferencePersistsAcrossStoreInstances() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val settings = io.github.nutea.anylisten.core.data.session.AppSettingsStore(context)
        try {
            settings.setThemeMode(ThemeMode.DARK)
            assertEquals(ThemeMode.DARK, io.github.nutea.anylisten.core.data.session.AppSettingsStore(context).themeMode.first())
            settings.setThemeMode(ThemeMode.LIGHT)
            assertEquals(ThemeMode.LIGHT, settings.themeMode.first())
        } finally { settings.setThemeMode(ThemeMode.SYSTEM) }
        assertEquals(ThemeMode.SYSTEM, settings.themeMode.first())
        assertEquals(ThemeMode.SYSTEM, ThemeMode.decode("invalid"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.decode(null))
    }

    @Test fun automaticAudioSwitchKeepsSidecarsAndExistingAudio() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("complete audio")); server.start()
            val mediaCalls = java.util.concurrent.atomic.AtomicInteger()
            val coverCalls = java.util.concurrent.atomic.AtomicInteger()
            val gateway = object : AnyListenGateway by MockAnyListenGateway() {
                override fun isOnline() = true
                override suspend fun resolveMedia(track: Track, refresh: Boolean): MediaResource {
                    mediaCalls.incrementAndGet(); return MediaResource(server.loopbackUrl("/audio"))
                }
                override suspend fun resolveCover(track: Track): String? { coverCalls.incrementAndGet(); return null }
                override suspend fun resolveLyrics(track: Track) = LrcParser.parse("[00:01.00]Cached lyric")
            }
            val http = OkHttpClient()
            val assets = OfflineAssets(temp.newFolder(), gateway, FileDownloader(http), ArtworkStore(temp.newFolder(), http)) { "https://example.test" }
            assets.setAutomaticAudioCaching(false)
            assets.cachePlayed(track)
            withTimeout(5000) { while (assets.inspect(track.cacheKey).lyrics != SidecarState.READY || coverCalls.get() == 0) delay(20) }
            assertEquals(0, mediaCalls.get()); assertNull(assets.audioFile(track.cacheKey))
            assertEquals("Cached lyric", assets.lyrics(track).lineAt(1000))
            assets.setAutomaticAudioCaching(true)
            assets.cachePlayed(track)
            withTimeout(5000) { while (assets.audioFile(track.cacheKey) == null) delay(20) }
            assets.setAutomaticAudioCaching(false)
            assertEquals("complete audio", assets.audioFile(track.cacheKey)!!.readText())
            assertEquals(1, mediaCalls.get())
        }
    }

    @Test fun disablingAutomaticAudioStopsInflightCopy() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("a".repeat(100000)).throttleBody(1000, 30, java.util.concurrent.TimeUnit.MILLISECONDS)); server.start()
            val gateway = object : AnyListenGateway by MockAnyListenGateway() {
                override suspend fun resolveMedia(track: Track, refresh: Boolean) = MediaResource(server.loopbackUrl("/audio"))
                override suspend fun resolveCover(track: Track): String? = null
            }
            val dir = temp.newFolder(); val http = OkHttpClient()
            val assets = OfflineAssets(dir, gateway, FileDownloader(http), ArtworkStore(temp.newFolder(), http)) { "https://example.test" }
            assets.cachePlayed(track)
            withTimeout(5000) { while (assets.audioBytes() == 0L) delay(10) }
            assets.setAutomaticAudioCaching(false)
            val stoppedAt = assets.audioBytes()
            delay(100)
            assertEquals(stoppedAt, assets.audioBytes()); assertNull(assets.audioFile(track.cacheKey))
        }
    }

    @Test fun autoCachePreferenceDefaultsOnAndPersistsOff() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val settings = io.github.nutea.anylisten.core.data.session.AppSettingsStore(context)
        assertTrue(settings.autoCacheAudio.first())
        try {
            settings.setAutoCacheAudio(false)
            assertFalse(io.github.nutea.anylisten.core.data.session.AppSettingsStore(context).autoCacheAudio.first())
        } finally { settings.setAutoCacheAudio(true) }
    }

    @Test fun interruptedAudioIsNeverOfferedAsAnOfflineFile() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("audio".repeat(10_000))
                .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            server.start()
            val gateway = object : AnyListenGateway by MockAnyListenGateway() {
                override suspend fun resolveMedia(track: Track, refresh: Boolean) = MediaResource(server.loopbackUrl("/audio"))
            }
            val http = OkHttpClient()
            val store = OfflineAssets(temp.newFolder(), gateway, FileDownloader(http), ArtworkStore(temp.newFolder(), http)) { "https://example.test" }
            assertTrue(runCatching { store.cacheAudio(track) }.isFailure)
            assertNull(store.audioFile(track.cacheKey))
        }
    }

    @Test fun completeAudioAndTimedLyricsSurviveOfflineRestart() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("complete audio data"))
        server.start()
        var online = true
        var lyricCalls = 0
        val gateway = object : AnyListenGateway by MockAnyListenGateway() {
            override suspend fun resolveMedia(track: Track, refresh: Boolean): MediaResource {
                check(online)
                return MediaResource(server.loopbackUrl("/audio"))
            }
            override suspend fun resolveLyrics(track: Track): Lyrics {
                check(online); lyricCalls++
                return LrcParser.parse("[00:10.00]Saved lyric")
            }
        }
        val dir = temp.newFolder()
        val http = OkHttpClient()
        val covers = ArtworkStore(temp.newFolder(), http)
        val assets = OfflineAssets(dir, gateway, FileDownloader(http), covers) { "https://example.test" }
        assertNull(assets.audioFile(track.cacheKey))
        val files = List(3) { async { assets.cacheAudio(track) } }.awaitAll()
        assertEquals(1, server.requestCount)
        assertEquals("complete audio data", files.first().readText())
        assertEquals("Saved lyric", assets.lyrics(track).lineAt(10_000))
        online = false
        server.shutdown()
        val restarted = OfflineAssets(dir, gateway, FileDownloader(http), covers) { "https://example.test" }
        assertEquals(files.first(), restarted.audioFile(track.cacheKey))
        assertEquals("Saved lyric", restarted.lyrics(track).lineAt(10_000))
        assertEquals(1, lyricCalls)
        restarted.clearAudio()
        assertNull(restarted.audioFile(track.cacheKey))
        assertEquals("Saved lyric", restarted.lyrics(track).lineAt(10_000))
    }

    @Test fun downloadCompletesWithAudioCoverAndLyricsPersisted() = runBlocking {
        val bytes = ByteArrayOutputStream()
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
            compress(Bitmap.CompressFormat.PNG, 100, bytes); recycle()
        }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("complete download"))
            server.start()
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                if (chain.request().url.host == "cover.test") okhttp3.Response.Builder()
                    .request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                    .body(okhttp3.ResponseBody.create(null, bytes.toByteArray())).build()
                else chain.proceed(chain.request())
            }.build()
            val gateway = object : AnyListenGateway by MockAnyListenGateway() {
                override suspend fun resolveMedia(track: Track, refresh: Boolean) = MediaResource(server.loopbackUrl("/audio"))
                override fun isOnline() = true
                override suspend fun resolveCover(track: Track) = "https://cover.test/image"
                override suspend fun resolveLyrics(track: Track) = LrcParser.parse("[00:01.00]Downloaded lyric")
            }
            val artworkDir = temp.newFolder()
            val assets = OfflineAssets(temp.newFolder(), gateway, FileDownloader(http), ArtworkStore(artworkDir, http)) { "https://example.test" }
            val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).build()
            try {
                db.libraryDao().upsertTracks(listOf(TrackEntity.from(track)))
                val streamDir = temp.newFolder()
                java.io.File(streamDir, "span").writeBytes(ByteArray(23))
                val coordinator = DownloadCoordinator(db.downloadDao(), db.libraryDao(), gateway, FileDownloader(http),
                    temp.newFolder(), temp.newFolder(), { Long.MAX_VALUE }, assets,
                    streamingCacheDir = streamDir,
                    resourceBytes = { artworkDir.listFiles().orEmpty().sumOf { it.length() } + assets.resourceBytes() })
                assets.setAutomaticAudioCaching(false)
                coordinator.enqueue(listOf(track))
                val finished = withTimeout(10_000) { coordinator.observe().first { records -> records.any { it.status == DownloadStatus.COMPLETED || it.status == DownloadStatus.FAILED } }.first() }
                assertEquals(DownloadStatus.COMPLETED, finished.status)
                assertNull(finished.error)
                assertEquals("complete download", coordinator.completedFile(track.cacheKey)!!.readText())
                assertEquals("https://cover.test/image", assets.savedCoverUrl(track.cacheKey))
                assertEquals(1, artworkDir.listFiles()!!.count { !it.name.endsWith(".http.json") })
                assertEquals("Downloaded lyric", assets.lyrics(track).lineAt(1_000))
                assertEquals(23L, coordinator.storage().cacheBytes)
                assertTrue(coordinator.storage().resourceBytes > 0)
                java.io.File(streamDir, "span").appendBytes(ByteArray(17))
                assertEquals(40L, coordinator.storage().cacheBytes)
                server.enqueue(MockResponse().setBody("updated complete download"))
                coordinator.refreshCompleted(track, force = true).join()
                assertEquals("updated complete download", coordinator.completedFile(track.cacheKey)!!.readText())
                assertEquals(DownloadStatus.COMPLETED, coordinator.observe().first().single().status)
                server.enqueue(MockResponse().setResponseCode(503))
                coordinator.refreshCompleted(track, force = true).join()
                assertEquals("updated complete download", coordinator.completedFile(track.cacheKey)!!.readText())
                assertEquals(DownloadStatus.COMPLETED, coordinator.observe().first().single().status)
            } finally { db.close() }
        }
    }

    @Test fun emptyAndFailedSidecarsAreInspectableAndCatalogJoinsMetadata() = runBlocking {
        val dir = temp.newFolder()
        val http = OkHttpClient()
        val covers = ArtworkStore(temp.newFolder(), http)
        val emptyLyrics = object : AnyListenGateway by MockAnyListenGateway() {
            override fun isOnline() = true
            override suspend fun resolveMedia(track: Track, refresh: Boolean) = MediaResource("https://example.test/audio")
            override suspend fun resolveLyrics(track: Track) = Lyrics(emptyList(), "")
            override suspend fun resolveCover(track: Track) = null
        }
        val assets = OfflineAssets(dir, emptyLyrics, FileDownloader(http), covers) { "https://example.test" }
        java.io.File(dir, "orphan.audio").writeText("no catalog")
        assertTrue(assets.catalog().isEmpty())
        assertTrue(LocalInventory.cached(assets.catalog(), assets::inspect, emptySet()).isEmpty())

        val failing = object : AnyListenGateway by MockAnyListenGateway() {
            override fun isOnline() = true
            override suspend fun resolveLyrics(track: Track) = error("lyric down")
            override suspend fun resolveCover(track: Track) = error("cover down")
        }
        val failed = OfflineAssets(dir, failing, FileDownloader(http), covers) { "https://example.test" }
        assertFalse(failed.cacheExtras(track))
        assertEquals(SidecarState.FAILED, failed.inspect(track.cacheKey).lyrics)
        assertEquals(SidecarState.FAILED, failed.inspect(track.cacheKey).cover)
        assertEquals("Song", failed.catalog().single().title)

        val none = OfflineAssets(dir, emptyLyrics, FileDownloader(http), covers) { "https://example.test" }
        assertTrue(none.cacheExtras(track))
        assertEquals(SidecarState.NONE, none.inspect(track.cacheKey).lyrics)
        assertEquals(SidecarState.NONE, none.inspect(track.cacheKey).cover)
        assertTrue(none.lyrics(track).lines.isEmpty())
    }

    @Test fun seekedPlaybackSessionsPublishOneCatalogAudioFile() = runBlocking {
        val dir = temp.newFolder()
        val http = OkHttpClient()
        val assets = OfflineAssets(dir, MockAnyListenGateway(), FileDownloader(http), ArtworkStore(temp.newFolder(), http)) { "https://example.test" }
        val body = ByteArray(20_000) { it.toByte() }
        val url = "https://example.test/audio"
        val first = assets.openStreamSink(track, url, 0, body.size.toLong())!!
        first.write(0, body, 0, 4096)
        first.close(endOfInput = false)
        assertNull(assets.audioFile(track.cacheKey))
        assertTrue(LocalInventory.cached(assets.catalog(), assets::inspect, emptySet()).isEmpty())

        val rest = assets.openStreamSink(track, url, 4096, (body.size - 4096).toLong())!!
        rest.write(4096, body, 4096, body.size - 4096)
        rest.close(endOfInput = true)
        assertArrayEquals(body, assets.audioFile(track.cacheKey)!!.readBytes())
        val listed = LocalInventory.cached(assets.catalog(), assets::inspect, emptySet())
        assertEquals(listOf(track.cacheKey), listed.map { it.cacheKey })
        assertTrue(listed.single().completeness.audioReady)

        assets.clearTrack(track.cacheKey)
        assertNull(assets.audioFile(track.cacheKey))
        assertTrue(LocalInventory.cached(assets.catalog(), assets::inspect, emptySet()).isEmpty())
    }

    @Test fun lateCloseFromClearedStreamDoesNotDeleteNewRecording() = runBlocking {
        val http = OkHttpClient()
        val assets = OfflineAssets(temp.newFolder(), MockAnyListenGateway(), FileDownloader(http), ArtworkStore(temp.newFolder(), http)) { "https://example.test" }
        val body = "new complete audio".toByteArray()
        val old = assets.openStreamSink(track, "https://example.test/audio", 0, body.size.toLong())!!
        old.write(0, body, 0, 3)
        assets.clearAudio()
        val fresh = assets.openStreamSink(track, "https://example.test/audio", 0, body.size.toLong())!!
        fresh.write(0, body, 0, 4)
        old.close(false)
        fresh.write(4, body, 4, body.size - 4)
        fresh.close(true)
        assertArrayEquals(body, assets.audioFile(track.cacheKey)!!.readBytes())
    }

    @Test fun staleStreamTokenDoesNotPublishAfterCacheClear() = runBlocking {
        val dir = temp.newFolder()
        val http = OkHttpClient()
        val assets = OfflineAssets(dir, MockAnyListenGateway(), FileDownloader(http), ArtworkStore(temp.newFolder(), http)) { "https://example.test" }
        val body = "full-audio".toByteArray()
        val token = assets.streamToken()
        val sink = assets.openStreamSink(track, "https://example.test/audio", 0, body.size.toLong(), token)!!
        sink.write(0, body, 0, body.size)
        assets.clearAudio()
        sink.close(endOfInput = true)
        assertNull(assets.audioFile(track.cacheKey))
        assertTrue(assets.catalog().isEmpty())
    }
}
