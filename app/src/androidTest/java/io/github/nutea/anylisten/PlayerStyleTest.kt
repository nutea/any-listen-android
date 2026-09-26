package io.github.nutea.anylisten

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nutea.anylisten.core.data.session.AppSettingsStore
import io.github.nutea.anylisten.core.model.*
import io.github.nutea.anylisten.ui.PlayerUiState
import io.github.nutea.anylisten.ui.screens.*
import io.github.nutea.anylisten.ui.theme.AnyListenTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class PlayerStyleTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun label(id: Int) = context.getString(id)

    private fun screenshot(name: String) {
        compose.onAllNodes(isRoot()).onLast().captureToImage()
        val folder = File(context.getExternalFilesDir(null), "ui-review").apply { mkdirs() }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun switchStyleKeepsPlaybackAndLyricsUsable() {
        val supplied = InstrumentationRegistry.getArguments().getString("coverPath")?.let(::File)
        val cover = supplied?.takeIf { it.isFile } ?: File(context.cacheDir, "style-fixture.png").also { file ->
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.rgb(165, 45, 30))
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        var style by mutableStateOf(PlayerStyle.CLASSIC)
        var dark by mutableStateOf(false)
        var toggles = 0
        val track = Track(TrackIdentity("fixture", "style"), "呼吸有害", "莫文蔚", "呼吸有害", 192000)
        val state = PlayerUiState(track = track, positionMs = 13000, durationMs = 192000, isPlaying = true,
            lyrics = LrcParser.parse("[00:01.00]让音乐慢慢铺满整个夜晚\n[00:15.00]听见每一句旋律"))
        compose.setContent { AnyListenTheme(darkTheme = dark) {
            PlayerContent(state, { cover.toURI().toString() }, false, false,
                PlayerActions({}, { toggles++ }, {}, {}, {}, {}, {}, {}, {}, style = { style = it }), style = style)
        } }
        compose.onNodeWithTag("immersive_background").assertDoesNotExist()
        compose.onNodeWithContentDescription(label(R.string.player_style)).performClick()
        compose.onNodeWithTag("player_style_IMMERSIVE").performClick()
        compose.onNodeWithTag("immersive_background").assertExists()
        // Wait for the asynchronous local image and palette, independently of server access.
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("immersive_artwork").fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(500)
        screenshot("player-immersive-light")
        compose.onNodeWithContentDescription(label(R.string.cd_pause)).performClick()
        assertEquals(1, toggles)
        compose.onNodeWithText(label(R.string.open_lyrics)).performClick()
        compose.onNodeWithTag("lyrics_page").assertIsDisplayed()
        screenshot("player-immersive-lyrics")
        compose.onNodeWithText(label(R.string.cover_tab)).performClick()
        compose.runOnIdle { dark = true }
        screenshot("player-immersive-dark")
        compose.onNodeWithContentDescription(label(R.string.player_style)).performClick()
        compose.onNodeWithTag("player_style_CLASSIC").performClick()
        compose.onNodeWithTag("immersive_background").assertDoesNotExist()
        compose.onNodeWithTag("cover_page").assertIsDisplayed()
        screenshot("player-classic-restored")
    }

    @Test fun preferenceSurvivesNewStoreAndUnknownValueUsesClassic() = runBlocking {
        val settings = AppSettingsStore(context)
        val previous = settings.playerStyle.first()
        try {
            settings.setPlayerStyle(PlayerStyle.IMMERSIVE)
            assertEquals(PlayerStyle.IMMERSIVE, AppSettingsStore(context).playerStyle.first())
            settings.setPlayerStyle(PlayerStyle.CLASSIC)
            assertEquals(PlayerStyle.CLASSIC, AppSettingsStore(context).playerStyle.first())
            assertEquals(PlayerStyle.CLASSIC, PlayerStyle.decode("unknown"))
        } finally { settings.setPlayerStyle(previous) }
    }

    @Test fun immersiveWithoutArtworkHasUsableControls() {
        compose.setContent { AnyListenTheme(darkTheme = false) {
            PlayerContent(PlayerUiState(track = Track(TrackIdentity("fixture", "missing"), "No artwork", "Artist", "", 1000)),
                { null }, false, true, PlayerActions({}, {}, {}, {}, {}, {}, {}, {}, {}), style = PlayerStyle.IMMERSIVE)
        } }
        compose.onNodeWithTag("immersive_background").assertExists()
        compose.onNodeWithContentDescription(label(R.string.cd_play)).assertIsDisplayed()
        screenshot("player-immersive-no-cover")
    }

    @Test fun corruptArtworkShowsPlaceholder() {
        val cover = File(context.cacheDir, "corrupt-style-cover.jpg").apply { writeText("invalid image") }
        try {
            compose.setContent { AnyListenTheme {
                PlayerContent(PlayerUiState(track = Track(TrackIdentity("fixture", "corrupt"), "Corrupt cover", "", "", 1000)),
                    { cover.toURI().toString() }, false, true, PlayerActions({}, {}, {}, {}, {}, {}, {}, {}, {}),
                    style = PlayerStyle.IMMERSIVE)
            } }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("immersive_artwork").fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("immersive_artwork_placeholder").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("immersive_artwork_placeholder").assertIsDisplayed()
            compose.onNodeWithContentDescription(label(R.string.cd_play)).assertIsDisplayed()
        } finally { cover.delete() }
    }
}
