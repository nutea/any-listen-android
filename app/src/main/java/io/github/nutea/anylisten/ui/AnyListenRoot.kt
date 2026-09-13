package io.github.nutea.anylisten.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
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
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.nutea.anylisten.R
import io.github.nutea.anylisten.core.data.AppContainer
import io.github.nutea.anylisten.ui.screens.ConnectScreen
import io.github.nutea.anylisten.ui.screens.DownloadsScreen
import io.github.nutea.anylisten.ui.screens.LibraryScreen
import io.github.nutea.anylisten.ui.screens.MiniPlayer
import io.github.nutea.anylisten.ui.screens.PlayerScreen
import io.github.nutea.anylisten.ui.screens.SettingsScreen

@Composable
fun AnyListenRoot(
    @Suppress("UNUSED_PARAMETER") container: AppContainer,
    playLast: Boolean = false,
    vm: AppViewModel = viewModel(),
) {
    RequestNotificationPermission()
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
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val player by vm.player.collectAsState()
    Scaffold(
        bottomBar = {
            Column {
                if (player.track != null && route != "player") {
                    MiniPlayer(vm) { nav.navigate("player") }
                }
                NavigationBar {
                    listOf(
                        "library" to (R.string.nav_library to Icons.AutoMirrored.Filled.List),
                        "downloads" to (R.string.nav_downloads to Icons.Filled.Check),
                        "settings" to (R.string.nav_settings to Icons.Filled.Settings),
                    ).forEach { (target, spec) ->
                        NavigationBarItem(
                            selected = route == target,
                            onClick = {
                                nav.navigate(target) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(spec.second, contentDescription = stringResource(spec.first)) },
                            label = { Text(stringResource(spec.first)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(navController = nav, startDestination = "library", modifier = Modifier.padding(padding)) {
            composable("library") { LibraryScreen(vm) { nav.navigate("player") } }
            composable("downloads") { DownloadsScreen(vm) { nav.navigate("player") } }
            composable("settings") { SettingsScreen(vm) }
            composable("player") { PlayerScreen(vm) }
        }
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
