package io.github.nutea.anylisten.core.data.session

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore("settings")

class AppSettingsStore(private val context: Context) {
    val wifiOnly: Flow<Boolean> = context.settingsDataStore.data.map { it[WIFI_ONLY] ?: true }
    val playback: Flow<PersistedPlayback> = context.settingsDataStore.data.map { PersistedPlayback.decode(it[PLAYBACK]) }

    suspend fun setWifiOnly(value: Boolean) {
        context.settingsDataStore.edit { it[WIFI_ONLY] = value }
    }

    suspend fun setPlayback(value: PersistedPlayback) {
        context.settingsDataStore.edit { it[PLAYBACK] = value.encode() }
    }

    private companion object {
        val WIFI_ONLY = booleanPreferencesKey("wifi_only_download")
        val PLAYBACK = stringPreferencesKey("playback_queue")
    }
}
