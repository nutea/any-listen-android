package io.github.nutea.anylisten

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nutea.anylisten.core.model.*
import io.github.nutea.anylisten.ui.*
import io.github.nutea.anylisten.ui.screens.*
import io.github.nutea.anylisten.ui.theme.AnyListenTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SearchScopeUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = "夜航/a%?#"
    private val first = Track(TrackIdentity("scope-fixture", "1"), "呼吸有害", "莫文蔚", "测试专辑", 60000)
    private val second = first.copy(identity = TrackIdentity("scope-fixture", "2"), title = "忽然之间")
    private val outside = first.copy(identity = TrackIdentity("scope-fixture", "3"), title = "盛夏的果实")
    private val playlist = Playlist(id, "夜航", "user", 2)
    private val snapshot = LibrarySnapshot(listOf(playlist, Playlist("other", "Other", "user", 2)),
        mapOf(id to listOf(first, second), "other" to listOf(first, outside)), 0, false)

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { AnyListenTheme { Surface(Modifier.fillMaxSize()) { Column(Modifier.safeDrawingPadding()) { content() } } } }
    }
    private fun awaitSong(title: String) {
        compose.waitUntil(5000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun awaitAbsent(title: String) {
        compose.waitUntil(5000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isEmpty() }
    }

    @Test fun scopedSearchAndPlaybackQueueExcludeOtherPlaylists() {
        var queue = emptyList<Track>()
        var played: Track? = null
        show { LibrarySearchContent(snapshot, null, { null }, { false }, { false }, {},
            { tracks, track -> queue = tracks; played = track }, playlistId = id) { _, _ -> } }
        compose.onNodeWithTag("library_search_scope").assertTextEquals(context.getString(R.string.library_search_playlist_scope, playlist.name))
        compose.onNodeWithTag("library_search_input").performTextInput("mww")
        awaitSong(second.title)
        compose.onAllNodesWithText(first.title).assertCountEquals(1)
        compose.onNodeWithText(outside.title).assertDoesNotExist()
        compose.onNodeWithText(second.title).performClick()
        compose.runOnIdle { assertEquals(listOf(first, second), queue); assertEquals(second, played) }
    }

    @Test fun historyDownloadSmartFiltersAndStatsStayWithinScope() {
        val now = System.currentTimeMillis()
        val stats = listOf(ListeningStat(first.cacheKey, 120000, 2, now - 40L * 86400000),
            ListeningStat(outside.cacheKey, 6000000, 100, now - 40L * 86400000))
        show { LibrarySearchContent(snapshot, null, { null }, { false }, { false }, {}, { _, _ -> },
            history = listOf(outside.title), stats = stats,
            downloadedKeys = setOf(first.cacheKey, outside.cacheKey), playlistId = id) { _, _ -> } }
        compose.onNodeWithTag("search_history_${outside.title}").performClick()
        compose.onNodeWithTag("library_search_input").assertTextContains(outside.title)
        compose.onNodeWithText(context.getString(R.string.search_empty_detail)).assertIsDisplayed()
        compose.onNodeWithTag("library_search_input").performTextClearance()
        compose.onNodeWithTag("search_downloaded").performScrollTo().performClick()
        awaitSong(first.title)
        compose.onNodeWithText(second.title).assertDoesNotExist()
        compose.onNodeWithText(outside.title).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.listening_summary, 2L, 2L)).assertIsDisplayed()
        listOf("FREQUENT", "REDISCOVER", "RANDOM").forEach { mode ->
            compose.onNodeWithTag("smart_$mode").performScrollTo().performClick()
            awaitSong(first.title)
            compose.onNodeWithText(outside.title).assertDoesNotExist()
        }
    }

    @Test fun removedPlaylistDisablesSearchAndNeverRevealsGlobalResults() {
        val state = mutableStateOf(snapshot)
        show { LibrarySearchContent(state.value, null, { null }, { false }, { false }, {}, { _, _ -> },
            playlistId = id) { _, _ -> } }
        compose.onNodeWithTag("library_search_input").performTextInput("莫文蔚")
        awaitSong(first.title)
        compose.runOnIdle { state.value = snapshot.copy(playlists = snapshot.playlists.filterNot { it.id == id }) }
        compose.onNodeWithTag("library_search_input").assertIsNotEnabled()
        compose.onNodeWithTag("search_downloaded").assertIsNotEnabled()
        compose.onNodeWithTag("library_search_scope").assertTextEquals(context.getString(R.string.library_search_playlist_missing))
        compose.onNodeWithText(first.title).assertDoesNotExist()
        compose.onNodeWithText(outside.title).assertDoesNotExist()
    }

    @Test fun navigationPreservesEncodedPlaylistScopeAndOverviewRemainsGlobal() {
        lateinit var nav: NavHostController
        show {
            nav = rememberNavController()
            LibraryNavHost(nav, motion = false) {
                libraryPage("library") {
                    LibraryOverviewContent(LibraryUiState(snapshot = snapshot, selected = playlist), { null },
                        { nav.navigate("playlist/${android.net.Uri.encode(it.id)}") }, { nav.navigate(librarySearchRoute()) })
                }
                libraryPage("playlist/{playlistId}") { entry ->
                    val openedId = entry.arguments!!.getString("playlistId")!!
                    LibraryContent(LibraryUiState(snapshot = snapshot, selected = playlist, filtered = snapshot.tracksByPlaylist.getValue(openedId)),
                        null, { null }, { false }, {}, {}, {}, {},
                        onBack = { nav.popBackStack() }, onSearch = { nav.navigate(librarySearchRoute(openedId)) }) { _, _ -> }
                }
                libraryPage("search") {
                    LibrarySearchContent(snapshot, null, { null }, { false }, { false }, { nav.popBackStack() }, { _, _ -> }) { _, _ -> }
                }
                libraryPage(PLAYLIST_SEARCH_ROUTE) { entry ->
                    LibrarySearchContent(snapshot, null, { null }, { false }, { false }, { nav.popBackStack() }, { _, _ -> },
                        playlistId = entry.arguments!!.getString("playlistId")!!) { _, _ -> }
                }
            }
        }
        compose.onNodeWithTag("playlist_$id").performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.show_search)).performClick()
        compose.runOnIdle { assertEquals(id, nav.currentBackStackEntry!!.arguments!!.getString("playlistId")); assertTrue(isLibrarySearchRoute(nav.currentDestination?.route)) }
        compose.onNodeWithTag("library_search_input").performTextInput("莫文蔚")
        awaitSong(second.title)
        compose.onNodeWithText(outside.title).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.back_to_library)).performClick()
        compose.onNodeWithTag("song_list").assertIsDisplayed()
        compose.runOnIdle { assertEquals(id, nav.currentBackStackEntry!!.arguments!!.getString("playlistId")); nav.popBackStack() }
        compose.onNodeWithContentDescription(context.getString(R.string.show_search)).performClick()
        compose.onNodeWithTag("library_search_scope").assertTextEquals(context.getString(R.string.library_search_scope))
        compose.onNodeWithTag("library_search_input").performTextInput("莫文蔚")
        awaitSong(outside.title)
        compose.onAllNodesWithText(first.title).assertCountEquals(1)
        compose.runOnIdle { assertEquals("search", nav.currentDestination?.route) }
        compose.onNodeWithTag("library_search_input").performTextReplacement("missing")
        awaitAbsent(first.title)
    }
}
