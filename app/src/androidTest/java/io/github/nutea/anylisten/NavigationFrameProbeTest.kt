package io.github.nutea.anylisten

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ActivityScenario
import io.github.nutea.anylisten.core.model.*
import io.github.nutea.anylisten.ui.*
import io.github.nutea.anylisten.ui.screens.*
import io.github.nutea.anylisten.ui.theme.AnyListenTheme
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Uses the production frame clock, not ComposeTestRule's fast-forwarding clock. */
class NavigationFrameProbeTest {
    @Test fun realTimeTransitions() {
        val list = Playlist("a","Virtual playlist","user",500)
        val songs = (0..499).map { Track(TrackIdentity("fixture","$it"),"Song $it","Artist","Album",120000) }
        val snapshot = LibrarySnapshot(listOf(list),mapOf("a" to songs),1,false)
        val ready = CountDownLatch(1)
        lateinit var nav: NavHostController
        val frames = java.util.concurrent.CopyOnWriteArrayList<Long>()
        val listener = android.view.Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            frames.add(metrics.getMetric(android.view.FrameMetrics.TOTAL_DURATION))
        }
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                AnyListenTheme { Surface(Modifier.fillMaxSize()) {
                    nav = rememberNavController()
                    SideEffect { ready.countDown() }
                    LibraryNavHost(nav,Modifier.fillMaxSize(),motion = true) {
                        libraryPage("library") { LibraryOverviewContent(LibraryUiState(snapshot = snapshot),{null},{},{}) }
                        libraryPage("playlist/a") {
                            LibraryContent(LibraryUiState(snapshot = snapshot,selected=list,filtered=songs),null,{null},{false},{},{},{},{},onBack={nav.popBackStack()}) { _,_-> }
                        }
                    }
                } }
            } }
            assertTrue(ready.await(10,TimeUnit.SECONDS))
            Thread.sleep(600)
            scenario.onActivity { it.window.addOnFrameMetricsAvailableListener(listener,android.os.Handler(android.os.Looper.getMainLooper())) }
            try {
                repeat(10) {
                    scenario.onActivity { nav.navigate("playlist/a") }
                    Thread.sleep(500)
                    scenario.onActivity { nav.popBackStack() }
                    Thread.sleep(500)
                }
            } finally { scenario.onActivity { it.window.removeOnFrameMetricsAvailableListener(listener) } }
        }
        val sorted = frames.sorted()
        assertTrue("Collect actual intermediate frames",sorted.size > 100)
        val message = "Real-clock virtual 500-song navigation: frames=${sorted.size}, medianMs=${sorted[sorted.size/2]/1_000_000.0}, p95Ms=${sorted[((sorted.size-1)*0.95).toInt()]/1_000_000.0}, over16ms=${sorted.count { it > 16_666_667L }}\n"
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply { putString("stream",message) })
    }
}
