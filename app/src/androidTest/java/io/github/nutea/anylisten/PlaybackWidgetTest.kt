package io.github.nutea.anylisten

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.*
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nutea.anylisten.core.data.local.DownloadEntity
import io.github.nutea.anylisten.core.data.local.TrackEntity
import io.github.nutea.anylisten.core.data.session.PersistedPlayback
import io.github.nutea.anylisten.core.model.*
import io.github.nutea.anylisten.core.playback.PlaybackService
import io.github.nutea.anylisten.ui.theme.AnyListenTheme
import io.github.nutea.anylisten.widget.PlaybackWidget
import io.github.nutea.anylisten.widget.PlaybackWidgetProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class PlaybackWidgetTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun waitFor(label: String, predicate: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 10000
        while (SystemClock.elapsedRealtime() < until) {
            var done = false
            instrumentation.runOnMainSync { done = predicate() }
            if (done) return
            Thread.sleep(25)
        }
        fail("Timed out: $label")
    }
    private fun controller(): MediaController {
        var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
        instrumentation.runOnMainSync {
            future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
        }
        return future!!.get(10, TimeUnit.SECONDS)
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        compose.onAllNodes(isRoot()).onLast().captureToImage()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val folder = File(context.getExternalFilesDir(null), "ui-review").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun widgetControlsServiceAndRestoresQueueAfterServiceRestart() {
        val app = context.applicationContext as AnyListenApp
        val container = app.container
        val previous = runBlocking { container.settings.playback.first() }
        val prefs = context.getSharedPreferences("playback_widget", 0)
        val oldSnapshot = prefs.getString("snapshot", null)
        val source = waveAudioFixture(context, 30)
        val audio = File(container.downloadsDir, "widget-fixture.wav").apply { source.copyTo(this, overwrite = true) }
        val cover = File(context.cacheDir, "widget-fixture.png").apply {
            val bitmap = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.rgb(71, 112, 125))
            outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        val tracks = (0..3).map { Track(TrackIdentity("widget-fixture", "$it"), "夜航 $it", "Any Listen",
            "Fixture", 30000, cover.toURI().toString(), extension = "wav") }
        val host = AppWidgetHost(context, 9827)
        val manager = AppWidgetManager.getInstance(context)
        var widgetId = 0
        var view: AppWidgetHostView? = null
        var bound: MediaController? = null
        var width by mutableStateOf(360)
        try {
            runBlocking {
                container.db.libraryDao().upsertTracks(tracks.map(TrackEntity::from))
                tracks.forEach { track -> container.db.downloadDao().upsert(DownloadEntity.from(DownloadRecord(
                    track.cacheKey, track.identity, track.title, track.artist, DownloadStatus.COMPLETED,
                    audio.length(), audio.length(), audio.path))) }
                container.settings.setPlayback(PersistedPlayback(tracks.map { it.cacheKey }, tracks[0].cacheKey,
                    shuffled = true, positionMs = 8000, laterKeys = listOf(tracks[2].cacheKey, tracks[1].cacheKey),
                    playbackOrder = listOf(0, 2, 1, 3).map { tracks[it].cacheKey }))
            }
            instrumentation.uiAutomation.executeShellCommand("appwidget grantbind --package ${context.packageName} --user 0").let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() } }
            instrumentation.runOnMainSync {
                widgetId = host.allocateAppWidgetId()
                assertTrue(manager.bindAppWidgetIdIfAllowed(widgetId, ComponentName(context, PlaybackWidgetProvider::class.java)))
                val options = Bundle().apply { putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 360) }
                manager.updateAppWidgetOptions(widgetId, options)
                view = host.createView(context, widgetId, manager.getAppWidgetInfo(widgetId))
                host.startListening()
            }
            compose.setContent { AnyListenTheme {
                Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    Text("Any Listen · 桌面小组件")
                    AndroidView(factory = { view!! }, modifier = Modifier.width(width.dp).height(160.dp))
                }
            } }
            // Seed the saved display exactly as a previous process would leave it.
            prefs.edit().putString("snapshot", "{\"key\":\"${tracks[0].cacheKey}\",\"title\":\"夜航 0\",\"artist\":\"Any Listen\"}").commit()
            PlaybackWidget.update(context, null)
            waitFor("saved widget title") { view!!.findViewById<TextView>(R.id.widget_title)?.text == "夜航 0" }
            instrumentation.runOnMainSync { assertTrue(view!!.findViewById<View>(R.id.widget_toggle).performClick()) }
            waitFor("cold widget playback") { PlaybackService.surfaceState.value?.isPlaying == true }
            bound = controller()
            waitFor("actual audio format") { PlaybackService.audioInfo.value.sampleRate == 44100 }
            instrumentation.runOnMainSync {
                assertEquals(tracks[0].cacheKey, bound!!.currentMediaItem!!.mediaId)
                assertTrue(bound!!.currentPosition >= 8000)
                assertEquals(listOf(0, 2, 1, 3).map { tracks[it].cacheKey }, PlaybackService.service!!.playbackOrderKeys())
                assertEquals(AudioLocation.DOWNLOAD, PlaybackService.audioInfo.value.location)
                assertEquals(1, PlaybackService.audioInfo.value.channels)
                assertEquals("audio/raw", PlaybackService.audioInfo.value.mimeType)
            }
            waitFor("playing widget") { view!!.findViewById<View>(R.id.widget_toggle).contentDescription == context.getString(R.string.cd_pause) }
            screenshot("playback-widget-wide")
            instrumentation.runOnMainSync {
            val position = bound!!.currentPosition
                assertTrue(PlaybackService.service!!.moveQueueTrack(tracks[1].cacheKey, tracks[2].cacheKey))
                assertEquals(listOf(tracks[1].cacheKey, tracks[2].cacheKey), PlaybackService.service!!.laterQueueKeys())
                assertEquals(tracks[0].cacheKey, bound!!.currentMediaItem!!.mediaId)
                assertTrue(kotlin.math.abs(bound!!.currentPosition - position) < 100)
                assertTrue(bound!!.playWhenReady)
            }
            instrumentation.runOnMainSync { view!!.findViewById<View>(R.id.widget_next).performClick() }
            waitFor("priority next") { bound!!.currentMediaItem?.mediaId == tracks[1].cacheKey }
            var previousKey: String? = null
            instrumentation.runOnMainSync {
                previousKey = bound!!.getMediaItemAt(bound!!.previousMediaItemIndex).mediaId
                view!!.findViewById<View>(R.id.widget_previous).performClick()
            }
            waitFor("previous") { bound!!.currentMediaItem?.mediaId == previousKey }
            instrumentation.runOnMainSync {
                view!!.findViewById<View>(R.id.widget_toggle).performClick()
            }
            waitFor("widget pause") { !bound!!.playWhenReady }
            instrumentation.runOnMainSync { assertFalse(view!!.findViewById<View>(R.id.widget_favorite).isEnabled) }
            compose.runOnIdle { width = 220 }
            manager.updateAppWidgetOptions(widgetId, Bundle().apply { putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 220) })
            PlaybackWidget.update(context)
            waitFor("compact widget") { view!!.findViewById<View>(R.id.widget_cover).visibility == View.GONE }
            screenshot("playback-widget-compact")
            instrumentation.runOnMainSync {
                bound!!.seekTo(10000); bound!!.release(); bound = null
                context.stopService(Intent(context, PlaybackService::class.java))
            }
            waitFor("service stopped") { PlaybackService.service == null }
            // Merely opening the app binds an idle MediaSession; it must keep the saved card.
            bound = controller()
            instrumentation.runOnMainSync { assertEquals(0, bound!!.mediaItemCount) }
            waitFor("idle service keeps last title") { view!!.findViewById<TextView>(R.id.widget_title).text.toString().startsWith("夜航") }
            instrumentation.runOnMainSync { bound!!.release(); bound = null; context.stopService(Intent(context, PlaybackService::class.java)) }
            waitFor("idle service stopped") { PlaybackService.service == null }
            waitFor("paused snapshot") { view!!.findViewById<View>(R.id.widget_toggle).contentDescription == context.getString(R.string.cd_play) }
            instrumentation.runOnMainSync { view!!.findViewById<View>(R.id.widget_toggle).performClick() }
            waitFor("widget service restart") { PlaybackService.surfaceState.value?.isPlaying == true }
            bound = controller()
            instrumentation.runOnMainSync { assertTrue(bound!!.currentPosition >= 10000); PlaybackService.service!!.startSleepTimer(250) }
            waitFor("background service timer") { !bound!!.playWhenReady && !PlaybackService.sleepTimerState.value.active }
            waitFor("widget follows timer") { view!!.findViewById<View>(R.id.widget_toggle).contentDescription == context.getString(R.string.cd_play) }
        } finally {
            instrumentation.runOnMainSync {
                bound?.release(); context.stopService(Intent(context, PlaybackService::class.java))
                host.stopListening(); if (widgetId != 0) host.deleteAppWidgetId(widgetId)
            }
            waitFor("cleanup service") { PlaybackService.service == null }
            runBlocking {
                PlaybackService.awaitPlaybackPersistence()
                container.settings.setPlayback(if (previous.cacheKeys.all { it.startsWith("widget-fixture:") }) PersistedPlayback() else previous)
                tracks.forEach { track ->
                    container.offlineAssets.clearTrack(track.cacheKey)
                    container.db.listeningStatDao().delete(track.cacheKey)
                    container.db.downloadDao().delete(track.cacheKey)
                    container.db.openHelper.writableDatabase.execSQL("DELETE FROM tracks WHERE cacheKey = ?", arrayOf(track.cacheKey))
                }
            }
            prefs.edit().putString("snapshot", oldSnapshot?.takeUnless { "widget-fixture:" in it }).commit()
            audio.delete(); cover.delete(); source.delete()
            instrumentation.uiAutomation.executeShellCommand("appwidget revokebind --package ${context.packageName} --user 0").let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() } }
        }
    }
}
