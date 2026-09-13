package io.github.nutea.anylisten

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import coil.compose.AsyncImage
import coil.request.ImageRequest
import io.github.nutea.anylisten.core.data.ArtworkStore
import io.github.nutea.anylisten.ui.screens.rememberArtwork
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

class ArtworkRefreshUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun firstDownloadAndEquivalentUrlDoNotReloadDecodedCover() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir,"art-first-${System.nanoTime()}").apply { mkdirs() }
        val bytes = ByteArrayOutputStream()
        Bitmap.createBitmap(4,4,Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.GREEN); compress(Bitmap.CompressFormat.PNG,100,bytes); recycle()
        }
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(bytes.toByteArray().toResponseBody()).build()
        }.build()
        val store = ArtworkStore(root,http)
        val url = androidx.compose.runtime.mutableStateOf("https://image-fixture.invalid/library")
        val observed = CopyOnWriteArrayList<Any?>()
        try {
            compose.setContent {
                val model = rememberArtwork(url.value,store)
                SideEffect { observed.add(model) }
                AsyncImage(model, "stable cover", Modifier.size(120.dp))
            }
            compose.waitUntil(5000) {
                val bitmap = compose.onNodeWithContentDescription("stable cover").captureToImage().asAndroidBitmap()
                bitmap.getPixel(bitmap.width/2,bitmap.height/2) == Color.GREEN
            }
            compose.waitForIdle()
            val before = observed.last() as ImageRequest
            assertEquals(1, observed.filterIsInstance<ImageRequest>().distinct().size)
            assertEquals(1,calls.get())
            observed.clear()
            compose.runOnIdle { url.value = "https://image-fixture.invalid/resolved-on-play" }
            compose.waitUntil(5000) { store.cached(url.value) != null }
            compose.waitForIdle()
            assertFalse(observed.contains(null))
            assertSame(before,observed.last())
            assertEquals(2,calls.get())
            compose.runOnIdle { url.value = android.net.Uri.fromFile(store.cached(url.value)!!).toString() }
            compose.waitForIdle()
            assertFalse(observed.contains(null))
            assertSame(before,observed.last())
            assertEquals(2,calls.get())
        } finally { root.deleteRecursively() }
    }

    @Test fun unrelatedCoversDoNotReloadAndChangedCoverRetainsPlaceholder() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir,"art-refresh-${System.nanoTime()}").apply { mkdirs() }
        var color = Color.RED
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val output = ByteArrayOutputStream()
            Bitmap.createBitmap(4,4,Bitmap.Config.ARGB_8888).apply { eraseColor(color); compress(Bitmap.CompressFormat.PNG,100,output); recycle() }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(output.toByteArray().toResponseBody()).build()
        }.build()
        val store = ArtworkStore(root,http)
        val a = "https://image-fixture.invalid/a"
        val b = "https://image-fixture.invalid/b"
        val observed = CopyOnWriteArrayList<Any?>()
        try {
            store.get(a)
            compose.setContent {
                val model = rememberArtwork(a,store)
                SideEffect { observed.add(model) }
                AsyncImage(model, "cover", Modifier.size(120.dp))
            }
            fun visibleColor(): Int {
                val bitmap = compose.onNodeWithContentDescription("cover").captureToImage().asAndroidBitmap()
                return bitmap.getPixel(bitmap.width/2,bitmap.height/2)
            }
            compose.waitUntil(5000) { visibleColor() == Color.RED }
            val before = observed.last() as ImageRequest
            store.get(a,force=true) // HTTP 200, unchanged bytes must not reload the image.
            compose.waitForIdle()
            assertSame(before, observed.last())
            store.get(b)
            color = Color.BLUE
            store.get(b,force=true)
            compose.waitForIdle()
            assertSame(before, observed.last())
            observed.clear()
            store.get(a,force=true)
            compose.waitUntil(5000) { visibleColor() == Color.BLUE }
            assertFalse(observed.contains(null))
            val after = observed.last() as ImageRequest
            assertNotEquals(before.memoryCacheKey,after.memoryCacheKey)
            assertEquals(before.memoryCacheKey,after.placeholderMemoryCacheKey)
        } finally { root.deleteRecursively() }
    }
}
