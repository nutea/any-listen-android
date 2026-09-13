package io.github.nutea.anylisten.core.data.session

import io.github.nutea.anylisten.core.model.ThemeMode
import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore("settings")

class AppSettingsStore(private val context: Context) {
    val themeMode: Flow<ThemeMode> = context.settingsDataStore.data.map { ThemeMode.decode(it[THEME_MODE]) }
    val autoCacheAudio: Flow<Boolean> = context.settingsDataStore.data.map { it[AUTO_CACHE_AUDIO] ?: true }
    val playback: Flow<PersistedPlayback> = context.settingsDataStore.data.map { PersistedPlayback.decode(it[PLAYBACK]) }

    suspend fun setThemeMode(value: ThemeMode) { context.settingsDataStore.edit { it[THEME_MODE] = value.name } }

    suspend fun setAutoCacheAudio(value: Boolean) {
        context.settingsDataStore.edit { it[AUTO_CACHE_AUDIO] = value }
    }

    suspend fun setPlayback(value: PersistedPlayback) {
        context.settingsDataStore.edit { it[PLAYBACK] = value.encode() }
    }

    private companion object {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val AUTO_CACHE_AUDIO = booleanPreferencesKey("auto_cache_audio")
        val PLAYBACK = stringPreferencesKey("playback_queue")
    }
}
