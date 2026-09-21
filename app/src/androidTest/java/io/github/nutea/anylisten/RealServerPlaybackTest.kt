package io.github.nutea.anylisten

import android.content.ComponentName
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nutea.anylisten.core.model.DownloadStatus
import io.github.nutea.anylisten.core.model.SidecarState
import io.github.nutea.anylisten.core.playback.PlaybackService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** Opt-in end-to-end check. Credentials are staged in app-private storage, never test arguments. */
class RealServerPlaybackTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val container get() = (context.applicationContext as AnyListenApp).container

    private fun <T> main(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private suspend fun await(label: String, timeout: Long = 45_000, check: () -> Boolean) {
        assertNotNull("Timed out: $label", withTimeoutOrNull(timeout) { while (!main(check)) delay(200); true })
        File(context.filesDir, "real-server-results.txt").appendText("PASS $label\n")
    }

    @Test fun onlinePlaybackAndDownload() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("real_server") == "online")
        val results = File(context.filesDir, "real-server-results.txt")
        results.writeText("")
        val credentials = File(context.filesDir, "real-server.env")
        if (credentials.exists() && container.sessionStore.current() == null) {
            val values = credentials.readLines().filter { '=' in it && !it.startsWith("#") }
                .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim().trim('"', '\'') }
            credentials.delete()
            compose.waitUntil(15_000) {
                compose.onAllNodesWithContentDescription("Server URL").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("Server URL").performTextReplacement(values.getValue("ANYLISTEN_BASE_URL"))
            compose.onNodeWithContentDescription("Password").performTextReplacement(values.getValue("ANYLISTEN_PASSWORD"))
            compose.onNode(hasText(context.getString(R.string.connect_action)) and hasClickAction())
                .performScrollTo().performClick()
        }
        credentials.delete()
        await("authenticated through app", 60_000) { container.isConnected() }
        val library = container.library.refresh()
        val tracks = library.tracksByPlaylist.values.flatten().distinctBy { it.cacheKey }.take(3)
        assertEquals("Need three tracks for the real playback check", 3, tracks.size)
        results.appendText("PASS library synchronized; playlists=${library.playlists.size}\n")
        main { context.startService(Intent(context, PlaybackService::class.java)) }
        await("playback service ready") { PlaybackService.service != null }
        val future = main {
            MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
        }
        val player = future.get(30, TimeUnit.SECONDS)
        try {
            main { PlaybackService.service!!.playTracks(tracks) }
            await("stream playing") { player.isPlaying && player.currentPosition > 1_000 }
            val position = main { player.currentPosition }
            delay(3_000)
            assertTrue(main { player.currentPosition } > position + 1_500)
            results.appendText("PASS playback clock advances\n")
            main { player.pause() }
            await("pause") { !player.isPlaying && !player.playWhenReady }
            val paused = main { player.currentPosition }
            delay(1_500)
            assertTrue(kotlin.math.abs(main { player.currentPosition } - paused) < 300)
            main { player.play() }
            await("resume") { player.isPlaying }
            main { player.seekTo(30_000) }
            await("seek to 30 seconds") { player.isPlaying && player.currentPosition in 29_000..40_000 }
            main { player.seekToNextMediaItem() }
            await("next track") { player.isPlaying && player.currentMediaItem?.mediaId == tracks[1].cacheKey }
            main { player.seekToPreviousMediaItem() }
            await("previous track") { player.isPlaying && player.currentMediaItem?.mediaId == tracks[0].cacheKey }
            main { player.seekTo(player.duration - 2_000) }
            await("automatic transition at track end") { player.isPlaying && player.currentMediaItem?.mediaId == tracks[1].cacheKey }
            main { player.repeatMode = Player.REPEAT_MODE_ONE }
            await("repeat one enabled") { player.repeatMode == Player.REPEAT_MODE_ONE }
            main { player.seekTo(player.duration - 2_000) }
            await("repeat one loops") { player.isPlaying && player.currentMediaItem?.mediaId == tracks[1].cacheKey && player.currentPosition < 10_000 }
            main { player.repeatMode = Player.REPEAT_MODE_ALL; player.shuffleModeEnabled = true }
            await("shuffle enabled") { player.shuffleModeEnabled }
            main { player.shuffleModeEnabled = false }
            main { compose.activity.moveTaskToBack(true) }
            val backgroundPosition = main { player.currentPosition }
            delay(10_000)
            assertTrue(main { player.isPlaying && player.currentPosition > backgroundPosition + 7_000 })
            results.appendText("PASS background playback advances\n")
            // Play a whole real track, including its natural end, with the display asleep.
            main { player.seekTo(0, 0); player.play() }
            await("full-track playback started") { player.isPlaying && player.currentMediaItem?.mediaId == tracks[0].cacheKey && player.duration > 0 }
            val duration = main { player.duration }
            instrumentation.uiAutomation.executeShellCommand("input keyevent 223").close()
            try {
                var lastPosition = -1L
                var stalledSamples = 0
                withTimeout(duration + 90_000) {
                    while (main { player.currentMediaItem?.mediaId } == tracks[0].cacheKey) {
                        delay(5_000)
                        assertNull("No player error during full-track playback", main { player.playerError })
                        val current = main { player.currentPosition }
                        stalledSamples = if (current <= lastPosition) stalledSamples + 1 else 0
                        assertTrue("Playback must not stall for 30 seconds", stalledSamples < 6)
                        lastPosition = current
                    }
                }
                await("full-track natural transition while screen asleep") { player.isPlaying && player.currentMediaItem?.mediaId == tracks[1].cacheKey }
                assertNotNull("A completely played track must be cached", container.offlineAssets.audioFile(tracks[0].cacheKey))
                results.appendText("PASS complete real track; durationMs=$duration; screen asleep; streaming cache complete\n")
            } finally {
                instrumentation.uiAutomation.executeShellCommand("input keyevent 224").close()
                instrumentation.uiAutomation.executeShellCommand("wm dismiss-keyguard").close()
            }
            main { player.pause() }
            val downloadTrack = tracks[2]
            container.downloads.enqueue(listOf(downloadTrack))
            withTimeout(180_000) {
                container.downloads.observe().first { records -> records.any { it.cacheKey == downloadTrack.cacheKey && it.status == DownloadStatus.COMPLETED } }
            }
            assertTrue(container.downloads.completedFile(downloadTrack.cacheKey)!!.length() > 0)
            results.appendText("PASS audio download completed\n")
            val resource = container.gateway.resolveMedia(downloadTrack, false)
            withContext(Dispatchers.IO) {
                container.http.newCall(Request.Builder().url(resource.url).header("Range", "bytes=0-65535").build()).execute().use { response ->
                    assertTrue(response.isSuccessful)
                    val remote = checkNotNull(response.body).byteStream().readNBytes(65536)
                    val local = container.downloads.completedFile(downloadTrack.cacheKey)!!.inputStream().use { it.readNBytes(65536) }
                    assertArrayEquals("Downloaded content must match the selected server track", remote, local)
                }
            }
            results.appendText("PASS downloaded bytes match server audio\n")
            container.offlineAssets.cacheExtras(downloadTrack)
            assertTrue(container.offlineAssets.lyrics(downloadTrack).lines.isNotEmpty())
            assertEquals(SidecarState.READY, container.offlineAssets.inspect(downloadTrack.cacheKey).cover)
            assertNotNull(container.artwork.cached(checkNotNull(container.offlineAssets.savedCoverUrl(downloadTrack.cacheKey))))
            results.appendText("PASS cover and lyrics available offline\n")
            File(context.filesDir, "real-server-track.txt").writeText(downloadTrack.cacheKey)
            File(context.filesDir, "real-server-cached-track.txt").writeText(tracks[0].cacheKey)
            main { PlaybackService.service!!.playTracks(tracks, start = tracks[0], startPositionMs = 15_000) }
            await("final playable queue") { player.isPlaying && player.currentMediaItem?.mediaId == tracks[0].cacheKey }
            main { player.pause() }
            delay(1_500)
        } finally {
            main { player.release() }
        }
    }

    @Test fun downloadedTrackPlaysWithNetworkDisabled() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("real_server") == "offline")
        assertFalse("Disable emulator networking before running this phase", container.network.status.value.online)
        val tracks = listOf("real-server-cached-track.txt", "real-server-track.txt").map { name ->
            checkNotNull(container.library.cachedTrack(File(context.filesDir, name).readText()))
        }
        assertNotNull(container.offlineAssets.audioFile(tracks[0].cacheKey))
        assertNull("Cached test track must not also be a download", container.downloads.completedFile(tracks[0].cacheKey))
        assertNotNull(container.downloads.completedFile(tracks[1].cacheKey))
        main { context.startService(Intent(context, PlaybackService::class.java)) }
        await("offline playback service ready") { PlaybackService.service != null }
        val future = main {
            MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
        }
        val player = future.get(30, TimeUnit.SECONDS)
        try {
            for ((index, track) in tracks.withIndex()) {
                val label = if (index == 0) "streaming cache" else "download"
                main { PlaybackService.service!!.playTracks(listOf(track)) }
                await("offline $label playing") { player.isPlaying && player.currentMediaItem?.mediaId == track.cacheKey && player.currentPosition > 1_000 }
                val position = main { player.currentPosition }
                delay(5_000)
                assertTrue(main { player.currentPosition } > position + 3_000)
                assertTrue(container.offlineAssets.lyrics(track).lines.isNotEmpty())
                main { player.pause() }
                File(context.filesDir, "real-server-results.txt").appendText("PASS offline $label clock advances and cached lyrics load\n")
            }
        } finally { main { player.release() } }
    }
}
