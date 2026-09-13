package io.github.nutea.anylisten

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.nutea.anylisten.core.model.*
import io.github.nutea.anylisten.ui.*
import io.github.nutea.anylisten.ui.screens.*
import io.github.nutea.anylisten.ui.theme.AnyListenTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LibraryBackStackTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var nav: NavHostController
    private var stale: NavBackStackEntry? = null
    private var songActions = 0
    private val lists = listOf(Playlist("a", "Playlist A", "user", 20), Playlist("b", "Playlist B", "user", 20))
    private val songs = lists.associate { playlist -> playlist.id to (0..19).map {
        Track(TrackIdentity("nav-fixture", "${playlist.id}-$it"), "${playlist.name} song $it", "Artist", "Album", 120000)
    } }
    private val snapshot = LibrarySnapshot(lists, songs, 1, false)

    private fun setup(motion: Boolean = false) {
        compose.mainClock.autoAdvance = false
        compose.setContent { AnyListenTheme { Surface(Modifier.fillMaxSize()) {
            nav = rememberNavController()
            Column {
                LibraryNavHost(nav, Modifier.weight(1f), motion = motion) {
                    libraryPage("library") { entry ->
                        LibraryOverviewContent(LibraryUiState(snapshot = snapshot), { null },
                            { playlist -> if (nav.acceptsInput(entry)) nav.navigate("playlist/${playlist.id}") }, {}, {})
                    }
                    libraryPage("playlist/{id}") { entry ->
                        val id = entry.arguments!!.getString("id")!!
                        val playlist = lists.first { it.id == id }
                        SideEffect { stale = entry }
                        LibraryContent(LibraryUiState(snapshot = snapshot, selected = playlist, filtered = songs[id]!!),
                            null, { null }, { false }, {}, {}, {}, {}, {},
                            onBack = { if (nav.acceptsInput(entry)) nav.popBackStack() }) { _, _ ->
                            if (nav.acceptsInput(entry)) songActions++
                        }
                    }
                    libraryPage("settings") { Text("Settings fixture") }
                }
                TextButton(onClick = { nav.selectMainTab("library") }) { Text("Library tab") }
            }
        } } }
        settle()
    }
    private fun settle() { compose.mainClock.advanceTimeBy(64); compose.waitForIdle() }

    @Test fun animatedPagesStayAdjacentAndRejectInputDuringBothDirections() {
        setup(motion = true)
        repeat(6) {
            compose.onNodeWithTag("playlist_a").performTouchInput { click() }
            settle()
            compose.runOnIdle { assertFalse(nav.acceptsInput(stale!!)) }
            val overview = compose.onNodeWithTag("library_overview").fetchSemanticsNode().boundsInRoot
            val detail = compose.onNodeWithTag("song_list").fetchSemanticsNode().boundsInRoot
            assertTrue("Pages must not overlap during entry", overview.right <= detail.left + 1f)
            compose.mainClock.advanceTimeBy(400); compose.waitForIdle()
            val old = stale!!
            compose.runOnIdle { assertTrue(nav.acceptsInput(old)); nav.popBackStack() }
            settle()
            compose.runOnIdle { assertFalse(nav.acceptsInput(old)) }
            val returning = compose.onNodeWithTag("library_overview").fetchSemanticsNode().boundsInRoot
            val leaving = compose.onNodeWithTag("song_list").fetchSemanticsNode().boundsInRoot
            assertTrue("Pages must not overlap during return", returning.right <= leaving.left + 1f)
            compose.mainClock.advanceTimeBy(400); compose.waitForIdle()
            compose.onNodeWithTag("song_list").assertDoesNotExist()
        }
        assertEquals(0,songActions)
    }

    @Test fun rapidBackAndReentryNeverClicksOutgoingSongs() {
        setup()
        repeat(16) { index ->
            val id = if (index % 2 == 0) "a" else "b"
            compose.onNodeWithTag("playlist_$id").performTouchInput { click() }
            settle()
            compose.onNodeWithTag("song_list").assertIsDisplayed()
            val old = stale!!
            compose.runOnIdle { assertTrue(nav.acceptsInput(old)); nav.popBackStack() }
            settle()
            compose.onNodeWithTag("song_list").assertDoesNotExist()
            compose.onNodeWithTag("library_overview").assertIsDisplayed()
            compose.runOnIdle { assertFalse(nav.acceptsInput(old)) }
        }
        assertEquals(0,songActions)
    }

    @Test fun libraryTabNeverRestoresThePlaylistItJustLeft() {
        setup()
        repeat(6) {
            compose.onNodeWithTag("playlist_a").performTouchInput { click() }
            settle()
            compose.onNodeWithText("Library tab").performTouchInput { click() }
            settle()
            compose.onNodeWithTag("library_overview").assertIsDisplayed()
            compose.onNodeWithTag("song_list").assertDoesNotExist()
            compose.runOnIdle { nav.selectMainTab("settings") }
            settle()
            compose.onNodeWithText("Library tab").performTouchInput { click() }
            settle()
            compose.onNodeWithTag("library_overview").assertIsDisplayed()
        }
        assertEquals(0,songActions)
    }
}
