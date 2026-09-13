package io.github.nutea.anylisten

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nutea.anylisten.core.data.ArtworkStore
import io.github.nutea.anylisten.core.data.OfflineAssets
import io.github.nutea.anylisten.core.data.download.FileDownloader
import io.github.nutea.anylisten.core.data.gateway.AnyListenGateway
import io.github.nutea.anylisten.core.data.gateway.MockAnyListenGateway
import io.github.nutea.anylisten.core.model.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/** Exercises Android image decoding and atomic file replacement without touching user resources. */
class CacheRefreshAcceptanceTest {
    @Test fun sameUrlChangesReachRetainedAudioCoverAndLyricsAndSurviveOffline() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "cache-refresh-test-${System.nanoTime()}").apply { mkdirs() }
        var version = 1
        var online = true
        var fail = false
        var requests = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            check(online)
            val request = chain.request()
            val unchanged = request.header("If-None-Match") == "v$version"
            val bytes = if (request.url.encodedPath.startsWith("/cover")) {
                ByteArrayOutputStream().use { output ->
                    Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply {
                        eraseColor(if (version == 1) Color.RED else Color.BLUE)
                        compress(Bitmap.CompressFormat.PNG, 100, output)
                        recycle()
                    }
                    output.toByteArray()
                }
            } else "audio version $version".toByteArray()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (fail) 503 else if (unchanged) 304 else 200).message("fixture")
                .header("ETag", "v$version").body(bytes.toResponseBody()).build()
        }.build()
        val track = Track(TrackIdentity("cache-fixture", "1"), "Fixture", "Artist", "Album", 1000)
        var coverPath = "/cover"
        val gateway = object : AnyListenGateway by MockAnyListenGateway() {
            override fun isOnline() = online
            override suspend fun resolveMedia(track: Track, refresh: Boolean) = MediaResource("https://cache-fixture.invalid/audio")
            override suspend fun resolveCover(track: Track) = "https://cache-fixture.invalid$coverPath"
            override suspend fun resolveLyrics(track: Track): Lyrics {
                check(online && !fail)
                return LrcParser.parse("[00:00.00]lyric version $version")
            }
        }
        try {
            val artwork = ArtworkStore(File(root, "artwork"), http, { online })
            val assets = OfflineAssets(File(root, "offline"), gateway, FileDownloader(http), artwork) { "https://cache-fixture.invalid" }
            val audio = assets.cacheAudio(track)
            assertTrue(assets.cacheExtras(track, force = true))
            val url = assets.savedCoverUrl(track.cacheKey)!!
            val cover = artwork.cached(url)!!
            assertEquals(Color.RED, BitmapFactory.decodeFile(cover.path).getPixel(0, 0))
            val revision = artwork.updates.value
            version = 2
            assets.cacheAudio(track, force = true)
            assets.cacheExtras(track, force = true)
            assertEquals("audio version 2", audio.readText())
            assertEquals("lyric version 2", assets.cachedLyrics(track)!!.lineAt(0))
            assertEquals(Color.BLUE, BitmapFactory.decodeFile(cover.path).getPixel(0, 0))
            assertTrue(artwork.updates.value > revision)
            val changedAt = audio.lastModified()
            assets.cacheAudio(track, force = true) // Conditional 304 keeps committed file.
            assertEquals(changedAt, audio.lastModified())
            coverPath = "/cover-new"
            assets.cacheExtras(track, force = true)
            assertTrue(assets.savedCoverUrl(track.cacheKey)!!.endsWith("/cover-new"))
            fail = true
            assets.cacheAudio(track, force = true)
            assets.cacheExtras(track, force = true)
            assertEquals("audio version 2", audio.readText())
            assertEquals("lyric version 2", assets.cachedLyrics(track)!!.lineAt(0))
            online = false
            val before = requests
            assets.cacheAudio(track, force = true)
            assets.cacheExtras(track, force = true)
            assertEquals(before, requests)
            assertTrue(artwork.get(assets.savedCoverUrl(track.cacheKey)!!, force = true).isFile)
            assertEquals(before, requests)
        } finally {
            root.deleteRecursively()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }
}
