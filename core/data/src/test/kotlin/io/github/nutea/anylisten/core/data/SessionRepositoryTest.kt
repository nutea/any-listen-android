package io.github.nutea.anylisten.core.data

import io.github.nutea.anylisten.core.data.gateway.MockAnyListenGateway
import io.github.nutea.anylisten.core.data.repo.SessionRepository
import io.github.nutea.anylisten.core.data.session.SessionStore
import io.github.nutea.anylisten.core.data.session.StoredSession
import io.github.nutea.anylisten.core.model.AppError
import io.github.nutea.anylisten.core.model.ErrorKind
import io.github.nutea.anylisten.core.model.ServerProfile
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRepositoryTest {
    @Test
    fun expiredTokenRelogsWithStoredPassword() = runTest {
        val store = MemorySessionStore()
        val gateway = MockAnyListenGateway()
        val repo = SessionRepository(store, gateway)
        repo.login("https://example.test", "secret")
        gateway.failNextRestore = true
        val restored = repo.restore()
        assertNotNull(restored)
        assertEquals("secret", store.current()?.password)
        assertTrue(store.current()?.token?.isNotBlank() == true)
    }

    @Test
    fun wrongPasswordClearsSession() = runTest {
        val store = MemorySessionStore()
        store.save(
            ServerProfile(id = "p", baseUrl = "https://example.test"),
            token = "stale",
            password = "wrong",
        )
        val gateway = MockAnyListenGateway()
        gateway.failNextRestore = true
        val repo = SessionRepository(store, gateway)
        val error = runCatching { repo.restore() }.exceptionOrNull() as AppError
        assertEquals(ErrorKind.AUTH_FAILED, error.kind)
        assertNull(store.current())
    }
}

private class MemorySessionStore : SessionStore {
    private var value: StoredSession? = null

    override fun current(): StoredSession? = value

    override fun save(profile: ServerProfile, token: String, password: String?) {
        value = StoredSession(profile, token, password ?: value?.password.orEmpty())
    }

    override fun clear() {
        value = null
    }
}
