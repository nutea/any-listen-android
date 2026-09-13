package io.github.nutea.anylisten.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.nutea.anylisten.BuildConfig
import io.github.nutea.anylisten.R
import io.github.nutea.anylisten.core.model.ProtocolConstants
import io.github.nutea.anylisten.ui.AppViewModel

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val wifiOnly by vm.wifiOnly.collectAsState()
    val profile by vm.profile.collectAsState()
    val storage by vm.storage.collectAsState()
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.settings_client_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE))
        Text(stringResource(R.string.settings_compatible_server, ProtocolConstants.TARGET_SERVER_VERSION))
        Text(stringResource(R.string.settings_server_name, profile?.serverName?.ifBlank { "—" } ?: "—"))
        Text(stringResource(R.string.settings_server_version, profile?.reportedVersion?.ifBlank { "—" } ?: "—"))
        Text(stringResource(R.string.settings_server_url, profile?.baseUrl ?: "—"))
        Text(
            stringResource(
                R.string.storage_summary,
                storage.downloadBytes / 1024,
                storage.cacheBytes / 1024,
                storage.usableBytes / 1024 / 1024,
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_wifi_only), modifier = Modifier.weight(1f))
            Switch(
                checked = wifiOnly,
                onCheckedChange = vm::setWifiOnly,
                modifier = Modifier.semantics { contentDescription = "Wi-Fi only downloads" },
            )
        }
        OutlinedButton(onClick = vm::clearPlaybackCache) { Text(stringResource(R.string.settings_clear_cache)) }
        OutlinedButton(onClick = { vm.shareDiagnostics(context) }) { Text(stringResource(R.string.settings_export_diagnostics)) }
        Button(onClick = vm::logout) { Text(stringResource(R.string.settings_sign_out)) }
    }
}
