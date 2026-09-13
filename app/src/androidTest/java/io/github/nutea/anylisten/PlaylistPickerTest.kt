package io.github.nutea.anylisten

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nutea.anylisten.core.model.Playlist
import io.github.nutea.anylisten.ui.screens.AddToPlaylistSheet
import io.github.nutea.anylisten.ui.theme.AnyListenTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class PlaylistPickerTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun playlistCoverNameCountAndScrollableSelection() {
        val cover = File(context.cacheDir, "picker-fixture.png")
        Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(70, 110, 140))
            cover.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
        val playlists = (0..18).map { Playlist("fixture-$it", if (it == 0) "午后散步 · 收藏的旋律与独处时光" else "音乐收藏 $it", "custom", it * 17) }
        var selected: String? = null
        try {
            compose.setContent {
                var open by remember { mutableStateOf(true) }
                AnyListenTheme {
                    if (open) AddToPlaylistSheet(playlists, 2, { android.net.Uri.fromFile(cover).toString() },
                        { selected = it.id; open = false }, { open = false })
                }
            }
            compose.onNodeWithText(playlists[0].name).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.track_count, 0)).assertIsDisplayed()
            compose.onNodeWithTag("add_playlist_cover_fixture-0", useUnmergedTree = true).assertIsDisplayed()
            compose.waitUntil(5_000) {
                val image = compose.onNodeWithTag("add_playlist_cover_fixture-0", useUnmergedTree = true).captureToImage().asAndroidBitmap()
                image.getPixel(image.width / 2, image.height / 2) == Color.rgb(70, 110, 140)
            }
            compose.onNodeWithTag("add_playlist_sheet").captureToImage().asAndroidBitmap().let { bitmap ->
                File(context.getExternalFilesDir(null), "playlist-picker-preview.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("add_playlist_fixture-18"))
            compose.onNodeWithTag("add_playlist_fixture-18").performClick()
            assertEquals("fixture-18", selected)
            compose.onNodeWithTag("add_playlist_sheet").assertDoesNotExist()
        } finally { cover.delete() }
    }

    @Test fun emptyTargetsCanBeDismissed() {
        compose.setContent {
            var open by remember { mutableStateOf(true) }
            AnyListenTheme {
                if (open) AddToPlaylistSheet(emptyList(), 1, { null }, { error("No target expected") }, { open = false })
            }
        }
        compose.onNodeWithText(context.getString(R.string.no_playlist_targets)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.dialog_cancel)).performClick()
        compose.onNodeWithTag("add_playlist_sheet").assertDoesNotExist()
    }
}
