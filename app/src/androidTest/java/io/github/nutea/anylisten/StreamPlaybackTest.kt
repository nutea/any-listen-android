package io.github.nutea.anylisten

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nutea.anylisten.core.data.*
import io.github.nutea.anylisten.core.data.download.*
import io.github.nutea.anylisten.core.data.gateway.*
import io.github.nutea.anylisten.core.model.*
import io.github.nutea.anylisten.core.playback.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class StreamPlaybackTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val track = Track(TrackIdentity("stream-test", "1"), "Fixture", "Artist", "Album", 10000)
    private val uri = android.net.Uri.parse("https://stream-fixture.invalid/audio.wav")

    @Test fun playbackStartsBeforeRemainingBytesArriveAndSavesSameStream() {
        val root = File(instrumentation.targetContext.cacheDir, "stream-test-${System.nanoTime()}").apply { mkdirs() }
        val payload = wave()
        val releaseTail = CountDownLatch(1)
        val playing = CountDownLatch(1)
        val saved = CountDownLatch(1)
        val count = AtomicInteger()
        val opens = AtomicInteger()
        val mediaCalls = AtomicInteger()
        val gateway = object : AnyListenGateway by MockAnyListenGateway() {
            override suspend fun resolveMedia(track: Track, refresh: Boolean): MediaResource { mediaCalls.incrementAndGet(); error("A second audio transfer must not start") }
            override suspend fun resolveCover(track: Track): String? = null
            override suspend fun resolveLyrics(track: Track) = Lyrics(emptyList(), "")
        }
        val http = OkHttpClient()
        val assets = OfflineAssets(File(root,"offline"), gateway, FileDownloader(http), ArtworkStore(File(root,"art"), http)) { uri.toString() }
        val factory = DataSource.Factory {
            val bytes = ByteArrayDataSource(payload)
            val slow = object : DataSource by bytes {
                override fun open(spec: DataSpec): Long { opens.incrementAndGet(); return bytes.open(spec) }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (count.get() >= 32768) check(releaseTail.await(10, TimeUnit.SECONDS))
                    val n = bytes.read(buffer, offset, minOf(length, 2048))
                    if (n > 0) { count.addAndGet(n); Thread.sleep(5) }
                    return n
                }
            }
            StreamingCacheDataSource(slow, File(root,"recording"), { true }) { spec, file ->
                runBlocking { assets.adoptStream(track, spec.uri.toString(), file, assets.streamToken()) }
                saved.countDown()
            }
        }
        var player: ExoPlayer? = null
        try {
            assets.cachePlayed(track, streaming = true)
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(instrumentation.targetContext)
                    .setLoadControl(streamingLoadControl()).setMediaSourceFactory(DefaultMediaSourceFactory(factory)).build().apply {
                        volume = 0f
                        addListener(object : Player.Listener {
                            override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) playing.countDown() }
                        })
                        setMediaItem(MediaItem.fromUri(uri)); prepare(); play()
                    }
            }
            assertTrue("Playback must start while the tail is withheld", playing.await(8, TimeUnit.SECONDS))
            assertTrue(count.get() < payload.size)
            assertNull(assets.audioFile(track.cacheKey))
            assertEquals(0, mediaCalls.get())
            releaseTail.countDown()
            assertTrue("Full consumed stream should become available offline", saved.await(8, TimeUnit.SECONDS))
            assertArrayEquals(payload, assets.audioFile(track.cacheKey)!!.readBytes())
            assertEquals(1, opens.get())
            assertEquals(0, mediaCalls.get())
        } finally {
            releaseTail.countDown()
            instrumentation.runOnMainSync { player?.release() }
            root.deleteRecursively()
        }
    }

    @Test fun interruptedSeekedAndDisabledStreamsNeverBecomeCompleteFiles() {
        val root = File(instrumentation.targetContext.cacheDir, "stream-partial-${System.nanoTime()}").apply { mkdirs() }
        var completions = 0
        var enabled = true
        try {
            fun readPart(position: Long, disable: Boolean) {
                val source = StreamingCacheDataSource(ByteArrayDataSource(wave()), root, { enabled }) { _, file -> completions++; file.delete() }
                source.open(DataSpec.Builder().setUri(uri).setPosition(position).build())
                source.read(ByteArray(4096), 0, 4096)
                if (disable) enabled = false
                source.close()
            }
            readPart(0, false)
            readPart(10000, false)
            readPart(0, true)
            assertEquals(0, completions)
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun firstResolutionIsSharedAndUsesNormalServerLookup() = runBlocking {
        val root = File(instrumentation.targetContext.cacheDir, "resolver-test-${System.nanoTime()}").apply { mkdirs() }
        val db = androidx.room.Room.inMemoryDatabaseBuilder(instrumentation.targetContext,
            io.github.nutea.anylisten.core.data.local.AppDatabase::class.java).build()
        val flags = mutableListOf<Boolean>()
        val gateway = object : AnyListenGateway by MockAnyListenGateway() {
            override suspend fun resolveMedia(track: Track, refresh: Boolean): MediaResource { flags.add(refresh); delay(100); return MediaResource(uri.toString()) }
        }
        val http = OkHttpClient()
        try {
            val assets = OfflineAssets(File(root,"offline"), gateway, FileDownloader(http), ArtworkStore(File(root,"art"), http)) { uri.toString() }
            val downloads = DownloadCoordinator(db.downloadDao(),db.libraryDao(),gateway,FileDownloader(http),File(root,"downloads"),File(root,"cache"), { Long.MAX_VALUE })
            val resolver = PlaybackResolver(downloads,gateway,assets)
            List(5) { async { resolver.resolve(track) } }.awaitAll()
            assertEquals(listOf(false), flags)
            assertNull(assets.audioFile(track.cacheKey))
            val other = track.copy(identity = TrackIdentity("stream-test", "2"))
            val otherResource = resolver.resolve(other)
            val otherKey = resolver.cacheKey(other,otherResource)
            resolver.invalidateRemoteUrls(track.cacheKey)
            assertEquals(otherResource,resolver.resolve(other))
            assertEquals(otherKey,resolver.cacheKey(other,otherResource))
            assertEquals(listOf(false, false),flags)
            resolver.resolve(track)
            assertEquals(listOf(false, false, true), flags)
        } finally { db.close(); root.deleteRecursively() }
    }

    private fun wave(): ByteArray {
        val size = 160000
        return ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()).putInt(36 + size).put("WAVEfmt ".toByteArray())
                .putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(size).put(ByteArray(size))
        }.array()
    }
}
