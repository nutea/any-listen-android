package io.github.nutea.anylisten

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Opt-in, read-only probe through the signed-in app session. */
class RealServerCommentsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun readAvailableComments() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("real_comments") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as AnyListenApp).container
        withTimeout(60000) { while (!container.isConnected()) delay(200) }
        val service = container.gateway.comments
        val sources = service.sources()
        val tracks = container.library.refresh().tracksByPlaylist.values.flatten().distinctBy { it.cacheKey }.take(3)
        var matched = 0
        var received = 0
        for (source in sources.take(2)) {
            for (track in tracks) {
                val music = service.match(track, source) ?: continue
                matched++
                received += service.page(source, music, "hot", 1).list.size
                received += service.page(source, music, "new", 1).list.size
                break
            }
        }
        File(context.getExternalFilesDir(null), "comments-probe.txt").writeText("sources=${sources.size}\nmatched=$matched\nreceived=$received\n")
    }
}
