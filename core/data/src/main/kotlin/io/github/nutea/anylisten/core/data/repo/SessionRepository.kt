package io.github.nutea.anylisten.core.data.repo

import io.github.nutea.anylisten.core.data.connection.ConnectionState
import io.github.nutea.anylisten.core.data.connection.SessionConnectionManager
import io.github.nutea.anylisten.core.data.gateway.SessionInfo
import kotlinx.coroutines.flow.StateFlow

/**
 * App-facing view of the server session.
 *
 * Deduplication, the re-login fallback and the reconnect policy all live in
 * [SessionConnectionManager] now, so this is a thin, intention-revealing wrapper rather than a
 * second place where connection decisions are made.
 */
class SessionRepository(
    private val connection: SessionConnectionManager,
) {
    val state: StateFlow<ConnectionState> = connection.state
    val session: StateFlow<SessionInfo?> = connection.session

    suspend fun login(baseUrl: String, password: String): SessionInfo =
        connection.signIn(baseUrl, password)

    /**
     * Ensure a session exists. Returns the live one when the socket is already healthy, null when
     * there is nothing stored to connect with, and throws the underlying error otherwise.
     */
    suspend fun restore(): SessionInfo? = connection.ensureConnected()

    suspend fun logout() = connection.signOut()

    /** Nudge the manager without waiting; used by UI and playback recovery paths. */
    fun requestConnect() = connection.requestConnect()
}
