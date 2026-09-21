package io.github.nutea.anylisten

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nutea.anylisten.core.model.*
import io.github.nutea.anylisten.ui.*
import io.github.nutea.anylisten.ui.screens.*
import io.github.nutea.anylisten.ui.theme.AnyListenTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class CatalogFeatureTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val first = Track(TrackIdentity("server", "one"), "晚风寄来的信", "夜航电台 / Echo", "Blue / 夜色 ? # 100%", 180000, rawJson = """{"meta":{"trackNo":1}}""")
    private val second = first.copy(identity = TrackIdentity("server", "two"), title = "把月亮留在窗边", rawJson = """{"meta":{"trackNo":2}}""")
    private val third = first.copy(identity = TrackIdentity("server", "three"), title = "日落之前", album = "日落之前")
    private val index = MusicCatalog.build(LibrarySnapshot(emptyList(), mapOf("a" to listOf(first, second, third), "b" to listOf(first)), 1, false))
    private fun label(id: Int) = context.getString(id)
    private fun show(content: @Composable () -> Unit) {
        compose.setContent { AnyListenTheme { Surface { Column(Modifier.fillMaxSize().safeDrawingPadding()) { content() } } } }
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        compose.onAllNodes(isRoot()).onLast().captureToImage()
        val image = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val folder = File(context.getExternalFilesDir(null), "ui-review").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
    @Test fun artistAlbumNavigationPreservesSpecialCharactersAndBackStack() {
        var played = emptyList<Track>()
        var downloads = emptyList<Track>()
        show {
            val nav = rememberNavController()
            LibraryNavHost(nav, motion = false) {
                libraryPage("library") {
                    CatalogIndexContent(index, true, { null }, {}, { nav.navigate(artistRoute(it)) }, {})
                }
                libraryPage("artist/{server}/{name}") { entry ->
                    val artist = index.artist(MusicCatalog.ArtistKey(entry.arguments!!.getString("server")!!, entry.arguments!!.getString("name")!!))
                    CatalogDetailContent(artist = artist, artwork = { null }, currentKey = null, isPlaying = false, offline = false,
                        downloaded = { it == first }, availableOffline = { true }, onBack = { nav.popBackStack() }, onArtist = {},
                        onAlbum = { nav.navigate(albumRoute(it)) }, onPlay = { tracks, _ -> played = tracks }, onDownload = { downloads = it })
                }
                libraryPage("album/{server}/{artist}/{name}") { entry ->
                    val key = MusicCatalog.AlbumKey(entry.arguments!!.getString("server")!!, entry.arguments!!.getString("artist")!!, entry.arguments!!.getString("name")!!)
                    CatalogDetailContent(album = index.album(key), artwork = { null }, currentKey = null, isPlaying = false, offline = false,
                        downloaded = { false }, availableOffline = { true }, onBack = { nav.popBackStack() }, onArtist = {},
                        onAlbum = {}, onPlay = { tracks, _ -> played = tracks }, onDownload = { downloads = it })
                }
            }
        }
        compose.onNodeWithText(first.artist).performClick()
        compose.onNodeWithTag("catalog_play_all").performClick()
        compose.runOnIdle { assertEquals(listOf(first, second, third), played) }
        compose.onNodeWithTag("catalog_download_all").performClick()
        compose.runOnIdle { assertEquals(listOf(second, third), downloads) }
        screenshot("catalog-artist")
        compose.onNodeWithText(first.album).performScrollTo().performClick()
        compose.onNodeWithTag("catalog_album_artist").assertExists()
        compose.onNodeWithTag("catalog_play_all").performClick()
        compose.runOnIdle { assertEquals(listOf(first, second), played) }
        screenshot("catalog-album")
        compose.onNodeWithContentDescription(label(R.string.catalog_back)).performClick()
        compose.onNodeWithText(context.getString(R.string.catalog_artist_count, 3, 2)).assertExists()
    }
    @Test fun albumIndexSearchesByTitleAndArtist() {
        show { CatalogIndexContent(index, false, { null }, {}, {}, {}) }
        compose.onNodeWithTag("catalog_search").performTextInput("Blue")
        compose.onNodeWithText(first.album).assertExists()
        compose.onNodeWithText(third.album).assertDoesNotExist()
        compose.onNodeWithTag("catalog_search").performTextReplacement("Echo")
        compose.onNodeWithText(third.album).assertExists()
        compose.onNodeWithTag("catalog_search").performTextReplacement("no matches")
        compose.onNodeWithText(label(R.string.catalog_empty)).assertExists()
    }
    @Test fun offlineOnlyPlaysLocalTracksAndDisablesDownloads() {
        var played = emptyList<Track>()
        show {
            CatalogDetailContent(album = index.album(MusicCatalog.albumKey(first)!!), artwork = { null }, currentKey = null,
                isPlaying = false, offline = true, downloaded = { it == first }, availableOffline = { it == first },
                onBack = {}, onArtist = {}, onAlbum = {}, onPlay = { tracks, _ -> played = tracks }, onDownload = { error("offline download") })
        }
        compose.onNodeWithTag("catalog_download_all").assertIsNotEnabled()
        compose.onNodeWithTag("catalog_play_all").performClick()
        compose.runOnIdle { assertEquals(listOf(first), played) }
        compose.onNodeWithTag("catalog_track_1").performScrollTo().assertIsNotEnabled()
    }
    @Test fun playerMetadataProvidesSeparateArtistAndAlbumActions() {
        var artist = false
        var album = false
        show { PlayerContent(PlayerUiState(track = first), { null }, false, false,
            PlayerActions({}, {}, {}, {}, {}, {}, {}, {}, {}, artist = { artist = true }, album = { album = true })) }
        compose.onNodeWithTag("player_artist").performClick()
        compose.onNodeWithTag("player_album").performClick()
        compose.runOnIdle { assertTrue(artist); assertTrue(album) }
        screenshot("catalog-player-links")
    }
}
