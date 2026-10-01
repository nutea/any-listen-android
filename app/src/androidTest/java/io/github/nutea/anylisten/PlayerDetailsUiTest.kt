package io.github.nutea.anylisten

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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

class PlayerDetailsUiTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val track = Track(TrackIdentity("fixture", "details"), "月光落在唱片上", "夜航电台", "失眠的星球", 213000)
    private val state = PlayerUiState(track = track, queueReady = true, isPlaying = true,
        positionMs = 8000, durationMs = 213000,
        lyrics = LrcParser.parse("[00:01]让音乐慢慢铺满整个夜晚\n[00:12]听见每一句旋律"))
    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onAllNodes(isRoot()).onLast().captureToImage()
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val folder = File(context.getExternalFilesDir(null), "ui-review").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
    private fun back() {
        instrumentation.uiAutomation.executeShellCommand("input keyevent 4").let {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() }
        }
        compose.waitForIdle()
    }
    @Test fun mainActionsStayDirectAndDetailsCloseBeforeExternalActions() {
        var favorites = 0; var comments = 0; var downloads = 0; var adds = 0; var albums = 0; var artists = 0
        val cover = File(context.cacheDir, "details-fixture.png")
        val bitmap = Bitmap.createBitmap(480, 480, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bitmap).apply {
            drawColor(android.graphics.Color.rgb(177, 181, 230))
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            paint.color = android.graphics.Color.rgb(89, 79, 157); drawCircle(240f, 240f, 195f, paint)
            paint.color = android.graphics.Color.rgb(225, 216, 241); drawCircle(240f, 240f, 128f, paint)
            paint.color = android.graphics.Color.rgb(89, 79, 157); drawCircle(240f, 240f, 15f, paint)
        }
        cover.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        try {
            compose.setContent { AnyListenTheme { Surface {
                val view = androidx.compose.ui.platform.LocalView.current
                val dark = io.github.nutea.anylisten.ui.theme.LocalDarkTheme.current
                DisposableEffect(view, dark) {
                    val window = (view.context as? android.app.Activity)?.window
                    val controller = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
                    val previousStatus = controller?.isAppearanceLightStatusBars
                    val previousNavigation = controller?.isAppearanceLightNavigationBars
                    controller?.isAppearanceLightStatusBars = !dark
                    controller?.isAppearanceLightNavigationBars = !dark
                    onDispose {
                        if (previousStatus != null) controller?.isAppearanceLightStatusBars = previousStatus
                        if (previousNavigation != null) controller?.isAppearanceLightNavigationBars = previousNavigation
                    }
                }
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    PlayerContent(state, { cover.toURI().toString() }, true, false,
                        PlayerActions({}, {}, {}, {}, { favorites++ }, {}, {}, {}, {}, download = { downloads++ },
                            comments = { comments++ }, more = { adds++ }, artist = { artists++ }, album = { albums++ }))
                }
            } } }
            for (tag in listOf("sleep_timer_button", "audio_info_button", "audio_effects_button", "player_album", "player_style_button"))
                compose.onNodeWithTag(tag).assertDoesNotExist()
            capture("player-layout-classic")
            compose.onNode(hasContentDescription(context.getString(R.string.cd_unfavorite)) and hasAnyAncestor(hasTestTag("cover_page"))).performClick()
            compose.onNodeWithTag("player_download").performClick()
            compose.onNodeWithTag("player_comments").performClick()
            compose.runOnIdle { assertEquals(1, favorites); assertEquals(1, downloads); assertEquals(1, comments) }
            compose.onNodeWithTag("player_more").performClick()
            compose.onNodeWithTag("player_details_sheet").assertIsDisplayed()
            capture("player-layout-details")
            compose.onNodeWithTag("player_add_to_playlist").performClick()
            compose.onNodeWithTag("player_details_sheet").assertDoesNotExist()
            compose.runOnIdle { assertEquals(1, adds) }
            compose.onNodeWithTag("player_more").performClick()
            compose.onNodeWithTag("player_album").performScrollTo().performClick()
            compose.onNodeWithTag("player_details_sheet").assertDoesNotExist()
            compose.runOnIdle { assertEquals(1, albums) }
            compose.onNodeWithTag("player_more").performClick()
            compose.onNodeWithTag("player_details_artist").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(1, artists) }
        } finally { cover.delete() }
    }
    @Test fun lyricsPageKeepsTransportAndCanReachEffectsAndRepair() {
        var speed = 1f; var repair = false
        compose.setContent { AnyListenTheme { Surface {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                PlayerContent(state, { null }, false, false,
                    PlayerActions({}, {}, {}, {}, {}, {}, {}, {}, {}, effects = { speed = it.speed }, repairLyrics = { repair = true }))
            }
        } } }
        compose.onNodeWithText(context.getString(R.string.open_lyrics)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.cd_pause)).assertIsDisplayed()
        compose.onNodeWithTag("player_more").performClick()
        compose.onNodeWithTag("audio_effects_button").performScrollTo().performClick()
        compose.onNodeWithTag("speed_1.5").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1.5f, speed) }
        back()
        compose.onNodeWithTag("player_more").performClick()
        compose.onNodeWithTag("lyrics_settings_button").performScrollTo().performClick()
        compose.onNodeWithTag("lyric_repair_button").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(repair) }
        compose.onNodeWithTag("player_more").assertIsDisplayed()
    }
    @Test fun offlineMenuKeepsLocalToolsAndDisablesUnavailableMetadataAndServerAdd() {
        compose.setContent { AnyListenTheme { Surface {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                PlayerContent(state.copy(track = track.copy(artist = "", album = ""), availableOffline = true),
                    { null }, false, true, PlayerActions({}, {}, {}, {}, {}, {}, {}, {}, {}, more = { error("offline write") }))
            }
        } } }
        compose.onNodeWithTag("player_more").performClick()
        compose.onNodeWithTag("player_add_to_playlist").assertIsNotEnabled()
        compose.onNodeWithTag("player_style_button").assertIsEnabled()
        compose.onNodeWithTag("sleep_timer_button").assertIsEnabled()
        compose.onNodeWithTag("player_album").assertIsNotEnabled()
        compose.onNodeWithTag("player_details_artist").assertIsNotEnabled()
        compose.onNodeWithTag("audio_info_button").performScrollTo().performClick()
        compose.onNodeWithTag("audio_info_sheet").assertIsDisplayed()
    }
}
