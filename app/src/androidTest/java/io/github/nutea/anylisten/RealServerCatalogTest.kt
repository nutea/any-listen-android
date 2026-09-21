package io.github.nutea.anylisten

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nutea.anylisten.core.model.MusicCatalog
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Read-only verification of navigation against the signed-in library. */
class RealServerCatalogTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun capture(name: String) {
        compose.waitForIdle()
        compose.onAllNodes(isRoot()).onLast().captureToImage()
        val image = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val folder = File(context.getExternalFilesDir(null), "ui-review").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
    @Test fun browseRealArtistAndAlbumWithoutChangingServerData() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("real_catalog") == "true")
        val container = (context.applicationContext as AnyListenApp).container
        withTimeout(60000) { while (!container.isConnected()) delay(200) }
        val index = MusicCatalog.build(container.library.refresh())
        val artist = index.artists.first { it.albums.isNotEmpty() }
        compose.onNodeWithText(context.getString(R.string.catalog_artists)).performClick()
        compose.onNodeWithTag("catalog_index").performScrollToNode(hasText(artist.name))
        compose.onNodeWithText(artist.name).performClick()
        compose.onNodeWithTag("catalog_play_all").assertIsEnabled()
        capture("catalog-real-artist")
        compose.onNodeWithTag("catalog_detail").performScrollToNode(hasText(context.getString(R.string.catalog_albums)))
        compose.onNodeWithTag("catalog_album_0").performClick()
        compose.onNodeWithTag("catalog_album_artist").assertExists()
        compose.onNodeWithTag("catalog_download_all").assertExists()
        capture("catalog-real-album")
        compose.onNodeWithContentDescription(context.getString(R.string.catalog_back)).performClick()
        compose.onNodeWithText(artist.name).assertExists()
        Unit
    }
}
