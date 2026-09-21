package io.github.nutea.anylisten

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nutea.anylisten.core.data.repo.CommentRepository
import io.github.nutea.anylisten.core.data.gateway.MusicComments
import io.github.nutea.anylisten.core.model.*
import io.github.nutea.anylisten.ui.PlayerUiState
import io.github.nutea.anylisten.ui.screens.*
import io.github.nutea.anylisten.ui.theme.AnyListenTheme
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Screenshots and accessibility checks for the actual feature composables. No server writes. */
class FeatureUiPolishTest {
    @get:Rule val compose = createComposeRule()
    private fun label(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    private fun screenshot(name: String) {
        compose.waitForIdle()
        compose.onAllNodes(isRoot()).onLast().captureToImage()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "ui-review").apply { mkdirs() }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private val track = Track(TrackIdentity("fixture", "design"), "在晚风里听见你", "Afterglow · 夜航电台", "", 60000)
    @Test fun commentsRemainReadableInLightDarkAndLargeText() {
        var dark by mutableStateOf(false)
        val service = MusicComments { method, _ ->
            Json.parseToJsonElement(when (method) {
                "getResourceList" -> """{"resources":{"musicComment":[{"id":"fixture","extensionId":"fixture","name":"云端音乐"}]}}"""
                "findMusic" -> """{"id":"fixture"}"""
                else -> """{"list":[{"id":"1","userName":"落日收藏家","text":"有些歌适合戴上耳机，一个人慢慢走。前奏响起的瞬间，好像又回到了那个有晚风的夏天。","time":1758400000000,"location":"上海","likedCount":1286,"reply":[{"id":"r","userName":"凌晨两点","text":"音乐真的会替我们记住很多事情。"}]},{"id":"2","userName":"不赶路的人","text":"把今天的疲惫留在这首歌里，明天继续好好生活。","time":1758300000000,"likedCount":328},{"id":"3","userName":"Echo","text":"The kind of song that feels like coming home.","likedCount":96}],"page":1,"limit":20,"total":186}"""
            })
        }
        compose.setContent {
            AnyListenTheme(darkTheme = dark) { CommentsSheet(track, CommentRepository(service)) {} }
        }
        compose.onNodeWithText("落日收藏家").assertIsDisplayed()
        compose.onNodeWithText(label(R.string.comments_next)).assertIsDisplayed()
        screenshot("polish-comments-light")
        compose.runOnIdle { dark = true }
        screenshot("polish-comments-dark")
        compose.onNodeWithContentDescription(label(R.string.close_panel)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.comments_next)).assertIsDisplayed()
        screenshot("polish-comments-large")
    }

    @Test fun lyricSettingsRemainScrollableWithLargeText() {
        var dark by mutableStateOf(false)
        var state by mutableStateOf(PlayerUiState(track = track, lyricOffsetMs = 200,
            lyrics = LrcParser.parse("[00:01.00]風が吹く", "[00:01.00]风轻轻吹过", "[00:01.00]kaze ga fuku", "[00:01.00]<0,500>風<500,300>が<800,400>吹く")))
        compose.setContent {
                AnyListenTheme(darkTheme = dark) { Surface { Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    PlayerContent(state, { null }, false, false, PlayerActions({}, {}, {}, {}, {}, {}, {}, {}, {},
                        translation = { state = state.copy(showTranslation = it) },
                        lyricOffset = { state = state.copy(lyricOffsetMs = it) }), startSheet = "lyrics")
                } } }
        }
        compose.onNodeWithTag("lyric_translation_toggle").assertIsOn()
        screenshot("polish-lyrics-light")
        compose.runOnIdle { dark = true }
        screenshot("polish-lyrics-dark")
        compose.onNodeWithText(label(R.string.lyric_later)).performScrollTo().performClick()
        compose.onNodeWithTag("lyric_offset_value").assertTextEquals(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.lyric_offset, 300L))
        screenshot("polish-lyrics-large")
    }
}
