package io.github.nutea.anylisten

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.Shader
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nutea.anylisten.core.model.*
import io.github.nutea.anylisten.ui.*
import io.github.nutea.anylisten.ui.screens.*
import io.github.nutea.anylisten.ui.theme.AnyListenTheme
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Public documentation captures. Only synthetic values and locally drawn artwork; no server calls. */
class ReadmeScreenshotsTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val titles = listOf("晚风来信", "云端散步", "橘色海岸", "月光邮局", "雨后晴空", "慢慢靠近", "山间回声", "星光抵达")
    private val artists = listOf("风屿", "白昼旅行家", "拾光乐队", "林间电台")
    private val tracks = titles.mapIndexed { i, title -> Track(TrackIdentity("readme-demo", "$i"), title, artists[i % 4], "沿途的风景", 218000 + i * 1000L) }
    private val playlists = listOf(Playlist("love", "我喜欢", "love", 24), Playlist("last_played", "最近播放", "last_played", 18),
        Playlist("default", "默认列表", "default", 128)) + listOf("晚风与散步", "通勤的好心情", "安静工作一小时", "周末去看海", "睡前的最后一首").mapIndexed { i, name -> Playlist("demo-$i", name, "user", 8 + i * 3) }
    private val library = LibraryUiState(snapshot = LibrarySnapshot(playlists, playlists.associate { it.id to tracks }, 0, false), selected = playlists[3], filtered = tracks)
    private val lyrics = Lyrics(listOf("把城市的喧嚣留在身后", "沿着微亮的街角慢慢走", "晚风捎来一封没有署名的信", "说每一颗星都有等候的理由", "让疲惫随云散向远方", "把温柔装进口袋收藏", "等明天的阳光落在肩上", "我们再一起去看海").mapIndexed { i, line -> LyricLine(i * 30000L, line) }, "")
    private val player = PlayerUiState(track = tracks[0], queue = tracks, isPlaying = false, durationMs = 218000, positionMs = 68000, lyrics = lyrics, availableOffline = true)
    private val actions = PlayerActions({}, {}, {}, {}, {}, {}, {}, {}, {})
    private fun covers(): List<String> = (0..3).map { i ->
        val file = File(context.cacheDir, "readme-art-$i.png")
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val starts = intArrayOf(0xff203947.toInt(), 0xff695177.toInt(), 0xffb76b53.toInt(), 0xff275d58.toInt())
        val ends = intArrayOf(0xff9fb8ae.toInt(), 0xffc4b6c9.toInt(), 0xffe8c39c.toInt(), 0xffa4c2ae.toInt())
        paint.shader = LinearGradient(0f,0f,512f,512f,starts[i],ends[i],Shader.TileMode.CLAMP)
        canvas.drawRect(0f,0f,512f,512f,paint)
        paint.shader = null; paint.color = 0xfff4deaf.toInt(); canvas.drawCircle(342f,162f,64f,paint)
        paint.color = 0x55203347; canvas.drawCircle(90f,520f,290f,paint)
        paint.color = 0x77426460; canvas.drawCircle(480f,620f,345f,paint)
        paint.color = 0xfff3f1e7.toInt(); paint.textSize = 25f; paint.letterSpacingCompat()
        canvas.drawText("ALONG THE WAY",38f,430f,paint)
        paint.textSize = 13f; canvas.drawText("DEMO COLLECTION  /  0${i+1}",40f,460f,paint)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
        file.toURI().toString()
    }
    private fun Paint.letterSpacingCompat() { typeface = android.graphics.Typeface.create("sans-serif-light",android.graphics.Typeface.NORMAL) }
    @Test fun capturePublicGallery() {
        val covers = covers()
        val cover: (Track?) -> String? = { t -> tracks.indexOfFirst { it.cacheKey == t?.cacheKey }.takeIf { it >= 0 }?.let { covers[it % covers.size] } }
        val page = mutableIntStateOf(0)
        val assets = tracks.mapIndexed { i,t -> LocalAssetItem(t.cacheKey,t.title,t.artist,t.album,t,LocalOrigin.DOWNLOAD,
            AssetCompleteness(true,SidecarState.READY,SidecarState.READY), bytes = (7+i)*1024*1024L) }
        compose.setContent {
            AnyListenTheme(darkTheme = page.intValue == 4) {
                Surface(Modifier.fillMaxSize().safeDrawingPadding().testTag("readme_frame")) {
                    Scaffold(bottomBar = {
                        if (page.intValue != 2) Column {
                            MiniPlayerContent(player,covers[0],{},{},{})
                            NavigationBar {
                                listOf(R.string.nav_library to Icons.Default.LibraryMusic,R.string.nav_downloads to Icons.Default.Folder,R.string.nav_settings to Icons.Default.Settings).forEachIndexed { i,spec ->
                                    NavigationBarItem(selected = i == when(page.intValue) { 3 -> 1; 4 -> 2; else -> 0 }, onClick = {},
                                        icon = { Icon(spec.second,null) }, label = { Text(stringResource(spec.first)) })
                                }
                            }
                        }
                    }, contentWindowInsets = WindowInsets(0,0,0,0)) { padding ->
                        Box(Modifier.padding(padding)) {
                            when(page.intValue) {
                                0 -> LibraryOverviewContent(library,cover,{},{},{}, { covers[playlists.indexOf(it).coerceAtLeast(0) % 4] })
                                1 -> LibraryContent(library,tracks[0].cacheKey,cover,{true},{},{},{},{},{},onBack = {},onAction = {_,_->})
                                2 -> PlayerContent(player,cover,true,false,actions,downloaded = true)
                                3 -> DownloadsContent(assets,assets.take(3),emptyList(),StorageSummary(72*1024*1024L,24*1024*1024L,28_000_000_000L),
                                    artwork = { cover(it.track) },onPlay = {},onDeleteDownload = {},onDeleteCache = {},onRetry = {},onCancel = {})
                                4 -> SettingsContent(ServerProfile("demo","https://music.example.com",serverName = "我的音乐空间",reportedVersion = "0.11.0-beta.1"),
                                    StorageSummary(72*1024*1024L,24*1024*1024L,28_000_000_000L),PlaybackMode.LIST_LOOP,"—",{},{},{},themeMode = ThemeMode.DARK)
                            }
                        }
                    }
                }
            }
        }
        val folder = File(context.getExternalFilesDir(null), "readme-screenshots").apply { mkdirs() }
        fun capture(name: String) {
            compose.waitForIdle()
            // Wait for local Coil file decoding before capturing actual production composables.
            android.os.SystemClock.sleep(800)
            compose.onNodeWithTag("readme_frame").captureToImage().asAndroidBitmap().let { bitmap ->
                File(folder,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            }
        }
        capture("library")
        compose.runOnIdle { page.intValue = 1 }; capture("playlist")
        compose.runOnIdle { page.intValue = 2 }; capture("player")
        compose.onNodeWithTag("player_pages").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("lyrics_page").assertIsDisplayed(); capture("lyrics")
        compose.runOnIdle { page.intValue = 3 }; capture("local")
        compose.runOnIdle { page.intValue = 4 }; capture("settings")
    }
}
