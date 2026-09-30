package io.github.nutea.anylisten

import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nutea.anylisten.core.data.local.DownloadEntity
import io.github.nutea.anylisten.core.data.local.TrackEntity
import io.github.nutea.anylisten.core.model.*
import io.github.nutea.anylisten.core.playback.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class SecondBatchPlaybackTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun waitFor(label: String, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 10000
        while (SystemClock.elapsedRealtime() < until) {
            var done = false
            instrumentation.runOnMainSync { done = condition() }
            if (done) return
            Thread.sleep(30)
        }
        fail("Timed out: $label")
    }
    @Test fun serviceAppliesPersistentSpeedAndEqualizerAndRecordsOnlyAudibleTime() {
        val container = (context.applicationContext as AnyListenApp).container
        val oldPlayback = runBlocking { container.settings.playback.first() }
        val oldEffects = runBlocking { container.settings.audioEffects.first() }
        val prefs = context.getSharedPreferences("playback_widget", 0)
        val oldWidget = prefs.getString("snapshot", null)
        val source = waveAudioFixture(context, 12)
        val audio = File(container.downloadsDir, "second-batch-fixture.wav").apply { source.copyTo(this, overwrite = true) }
        val track = Track(TrackIdentity("second-batch-fixture", "audio"), "夜航 · 音效测试", "Any Listen", "Fixture", 12000)
        var bound: MediaController? = null
        try {
            runBlocking {
                container.db.libraryDao().upsertTracks(listOf(TrackEntity.from(track)))
                container.db.downloadDao().upsert(DownloadEntity.from(DownloadRecord(track.cacheKey, track.identity,
                    track.title, track.artist, DownloadStatus.COMPLETED, audio.length(), audio.length(), audio.path)))
                container.db.listeningStatDao().delete(track.cacheKey)
                container.settings.setAudioEffects(AudioEffectsSettings(1.25f, true, "rock"))
            }
            instrumentation.runOnMainSync {
                PlaybackService.pendingPlay.set(PendingPlayback(listOf(track), track))
                context.startService(Intent(context, PlaybackService::class.java))
            }
            waitFor("service starts") { PlaybackService.service != null && PlaybackService.surfaceState.value?.isPlaying == true }
            var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
            instrumentation.runOnMainSync { future = MediaController.Builder(context,
                SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync() }
            bound = future!!.get(10, TimeUnit.SECONDS)
            waitFor("speed propagated") { bound!!.playbackParameters.speed == 1.25f }
            instrumentation.runOnMainSync {
                assertEquals(1f, bound!!.playbackParameters.pitch)
                assertEquals(track.cacheKey, bound!!.currentMediaItem!!.mediaId)
            }
            // A seek near the end must not add virtual listening time or a play count.
            Thread.sleep(700)
            instrumentation.runOnMainSync { bound!!.seekTo(10000); bound!!.pause() }
            Thread.sleep(400)
            val first = runBlocking { container.db.listeningStatDao().observe().first().firstOrNull { it.trackKey == track.cacheKey } }
            assertTrue((first?.listenedMs ?: 0) < 3000); assertEquals(0L, first?.plays ?: 0)
            instrumentation.runOnMainSync { bound!!.seekTo(0); bound!!.play() }
            Thread.sleep(6300)
            instrumentation.runOnMainSync { bound!!.pause() }
            Thread.sleep(400)
            val after = runBlocking { container.db.listeningStatDao().observe().first().first { it.trackKey == track.cacheKey } }
            assertEquals(1L, after.plays)
            assertTrue(after.listenedMs in 6000..9000)
            Thread.sleep(700)
            val paused = runBlocking { container.db.listeningStatDao().observe().first().first { it.trackKey == track.cacheKey } }
            assertEquals(after.listenedMs, paused.listenedMs)
            val state = PlaybackService.audioEffects.value
            assertEquals("rock", state.settings.preset)
            android.util.Log.i("SecondBatchEqFixture", "available=${state.available}; bands=${state.bands.map { it.frequencyHz }}")
            if (state.available) {
                assertTrue(state.bands.isNotEmpty())
                state.bands.forEach { band -> assertEquals(presetGain("rock", band.frequencyHz).coerceIn(state.minGain, state.maxGain), band.gain) }
            }
            runBlocking { container.settings.setAudioEffects(AudioEffectsSettings()) }
            waitFor("reset speed") { bound!!.playbackParameters.speed == 1f }
            instrumentation.runOnMainSync { assertEquals(1f, bound!!.playbackParameters.pitch); bound!!.release(); bound = null; context.stopService(Intent(context, PlaybackService::class.java)) }
            waitFor("service stopped") { PlaybackService.service == null }
            runBlocking { container.settings.setAudioEffects(AudioEffectsSettings(1.5f)) }
            instrumentation.runOnMainSync {
                PlaybackService.pendingPlay.set(PendingPlayback(listOf(track), track))
                context.startService(Intent(context, PlaybackService::class.java))
            }
            waitFor("settings restored after service restart") { PlaybackService.audioEffects.value.settings.speed == 1.5f && PlaybackService.surfaceState.value?.isPlaying == true }
        } finally {
            instrumentation.runOnMainSync { bound?.release(); context.stopService(Intent(context, PlaybackService::class.java)) }
            waitFor("cleanup") { PlaybackService.service == null }
            runBlocking {
                PlaybackService.awaitPlaybackPersistence()
                container.settings.setPlayback(oldPlayback); container.settings.setAudioEffects(oldEffects)
                container.offlineAssets.clearTrack(track.cacheKey)
                container.db.downloadDao().delete(track.cacheKey)
                container.db.listeningStatDao().delete(track.cacheKey)
                container.db.openHelper.writableDatabase.execSQL("DELETE FROM tracks WHERE cacheKey = ?", arrayOf(track.cacheKey))
            }
            prefs.edit().putString("snapshot", oldWidget).commit()
            audio.delete(); source.delete()
        }
    }
}
