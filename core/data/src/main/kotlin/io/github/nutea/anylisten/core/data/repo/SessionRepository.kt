package io.github.nutea.anylisten.core.data.repo

import io.github.nutea.anylisten.core.data.gateway.AnyListenGateway
import io.github.nutea.anylisten.core.data.gateway.SessionInfo
import io.github.nutea.anylisten.core.data.session.SessionStore
import io.github.nutea.anylisten.core.data.session.StoredSession
import io.github.nutea.anylisten.core.model.AppError
import io.github.nutea.anylisten.core.model.ErrorKind

class SessionRepository(
    private val store: SessionStore,
    private val gateway: AnyListenGateway,
    private val afterAuth: suspend () -> Unit = {},
) {
    suspend fun login(baseUrl: String, password: String): SessionInfo {
        val info = gateway.login(baseUrl, password)
        store.save(info.profile, info.token, password)
        runCatching { afterAuth() }
        return info
    }

    private val restoreFlight = SingleFlight<SessionInfo?>()

    suspend fun restore(): SessionInfo? = restoreFlight.join { restoreOnce() }

    private suspend fun restoreOnce(): SessionInfo? {
        val stored = store.current() ?: return null
        return try {
            persist(gateway.restore(stored.profile, stored.token), stored.password)
        } catch (error: AppError) {
            if (error.kind == ErrorKind.AUTH_FAILED || error.kind == ErrorKind.SESSION_EXPIRED) {
                reauth(stored)
            } else {
                null
            }
        }
    }

    suspend fun logout() {
        runCatching { gateway.logout() }
        store.clear()
    }

    private suspend fun reauth(stored: StoredSession): SessionInfo {
        if (stored.password.isBlank()) {
            throw AppError(ErrorKind.AUTH_FAILED, "Session expired")
        }
        return try {
            persist(gateway.login(stored.profile.baseUrl, stored.password), stored.password)
        } catch (error: AppError) {
            if (error.kind == ErrorKind.AUTH_FAILED) {
                store.clear()
            }
            throw error
        }
    }

    private suspend fun persist(info: SessionInfo, password: String): SessionInfo {
        store.save(info.profile, info.token, password)
        runCatching { afterAuth() }
        return info
    }
}
