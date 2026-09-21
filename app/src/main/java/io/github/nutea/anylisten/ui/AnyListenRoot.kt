package io.github.nutea.anylisten.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import io.github.nutea.anylisten.core.model.MusicCatalog
import io.github.nutea.anylisten.ui.screens.CatalogIndexContent
import io.github.nutea.anylisten.ui.screens.CatalogDetailContent
import io.github.nutea.anylisten.ui.screens.downloadedTrackKeys
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.animation.fadeIn
import androidx.compose.ui.platform.LocalView
import io.github.nutea.anylisten.R
import io.github.nutea.anylisten.core.data.AppContainer
import io.github.nutea.anylisten.ui.screens.ConnectScreen
import io.github.nutea.anylisten.ui.screens.DownloadsScreen
import io.github.nutea.anylisten.ui.screens.PlaylistScreen
import io.github.nutea.anylisten.ui.screens.LibrarySearchScreen
import io.github.nutea.anylisten.ui.screens.LibraryScreen
import io.github.nutea.anylisten.ui.screens.QueueBottomSheet
import io.github.nutea.anylisten.ui.screens.MiniPlayer
import io.github.nutea.anylisten.ui.screens.PlayerScreen
import io.github.nutea.anylisten.ui.screens.SettingsScreen

@Composable
fun AnyListenRoot(
    @Suppress("UNUSED_PARAMETER") container: AppContainer,
    playLast: Boolean = false,
    vm: AppViewModel = viewModel(),
) {
    val signedIn by vm.signedIn.collectAsState()
    LaunchedEffect(playLast, signedIn) {
        if (playLast && signedIn) {
            delay(800)
            vm.playLast()
        }
    }
    if (!signedIn) {
        val connect by vm.connect.collectAsState()
        ConnectScreen(connect, vm::updateUrl, vm::updatePassword, vm::testHello, vm::login)
        return
    }
    io.github.nutea.anylisten.ui.screens.LibraryDialogs(vm)
    RequestNotificationPermission()
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val player by vm.player.collectAsState()
    val library by vm.library.collectAsState()
    val requestedSheet by vm.playerSheet.collectAsState()
    val catalog by vm.catalog.collectAsState()
    val downloadedKeys = downloadedTrackKeys(vm)
    val motion = rememberMotionEnabled()
    val hapticView = LocalView.current
    if (requestedSheet == "queue" && route != "player") {
        QueueBottomSheet(player, vm::artworkUrl, vm::playQueueItem, vm::removeQueueItem, vm::consumePlayerSheet)
    }
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
    Scaffold(
        bottomBar = {
            if (route != "player") Column {
                if (player.track != null && route != "player") {
                    MiniPlayer(vm, { hapticView.confirmHaptic(); nav.navigate("player") }, {
                        hapticView.tickHaptic()
                        vm.openQueueSheet()
                    })
                }
                NavigationBar {
                    listOf(
                        "library" to (R.string.nav_library to Icons.Filled.LibraryMusic),
                        "downloads" to (R.string.nav_downloads to Icons.Filled.Folder),
                        "settings" to (R.string.nav_settings to Icons.Filled.Settings),
                    ).forEach { (target, spec) ->
                        NavigationBarItem(
                            selected = route == target || (target == "library" && (route == "search" || route?.startsWith("playlist/") == true || isCatalogRoute(route))),
                            onClick = {
                                nav.selectMainTab(target)
                            },
                            icon = { Icon(spec.second, contentDescription = stringResource(spec.first)) },
                            label = { Text(stringResource(spec.first)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        LibraryNavHost(nav, Modifier.padding(padding).consumeWindowInsets(padding)) {
            libraryPage("library") { entry ->
                LibraryScreen(vm, { playlist ->
                    if (nav.acceptsInput(entry)) {
                        vm.openPlaylist(playlist)
                        nav.navigate("playlist/${android.net.Uri.encode(playlist.id)}") { launchSingleTop = true }
                    }
                }, { if (nav.acceptsInput(entry)) nav.navigate("search") { launchSingleTop = true } },
                    onArtists = { if (nav.acceptsInput(entry)) nav.navigate("catalog/artists") },
                    onAlbums = { if (nav.acceptsInput(entry)) nav.navigate("catalog/albums") })
            }
            libraryPage("playlist/{playlistId}") { entry ->
                val id = entry.arguments?.getString("playlistId")
                LaunchedEffect(id, library.snapshot.playlists, backStack?.id) {
                    if (nav.currentBackStackEntry?.id != entry.id) return@LaunchedEffect
                    val playlist = library.snapshot.playlists.firstOrNull { it.id == id }
                    if (playlist != null) vm.openPlaylist(playlist)
                    else nav.popBackStack()
                }
                if (library.selected?.id == id) {
                    androidx.compose.runtime.key(id) {
                        PlaylistScreen(vm,
                            { if (nav.acceptsInput(entry)) nav.popBackStack() },
                            { if (nav.acceptsInput(entry)) nav.navigate("search") },
                            { if (nav.acceptsInput(entry)) nav.navigate("player") },
                            canInteract = { nav.acceptsInput(entry) })
                    }
                }
            }
            libraryPage("search") {
                LibrarySearchScreen(vm, { nav.popBackStack() }, { nav.navigate("player") })
            }
            libraryPage("catalog/{kind}") { entry ->
                CatalogIndexContent(catalog, entry.arguments?.getString("kind") == "artists", vm::artworkUrl,
                    onBack = { if (nav.acceptsInput(entry)) nav.popBackStack() },
                    onArtist = { if (nav.acceptsInput(entry)) nav.navigate(artistRoute(it)) },
                    onAlbum = { if (nav.acceptsInput(entry)) nav.navigate(albumRoute(it)) })
            }
            listOf("artist/{server}/{name}", "album/{server}/{artist}/{name}").forEach { destination ->
                libraryPage(destination) { entry ->
                    val server = entry.arguments?.getString("server").orEmpty()
                    val name = entry.arguments?.getString("name").orEmpty()
                    val isArtist = destination.startsWith("artist/")
                    CatalogDetailContent(
                        artist = if (isArtist) catalog.artist(MusicCatalog.ArtistKey(server, name)) else null,
                        album = if (!isArtist) catalog.album(MusicCatalog.AlbumKey(server, entry.arguments?.getString("artist").orEmpty().trim(), name)) else null,
                        artwork = vm::artworkUrl, currentKey = player.track?.cacheKey, isPlaying = player.isPlaying,
                        artistPage = isArtist, error = library.error,
                        offline = library.snapshot.offline, downloaded = { it.cacheKey in downloadedKeys }, availableOffline = vm::isAvailableOffline,
                        onBack = { if (nav.acceptsInput(entry)) nav.popBackStack() },
                        onArtist = { if (nav.acceptsInput(entry)) nav.navigate(artistRoute(it)) { launchSingleTop = true } },
                        onAlbum = { if (nav.acceptsInput(entry)) nav.navigate(albumRoute(it)) { launchSingleTop = true } },
                        onPlay = { tracks, start -> if (nav.acceptsInput(entry)) vm.play(tracks, start) },
                        onDownload = { tracks -> if (nav.acceptsInput(entry)) vm.requestDownload(tracks) },
                    )
                }
            }
            libraryPage("downloads") { DownloadsScreen(vm) { nav.navigate("player") } }
            libraryPage("settings") { SettingsScreen(vm) }
            composable(
                "player",
                enterTransition = { playerEnter(motion) },
                exitTransition = { playerExit(motion) },
                popEnterTransition = { if (motion) fadeIn() else androidx.compose.animation.EnterTransition.None },
                popExitTransition = { playerExit(motion) },
            ) { entry -> androidx.compose.material3.Surface(Modifier.fillMaxSize()) {
                PlayerScreen(vm, onBack = { nav.popBackStack() },
                    onArtist = { track -> if (nav.acceptsInput(entry)) MusicCatalog.artistKey(track)?.let { nav.navigate(artistRoute(it)) } },
                    onAlbum = { track -> if (nav.acceptsInput(entry)) MusicCatalog.albumKey(track)?.let { nav.navigate(albumRoute(it)) } })
            } }
        }
    }
    OperationNotice(library.status, vm::acknowledgeStatus, Modifier.align(androidx.compose.ui.Alignment.TopCenter))
    }

}

@Composable
private fun RequestNotificationPermission() {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
