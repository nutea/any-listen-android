package io.github.nutea.anylisten

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nutea.anylisten.core.model.*
import io.github.nutea.anylisten.core.data.gateway.*
import kotlinx.serialization.json.*
import io.github.nutea.anylisten.ui.screens.*
import io.github.nutea.anylisten.ui.theme.AnyListenTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class SecondBatchUiTest {
    @get:Rule val compose = createComposeRule()
    private fun track(id: String, title: String) = Track(TrackIdentity("fixture", id), title, "莫文蔚", "测试专辑", 60000)
    private val songs = listOf(track("a", "呼吸有害"), track("b", "忽然之间"), track("c", "盛夏的果实"))
    private val snapshot = LibrarySnapshot(listOf(Playlist("list", "夜航", "general", 3)), mapOf("list" to songs), 0, false)
    private fun themedContent(content: @Composable () -> Unit) {
        compose.setContent { AnyListenTheme { Surface(Modifier.fillMaxSize(), content = content) } }
    }
    private fun capture(name: String) {
        compose.waitForIdle(); compose.onAllNodes(isRoot()).onLast().captureToImage()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val folder = File(context.getExternalFilesDir(null), "ui-review").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun soundSettingsPresetsSpeedAndResetAndUnsupportedDevice() {
        var config by mutableStateOf(AudioEffectsSettings(enabled = true))
        var state by mutableStateOf(AudioEffectsState(bands = listOf(EqualizerBand(60, 0), EqualizerBand(1000, 0), EqualizerBand(14000, 0)), available = true))
        themedContent { AudioEffectsContent(config, state) { config = it } }
        compose.onNodeWithTag("speed_1.5").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1.5f, config.speed) }
        compose.onNodeWithTag("eq_preset_rock").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("rock", config.preset) }
        capture("second-batch-sound")
        compose.onNodeWithTag("effects_reset").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(AudioEffectsSettings(), config); state = AudioEffectsState() }
        compose.onNodeWithTag("equalizer_enable").assertIsNotEnabled()
        compose.onNodeWithTag("speed_1.25").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1.25f, config.speed) }
    }
    @Test fun searchHistoryPinyinDownloadFilterAndSmartLibrary() {
        var history by mutableStateOf(listOf("呼吸有害"))
        val stats = listOf(ListeningStat(songs[1].cacheKey, 120000, 2, System.currentTimeMillis()))
        var played: Track? = null
        themedContent {
            LibrarySearchContent(snapshot, null, { null }, { false }, { false }, {}, { _, t -> played = t },
                history = history, stats = stats, downloadedKeys = setOf(songs[0].cacheKey),
                rememberSearch = { if (it.isNotBlank()) history = listOf(it) + history.filterNot { q -> q == it } },
                clearHistory = { history = emptyList() }) { _, _ -> }
        }
        compose.onNodeWithTag("search_history_呼吸有害").assertExists()
        compose.onNodeWithTag("library_search_input").performTextInput("hxyh")
        compose.waitUntil(5000) { compose.onAllNodesWithText("呼吸有害").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("library_search_input").performImeAction()
        compose.runOnIdle { assertEquals("hxyh", history.first()) }
        compose.onNodeWithTag("search_downloaded").performScrollTo().performClick()
        compose.onNodeWithText("呼吸有害").performClick()
        compose.runOnIdle { assertEquals(songs[0], played) }
        compose.onNodeWithTag("library_search_input").performTextClearance()
        compose.onNodeWithTag("search_downloaded").performScrollTo().performClick()
        compose.onNodeWithTag("smart_FREQUENT").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("忽然之间").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("呼吸有害").assertDoesNotExist()
        capture("second-batch-smart-library")
        compose.onNodeWithTag("smart_RANDOM").performScrollTo().performClick()
        compose.onNodeWithTag("search_downloaded").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("呼吸有害").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5000) { compose.onAllNodesWithText("忽然之间").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("忽然之间").assertDoesNotExist()
        compose.onNodeWithTag("search_downloaded").performScrollTo().performClick()
        compose.onNodeWithTag("smart_ALL").performScrollTo().performClick()
        compose.onNodeWithTag("search_history_clear").performClick()
        compose.runOnIdle { assertTrue(history.isEmpty()) }
    }
    @Test fun playlistDragAccessibilitySavesOnlyExplicitlyAndOfflineDisablesWrites() {
        var disabled by mutableStateOf(false)
        var saved: List<Track>? = null
        themedContent { PlaylistOrderContent(songs, { null }, disabled, null, { saved = it }, {}) }
        compose.onNodeWithTag("playlist_order_save").assertIsNotEnabled()
        val move = compose.onNodeWithTag("queue_drag_c").fetchSemanticsNode().config[SemanticsActions.CustomActions].first()
        compose.runOnIdle { assertTrue(move.action()) }
        compose.runOnIdle { assertNull(saved) }
        capture("second-batch-playlist-order")
        compose.onNodeWithTag("playlist_order_save").performClick()
        compose.runOnIdle { assertEquals(listOf("a", "c", "b"), saved!!.map { it.identity.remoteTrackId }); disabled = true }
        compose.onNodeWithTag("playlist_order_save").assertIsNotEnabled()
        compose.onNodeWithTag("playlist_copy").assertIsNotEnabled()
    }
    @Test fun lyricCandidatesPreviewAndLocalServerScopesRemainDistinct() {
        var offline by mutableStateOf(false)
        var local = false
        var serverSaved = false
        val lyrics = LrcParser.parse("[00:01]候选歌词\n[00:02]第二行", "[00:01]Translation")
        val api = LyricRepair({ !offline }) { method, _, _ ->
            when (method) {
                "getResourceList" -> Json.parseToJsonElement("""{"resources":{"lyricSearch":[{"id":"source","extensionId":"fixture","name":"歌词来源"}]}}""")
                "lyricSearch" -> buildJsonArray { add(buildJsonObject { put("id", "candidate"); put("name", "呼吸有害 · 候选"); put("artist", "莫文蔚"); put("lyric", lyricPayload(songs[0], lyrics)) }) }
                else -> error("Unexpected read")
            }
        }
        val actions = LyricRepairActions(api, { local }, { value, server ->
            assertEquals(lyrics, value)
            if (server) serverSaved = true else local = true
        }, { local = false })
        themedContent { LyricRepairSheet(songs[0], actions, offline) {} }
        compose.waitUntil(5000) { compose.onAllNodesWithText("歌词来源").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("lyric_search").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("呼吸有害 · 候选").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("呼吸有害 · 候选").performScrollTo().performClick()
        compose.onNodeWithTag("lyric_preview").performScrollTo().assertTextContains("候选歌词", substring = true)
        compose.onNodeWithTag("lyric_apply_local").performScrollTo().assertIsEnabled().assertIsDisplayed()
        capture("second-batch-lyric-before-apply")
        compose.onNodeWithTag("lyric_apply_local").performClick()
        compose.waitUntil(5000) { local }
        assertFalse(serverSaved)
        compose.onNodeWithTag("lyric_save_server").performScrollTo().performClick()
        compose.waitUntil(5000) { serverSaved }
        capture("second-batch-lyric-repair")
        compose.runOnIdle { offline = true }
        compose.onNodeWithTag("lyric_save_server").performScrollTo().assertIsNotEnabled()
    }
    @Test fun movePickerExplainsDuplicatesBeforeSelection() {
        var selected: Playlist? = null
        val target = Playlist("target", "午后收藏", "general", 8)
        themedContent {
            AddToPlaylistSheet(listOf(target), 3, { null }, { selected = it }, {}, moving = true, duplicates = { 2 })
        }
        compose.onNodeWithTag("playlist_duplicates_target", useUnmergedTree = true).assertExists()
        capture("second-batch-move-picker")
        compose.onNodeWithTag("add_playlist_target").performClick()
        compose.runOnIdle { assertEquals(target, selected) }
    }
}
