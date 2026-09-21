package io.github.nutea.anylisten

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nutea.anylisten.core.model.*
import io.github.nutea.anylisten.ui.*
import io.github.nutea.anylisten.ui.screens.*
import io.github.nutea.anylisten.ui.theme.AnyListenTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlaylistLyricsFeatureTest {
    @get:Rule val compose = createComposeRule()
    private fun label(id: Int, vararg args: Any) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)
    private fun show(content: @Composable () -> Unit) {
        compose.setContent { AnyListenTheme { Surface(Modifier.fillMaxSize()) { Column(Modifier.safeDrawingPadding()) { content() } } } }
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        compose.onAllNodes(isRoot()).onLast().captureToImage()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "ui-review").apply { mkdirs() }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        java.io.File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private val a = Playlist("a", "Evening", "general", 0)
    private val b = Playlist("b", "Morning", "general", 0)
    private fun state(offline: Boolean = false) = LibraryUiState(snapshot = LibrarySnapshot(
        listOf(Playlist("love", "Love", "default", 0), a, b), emptyMap(), 1, offline))

    @Test fun playlistCreateRenameReorderAndDeleteConfirmation() {
        var edit: PlaylistEdit? = null
        show {
            var current by remember { mutableStateOf(state()) }
            LibraryOverviewContent(current, { null }, {}, {}, onEdit = {
                edit = it
                current = current.copy(playlistEditSuccess = current.playlistEditSuccess + 1)
            })
        }
        compose.onNodeWithContentDescription(label(R.string.playlist_create)).performClick()
        screenshot("playlist-create")
        compose.onNodeWithText(label(R.string.playlist_save)).assertIsNotEnabled()
        compose.onNodeWithTag("playlist_name").performTextInput("New list")
        compose.onNodeWithText(label(R.string.playlist_save)).performClick()
        compose.runOnIdle { assertEquals("New list", (edit as PlaylistEdit.Create).name) }
        compose.onNodeWithContentDescription(label(R.string.playlist_options, a.name)).performClick()
        screenshot("playlist-actions")
        compose.onNodeWithText(label(R.string.playlist_rename)).performClick()
        compose.onNodeWithTag("playlist_name").performTextReplacement("Renamed")
        compose.onNodeWithText(label(R.string.playlist_save)).performClick()
        compose.runOnIdle { assertEquals(PlaylistEdit.Rename("a", "Renamed"), edit) }
        compose.onNodeWithContentDescription(label(R.string.playlist_options, a.name)).performClick()
        compose.onNodeWithText(label(R.string.playlist_move_down)).performClick()
        compose.runOnIdle { assertEquals(PlaylistEdit.Move("a", 1), edit); edit = null }
        compose.onNodeWithContentDescription(label(R.string.playlist_options, a.name)).performClick()
        compose.onNodeWithText(label(R.string.playlist_delete)).performClick()
        compose.runOnIdle { assertNull(edit) }
        compose.onNodeWithText(label(R.string.dialog_cancel)).performClick()
        compose.runOnIdle { assertNull(edit) }
        compose.onNodeWithContentDescription(label(R.string.playlist_options, a.name)).performClick()
        compose.onNodeWithText(label(R.string.playlist_delete)).performClick()
        compose.onNode(hasText(label(R.string.playlist_delete)) and hasClickAction()).performClick()
        compose.runOnIdle { assertEquals(PlaylistEdit.Delete("a"), edit) }
    }

    @Test fun failedCreateKeepsNameAndIdForRetry() {
        val attempts = mutableListOf<PlaylistEdit.Create>()
        show {
            var current by remember { mutableStateOf(state()) }
            LibraryOverviewContent(current, { null }, {}, {}, onEdit = {
                attempts += it as PlaylistEdit.Create
                current = current.copy(playlistError = "Unable to confirm; retry")
            })
        }
        compose.onNodeWithContentDescription(label(R.string.playlist_create)).performClick()
        compose.onNodeWithTag("playlist_name").performTextInput("Keep this name")
        compose.onNodeWithText(label(R.string.playlist_save)).performClick()
        compose.onNodeWithText("Unable to confirm; retry").assertExists()
        compose.onNodeWithTag("playlist_name").assertTextContains("Keep this name")
        compose.onNodeWithText(label(R.string.playlist_save)).performClick()
        compose.runOnIdle {
            assertEquals(2, attempts.size)
            assertEquals(attempts[0], attempts[1])
        }
    }

    @Test fun offlineDisablesPlaylistManagement() {
        show { LibraryOverviewContent(state(true), { null }, {}, {}) }
        compose.onNodeWithContentDescription(label(R.string.playlist_create)).assertIsNotEnabled()
        compose.onNodeWithContentDescription(label(R.string.playlist_options, a.name)).assertIsNotEnabled()
        compose.onNodeWithContentDescription(label(R.string.playlist_options, "Love")).assertDoesNotExist()
    }

    @Test fun bilingualLyricsToggleAndOffsetAffectSeek() {
        var seek = -1L
        val track = Track(TrackIdentity("fixture", "song"), "Test Song", "Artist", "Album", 60_000)
        show {
            var current by remember { mutableStateOf(PlayerUiState(track = track, durationMs = 60_000,
                lyrics = LrcParser.parse("[00:01.00]Original line\n[00:03.00]Second line", "[00:01.00]翻译歌词\n[00:03.00]第二句"))) }
            PlayerContent(current, { null }, false, true, PlayerActions({}, {}, {}, {}, {}, { seek = it }, {}, {}, {},
                translation = { current = current.copy(showTranslation = it) },
                lyricOffset = { current = current.copy(lyricOffsetMs = it) }))
        }
        compose.onNodeWithText(label(R.string.open_lyrics)).performClick()
        compose.onNodeWithText("翻译歌词").assertExists()
        screenshot("bilingual-lyrics")
        compose.onNodeWithContentDescription(label(R.string.lyric_settings)).performClick()
        compose.onNodeWithTag("lyric_translation_toggle").performClick()
        compose.onNodeWithText(label(R.string.lyric_later)).performClick()
        compose.onNodeWithTag("lyric_offset_value").assertTextEquals(label(R.string.lyric_offset, 100L))
        screenshot("lyric-settings")
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithText("翻译歌词").assertDoesNotExist()
        compose.onNodeWithText("Original line").performClick()
        compose.runOnIdle { assertEquals(1100L, seek) }
        compose.onNodeWithContentDescription(label(R.string.lyric_settings)).performClick()
        compose.onNodeWithText(label(R.string.lyric_offset_reset)).performClick()
        compose.onNodeWithTag("lyric_offset_value").assertTextEquals(label(R.string.lyric_offset, 0L))
    }
}
