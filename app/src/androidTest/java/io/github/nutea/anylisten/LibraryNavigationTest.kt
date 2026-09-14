package io.github.nutea.anylisten

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nutea.anylisten.core.model.*
import io.github.nutea.anylisten.ui.LibraryUiState
import io.github.nutea.anylisten.ui.screens.*
import io.github.nutea.anylisten.ui.theme.AnyListenTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class LibraryNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val first = Track(TrackIdentity("fixture", "1"), "午夜漫游", "林间来信", "夜色散步", 218000)
    private val other = Track(TrackIdentity("fixture", "2"), "星河之间", "远山", "夜色散步", 246000)
    private val lists = listOf(Playlist("love", "我喜欢", "love", 1), Playlist("last_played", "最近播放", "last_played", 1), Playlist("default", "默认列表", "default", 1), Playlist("evening", "夜色散步", "user", 2), Playlist("travel", "沿途的风景", "user", 1))
    private val snapshot = LibrarySnapshot(lists, mapOf("love" to listOf(first), "last_played" to listOf(first), "default" to listOf(first), "evening" to listOf(first, other), "travel" to listOf(other)), 1, false)
    private fun show(content: @Composable () -> Unit) { compose.setContent { AnyListenTheme { Surface(Modifier.fillMaxSize()) { Column(Modifier.safeDrawingPadding()) { content() } } } } }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        compose.onRoot().captureToImage()
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val folder = File(context.getExternalFilesDir(null), "ui-review").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun overviewShowsThreeHorizontalEntriesAndCustomPlaylistRows() {
        var opened: String? = null
        var search = false
        show { LibraryOverviewContent(LibraryUiState(snapshot = snapshot), { null }, { opened = it.id }, { search = true }, {}) }
        val bounds = listOf("love", "last_played", "default").map { compose.onNodeWithTag("playlist_$it").fetchSemanticsNode().boundsInRoot }
        assertEquals(bounds[0].top, bounds[1].top)
        assertEquals(bounds[1].top, bounds[2].top)
        assertTrue(bounds[0].right < bounds[1].left)
        compose.onNodeWithText(first.title).assertDoesNotExist()
        compose.onNodeWithTag("playlist_evening").performClick()
        compose.runOnIdle { assertEquals("evening", opened) }
        screenshot("library-overview")
        compose.onNodeWithContentDescription(context.getString(R.string.show_search)).performClick()
        compose.runOnIdle { assertTrue(search) }
    }
    @Test fun searchFindsAcrossPlaylistsDeduplicatesAndPlaysResults() {
        var queue = emptyList<Track>()
        var started: Track? = null
        show { LibrarySearchContent(snapshot, null, { null }, { false }, { false }, {}, { tracks, track -> queue = tracks; started = track }) { _, _ -> } }
        compose.onNodeWithText(first.title).assertDoesNotExist()
        compose.onNode(hasSetTextAction()).performTextInput("夜色散步")
        compose.onAllNodesWithText(first.title).assertCountEquals(1)
        compose.onNodeWithText(other.title).assertIsDisplayed()
        compose.onNodeWithText(other.title).performClick()
        compose.runOnIdle { assertEquals(listOf(first.cacheKey, other.cacheKey), queue.map { it.cacheKey }); assertEquals(other, started) }
        screenshot("library-search")
        compose.onNodeWithContentDescription(context.getString(R.string.clear_search)).performClick()
        compose.onNodeWithText(first.title).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.library_search_start)).assertIsDisplayed()
    }

    @Test fun lastPlayedShowsNewestFirstAndEmptyState() {
        val older = Track(TrackIdentity("fixture", "old"), "AAA Song", "Artist", "Album", 1000)
        val newer = Track(TrackIdentity("fixture", "new"), "ZZZ Song", "Artist", "Album", 1000)
        val playlist = Playlist("last_played", "Recently played", "last_played", 2, canMutateOnline = false)
        val filled = LibraryUiState(
            snapshot = LibrarySnapshot(listOf(playlist), mapOf("last_played" to listOf(newer, older)), 1, false),
            selected = playlist,
            filtered = listOf(newer, older),
            sort = TrackSortField.PLAY_TIME,
            sortAscending = false,
        )
        var played: Track? = null
        show { LibraryContent(filled, null, { null }, { false }, {}, {}, {}, {}, {}) { track, action -> if (action == TrackAction.PLAY) played = track } }
        val newerTop = compose.onNodeWithTag("song_${newer.cacheKey}").fetchSemanticsNode().boundsInRoot.top
        val olderTop = compose.onNodeWithTag("song_${older.cacheKey}").fetchSemanticsNode().boundsInRoot.top
        assertTrue(newerTop < olderTop)
        compose.onNodeWithTag("song_${newer.cacheKey}").performClick()
        compose.runOnIdle { assertEquals(newer, played) }
        val empty = filled.copy(
            snapshot = filled.snapshot.copy(tracksByPlaylist = mapOf("last_played" to emptyList()), playlists = listOf(playlist.copy(trackCount = 0))),
            filtered = emptyList(),
        )
        show { LibraryContent(empty, null, { null }, { false }, {}, {}, {}, {}, {}) { _, _ -> } }
        compose.onAllNodesWithText(context.getString(R.string.list_last_played)).onFirst().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.last_played_empty_detail)).assertIsDisplayed()
        screenshot("last-played-empty")
    }
}
