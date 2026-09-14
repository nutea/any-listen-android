package io.github.nutea.anylisten.core.data.connection

import io.github.nutea.anylisten.core.data.gateway.Message2Call
import io.github.nutea.anylisten.core.data.gateway.ProtocolDtos
import io.github.nutea.anylisten.core.data.gateway.SessionInfo
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * End-to-end behaviour of the connection owner against fake transports, focused on the races the
 * old multi-owner design lost: two callers reconnecting at once, a handover while calls are in
 * flight, and a socket reporting its death after it was already replaced.
 */
class SessionConnectionManagerTest {
    // A private pool: the manager's event loop must not compete with whatever else the suite
    // happens to be running on Dispatchers.Default.
    private val executor = Executors.newFixedThreadPool(4)
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())

    @After
    fun tearDown() {
        scope.cancel()
        executor.shutdownNow()
    }

    @Test
    fun rejectedPushInitializationRebuildsConnectionBeforePublishingOnline() = runBlocking {
        val world = World()
        world.store.save(profile(), "token", "secret")
        world.network.emit(NetworkSnapshot(id = 1L, online = true))
        world.channels.failInitializationOnce = true
        val manager = world.manager()
        manager.requestConnect()
        withTimeout(WAIT) { manager.state.first { it is ConnectionState.Online } }
        assertEquals(2, world.channels.opened)
        assertTrue(manager.isOnline)
    }

    @Test
    fun partialLibraryRefreshReadsOnlyChangedAndNewPlaylists() = runBlocking {
        val world = World()
        world.store.save(profile(), "token", "secret")
        world.network.emit(NetworkSnapshot(id = 1L, online = true))
        val requested = CopyOnWriteArrayList<String>()
        world.channels.responder = { path, args ->
            when (path) {
                "getAllUserLists" -> ProtocolDtos.json.parseToJsonElement("""{"userList":[{"id":"a","name":"Renamed"},{"id":"b","name":"B"},{"id":"new","name":"New"}]}""")
                "getListMusics" -> {
                    val id = args[0].jsonPrimitive.content
                    requested.add(id)
                    ProtocolDtos.json.parseToJsonElement("""[{"id":"$id-song","name":"Fresh $id","singer":"Artist","meta":{}}]""")
                }
                else -> null
            }
        }
        val manager = world.manager()
        withTimeout(WAIT) { manager.ensureConnected() }
        val gateway = io.github.nutea.anylisten.core.data.gateway.ProtocolAnyListenGateway(manager)
        val old = io.github.nutea.anylisten.core.model.Track(
            io.github.nutea.anylisten.core.model.TrackIdentity("p", "kept"), "Kept", "Artist", "Album", null)
        val cached = io.github.nutea.anylisten.core.model.LibrarySnapshot(
            playlists = emptyList(), refreshedAtEpochMs = 1L, offline = false,
            tracksByPlaylist = mapOf("a" to listOf(old), "b" to listOf(old), "removed" to listOf(old)))
        val updated = gateway.refreshLibrary(cached, setOf("b"))
        assertEquals(listOf("b", "new"), requested.toList())
        assertSame(old, updated.tracksByPlaylist["a"]!!.single())
        assertEquals("Fresh b", updated.tracksByPlaylist["b"]!!.single().title)
        assertEquals("Renamed", updated.playlists.first().name)
        assertFalse(updated.tracksByPlaylist.containsKey("removed"))
        requested.clear()
        gateway.refreshLibrary()
        assertEquals(listOf("a", "b", "new"), requested.toList())
    }

    @Test
    fun libraryPushObservationMovesToTheNewSocketAfterReconnect() = runBlocking {
        val world = World()
        world.store.save(profile(), "token", "secret")
        world.network.emit(NetworkSnapshot(id = 1L, online = true))
        val manager = world.manager()
        withTimeout(WAIT) { manager.ensureConnected() }
        val first = world.channels.last()!!
        val push = """[0,"server-list",["listAction"],[{"action":"list_music_add","data":{"id":"love"}}],[]]"""
        first.calls.dispatch(push)
        withTimeout(WAIT) { manager.libraryChanges.first { it > 0 } }
        val beforeReconnect = manager.libraryChanges.value
        world.network.emit(NetworkSnapshot(id = 2L, online = true))
        world.awaitLink(manager, 2L)
        val second = world.channels.last()!!
        assertTrue(first.calls.isClosed)
        first.calls.dispatch(push)
        world.settle(manager)
        assertEquals(beforeReconnect, manager.libraryChanges.value)
        second.calls.dispatch(push)
        withTimeout(WAIT) { manager.libraryChanges.first { it > beforeReconnect } }
        assertTrue(manager.libraryChanges.value > beforeReconnect)
    }

    @Test
    fun signInStoresCredentialsAndPublishesAnOnlineSession() = runBlocking {
        val world = World()
        val manager = world.manager()
        val info = withTimeout(WAIT) { manager.signIn("https://example.test", "secret") }
        assertEquals("https://example.test", info.profile.baseUrl)
        assertTrue(manager.isOnline)
        assertEquals("secret", world.store.current()?.password)
        assertEquals(1, world.signIns.get())
    }

    @Test
    fun concurrentRequestsShareOneAttempt() = runBlocking {
        val world = World(authDelayMs = 100)
        world.store.save(profile(), "token", "secret")
        val manager = world.manager()
        val results = List(5) { async { withTimeout(WAIT) { manager.ensureConnected() } } }.awaitAll()
        assertTrue(results.all { it != null })
        assertEquals(1, world.resumes.get())
        assertEquals(world.channels.trace, 1, world.channels.opened)
    }

    @Test
    fun anAlreadyHealthySessionIsReusedInsteadOfRebuilt() = runBlocking {
        val world = World()
        world.store.save(profile(), "token", "secret")
        val manager = world.manager()
        withTimeout(WAIT) { manager.ensureConnected() }
        val first = world.channels.last()
        repeat(3) { withTimeout(WAIT) { manager.ensureConnected() } }
        assertEquals(world.channels.trace, 1, world.channels.opened)
        assertSame(world.channels.trace, first, world.channels.last())
    }

    @Test
    fun theFirstNetworkCallbackDoesNotDisturbAFreshSession() = runBlocking {
        // 0.1.1-beta.4: the socket exists before ConnectivityManager speaks, and its first
        // callback used to tear down the session that had just signed in.
        val world = World()
        world.store.save(profile(), "token", "secret")
        val manager = world.manager()
        withTimeout(WAIT) { manager.ensureConnected() }
        val established = world.channels.last()!!

        world.network.emit(NetworkSnapshot(id = 11L, online = true))
        world.settle(manager)

        assertEquals(world.channels.trace, 1, world.channels.opened)
        assertSame(world.channels.trace, established, world.channels.last())
        assertFalse(established.calls.isClosed)
        assertTrue(manager.isOnline)
    }

    @Test
    fun switchingNetworksRebuildsTheSessionAndRetriesTheCallInFlight() = runBlocking {
        val world = World()
        world.store.save(profile(), "token", "secret")
        world.network.emit(NetworkSnapshot(id = 1L, online = true))
        val manager = world.manager()
        withTimeout(WAIT) { manager.ensureConnected() }
        val first = world.channels.last()!!
        val firstGen = first.generation

        first.answerCalls = false
        val resolve = async { withTimeout(WAIT) { manager.withChannel { it.call(listOf("getMusicUrl")) } } }
        withTimeout(WAIT) { first.awaitSent("getMusicUrl") }

        world.network.emit(NetworkSnapshot(id = 2L, online = true))
        withTimeout(WAIT) { manager.state.first { it is ConnectionState.Online && it.generation > firstGen } }

        assertEquals("url", (resolve.await() as JsonPrimitive).content)
        assertTrue(world.channels.trace, world.channels.last()!!.generation > firstGen)
        assertTrue(first.calls.isClosed)
    }

    @Test
    fun aCallStartedWhileTheSessionIsStillConnectingWaitsForTheSocket() = runBlocking {
        val world = World(authDelayMs = 150)
        world.store.save(profile(), "token", "secret")
        val manager = world.manager()
        val resolved = withTimeout(WAIT) { manager.withChannel { it.call(listOf("getMusicUrl")) } }
        assertEquals("url", (resolved as JsonPrimitive).content)
        assertEquals(world.channels.trace, 1, world.channels.opened)
    }

    @Test
    fun aSocketThatDiesAfterBeingReplacedCannotTearDownItsSuccessor() = runBlocking {
        val world = World()
        world.store.save(profile(), "token", "secret")
        world.network.emit(NetworkSnapshot(id = 1L, online = true))
        val manager = world.manager()
        withTimeout(WAIT) { manager.ensureConnected() }
        val first = world.channels.last()!!
        val firstGen = first.generation

        world.network.emit(NetworkSnapshot(id = 2L, online = true))
        withTimeout(WAIT) { manager.state.first { it is ConnectionState.Online && it.generation > firstGen } }
        val second = world.channels.last()!!
        val secondGen = second.generation

        first.die()
        manager.report(first.generation, ConnectionFault.TRANSPORT)
        world.settle(manager)

        assertTrue(manager.isOnline)
        assertEquals(secondGen, (manager.state.value as ConnectionState.Online).generation)
        assertSame(world.channels.trace, second, world.channels.last())
        assertFalse(second.calls.isClosed)
    }

    @Test
    fun aLostLinkKeepsTheSessionAndTheReturningLinkOnlyProbesIt() = runBlocking {
        val world = World()
        world.store.save(profile(), "token", "secret")
        world.network.emit(NetworkSnapshot(id = 1L, online = true))
        val manager = world.manager()
        withTimeout(WAIT) { manager.ensureConnected() }
        val channel = world.channels.last()!!
        val generation = channel.generation
        channel.resetProbe()

        world.network.emit(NetworkSnapshot.Offline)
        withTimeout(WAIT) { manager.state.first { it is ConnectionState.Online && it.linkDown } }
        assertTrue("a live socket must survive a brief link loss", manager.isOnline)
        assertEquals(1, world.transport.apiCancels.get())
        assertEquals(0, world.transport.mediaCancels.get())

        world.network.emit(NetworkSnapshot(id = 1L, online = true))
        withTimeout(WAIT) { channel.awaitProbe() }
        assertEquals(generation, (manager.state.value as ConnectionState.Online).generation)
        assertTrue(manager.isOnline)
    }

    @Test
    fun aSocketThatFailsItsProbeIsRebuilt() = runBlocking {
        val world = World()
        world.store.save(profile(), "token", "secret")
        world.network.emit(NetworkSnapshot(id = 1L, online = true))
        val manager = world.manager()
        withTimeout(WAIT) { manager.ensureConnected() }
        val firstGen = world.channels.last()!!.generation
        world.channels.last()!!.answerCalls = false

        world.network.emit(NetworkSnapshot.Offline)
        withTimeout(WAIT) { manager.state.first { it is ConnectionState.Online && it.linkDown } }
        world.network.emit(NetworkSnapshot(id = 1L, online = true))

        withTimeout(WAIT) { manager.state.first { it is ConnectionState.Online && it.generation > firstGen } }
        assertTrue(manager.isOnline)
    }

    @Test
    fun onlyANetworkIdentityChangeCancelsMediaCalls() = runBlocking {
        val world = World()
        world.store.save(profile(), "token", "secret")
        world.network.emit(NetworkSnapshot(id = 1L, online = true))
        val manager = world.manager()
        withTimeout(WAIT) { manager.ensureConnected() }
        val firstGen = (manager.state.value as ConnectionState.Online).generation

        world.network.emit(NetworkSnapshot(id = 1L, online = true))
        world.settle(manager)
        assertEquals(0, world.transport.mediaCancels.get())

        world.network.emit(NetworkSnapshot(id = 2L, online = true))
        withTimeout(WAIT) { manager.state.first { it is ConnectionState.Online && it.generation > firstGen } }
        assertEquals(1, world.transport.mediaCancels.get())
    }

    @Test
    fun anExpiredTokenIsRecoveredWithTheStoredPassword() = runBlocking {
        val world = World()
        world.store.save(profile(), "stale", "secret")
        world.rejectResume = true
        val manager = world.manager()
        val info = withTimeout(WAIT) { manager.ensureConnected() }
        assertNotNull(info)
        assertEquals("secret", world.store.current()?.password)
        assertTrue(world.store.current()?.token?.isNotBlank() == true)
        assertEquals(1, world.signIns.get())
    }

    @Test
    fun aRejectedPasswordClearsTheSessionAndStopsRetrying() = runBlocking {
        val world = World()
        world.store.save(profile(), "stale", "wrong")
        world.rejectResume = true
        world.rejectSignIn = true
        val manager = world.manager()
        val error = runCatching { withTimeout(WAIT) { manager.ensureConnected() } }.exceptionOrNull()
        assertEquals(ErrorKind.AUTH_FAILED, (error as? AppError)?.kind)
        assertNull(world.store.current())
        withTimeout(WAIT) { manager.state.first { it is ConnectionState.SignedOut } }
        assertEquals(1, world.signIns.get())
    }

    @Test
    fun transientFailuresRetryUntilTheServerAnswers() = runBlocking {
        val world = World()
        world.store.save(profile(), "token", "secret")
        world.failResumes = 2
        val manager = world.manager()
        val info = withTimeout(WAIT) { manager.ensureConnected() }
        assertNotNull(info)
        assertEquals(3, world.resumes.get())
        assertEquals(world.channels.trace, 1, world.channels.opened)
    }

    @Test
    fun signOutClosesTheSocketAndForgetsTheStoredSession() = runBlocking {
        val world = World()
        val manager = world.manager()
        withTimeout(WAIT) { manager.signIn("https://example.test", "secret") }
        val channel = world.channels.last()!!
        withTimeout(WAIT) { manager.signOut() }
        assertTrue(channel.calls.isClosed)
        assertNull(world.store.current())
        assertFalse(manager.isOnline)
        assertNull(withTimeout(WAIT) { manager.ensureConnected() })
    }

    @Test
    fun nothingStoredMeansNothingToConnectTo() = runBlocking {
        val world = World()
        assertNull(withTimeout(WAIT) { world.manager().ensureConnected() })
        assertEquals(world.channels.trace, 0, world.channels.opened)
    }

    private fun profile() = ServerProfile(id = "p", baseUrl = "https://example.test")

    private inner class World(private val authDelayMs: Long = 0L) {
        val store = MemorySessionStore()
        val network = FakeNetworkMonitor()
        val transport = CountingTransport()
        val channels = FakeChannelFactory()
        val signIns = AtomicInteger()
        val resumes = AtomicInteger()

        @Volatile var rejectResume = false

        @Volatile var rejectSignIn = false

        @Volatile var failResumes = 0

        private val authenticator = object : IpcAuthenticator {
            override suspend fun signIn(baseUrl: String, password: String): SessionInfo {
                if (authDelayMs > 0) delay(authDelayMs)
                val attempt = signIns.incrementAndGet()
                if (rejectSignIn) throw AppError(ErrorKind.AUTH_FAILED, "Authentication failed")
                return SessionInfo(ServerProfile(id = "p", baseUrl = baseUrl), "token-$attempt")
            }

            override suspend fun resume(profile: ServerProfile, token: String): SessionInfo {
                if (authDelayMs > 0) delay(authDelayMs)
                val attempt = resumes.incrementAndGet()
                if (rejectResume) throw AppError(ErrorKind.SESSION_EXPIRED, "Session expired")
                if (attempt <= failResumes) throw AppError(ErrorKind.NETWORK_UNREACHABLE, "offline", retryable = true)
                return SessionInfo(profile, token)
            }
        }

        fun manager(): SessionConnectionManager = SessionConnectionManager(
            store = store,
            authenticator = authenticator,
            channels = channels,
            network = network,
            transport = transport,
            scope = scope,
            jitter = { 0.0 },
        ).also { it.start() }

        /** Drain the queue, then give any work it launched a moment to finish. */
        suspend fun settle(manager: SessionConnectionManager) {
            manager.awaitProcessed()
            delay(150)
            manager.awaitProcessed()
        }

        /** Wait until the session is known to be bound to [networkId]. */
        suspend fun awaitLink(manager: SessionConnectionManager, networkId: Long) {
            withTimeout(WAIT) {
                manager.state.first { it is ConnectionState.Online && it.networkId == networkId }
            }
        }
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

    private class FakeNetworkMonitor : NetworkMonitor {
        private val state = MutableStateFlow(NetworkSnapshot.Unknown)
        override val status: StateFlow<NetworkSnapshot> = state.asStateFlow()
        override fun start() = Unit
        override fun stop() = Unit
        fun emit(snapshot: NetworkSnapshot) {
            state.value = snapshot
        }
    }

    private class CountingTransport : TransportReset {
        val evictions = AtomicInteger()
        val apiCancels = AtomicInteger()
        val mediaCancels = AtomicInteger()

        override fun evictPooledConnections() {
            evictions.incrementAndGet()
        }

        override fun cancelApiCalls() {
            apiCancels.incrementAndGet()
        }

        override fun cancelMediaCalls() {
            mediaCancels.incrementAndGet()
        }
    }

    private class FakeChannelFactory : IpcChannelFactory {
        private val history = CopyOnWriteArrayList<FakeChannel>()
        var failInitializationOnce = false
        var responder: ((String, JsonArray) -> JsonElement?)? = null

        val opened: Int get() = history.size

        /** Generations in the order they were opened; shown when a count assertion fails. */
        val trace: String get() = history.joinToString(prefix = "generations=[", postfix = "]") { it.generation.toString() }

        fun last(): FakeChannel? = history.lastOrNull()

        override suspend fun open(session: SessionInfo, generation: Long): IpcChannel =
            FakeChannel(generation, session, failInitializationOnce && history.isEmpty(), responder).also { history.add(it) }
    }

    /**
     * A socket whose wire is faked but whose [Message2Call] is real, so the close and dispatch
     * semantics under test are the production ones.
     */
    private class FakeChannel(
        override val generation: Long,
        override val session: SessionInfo,
        private val failInitialization: Boolean = false,
        private val responder: ((String, JsonArray) -> JsonElement?)? = null,
    ) : IpcChannel {
        override val closed = CompletableDeferred<ConnectionFault>()
        private val sent = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        private val probe = AtomicReference(CompletableDeferred<Unit>())

        @Volatile var answerCalls = true

        override val calls: Message2Call = Message2Call(ProtocolDtos.json, callTimeoutMs = CALL_TIMEOUT_MS) { frame ->
            val parsed = ProtocolDtos.json.parseToJsonElement(frame) as JsonArray
            if (parsed[0].jsonPrimitive.content != "0") return@Message2Call
            val name = parsed[1].jsonPrimitive.content
            val path = (parsed[2] as JsonArray)[0].jsonPrimitive.content
            sent.getOrPut(path) { CompletableDeferred() }.complete(Unit)
            if (path == "getCurrentVersionInfo") probe.get().complete(Unit)
            if (path == "inited" && failInitialization) {
                calls.dispatch(JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(name),
                    buildJsonObject { put("message", JsonPrimitive("Initialization rejected")) })).toString())
            } else if (answerCalls) dispatchReply(name, path, parsed[3] as JsonArray)
        }

        private fun dispatchReply(name: String, path: String, args: JsonArray) {
            val result = responder?.invoke(path, args) ?: when (path) {
                "getCurrentVersionInfo" -> buildJsonObject { }
                "inited" -> JsonNull
                else -> JsonPrimitive("url")
            }
            calls.dispatch(
                buildJsonArray {
                    add(JsonPrimitive(1))
                    add(JsonPrimitive(name))
                    add(JsonNull)
                    add(result)
                }.toString(),
            )
        }

        override fun close(code: Int, reason: String) {
            calls.failAll(reason)
            closed.complete(ConnectionFault.TRANSPORT)
        }

        fun die() = close(IpcChannel.NORMAL_CLOSE, "socket died")

        suspend fun awaitSent(path: String) = sent.getOrPut(path) { CompletableDeferred() }.await()

        /** Forget the handshake's version call so a later probe can be observed on its own. */
        fun resetProbe() {
            probe.set(CompletableDeferred())
        }

        suspend fun awaitProbe() = probe.get().await()
    }

    private companion object {
        const val WAIT = 20_000L

        /**
         * Far longer than any handover these tests simulate: a call deadline firing first would
         * exercise the stale-socket path instead of the one under test.
         */
        const val CALL_TIMEOUT_MS = 30_000L
    }
}
