package io.github.nutea.anylisten

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nutea.anylisten.core.model.*
import io.github.nutea.anylisten.core.playback.AudioWaveSignal
import io.github.nutea.anylisten.ui.PlayerUiState
import io.github.nutea.anylisten.ui.screens.*
import io.github.nutea.anylisten.ui.theme.AnyListenTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class AudioWaveStyleTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun label(id: Int) = context.getString(id)
    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val folder = File(context.getExternalFilesDir(null), "ui-review").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun allWaveStylesAnimateAndFreezeOnPause() {
        compose.mainClock.autoAdvance = false
        var active by mutableStateOf(true)
        var style by mutableStateOf(PlayerStyle.COSMIC_DUST)
        compose.setContent { AnyListenTheme(darkTheme = true) {
            AudioWaveArtwork(null, style, PlayerPalette(Color(0xFF17233A), Color(0xFF294D85)), active,
                Modifier.size(320.dp), signal = { AudioWaveSignal.Sample(.8f, .9f) })
        } }
        for (option in PlayerStyle.entries.filter { it.hasWave }) {
            compose.runOnIdle { style = option; active = true }
            compose.mainClock.advanceTimeBy(800)
            compose.waitForIdle()
            val before = compose.onRoot().captureToImage().asAndroidBitmap()
            compose.mainClock.advanceTimeBy(500)
            compose.waitForIdle()
            val after = compose.onRoot().captureToImage().asAndroidBitmap()
            assertFalse("$option must animate", before.sameAs(after))
            compose.runOnIdle { active = false }
            compose.mainClock.advanceTimeBy(64)
            compose.waitForIdle()
            compose.runOnIdle { assertFalse("Meter should stop before paused capture", AudioWaveSignal.enabled) }
            // Drain the last submitted RenderThread layer before comparing frozen frames.
            compose.onRoot().captureToImage()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val paused = compose.onRoot().captureToImage().asAndroidBitmap()
            compose.mainClock.advanceTimeBy(500)
            compose.waitForIdle()
            val stopped = compose.onRoot().captureToImage().asAndroidBitmap()
            assertTrue("$option must freeze", paused.sameAs(stopped))
            assertFalse(AudioWaveSignal.enabled)
        }
    }

    @Test fun stylePickerSupportsAllEffectsAndLyrics() {
        val supplied = InstrumentationRegistry.getArguments().getString("coverPath")
        val cover = supplied?.let(::File)?.takeIf { it.isFile }?.toURI()?.toString()
        var style by mutableStateOf(PlayerStyle.CLASSIC)
        val state = PlayerUiState(track = Track(TrackIdentity("wave", "fixture"), "呼吸有害", "莫文蔚", "呼吸有害", 192000),
            positionMs = 13000, durationMs = 192000, lyrics = LrcParser.parse("[00:01.00]让音乐慢慢铺满整个夜晚"))
        compose.setContent { AnyListenTheme { PlayerContent(state, { cover }, false, false,
            PlayerActions({}, {}, {}, {}, {}, {}, {}, {}, {}, style = { style = it }), style = style) } }
        for (option in PlayerStyle.entries.filter { it.hasWave }) {
            compose.onNodeWithTag("player_more").performClick()
            compose.onNodeWithTag("player_style_button").performScrollTo().performClick()
            compose.onNodeWithTag("player_style_${option.name}").performScrollTo().performClick()
            compose.onNodeWithTag("audio_wave_${option.name}").assertIsDisplayed()
            compose.onNodeWithContentDescription(label(R.string.cd_play)).assertIsDisplayed()
            capture("wave-${option.name.lowercase()}")
            compose.onNodeWithText(label(R.string.open_lyrics)).performClick()
            compose.onNodeWithTag("lyrics_page").assertIsDisplayed()
            compose.onNodeWithText(label(R.string.cover_tab)).performClick()
        }
    }

    @Test fun hiddenLyricsAndReducedMotionDisableMeter() {
        compose.mainClock.autoAdvance = false
        val motion = android.provider.Settings.Global.getFloat(context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
        var playing by mutableStateOf(true)
        compose.setContent { AnyListenTheme {
            PlayerContent(PlayerUiState(track = Track(TrackIdentity("wave", "visibility"), "Wave", "Artist", "", 10000),
                isPlaying = playing, lyrics = LrcParser.parse("[00:00.00]Words")), { null }, false, false,
                PlayerActions({}, {}, {}, {}, {}, {}, {}, {}, {}), style = PlayerStyle.CRYSTAL_WAVE)
        } }
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle { assertEquals(motion, AudioWaveSignal.enabled) }
        compose.onNodeWithText(label(R.string.open_lyrics)).performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { assertFalse(AudioWaveSignal.enabled) }
        compose.onNodeWithText(label(R.string.cover_tab)).performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { assertEquals(motion, AudioWaveSignal.enabled); playing = false }
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { assertFalse(AudioWaveSignal.enabled) }
    }

    @Test fun backgroundLifecycleReleasesAudioSubscription() {
        compose.mainClock.autoAdvance = false
        val owner = object : androidx.lifecycle.LifecycleOwner {
            val registry = androidx.lifecycle.LifecycleRegistry(this)
            override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
        }
        compose.runOnUiThread { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides owner) {
                AudioWaveArtwork(null, PlayerStyle.COSMIC_DUST, PlayerPalette.Fallback, true, Modifier.size(240.dp))
            }
        }
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { assertTrue(AudioWaveSignal.enabled); owner.registry.currentState = androidx.lifecycle.Lifecycle.State.CREATED }
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { assertFalse(AudioWaveSignal.enabled); owner.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { assertTrue(AudioWaveSignal.enabled); owner.registry.currentState = androidx.lifecycle.Lifecycle.State.DESTROYED }
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { assertFalse(AudioWaveSignal.enabled) }
    }

    /** Opt-in, paced frames for a device screen recording of the actual player UI. */
    @Test fun recordWavePreview() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("wavePreview") == "true")
        compose.mainClock.autoAdvance = false
        val file = waveAudioFixture(context, 20)
        var player: androidx.media3.exoplayer.ExoPlayer? = null
        var style by mutableStateOf(PlayerStyle.COSMIC_DUST)
        val cover = InstrumentationRegistry.getArguments().getString("coverPath")?.let { File(it).toURI().toString() }
        compose.setContent { AnyListenTheme(darkTheme = true) {
            PlayerContent(PlayerUiState(track = Track(TrackIdentity("preview", "wave"), label(playerStyleName(style)),
                "音波动效预览", "", 20000), isPlaying = true, durationMs = 20000), { cover }, false, false,
                PlayerActions({}, {}, {}, {}, {}, {}, {}, {}, {}), style = style)
        } }
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                player = androidx.media3.exoplayer.ExoPlayer.Builder(context,
                    io.github.nutea.anylisten.core.playback.AudioWaveRenderersFactory(context)).build().apply {
                    volume = 0f
                    setMediaItem(androidx.media3.common.MediaItem.fromUri(file.toURI().toString())); prepare(); play()
                }
            }
            for (option in PlayerStyle.entries.filter { it.hasWave }) {
                compose.runOnIdle { style = option }
                repeat(100) { compose.mainClock.advanceTimeBy(32); Thread.sleep(32) }
                capture("wave-active-${option.name.lowercase()}")
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { player?.release() }
            file.delete()
        }
    }
}
