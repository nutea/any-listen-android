package io.github.nutea.anylisten.core.data.session

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.github.nutea.anylisten.core.model.ServerProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class StoredSession(
    val profile: ServerProfile,
    val token: String,
    val password: String = "",
)

interface SessionStore {
    fun current(): StoredSession?
    fun save(profile: ServerProfile, token: String, password: String? = null)
    fun clear()
}

class SecureSessionStore(context: Context) : SessionStore {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "session",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    private val state = MutableStateFlow(read())
    val session: StateFlow<StoredSession?> = state.asStateFlow()

    override fun current(): StoredSession? = state.value

    override fun save(profile: ServerProfile, token: String, password: String?) {
        val keepPassword = password ?: prefs.getString(KEY_PASSWORD, null).orEmpty()
        prefs.edit()
            .putString(KEY_PROFILE_ID, profile.id)
            .putString(KEY_BASE, profile.baseUrl)
            .putString(KEY_SERVER_ID, profile.serverId)
            .putString(KEY_SERVER_NAME, profile.serverName)
            .putString(KEY_VERSION, profile.reportedVersion)
            .putString(KEY_TOKEN, token)
            .putString(KEY_PASSWORD, keepPassword)
            .commit()
        state.value = StoredSession(profile, token, keepPassword)
    }

    override fun clear() {
        prefs.edit().clear().commit()
        state.value = null
    }

    private fun read(): StoredSession? {
        val base = prefs.getString(KEY_BASE, null) ?: return null
        val token = prefs.getString(KEY_TOKEN, null) ?: return null
        return StoredSession(
            profile = ServerProfile(
                id = prefs.getString(KEY_PROFILE_ID, "default").orEmpty(),
                baseUrl = base,
                serverId = prefs.getString(KEY_SERVER_ID, "").orEmpty(),
                serverName = prefs.getString(KEY_SERVER_NAME, "").orEmpty(),
                reportedVersion = prefs.getString(KEY_VERSION, "").orEmpty(),
            ),
            token = token,
            password = prefs.getString(KEY_PASSWORD, "").orEmpty(),
        )
    }

    private companion object {
        const val KEY_PROFILE_ID = "profile_id"
        const val KEY_BASE = "base_url"
        const val KEY_SERVER_ID = "server_id"
        const val KEY_SERVER_NAME = "server_name"
        const val KEY_VERSION = "version"
        const val KEY_TOKEN = "token"
        const val KEY_PASSWORD = "password"
    }
}
