package io.github.nutea.anylisten

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nutea.anylisten.core.model.*
import io.github.nutea.anylisten.ui.PlayerUiState
import io.github.nutea.anylisten.ui.screens.*
import io.github.nutea.anylisten.ui.theme.AnyListenTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class KaraokeLyricsFeatureTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun label(id: Int) = context.getString(id)
    private fun screenshot(name: String) {
        compose.waitForIdle()
        compose.onAllNodes(isRoot()).onLast().captureToImage()
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val folder = File(context.getExternalFilesDir(null), "ui-review").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun romanizationAndKaraokeToggleIndependentlyAndSeekUsesOffset() {
        var seek = -1L
        val track = Track(TrackIdentity("fixture", "karaoke"), "歌词功能测试", "测试歌手", "", 60_000)
        val lyrics = LrcParser.parse("[00:01.00]風が吹く\n[00:04.00]空が青い", "[00:01.00]风在吹\n[00:04.00]天空湛蓝",
            "[00:01.00]kaze ga fuku\n[00:04.00]sora ga aoi", "[00:01.00]<0,500>風<500,300>が<800,400>吹く\n[00:04.00]<0,500>空<500,300>が<800,500>青い")
        compose.setContent { AnyListenTheme { Surface(Modifier.fillMaxSize()) {
            var state by remember { mutableStateOf(PlayerUiState(track = track, lyrics = lyrics, durationMs = 60_000,
                positionMs = 1750, lyricOffsetMs = 200)) }
            Column(Modifier.safeDrawingPadding()) {
                PlayerContent(state, { null }, false, true, PlayerActions({}, {}, {}, {}, {}, { seek = it }, {}, {}, {},
                    romanization = { state = state.copy(showRomanization = it) }, karaoke = { state = state.copy(karaokeEnabled = it) }))
            }
        } } }
        compose.onNodeWithText(label(R.string.open_lyrics)).performClick()
        compose.onNodeWithText("kaze ga fuku").assertExists()
        compose.onNodeWithText("风在吹").assertExists()
        compose.onNodeWithTag("karaoke_line", useUnmergedTree = true).assertExists()
        screenshot("karaoke-romanization")
        compose.onNodeWithText("風が吹く").performClick()
        compose.runOnIdle { assertEquals(1200L, seek) }
        compose.onNodeWithContentDescription(label(R.string.lyric_settings)).performClick()
        screenshot("karaoke-settings")
        compose.onNodeWithTag("lyric_romanization_toggle").performClick()
        compose.onNodeWithTag("lyric_karaoke_toggle").performClick()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithText("kaze ga fuku").assertDoesNotExist()
        compose.onNodeWithText("风在吹").assertExists()
        compose.onNodeWithTag("karaoke_line", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("風が吹く").assertExists()
        compose.onNodeWithText("<0,500>", substring = true).assertDoesNotExist()
    }

    @Test fun karaokePaintProgressesAcrossWrappedTextAndRewindsOnSeek() {
        val line = LrcParser.parse(null, karaokeRaw = "[00:01.00]<0,1000>風が吹く 空が青い").karaokeLines.single()
        var position by mutableLongStateOf(1000)
        compose.setContent { AnyListenTheme {
            MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = Color.Red, onSurface = Color.Black)) {
                Surface(color = Color.White) { KaraokeLyricText(line, position, Modifier.width(160.dp)) }
            }
        } }
        fun redPixels(): Int {
            val pixels = compose.onNodeWithTag("karaoke_line", useUnmergedTree = true).captureToImage().toPixelMap()
            var count = 0
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                val color = pixels[x, y]
                if (color.red > .7f && color.green < .3f && color.blue < .3f) count++
            }
            return count
        }
        assertEquals(0, redPixels())
        compose.runOnIdle { position = 1500 }
        val partial = redPixels()
        assertTrue(partial > 0)
        compose.runOnIdle { position = 2100 }
        assertTrue(redPixels() > partial)
        compose.runOnIdle { position = 1000 }
        assertEquals(0, redPixels())
    }
}
