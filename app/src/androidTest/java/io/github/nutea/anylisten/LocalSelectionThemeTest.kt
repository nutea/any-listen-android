package io.github.nutea.anylisten

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nutea.anylisten.core.model.*
import io.github.nutea.anylisten.ui.screens.*
import io.github.nutea.anylisten.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class LocalSelectionThemeTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun label(id: Int, vararg args: Any) = context.getString(id, *args)
    private val tracks = (1..3).map { Track(TrackIdentity("fixture", "$it"), "歌曲 $it", "歌手", "专辑", 120000) }
    private fun assets(origin: LocalOrigin) = tracks.map { LocalAssetItem(it.cacheKey, it.title, it.artist, it.album, it, origin,
        AssetCompleteness(true, SidecarState.READY, SidecarState.READY)) }
    private val calls = mutableListOf<Triple<Int, List<String>, LocalBatchAction>>()
    private var plays = 0
    private fun showLocal() {
        val tasks = tracks.mapIndexed { i, t -> DownloadRecord(t.cacheKey, t.identity, t.title, t.artist,
            listOf(DownloadStatus.FAILED, DownloadStatus.PAUSED, DownloadStatus.DOWNLOADING)[i]) }
        compose.setContent { AnyListenTheme(false) { Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
            DownloadsContent(assets(LocalOrigin.DOWNLOAD), assets(LocalOrigin.CACHE), tasks, StorageSummary(1000,2000,3000),
                onPlay = { plays++ }, onDeleteDownload = {}, onDeleteCache = {}, onRetry = {}, onCancel = {},
                onBatch = { tab, keys, action -> calls.add(Triple(tab, keys, action)) })
        } } }
    }
    private fun selectAll() {
        compose.onNodeWithText(label(R.string.local_multiselect)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.local_select_all)).performClick()
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val dir = File(context.getExternalFilesDir(null), "ui-review").apply { mkdirs() }
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    @Test fun downloadedAndCachedBatchActionsRespectSelectionAndConfirmation() {
        showLocal()
        compose.onNodeWithTag("local_${tracks[0].cacheKey}").performTouchInput { longClick() }
        compose.onNodeWithText(label(R.string.selection_count,1,3)).assertExists()
        compose.onNodeWithTag("local_${tracks[1].cacheKey}").performClick()
        compose.onNodeWithText(label(R.string.play_later)).performClick()
        compose.runOnIdle { assertEquals(0,plays); assertEquals(Triple(0,tracks.take(2).map { it.cacheKey },LocalBatchAction.LATER),calls.single()) }
        selectAll()
        screenshot("local-downloaded-selection")
        compose.onNodeWithText(label(R.string.cached_tab,3)).performClick()
        compose.onNodeWithText(label(R.string.local_multiselect)).assertExists()
        selectAll()
        compose.onNodeWithText(label(R.string.local_unselect_all)).performClick()
        compose.onNodeWithText(label(R.string.local_delete_selected)).assertIsNotEnabled()
        compose.onNodeWithTag("local_${tracks[2].cacheKey}").performClick()
        compose.onNodeWithText(label(R.string.local_delete_selected)).performClick()
        compose.runOnIdle { assertEquals(1,calls.size) }
        compose.onNodeWithText(label(R.string.dialog_cancel)).performClick()
        compose.onNodeWithText(label(R.string.local_delete_selected)).performClick()
        compose.onNodeWithText(label(R.string.local_confirm)).performClick()
        compose.runOnIdle { assertEquals(Triple(1,listOf(tracks[2].cacheKey),LocalBatchAction.DELETE),calls.last()) }
        selectAll()
        compose.onNodeWithText(label(R.string.cd_play)).performClick()
        compose.runOnIdle { assertEquals(Triple(1,tracks.map { it.cacheKey },LocalBatchAction.PLAY),calls.last()) }
    }
    @Test fun taskBatchFiltersStatusesAndBackExitsSelection() {
        showLocal()
        compose.onNodeWithText(label(R.string.tasks_tab,3)).performClick()
        compose.onNodeWithTag("task_${tracks[0].cacheKey}").performTouchInput { longClick() }
        compose.onNodeWithText(label(R.string.local_select_all)).performClick()
        screenshot("local-task-selection")
        compose.onNodeWithText(label(R.string.download_retry)).performClick()
        compose.runOnIdle { assertEquals(Triple(2,tracks.take(2).map { it.cacheKey },LocalBatchAction.RETRY),calls.last()) }
        selectAll()
        compose.onNodeWithText(label(R.string.download_cancel)).performClick()
        compose.onNodeWithText(label(R.string.local_confirm)).performClick()
        compose.runOnIdle { assertEquals(Triple(2,listOf(tracks[2].cacheKey),LocalBatchAction.CANCEL),calls.last()) }
        selectAll()
        compose.onNodeWithText(label(R.string.remove_download_task)).performClick()
        compose.onNodeWithText(label(R.string.local_confirm)).performClick()
        compose.runOnIdle { assertEquals(Triple(2,tracks.take(2).map { it.cacheKey },LocalBatchAction.REMOVE),calls.last()) }
        selectAll()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithText(label(R.string.local_multiselect)).assertExists()
    }
    @Test fun themeChoiceUpdatesWholeThemeAndFollowsSystem() {
        val mode = mutableStateOf(ThemeMode.SYSTEM)
        val systemDark = mutableStateOf(false)
        var actualDark = false
        compose.setContent { AnyListenTheme(mode.value.isDark(systemDark.value)) {
            actualDark = LocalDarkTheme.current
            Surface(Modifier.fillMaxSize().safeDrawingPadding()) { SettingsContent(null,StorageSummary(0,0,0),PlaybackMode.LIST_LOOP,"—",{},{},{},
                themeMode = mode.value, onThemeMode = { mode.value = it }) }
        } }
        compose.onNodeWithText(label(R.string.theme_dark)).performScrollTo().performClick().assertIsSelected()
        compose.runOnIdle { assertTrue(actualDark) }
        screenshot("settings-theme-dark")
        compose.onNodeWithText(label(R.string.theme_light)).performClick().assertIsSelected()
        compose.runOnIdle { assertFalse(actualDark); systemDark.value = true }
        compose.runOnIdle { assertFalse(actualDark) }
        compose.onNodeWithText(label(R.string.theme_system)).performClick().assertIsSelected()
        compose.runOnIdle { assertTrue(actualDark); systemDark.value = false }
        compose.runOnIdle { assertFalse(actualDark) }
        screenshot("settings-theme-light")
    }
}
