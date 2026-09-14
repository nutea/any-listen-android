package io.github.nutea.anylisten

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nutea.anylisten.core.model.*
import io.github.nutea.anylisten.ui.*
import io.github.nutea.anylisten.ui.screens.*
import io.github.nutea.anylisten.ui.theme.AnyListenTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class MusicExperienceTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun label(id: Int, vararg args: Any) = context.getString(id, *args)
    private val tracks = listOf(
        Track(TrackIdentity("fixture", "1"), "午夜漫游", "林间来信", "夜色散步", 218_000),
        Track(TrackIdentity("fixture", "2"), "星河之间", "远山", "夜色散步", 246_000),
        Track(TrackIdentity("fixture", "3"), "把日子唱成一首很长很长的歌", "夏日和弦", "沿途的风景", 183_000),
        Track(TrackIdentity("fixture", "4"), "慢一点，也没关系", "南方来客", "慢慢生活", 232_000),
    )
    private val playlists = listOf(Playlist("love", "我喜欢", "love", 4), Playlist("default", "默认列表", "default", 4), Playlist("evening", "夜色散步", "user", 4))
    private fun library(offline: Boolean = false) = LibraryUiState(
        snapshot = LibrarySnapshot(playlists, playlists.associate { it.id to tracks }, 1, offline),
        selected = playlists[0], filtered = tracks)
    private fun player() = PlayerUiState(track = tracks[0], queue = tracks, isPlaying = true, durationMs = 218_000, positionMs = 32_000,
        lyrics = Lyrics(listOf(LyricLine(0, "城市渐渐安静"), LyricLine(30_000, "在晚风里，听见自己"), LyricLine(60_000, "让星光陪我们慢慢走")), ""))
    private fun show(dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent { AnyListenTheme(darkTheme = dark) { Surface(Modifier.fillMaxSize()) { Column(Modifier.safeDrawingPadding()) { content() } } } }
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val folder = File(context.getExternalFilesDir(null), "ui-review").apply { mkdirs() }
        // Force a rendered frame before taking the composed window (including sheets).
        compose.onAllNodes(isRoot()).onLast().captureToImage()
        android.os.SystemClock.sleep(200)
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun libraryMenusKeepActionsReachableAndConfirmRemoval() {
        var action: TrackAction? = null
        show {
            Column {
                Box(Modifier.weight(1f)) { LibraryContent(library(), tracks[0].cacheKey, { null }, { true }, {}, {}, {}, {}) { _, value -> action = value } }
                MiniPlayerContent(player(), null, {}, {}, {})
            }
        }
        compose.onAllNodesWithText(tracks[0].title).onFirst().assertExists()
        screenshot("library")
        compose.onNodeWithContentDescription(label(R.string.track_options, tracks[0].title)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(label(R.string.play_later)).assertExists()
        compose.onNodeWithText(label(R.string.cd_download)).assertExists()
        compose.onNodeWithText(label(R.string.cd_add_to_playlist)).assertExists()
        compose.onNodeWithText(label(R.string.cd_remove)).performClick()
        compose.runOnIdle { assertNull(action) }
        compose.onNodeWithText(label(R.string.dialog_cancel)).performClick()
        compose.runOnIdle { assertNull(action) }
        compose.onNodeWithContentDescription(label(R.string.track_options, tracks[0].title)).performClick()
        screenshot("track-menu")
        compose.onNodeWithText(label(R.string.cd_remove)).performClick()
        compose.onNodeWithText(label(R.string.confirm_remove)).performClick()
        compose.runOnIdle { assertEquals(TrackAction.REMOVE, action) }
    }

    @Test fun offlineLibraryDisablesServerWrites() {
        var action: TrackAction? = null
        show { LibraryContent(library(true), null, { null }, { false }, {}, {}, {}, {}) { _, value -> action = value } }
        compose.onNodeWithContentDescription(label(R.string.track_options, tracks[0].title)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.cd_favorite)).assertIsNotEnabled()
        compose.onNodeWithText(label(R.string.play_later)).assertIsEnabled()
        compose.onNodeWithText(label(R.string.cd_add_to_playlist)).assertIsNotEnabled()
        compose.onNodeWithText(label(R.string.cd_remove)).assertIsNotEnabled()
        compose.onNodeWithText(label(R.string.error_offline_write)).assertExists()
        compose.runOnIdle { assertNull(action) }
    }

    @Test fun playerSelectsModeSeeksLyricsAndRemovesQueueItem() {
        var seek = -1L
        var chosen: PlaybackMode? = null
        var removed: Track? = null
        show {
            var state by remember { mutableStateOf(player()) }
            PlayerContent(state, { null }, true, false, PlayerActions({}, {}, {}, {}, {},
                { seek = it }, { chosen = it; state = state.copy(repeat = it.repeat, shuffled = it.shuffled) }, {},
                { removed = it; state = state.copy(queue = state.queue.filter { item -> item.cacheKey != it.cacheKey }) }))
        }
        screenshot("player")
        compose.onNodeWithContentDescription(label(R.string.cd_play_mode_list_loop)).performClick()
        compose.onNodeWithText(label(R.string.cd_play_mode_single)).performClick()
        compose.runOnIdle { assertEquals(PlaybackMode.SINGLE, chosen) }
        compose.onNodeWithText(label(R.string.open_lyrics)).performClick()
        compose.onNodeWithText("让星光陪我们慢慢走").performClick()
        compose.runOnIdle { assertEquals(60_000L, seek) }
        screenshot("lyrics")
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithContentDescription(label(R.string.open_queue)).performClick()
        compose.onAllNodesWithText(label(R.string.queue_now_playing)).onFirst().assertExists()
        compose.onAllNodesWithText(label(R.string.queue_next)).onFirst().assertExists()
        screenshot("queue")
        compose.onNodeWithContentDescription(label(R.string.remove_queue_track, tracks[1].title)).performClick()
        compose.onNodeWithText(tracks[1].title).assertDoesNotExist()
        compose.runOnIdle { assertEquals(tracks[1], removed) }
    }

    @Test fun playerSwipesBetweenCoverAndFullLyricsWithoutSeeking() {
        var seeks = 0
        val playback = mutableStateOf(player().copy(lyrics = Lyrics(
            (0..39).map { LyricLine(it * 10_000L, "歌词第 $it 行") }, "")))
        show { PlayerContent(playback.value, { null }, false, false,
            PlayerActions({}, {}, {}, {}, {}, { seeks++ }, {}, {}, {})) }
        compose.onNodeWithTag("cover_lyric_preview").assertTextEquals("歌词第 3 行")
        compose.runOnIdle { playback.value = playback.value.copy(positionMs = 42_000) }
        compose.onNodeWithTag("cover_lyric_preview").assertTextEquals("歌词第 4 行")
        compose.onNodeWithTag("player_pages").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("lyrics_page").assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.cd_pause)).assertIsDisplayed()
        screenshot("lyrics-fullscreen")
        compose.onNodeWithTag("timed_lyrics").performTouchInput { swipeUp() }
        compose.onNodeWithText(label(R.string.return_to_lyric)).assertIsDisplayed()
        compose.onNodeWithTag("lyrics_page").assertIsDisplayed()
        compose.onNodeWithText(label(R.string.return_to_lyric)).performClick()
        compose.onNodeWithTag("player_pages").performTouchInput { swipeRight() }
        compose.onNodeWithTag("cover_page").assertIsDisplayed()
        compose.onNodeWithTag("cover_lyric_preview").assertTextEquals("歌词第 4 行")
        compose.runOnIdle { assertEquals(0, seeks) }
        screenshot("cover-with-lyric")
    }

    @Test fun cachedTrackCanDownloadButCompletedDownloadIsDisabled() {
        val downloaded = mutableStateOf(false)
        var downloads = 0
        show { PlayerContent(player().copy(availableOffline = true), { null }, false, false,
            PlayerActions({}, {}, {}, {}, {}, {}, {}, {}, {}, download = { downloads++ }), downloaded = downloaded.value) }
        compose.onNodeWithText(label(R.string.track_available_offline)).assertDoesNotExist()
        compose.onNodeWithContentDescription(label(R.string.track_available_offline)).assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.cd_download)).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, downloads); downloaded.value = true }
        compose.onNodeWithContentDescription(label(R.string.cd_downloaded)).assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, downloads) }
        screenshot("player-downloaded-icon")
        compose.runOnIdle { downloaded.value = false }
        compose.onNodeWithContentDescription(label(R.string.cd_download)).assertIsEnabled()
    }

    @Test fun darkPlayerKeepsControlsAccessible() {
        show(true) { PlayerContent(player(), { null }, false, false, PlayerActions({}, {}, {}, {}, {}, {}, {}, {}, {})) }
        compose.onNodeWithContentDescription(label(R.string.cd_pause)).assertIsDisplayed()
        screenshot("player-dark")
    }

    @Test fun downloadsSwitchTabsAndRequireDeleteConfirmation() {
        val records = tracks.take(2).mapIndexed { index, t -> DownloadRecord(t.cacheKey, t.identity, t.title, t.artist,
            if (index == 0) DownloadStatus.COMPLETED else DownloadStatus.FAILED, bytesDownloaded = 8_400_000) }
        val downloaded = LocalInventory.downloaded(records, { AssetCompleteness(true, SidecarState.NONE, SidecarState.FAILED) }, { true })
        val cached = listOf(LocalAssetItem(tracks[3].cacheKey, tracks[3].title, tracks[3].artist, tracks[3].album, tracks[3],
            LocalOrigin.CACHE, AssetCompleteness(true, SidecarState.NONE, SidecarState.READY)))
        var deleted: String? = null
        var retried: String? = null
        var removedTask: String? = null
        show { DownloadsContent(downloaded, cached, LocalInventory.tasks(records), StorageSummary(8_400_000, 4_000_000, 12_000_000_000),
            { null }, {}, { deleted = it }, {}, { retried = it }, {}, onRemoveTask = { removedTask = it }) }
        compose.onNodeWithContentDescription(label(R.string.asset_playable)).assertDoesNotExist()
        screenshot("downloads")
        compose.onNodeWithContentDescription(label(R.string.track_options, downloaded.first().title)).performClick()
        compose.onNodeWithText(label(R.string.asset_playable)).assertExists()
        compose.onNodeWithText(label(R.string.asset_lyrics_none)).assertExists()
        compose.onNodeWithText(label(R.string.asset_cover_failed)).assertExists()
        screenshot("local-details")
        compose.onNodeWithText(label(R.string.download_delete)).performClick()
        compose.runOnIdle { assertNull(deleted) }
        compose.onNodeWithText(label(R.string.dialog_cancel)).performClick()
        compose.onNodeWithText(label(R.string.cached_tab, 1)).performClick()
        compose.onNodeWithText(tracks[3].title).assertExists()
        screenshot("local-cached")
        compose.onNodeWithText(label(R.string.tasks_tab, 1)).performClick()
        compose.onNodeWithContentDescription(label(R.string.download_retry)).performClick()
        compose.runOnIdle { assertEquals(records[1].cacheKey, retried); assertNull(deleted) }
        compose.onNodeWithContentDescription(label(R.string.remove_download_task)).performClick()
        compose.runOnIdle { assertEquals(records[1].cacheKey, removedTask); assertNull(deleted) }
    }

    @Test fun localRowsStayCompactAndPlayFromTheRow() {
        val items = (0..7).map { index ->
            val track = tracks[index % tracks.size].copy(identity = TrackIdentity("local", "$index"), title = "${tracks[index % tracks.size].title} $index")
            LocalAssetItem(track.cacheKey, track.title, track.artist, track.album, track, LocalOrigin.DOWNLOAD,
                AssetCompleteness(true, SidecarState.READY, SidecarState.READY), bytes = 8_000_000)
        }
        var played: String? = null
        show { DownloadsContent(items, emptyList(), emptyList(), StorageSummary(64_000_000, 4_000_000, 12_000_000_000),
            { null }, { played = it.cacheKey }, {}, {}, {}, {}) }
        compose.onNodeWithText(items.last().title).assertIsDisplayed()
        compose.onNodeWithTag("local_${items.first().cacheKey}").assertHeightIsEqualTo(androidx.compose.ui.unit.Dp(64f)).performClick()
        compose.runOnIdle { assertEquals(items.first().cacheKey, played) }
        screenshot("local-dense-list")
    }

    @Test fun settingsGroupsConnectionPlaybackAndStorage() {
        val automatic = mutableStateOf(true)
        show {
            SettingsContent(ServerProfile("p", "https://example.test", serverName = "Home"),
                StorageSummary(1_000, 2_000, 12_000_000_000, 300), PlaybackMode.LIST_LOOP, "—", {}, {}, {}, autoCacheAudio = automatic.value, onAutoCacheAudio = { automatic.value = it })
        }
        compose.onNodeWithText(label(R.string.settings_connection)).assertExists()
        compose.onNodeWithText(label(R.string.settings_playback)).assertExists()
        compose.onNodeWithText(label(R.string.settings_storage)).assertExists()
        compose.onNodeWithContentDescription(label(R.string.settings_auto_cache_audio)).performScrollTo().assertIsOn().performClick()
        compose.runOnIdle { assertFalse(automatic.value) }
        compose.onNodeWithContentDescription(label(R.string.settings_auto_cache_audio)).assertIsOff()
        screenshot("settings")
        compose.onNodeWithContentDescription(label(R.string.settings_auto_cache_audio)).performClick()
        compose.runOnIdle { assertTrue(automatic.value) }
    }

    @Test fun playingIndicatorAnimatesAndStopsWhenPaused() {
        val playing = mutableStateOf(true)
        show { LibraryContent(library(), tracks[0].cacheKey, { null }, { true }, {}, {}, {}, {},
            offlineReady = { true }, isPlaying = playing.value) { _, _ -> } }
        compose.onNodeWithContentDescription(label(R.string.cd_favorite)).assertDoesNotExist()
        compose.onNodeWithContentDescription(label(R.string.offline_ready)).assertDoesNotExist()
        compose.mainClock.autoAdvance = false
        fun pixels(): List<androidx.compose.ui.graphics.Color> {
            val map = compose.onNodeWithTag("playback_indicator", useUnmergedTree = true).captureToImage().toPixelMap()
            return (0 until map.height).flatMap { y -> (0 until map.width).map { x -> map[x, y] } }
        }
        compose.mainClock.advanceTimeBy(100)
        val first = pixels()
        compose.mainClock.advanceTimeBy(250)
        assertNotEquals(first, pixels())
        compose.runOnIdle { playing.value = false }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("playback_indicator", useUnmergedTree = true).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, label(R.string.indicator_paused)))
        val paused = pixels()
        compose.mainClock.advanceTimeBy(250)
        assertEquals(paused, pixels())
        compose.mainClock.autoAdvance = true
    }

    @Test fun libraryFirstScreenDoesNotKeepShortStatus() {
        show { LibraryContent(library().copy(status = "Queued 2 download(s)"), null, { null }, { false }, {}, {}, {}, {}) { _, _ -> } }
        compose.onNodeWithText("Queued 2 download(s)").assertDoesNotExist()
    }

    @Test fun librarySortAndLongPressSelectionStayInCurrentPlaylist() {
        var later: Track? = null
        show {
            LibraryContent(library(), tracks[0].cacheKey, { null }, { false }, {}, {}, {}, {},
                onBatch = { items, action -> if (action == TrackAction.PLAY_LATER) later = items.firstOrNull() }) { _, _ -> }
        }
        compose.onNodeWithContentDescription(label(R.string.sort_tracks)).performClick()
        compose.onNodeWithText(label(R.string.sort_local_only)).assertExists()
        compose.onNodeWithText(label(R.string.sort_title) + " ↑").assertExists()
        androidx.test.espresso.Espresso.pressBack()
        compose.onAllNodesWithText(tracks[1].title).onFirst().performTouchInput { longClick() }
        compose.onNodeWithText(label(R.string.selection_scope)).assertExists()
        compose.onNodeWithText(label(R.string.selection_count, 1, 4)).assertExists()
        screenshot("library-select")
        compose.onNodeWithText(label(R.string.play_later)).performClick()
        compose.runOnIdle { assertEquals(tracks[1], later) }
    }

    @Test fun playLaterSectionDoesNotReplaceNowPlaying() {
        show {
            PlayerContent(player().copy(laterKeys = listOf(tracks[2].cacheKey)), { null }, true, false, PlayerActions({}, {}, {}, {}, {}, {}, {}, {}, {}))
        }
        compose.onNodeWithContentDescription(label(R.string.open_queue)).performClick()
        compose.onAllNodesWithText(label(R.string.queue_now_playing)).onFirst().assertExists()
        compose.onAllNodesWithText(label(R.string.queue_later)).onFirst().assertExists()
        compose.onAllNodesWithText(tracks[0].title).onFirst().assertExists()
        compose.onAllNodesWithText(tracks[2].title).onFirst().assertExists()
        screenshot("queue-later")
    }
}
