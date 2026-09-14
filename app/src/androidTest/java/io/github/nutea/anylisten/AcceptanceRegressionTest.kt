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

/** Acceptance regressions: isolated UI fixtures, no server writes or production file changes. */
class AcceptanceRegressionTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val tracks = listOf(
        Track(TrackIdentity("acceptance", "1"), "Alpha", "Artist", "Album", 120000),
        Track(TrackIdentity("acceptance", "2"), "Beta", "Artist", "Album", 120000),
    )

    @Test fun leavingSearchMustNotLeaveAnInvisiblePlaylistFilter() {
        val playlist = Playlist("love", "Love", "love", 2)
        val state = LibraryUiState(snapshot = LibrarySnapshot(listOf(playlist), mapOf("love" to tracks), 1, false), selected = playlist, filtered = tracks)
        compose.setContent {
            var searching by remember { mutableStateOf(false) }
            AnyListenTheme { Surface(Modifier.fillMaxSize()) {
                if (searching) LibrarySearchContent(state.snapshot, null, { null }, { false }, { false }, { searching = false }, { _, _ -> }) { _, _ -> }
                else LibraryContent(state, null, { null }, { false }, {}, {}, {}, {}, onSearch = { searching = true }) { _, _ -> }
            } }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.show_search)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Alpha")
        compose.onNodeWithText("Beta").assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.back_to_library)).performClick()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithText("Beta").assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.show_search)).performClick()
        compose.onNodeWithText(context.getString(R.string.library_search_start)).assertIsDisplayed()
    }

    @Test fun longUntimedLyricsMustHaveScrollableReadingArea() {
        val raw = (1..80).joinToString("\n") { "Untimed lyric line $it" }
        compose.setContent { AnyListenTheme { Surface(Modifier.fillMaxSize()) {
            PlayerContent(PlayerUiState(track = tracks[0], queue = tracks, lyrics = Lyrics(emptyList(), raw)),
                { null }, false, false, PlayerActions({}, {}, {}, {}, {}, {}, {}, {}, {}))
        } } }
        compose.onNodeWithText(context.getString(R.string.open_lyrics)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("plain_lyrics").assert(hasScrollAction())
    }
}
