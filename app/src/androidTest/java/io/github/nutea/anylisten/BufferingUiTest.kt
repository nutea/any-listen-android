package io.github.nutea.anylisten

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nutea.anylisten.core.model.Track
import io.github.nutea.anylisten.core.model.TrackIdentity
import io.github.nutea.anylisten.ui.PlayerUiState
import io.github.nutea.anylisten.ui.screens.*
import io.github.nutea.anylisten.ui.theme.AnyListenTheme
import org.junit.Rule
import org.junit.Test

class BufferingUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val track = Track(TrackIdentity("buffer-fixture", "1"), "Morning", "Artist", "Album", 120000)

    @Test fun fullPlayerShowsLoadingAndCanPauseWhileBuffering() {
        val state = mutableStateOf(PlayerUiState(track = track, queue = listOf(track), durationMs = 120000,
            isBuffering = true, playWhenReady = true))
        compose.setContent { AnyListenTheme {
            PlayerContent(state.value, { null }, false, false,
                PlayerActions({}, { state.value = state.value.copy(playWhenReady = false, isBuffering = false) }, {}, {}, {}, {}, {}, {}, {}))
        } }
        compose.onNodeWithTag("playback_loading_spinner").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.playback_loading)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.cd_pause)).performClick()
        compose.onNodeWithTag("playback_loading_spinner").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.playback_loading)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.cd_play)).assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(isPlaying = true, playWhenReady = true) }
        compose.onNodeWithTag("playback_loading_spinner").assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.cd_pause)).assertIsDisplayed()
    }

    @Test fun miniPlayerDistinguishesRebufferingFromPauseAndError() {
        val state = mutableStateOf(PlayerUiState(track = track, isPlaying = true, playWhenReady = true))
        compose.setContent { AnyListenTheme { MiniPlayerContent(state.value, null, {}, {}, {}) } }
        compose.onNodeWithTag("playback_loading_spinner").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(isPlaying = false, isBuffering = true) }
        compose.onNodeWithTag("playback_loading_spinner").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.playback_loading)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.cd_pause)).assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(isBuffering = false, playWhenReady = false, error = "Unavailable") }
        compose.onNodeWithTag("playback_loading_spinner").assertDoesNotExist()
        compose.onNodeWithText(track.artist).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.cd_play)).assertIsDisplayed()
    }
}
