package io.github.nutea.anylisten.core.data

import io.github.nutea.anylisten.core.data.connection.ConnectionFault
import io.github.nutea.anylisten.core.data.connection.IpcAuthenticator
import io.github.nutea.anylisten.core.data.connection.IpcChannel
import io.github.nutea.anylisten.core.data.connection.IpcChannelFactory
import io.github.nutea.anylisten.core.data.connection.NetworkMonitor
import io.github.nutea.anylisten.core.data.connection.NetworkSnapshot
import io.github.nutea.anylisten.core.data.connection.SessionConnectionManager
import io.github.nutea.anylisten.core.data.connection.TransportReset
import io.github.nutea.anylisten.core.data.gateway.Message2Call
import io.github.nutea.anylisten.core.data.gateway.ProtocolDtos
import io.github.nutea.anylisten.core.data.gateway.SessionInfo
import io.github.nutea.anylisten.core.data.repo.SessionRepository
import io.github.nutea.anylisten.core.data.session.SessionStore
import io.github.nutea.anylisten.core.data.session.StoredSession
import io.github.nutea.anylisten.core.model.AppError
import io.github.nutea.anylisten.core.model.ErrorKind
import io.github.nutea.anylisten.core.model.ServerProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class SessionRepositoryTest {
    private val executor = Executors.newFixedThreadPool(4)
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
    private val store = MemorySessionStore()
    private val attempts = AtomicInteger()
    private val signIns = AtomicInteger()

    @Volatile private var failResumes = 0

    @Volatile private var rejectResume = false

    @Volatile private var rejectSignIn = false

    @After
    fun tearDown() {
        scope.cancel()
        executor.shutdownNow()
    }

    private fun repository(): SessionRepository = SessionRepository(
        SessionConnectionManager(
            store = store,
            authenticator = object : IpcAuthenticator {
                override suspend fun signIn(baseUrl: String, password: String): SessionInfo {
                    val attempt = signIns.incrementAndGet()
                    if (rejectSignIn) throw AppError(ErrorKind.AUTH_FAILED, "Authentication failed")
                    return SessionInfo(ServerProfile(id = "p", baseUrl = baseUrl), "token-$attempt")
                }

                override suspend fun resume(profile: ServerProfile, token: String): SessionInfo {
                    val attempt = attempts.incrementAndGet()
                    delay(60)
                    if (rejectResume) throw AppError(ErrorKind.SESSION_EXPIRED, "Session expired")
                    if (attempt <= failResumes) {
                        throw AppError(ErrorKind.NETWORK_UNREACHABLE, "offline", retryable = true)
                    }
                    return SessionInfo(profile, token)
                }
            },
            channels = EchoChannelFactory(),
            network = AlwaysOnline(),
            transport = NoopTransport(),
            scope = scope,
            jitter = { 0.0 },
        ).also { it.start() },
    )

    @Test
    fun aLiveSessionSatisfiesEveryCallerWithoutAnotherRoundTrip() = runBlocking {
        val repo = repository()
        withTimeout(WAIT) { repo.login("https://example.test", "secret") }
        val reused = List(3) { async { withTimeout(WAIT) { repo.restore() } } }.awaitAll()
        assertTrue(reused.all { it != null })
        assertEquals(0, attempts.get())
    }

    @Test
    fun concurrentRestoresShareOneAttemptAndRecoverAfterANetworkFailure() = runBlocking {
        store.save(ServerProfile(id = "p", baseUrl = "https://example.test"), "token", "secret")
        failResumes = 1
        val repo = repository()
        val recovered = List(3) { async { withTimeout(WAIT) { repo.restore() } } }.awaitAll()
        assertTrue(recovered.all { it != null })
        // One failed attempt plus the retry that succeeded, shared by all three callers.
        assertEquals(2, attempts.get())
        assertNotNull(store.current())
    }

    @Test
    fun expiredTokenRelogsWithStoredPassword() = runBlocking {
        store.save(ServerProfile(id = "p", baseUrl = "https://example.test"), "stale", "secret")
        rejectResume = true
        val restored = withTimeout(WAIT) { repository().restore() }
        assertNotNull(restored)
        assertEquals("secret", store.current()?.password)
        assertTrue(store.current()?.token?.isNotBlank() == true)
    }

    @Test
    fun wrongPasswordClearsSession() = runBlocking {
        store.save(ServerProfile(id = "p", baseUrl = "https://example.test"), "stale", "wrong")
        rejectResume = true
        rejectSignIn = true
        val error = runCatching { withTimeout(WAIT) { repository().restore() } }.exceptionOrNull() as AppError
        assertEquals(ErrorKind.AUTH_FAILED, error.kind)
        assertNull(store.current())
    }

    @Test
    fun nothingStoredRestoresToNothing() = runBlocking {
        assertNull(withTimeout(WAIT) { repository().restore() })
    }

    private class MemorySessionStore : SessionStore {
        private val state = MutableStateFlow<StoredSession?>(null)
        override val changes: StateFlow<StoredSession?> = state.asStateFlow()
        override fun current(): StoredSession? = state.value
        override fun save(profile: ServerProfile, token: String, password: String?) {
            state.value = StoredSession(profile, token, password ?: state.value?.password.orEmpty())
        }

        override fun clear() {
            state.value = null
        }
    }

    private class AlwaysOnline : NetworkMonitor {
        override val status: StateFlow<NetworkSnapshot> =
            MutableStateFlow(NetworkSnapshot(id = 1L, online = true)).asStateFlow()

        override fun start() = Unit
        override fun stop() = Unit
    }

    private class NoopTransport : TransportReset {
        override fun evictPooledConnections() = Unit
        override fun cancelApiCalls() = Unit
        override fun cancelMediaCalls() = Unit
    }

    private class EchoChannelFactory : IpcChannelFactory {
        override suspend fun open(session: SessionInfo, generation: Long): IpcChannel =
            EchoChannel(generation, session)
    }

    private class EchoChannel(
        override val generation: Long,
        override val session: SessionInfo,
    ) : IpcChannel {
        override val closed = CompletableDeferred<ConnectionFault>()
        override val calls: Message2Call = Message2Call(ProtocolDtos.json, callTimeoutMs = 2_000) { frame ->
            val parsed = ProtocolDtos.json.parseToJsonElement(frame) as JsonArray
            calls.dispatch(
                buildJsonArray {
                    add(JsonPrimitive(1))
                    add(JsonPrimitive(parsed[1].jsonPrimitive.content))
                    add(JsonNull)
                    add(buildJsonObject { })
                }.toString(),
            )
        }

        override fun close(code: Int, reason: String) {
            calls.failAll(reason)
            closed.complete(ConnectionFault.TRANSPORT)
        }
    }

    private companion object {
        const val WAIT = 15_000L
    }
}
