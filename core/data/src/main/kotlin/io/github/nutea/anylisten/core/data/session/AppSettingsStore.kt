package io.github.nutea.anylisten.core.data.session

import io.github.nutea.anylisten.core.model.ThemeMode
import io.github.nutea.anylisten.core.model.PlayerStyle
import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore("settings")

class AppSettingsStore(private val context: Context) {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val audioEffects: Flow<io.github.nutea.anylisten.core.model.AudioEffectsSettings> = context.settingsDataStore.data.map {
        runCatching { json.decodeFromString<io.github.nutea.anylisten.core.model.AudioEffectsSettings>(it[AUDIO_EFFECTS].orEmpty()) }
            .getOrDefault(io.github.nutea.anylisten.core.model.AudioEffectsSettings()).sanitized()
    }
    val searchHistory: Flow<List<String>> = context.settingsDataStore.data.map {
        runCatching { json.decodeFromString<List<String>>(it[SEARCH_HISTORY].orEmpty()) }.getOrDefault(emptyList())
    }
    suspend fun setAudioEffects(value: io.github.nutea.anylisten.core.model.AudioEffectsSettings) {
        context.settingsDataStore.edit { it[AUDIO_EFFECTS] = json.encodeToString(io.github.nutea.anylisten.core.model.AudioEffectsSettings.serializer(), value.sanitized()) }
    }
    suspend fun rememberSearch(query: String) {
        val q = query.trim().take(100)
        if (q.isBlank()) return
        context.settingsDataStore.edit { prefs ->
            val old = runCatching { json.decodeFromString<List<String>>(prefs[SEARCH_HISTORY].orEmpty()) }.getOrDefault(emptyList())
            prefs[SEARCH_HISTORY] = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()),
                (listOf(q) + old.filterNot { it.equals(q, true) }).take(20))
        }
    }
    suspend fun clearSearchHistory() { context.settingsDataStore.edit { it.remove(SEARCH_HISTORY) } }
    val themeMode: Flow<ThemeMode> = context.settingsDataStore.data.map { ThemeMode.decode(it[THEME_MODE]) }
    val playerStyle: Flow<PlayerStyle> = context.settingsDataStore.data.map { PlayerStyle.decode(it[PLAYER_STYLE]) }
    val autoCacheAudio: Flow<Boolean> = context.settingsDataStore.data.map { it[AUTO_CACHE_AUDIO] ?: true }
    val lyricSettings: Flow<LyricSettings> = context.settingsDataStore.data.map { LyricSettings.decode(it[LYRICS]) }
    val playback: Flow<PersistedPlayback> = context.settingsDataStore.data.map { PersistedPlayback.decode(it[PLAYBACK]) }

    suspend fun setThemeMode(value: ThemeMode) { context.settingsDataStore.edit { it[THEME_MODE] = value.name } }
    suspend fun setPlayerStyle(value: PlayerStyle) { context.settingsDataStore.edit { it[PLAYER_STYLE] = value.name } }

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
        val AUDIO_EFFECTS = stringPreferencesKey("audio_effects")
        val SEARCH_HISTORY = stringPreferencesKey("search_history")
        val LYRICS = stringPreferencesKey("lyric_settings")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val PLAYER_STYLE = stringPreferencesKey("player_style")
        val AUTO_CACHE_AUDIO = booleanPreferencesKey("auto_cache_audio")
        val PLAYBACK = stringPreferencesKey("playback_queue")
    }
}
