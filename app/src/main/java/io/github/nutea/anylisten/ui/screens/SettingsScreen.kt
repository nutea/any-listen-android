package io.github.nutea.anylisten.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.nutea.anylisten.BuildConfig
import io.github.nutea.anylisten.R
import io.github.nutea.anylisten.core.model.ThemeMode
import io.github.nutea.anylisten.core.model.PlaybackMode
import io.github.nutea.anylisten.core.model.ProtocolConstants
import io.github.nutea.anylisten.core.model.ServerProfile
import io.github.nutea.anylisten.core.model.StorageSummary
import io.github.nutea.anylisten.ui.AppViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val autoCacheAudio by vm.autoCacheAudio.collectAsState()
    val profile by vm.profile.collectAsState()
    val themeMode by vm.themeMode.collectAsState()
    val storage by vm.storage.collectAsState()
    val player by vm.player.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(vm, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                vm.refreshStorage()
                delay(2_000L)
            }
        }
    }
    var signOut by remember { mutableStateOf(false) }
    SettingsContent(
        profile = profile,
        storage = storage,
        playMode = PlaybackMode.from(player.repeat, player.shuffled),
        lastRefresh = vm.lastRefreshLabel(),
        onClearCache = vm::clearPlaybackCache,
        onExport = { vm.shareDiagnostics(context) },
        onSignOut = { signOut = true },
        themeMode = themeMode, onThemeMode = vm::setThemeMode,
        autoCacheAudio = autoCacheAudio, onAutoCacheAudio = vm::setAutoCacheAudio,
    )
    if (signOut) AlertDialog(onDismissRequest = { signOut = false }, title = { Text(stringResource(R.string.settings_sign_out)) },
        text = { Text(stringResource(R.string.sign_out_confirm)) },
        confirmButton = { TextButton(onClick = { signOut = false; vm.logout() }) { Text(stringResource(R.string.settings_sign_out)) } },
        dismissButton = { TextButton(onClick = { signOut = false }) { Text(stringResource(R.string.dialog_cancel)) } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    profile: ServerProfile?,
    storage: StorageSummary,
    playMode: PlaybackMode,
    lastRefresh: String,
    onClearCache: () -> Unit,
    onExport: () -> Unit,
    onSignOut: () -> Unit,
    autoCacheAudio: Boolean = true,
    onAutoCacheAudio: (Boolean) -> Unit = {},
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onThemeMode: (ThemeMode) -> Unit = {},
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        PageHeading(stringResource(R.string.settings_title), stringResource(R.string.settings_subtitle))
        SettingsGroup(stringResource(R.string.settings_connection)) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.Dns, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(profile?.serverName?.ifBlank { "Any Listen" } ?: "Any Listen", style = MaterialTheme.typography.titleMedium)
                    Text(profile?.baseUrl.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.settings_server_version, profile?.reportedVersion?.ifBlank { "—" } ?: "—"),
                        style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.last_refresh, lastRefresh), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 20.dp))
            ActionLine(stringResource(R.string.settings_sign_out), Icons.AutoMirrored.Filled.Logout, onSignOut, destructive = true)
        }
        SettingsGroup(stringResource(R.string.settings_theme)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(16.dp)) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(selected = themeMode == mode, onClick = { onThemeMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size)) {
                        Text(stringResource(when (mode) { ThemeMode.LIGHT -> R.string.theme_light; ThemeMode.DARK -> R.string.theme_dark; ThemeMode.SYSTEM -> R.string.theme_system }))
                    }
                }
            }
        }
        SettingsGroup(stringResource(R.string.settings_playback)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(playModeLabel(playMode), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.settings_playback_resume), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        SettingsGroup(stringResource(R.string.settings_storage)) {
            val autoCacheLabel = stringResource(R.string.settings_auto_cache_audio)
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(autoCacheLabel, style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.settings_auto_cache_audio_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
                Switch(checked = autoCacheAudio, onCheckedChange = onAutoCacheAudio,
                    modifier = Modifier.semantics { contentDescription = autoCacheLabel })
            }
            HorizontalDivider(Modifier.padding(horizontal = 20.dp))
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.settings_downloads_size, humanBytes(storage.downloadBytes)), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.cache_size, humanBytes(storage.cacheBytes)), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.cached_resources_size, humanBytes(storage.resourceBytes)), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.settings_free_space, humanBytes(storage.usableBytes)), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ActionLine(stringResource(R.string.settings_clear_cache), Icons.Default.CleaningServices, onClearCache)
        }
        Text(stringResource(R.string.settings_about), style = MaterialTheme.typography.titleMedium)
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column {
                ActionLine(stringResource(R.string.settings_export_diagnostics), Icons.Default.Description, onExport)
                HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.settings_client_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.settings_compatible_server, ProtocolConstants.TARGET_SERVER_VERSION), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(content = content)
        }
    }
}

@Composable
private fun playModeLabel(mode: PlaybackMode): String = stringResource(when (mode) {
    PlaybackMode.LIST_LOOP -> R.string.cd_play_mode_list_loop
    PlaybackMode.RANDOM -> R.string.cd_play_mode_random
    PlaybackMode.SEQUENCE -> R.string.cd_play_mode_sequence
    PlaybackMode.SINGLE -> R.string.cd_play_mode_single
})
