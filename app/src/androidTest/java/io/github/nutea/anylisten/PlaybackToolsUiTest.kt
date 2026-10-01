package io.github.nutea.anylisten

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nutea.anylisten.core.model.*
import io.github.nutea.anylisten.ui.PlayerUiState
import io.github.nutea.anylisten.ui.screens.*
import io.github.nutea.anylisten.ui.theme.AnyListenTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class PlaybackToolsUiTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val track = Track(TrackIdentity("fixture", "tools"), "夜航 · 测试曲", "Any Listen", "", 180000, extension = "flac")
    private fun actions() = PlayerActions({}, {}, {}, {}, {}, {}, {}, {}, {})
    private fun capture(name: String) {
        compose.waitForIdle()
        compose.onAllNodes(isRoot()).onLast().captureToImage()
        val folder = File(context.getExternalFilesDir(null), "ui-review").apply { mkdirs() }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun timerPresetsCustomValidationAndCancellation() {
        var state by mutableStateOf(PlayerUiState(track = track))
        var started = 0L
        val controls = actions().copy(sleepStart = { started = it; state = state.copy(sleepTimer = SleepTimerState(remainingMs = it)) },
            sleepAfterTrack = { state = state.copy(sleepTimer = SleepTimerState(endOfTrack = true)) },
            sleepCancel = { state = state.copy(sleepTimer = SleepTimerState()) })
        compose.setContent { AnyListenTheme { SleepTimerContent(state, controls, {}) } }
        compose.onNodeWithTag("sleep_preset_15").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(queueReady = true) }
        for (minutes in listOf(15, 30, 60)) {
            compose.onNodeWithTag("sleep_preset_$minutes").performClick()
            assertEquals(minutes * 60000L, started)
        }
        for (invalid in listOf("0", "1441", "")) {
            compose.onNodeWithTag("sleep_custom_input").performTextReplacement(invalid)
            compose.onNodeWithTag("sleep_custom_start").assertIsNotEnabled()
        }
        compose.onNodeWithTag("sleep_custom_input").performTextReplacement("17")
        compose.onNodeWithTag("sleep_custom_start").performClick()
        assertEquals(17 * 60000L, started)
        compose.onNodeWithTag("sleep_after_track").performClick()
        compose.runOnIdle { assertTrue(state.sleepTimer.endOfTrack) }
        capture("playback-sleep-timer")
        compose.onNodeWithTag("sleep_cancel").performClick()
        compose.runOnIdle { assertFalse(state.sleepTimer.active) }
    }

    @Test fun audioPanelShowsDecoderDataAndMissingValuesHonestly() {
        var state by mutableStateOf(PlayerUiState(track = track.copy(rawJson = "{\"quality\":\"128k\"}"),
            audioInfo = AudioInfo(track.cacheKey, "audio/flac", 923000, 96000, 2, AudioLocation.DOWNLOAD)))
        compose.setContent { AnyListenTheme { AudioInfoContent(state, downloaded = true) } }
        compose.onNodeWithText("923 kbps").assertIsDisplayed()
        compose.onNodeWithText("96000 Hz").assertIsDisplayed()
        compose.onAllNodesWithText("FLAC", useUnmergedTree = true).assertCountEquals(2)
        compose.onNodeWithText("128 kbps").assertDoesNotExist()
        capture("playback-audio-info")
        compose.runOnIdle { state = state.copy(audioInfo = AudioInfo(track.cacheKey)) }
        compose.onNodeWithText("923 kbps").assertDoesNotExist()
        compose.onAllNodesWithText(context.getString(R.string.audio_unavailable)).assertCountEquals(5)
    }

    @Test fun longPressReordersBothSectionsWithoutSelectingASong() {
        val tracks = (0..5).map { Track(TrackIdentity("fixture", "$it"), "Track $it", "Artist", "", 1000) }
        var state by mutableStateOf(PlayerUiState(track = tracks[0], queue = tracks,
            laterKeys = listOf(tracks[1].cacheKey, tracks[2].cacheKey)))
        var selected = 0
        compose.setContent { AnyListenTheme {
            QueueSheetContent(state, { null }, { selected++ }, {}, { from, to ->
                QueueReorder.move(state.queue, state.track?.cacheKey, state.laterKeys, from, to)?.let {
                    state = state.copy(queue = it.queue, laterKeys = it.laterKeys)
                }
            })
        } }
        fun drag(from: Int, to: Int) {
            val source = compose.onNodeWithTag("queue_drag_$from", useUnmergedTree = true)
            val target = compose.onNodeWithTag("queue_drag_$to", useUnmergedTree = true)
            val distance = target.fetchSemanticsNode().boundsInRoot.center.y - source.fetchSemanticsNode().boundsInRoot.center.y
            source.performTouchInput { down(center); advanceEventTime(650); moveBy(Offset(0f, distance)); advanceEventTime(100); up() }
            compose.waitForIdle()
        }
        drag(2, 1)
        compose.runOnIdle { assertEquals(listOf(tracks[2].cacheKey, tracks[1].cacheKey), state.laterKeys) }
        drag(5, 3)
        compose.runOnIdle {
            assertEquals(listOf(tracks[5], tracks[3], tracks[4]), QueueSections.from(state.queue, tracks[0].cacheKey, state.laterKeys).rest)
            assertEquals(0, selected)
        }
        capture("playback-queue-reorder")
    }

    @Test fun playerToolsRemainReachableInBothPlayerStyles() {
        var style by mutableStateOf(PlayerStyle.CLASSIC)
        var state by mutableStateOf(PlayerUiState(track = track, queueReady = true, isPlaying = true,
            positionMs = 12000, durationMs = 180000,
            audioInfo = AudioInfo(track.cacheKey, "audio/flac", 923000, 96000, 2, AudioLocation.DOWNLOAD)))
        val controls = actions().copy(style = { style = it },
            sleepStart = { state = state.copy(sleepTimer = SleepTimerState(remainingMs = it)) },
            sleepCancel = { state = state.copy(sleepTimer = SleepTimerState()) })
        compose.setContent { AnyListenTheme { PlayerContent(state, { null }, false, false, controls, downloaded = true, style = style) } }
        compose.onNodeWithTag("player_more").performClick()
        compose.onNodeWithTag("sleep_timer_button").assertIsDisplayed().performClick()
        capture("playback-timer-sheet")
        compose.onNodeWithTag("sleep_preset_30").performClick()
        compose.onNodeWithTag("sleep_timer_sheet").assertDoesNotExist()
        compose.onNodeWithTag("active_sleep_timer").assertIsDisplayed()
        compose.onNodeWithTag("player_more").performClick()
        compose.onNodeWithTag("audio_info_button").performScrollTo().performClick()
        capture("playback-audio-sheet")
        compose.onNodeWithText("923 kbps").assertIsDisplayed()
        instrumentation.uiAutomation.executeShellCommand("input keyevent 4").let {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() }
        }
        compose.onNodeWithTag("audio_info_sheet").assertDoesNotExist()
        compose.runOnIdle { style = PlayerStyle.IMMERSIVE }
        compose.onNodeWithTag("active_sleep_timer").assertIsDisplayed()
        compose.onNodeWithTag("player_more").assertIsDisplayed()
        compose.onNodeWithTag("audio_info_button").assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.cd_pause)).assertIsDisplayed()
        capture("playback-tools-immersive")
    }
}
