package io.github.nutea.anylisten

import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nutea.anylisten.core.model.*
import io.github.nutea.anylisten.ui.LibraryUiState
import io.github.nutea.anylisten.ui.screens.LibraryContent
import io.github.nutea.anylisten.ui.theme.AnyListenTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LocateCurrentTrackTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val tracks = (0..79).map { Track(TrackIdentity("locate-fixture", "$it"), "Song $it", "Artist", "Album", 120000) }
    private val playlist = Playlist("fixture", "Fixture", "user", tracks.size)
    private val state = LibraryUiState(snapshot = LibrarySnapshot(listOf(playlist), mapOf(playlist.id to tracks), 1, false),
        selected = playlist, filtered = tracks.reversed())

    @Test fun locateUsesDisplayedSortAndKeepsPlaybackUntouched() {
        var actions = 0
        val current = tracks[20]
        compose.setContent { AnyListenTheme { Surface(Modifier.fillMaxSize()) {
            LibraryContent(state, current.cacheKey, { null }, { false }, {}, {}, {}, {}) { _, _ -> actions++ }
        } } }
        compose.onNodeWithTag("song_${current.cacheKey}").assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.locate_current_track)).performClick()
        compose.onNodeWithTag("song_${current.cacheKey}").assertIsDisplayed()
        assertEquals(0, actions)
        compose.onNodeWithTag("song_list").performScrollToIndex(70)
        compose.onNodeWithContentDescription(context.getString(R.string.locate_current_track)).assertIsDisplayed().performClick()
        compose.onNodeWithTag("song_${current.cacheKey}").assertIsDisplayed()
        assertEquals(0, actions)
    }

    @Test fun missingCurrentTrackDisablesLocate() {
        compose.setContent { AnyListenTheme {
            LibraryContent(state, "another-playlist-song", { null }, { false }, {}, {}, {}, {}) { _, _ -> }
        } }
        compose.onNodeWithContentDescription(context.getString(R.string.locate_current_track)).assertIsNotEnabled()
    }
}
