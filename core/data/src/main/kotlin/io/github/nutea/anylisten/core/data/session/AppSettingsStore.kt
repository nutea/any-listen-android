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
    val lyricSettings: Flow<LyricSettings> = context.settingsDataStore.data.map { LyricSettings.decode(it[LYRICS]) }
    val playback: Flow<PersistedPlayback> = context.settingsDataStore.data.map { PersistedPlayback.decode(it[PLAYBACK]) }

    suspend fun setThemeMode(value: ThemeMode) { context.settingsDataStore.edit { it[THEME_MODE] = value.name } }

    suspend fun setAutoCacheAudio(value: Boolean) {
        context.settingsDataStore.edit { it[AUTO_CACHE_AUDIO] = value }
    }

    suspend fun setPlayback(value: PersistedPlayback) {
        context.settingsDataStore.edit { it[PLAYBACK] = value.encode() }
    }

    suspend fun setShowTranslation(show: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[LYRICS] = LyricSettings.decode(prefs[LYRICS]).copy(showTranslation = show).encode()
        }
    }

    suspend fun setShowRomanization(show: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[LYRICS] = LyricSettings.decode(prefs[LYRICS]).copy(showRomanization = show).encode()
        }
    }

    suspend fun setKaraokeEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[LYRICS] = LyricSettings.decode(prefs[LYRICS]).copy(karaokeEnabled = enabled).encode()
        }
    }

    suspend fun setLyricOffset(trackKey: String, offsetMs: Long) {
        context.settingsDataStore.edit { prefs ->
            val current = LyricSettings.decode(prefs[LYRICS])
            val offsets = current.offsets.toMutableMap()
            val offset = io.github.nutea.anylisten.core.model.LyricTiming.clamp(offsetMs)
            if (offset == 0L) offsets.remove(trackKey) else offsets[trackKey] = offset
            prefs[LYRICS] = current.copy(offsets = offsets).encode()
        }
    }

    private companion object {
        val LYRICS = stringPreferencesKey("lyric_settings")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val AUTO_CACHE_AUDIO = booleanPreferencesKey("auto_cache_audio")
        val PLAYBACK = stringPreferencesKey("playback_queue")
    }
}
