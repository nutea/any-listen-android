package io.github.nutea.anylisten

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nutea.anylisten.core.model.PlaylistEdit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Opt-in. Only creates and removes its own temporary playlists; no existing playlist is edited. */
class RealServerPlaylistTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun temporaryPlaylistsRoundTripThroughServer() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("real_playlists") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as AnyListenApp).container
        withTimeout(60_000) { while (!container.isConnected()) delay(200) }
        val library = container.library
        val before = library.refresh()
        val a = UUID.randomUUID().toString()
        val b = UUID.randomUUID().toString()
        try {
            library.editPlaylist(PlaylistEdit.Create(a, "Android temporary test A"))
            library.editPlaylist(PlaylistEdit.Create(b, "Android temporary test B"))
            library.editPlaylist(PlaylistEdit.Rename(a, "Android temporary renamed"))
            library.editPlaylist(PlaylistEdit.Move(b, -1))
            val after = library.refresh()
            assertEquals("Android temporary renamed", after.playlists.single { it.id == a }.name)
            assertEquals(listOf(b, a), after.playlists.filter { it.id == a || it.id == b }.map { it.id })
            val track = before.tracksByPlaylist.values.flatten().firstOrNull()
            if (track != null) {
                library.addToPlaylist(a, track)
                assertEquals(track.identity, library.cached().tracksByPlaylist.getValue(a).single().identity)
            }
        } finally {
            withContext(NonCancellable) {
                // Each deletion is attempted even if the other cleanup fails.
                val failures = mutableListOf<Exception>()
                for (id in listOf(a, b)) try { library.editPlaylist(PlaylistEdit.Delete(id)) } catch (error: Exception) { failures += error }
                if (failures.isNotEmpty()) throw failures.first()
            }
        }
        val cleaned = library.refresh()
        assertFalse(cleaned.playlists.any { it.id == a || it.id == b })
        assertEquals(before.playlists.map { it.id }, cleaned.playlists.map { it.id })
    }
}
