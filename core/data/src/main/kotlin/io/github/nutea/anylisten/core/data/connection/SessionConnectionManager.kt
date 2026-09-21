package io.github.nutea.anylisten.core.data.connection

import io.github.nutea.anylisten.core.data.gateway.IpcCallException
import io.github.nutea.anylisten.core.data.gateway.IpcClosedException
import io.github.nutea.anylisten.core.data.gateway.IpcTimeoutException
import io.github.nutea.anylisten.core.data.gateway.Message2Call
import io.github.nutea.anylisten.core.data.gateway.SessionInfo
import io.github.nutea.anylisten.core.data.session.SessionStore
import io.github.nutea.anylisten.core.model.AppError
import io.github.nutea.anylisten.core.model.ErrorKind
import io.github.nutea.anylisten.core.model.ProtocolConstants
import io.github.nutea.anylisten.core.model.ServerProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/** HTTP side of signing in. Split from the socket so both can be faked independently in tests. */
interface IpcAuthenticator {
    suspend fun signIn(baseUrl: String, password: String): SessionInfo

    suspend fun resume(profile: ServerProfile, token: String): SessionInfo

    /** Best-effort warm-up of the media proxy cookie. Failure must not fail the session. */
    suspend fun primeStreamToken(session: SessionInfo) = Unit
}

/**
 * Connection-scoped control over the shared OkHttp state.
 *
 * Split by purpose so that clearing out API calls after a handover can never cancel the media
 * stream and vice versa: they no longer share a dispatcher.
 */
interface TransportReset {
    /** Drop pooled keep-alives; they are bound to an interface that may be gone. */
    fun evictPooledConnections()

    /** Fail API calls still trying the old interface. */
    fun cancelApiCalls()

    /** Fail media calls still trying the old interface, so ExoPlayer retries instead of stalling. */
    fun cancelMediaCalls()
}

/**
 * Single owner of the server session: network observation, authentication, the IPC socket and
 * the reconnect policy.
 *
 * All state transitions happen on one coroutine draining an unbounded event queue, so the
 * interleavings the old code allowed — two `ConnectivityManager` callbacks each starting their
 * own restore, a dying socket tearing down its replacement, a teardown racing an inbound frame —
 * cannot be expressed. Callers submit facts and observe [state]; nothing else mutates the
 * session.
 */
class SessionConnectionManager(
    private val store: SessionStore,
    private val authenticator: IpcAuthenticator,
    private val channels: IpcChannelFactory,
    private val network: NetworkMonitor,
    private val transport: TransportReset,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val jitter: () -> Double = { Random.nextDouble() },
    private val onConnected: suspend (SessionInfo) -> Unit = {},
) {
    private data class Envelope(
        val event: ConnectionEvent,
        val channel: IpcChannel? = null,
        val session: SessionInfo? = null,
        /** Completed with the sequence number this event was reduced at. */
        val reduced: CompletableDeferred<Long>? = null,
    )

    private data class Credentials(val baseUrl: String, val password: String)

    /** [seq] advances once per processed event so waiters can tell "still" from "again". */
    private data class Progress(val seq: Long, val state: ConnectionState)

    private val events = Channel<Envelope>(Channel.UNLIMITED)
    private val progress = MutableStateFlow(Progress(0L, ConnectionState.SignedOut))
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.SignedOut)
    private val _session = MutableStateFlow<SessionInfo?>(null)
    private val live = AtomicReference<IpcChannel?>(null)
    private val pendingLogin = AtomicReference<Credentials?>(null)
    private val lastError = AtomicReference<AppError?>(null)

    private val dirtyLibrary = io.github.nutea.anylisten.core.data.gateway.PendingLibraryChanges()
    fun takeLibraryChange() = dirtyLibrary.take()
    private val _libraryChanges = MutableStateFlow(0L)
    val libraryChanges = _libraryChanges.asStateFlow()
    private var libraryWatch: Job? = null

    private var model = ConnectionModel()
    private var sequence = 0L
    private var connectJob: Job? = null
    private var retryJob: Job? = null
    private var probeJob: Job? = null
    private var started = false

    val state: StateFlow<ConnectionState> = _state.asStateFlow()
    val session: StateFlow<SessionInfo?> = _session.asStateFlow()

    val isOnline: Boolean get() = live.get() != null && _state.value.isOnline

    fun start() {
        if (started) return
        started = true
        network.start()
        scope.launch { drain() }
        // Seeded before the collectors so the first connect request can never be reduced against
        // a model that does not know a stored session exists.
        post(Envelope(ConnectionEvent.CredentialsChanged(store.current() != null)))
        post(Envelope(ConnectionEvent.NetworkChanged(network.status.value)))
        scope.launch { network.status.collect { post(Envelope(ConnectionEvent.NetworkChanged(it))) } }
        scope.launch { store.changes.collect { post(Envelope(ConnectionEvent.CredentialsChanged(it != null))) } }
    }

    /** Ask for a session without waiting. Safe from any thread, any number of times. */
    fun requestConnect() {
        post(Envelope(ConnectionEvent.ConnectRequested()))
    }

    suspend fun signIn(baseUrl: String, password: String): SessionInfo {
        pendingLogin.set(Credentials(baseUrl, password))
        lastError.set(null)
        // The user is waiting, so report the first outcome rather than sitting through retries.
        awaitSettled(submit(ConnectionEvent.ConnectRequested(force = true)), SIGN_IN_WAIT_MS, ::settled)
        return _session.value?.takeIf { isOnline } ?: throw (lastError.get() ?: unreachable())
    }

    /**
     * Make sure a session exists, returning the live one when there already is a healthy socket.
     * Returns null when there is nothing stored to connect with.
     *
     * Transient failures are waited out inside [waitMs]: the manager keeps retrying, and a caller
     * that can afford to wait should get the session rather than an error it would only retry.
     */
    suspend fun ensureConnected(waitMs: Long = CONNECT_WAIT_MS): SessionInfo? {
        if (store.current() == null && pendingLogin.get() == null) return null
        live.get()?.let { return _session.value ?: it.session }
        val reached = awaitSettled(
            submit(ConnectionEvent.ConnectRequested()),
            waitMs,
        ) { it is ConnectionState.Online || terminal(it) }
        live.get()?.let { return _session.value ?: it.session }
        lastError.get()?.let { throw it }
        if (reached is ConnectionState.SignedOut || store.current() == null) return null
        throw unreachable()
    }

    suspend fun signOut() {
        pendingLogin.set(null)
        lastError.set(null)
        post(Envelope(ConnectionEvent.SignOut))
        withTimeoutOrNull(SIGN_OUT_WAIT_MS) { progress.first { it.state is ConnectionState.SignedOut } }
        lastError.set(null)
    }

    /**
     * Run an IPC call against the live socket, waiting for a reconnect in progress instead of
     * failing while one runs.
     *
     * This is what lets an uncached track re-resolve its stream URL across a Wi-Fi/cellular
     * handover: the resolve waits for the new session rather than throwing "socket not connected"
     * and killing the load.
     */
    suspend fun <T> withChannel(waitMs: Long = CALL_WAIT_MS, retryOnDisconnect: Boolean = true, block: suspend (Message2Call) -> T): T {
        var attempt = 0
        // One budget for the whole call, retry included, so a resolve on a Media3 loading thread
        // cannot block for a multiple of the wait window.
        val deadline = clock() + waitMs
        while (true) {
            val channel = awaitChannel((deadline - clock()).coerceAtLeast(0L))
            try {
                return block(channel.calls)
            } catch (call: IpcCallException) {
                throw call
            } catch (timeout: IpcTimeoutException) {
                report(channel.generation, ConnectionFault.STALE)
                if (!retryOnDisconnect || ++attempt >= MAX_CALL_ATTEMPTS) {
                    throw AppError(ErrorKind.NETWORK_UNREACHABLE, "Server did not answer", retryable = true)
                }
            } catch (closed: IpcClosedException) {
                report(channel.generation, ConnectionFault.TRANSPORT)
                if (!retryOnDisconnect || ++attempt >= MAX_CALL_ATTEMPTS) {
                    throw AppError(ErrorKind.NETWORK_UNREACHABLE, "Server connection lost", retryable = true)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            }
        }
    }

    /** Report that a socket looks dead. Ignored unless [generation] is the live one. */
    fun report(generation: Long, fault: ConnectionFault) {
        post(Envelope(ConnectionEvent.SocketClosed(generation, fault)))
    }

    /**
     * Barrier for tests: returns once every event queued before this call has been reduced.
     * Uses a credentials event, which the planner treats as a no-op when nothing changed.
     */
    internal suspend fun awaitProcessed(timeoutMs: Long = 5_000L) {
        withTimeoutOrNull(timeoutMs) {
            submit(ConnectionEvent.CredentialsChanged(store.current() != null))
        }
    }

    /** Wait for a socket, tolerating a reconnect that is still running. */
    private suspend fun awaitChannel(waitMs: Long): IpcChannel {
        live.get()?.let { return it }
        val from = submit(ConnectionEvent.ConnectRequested())
        if (waitMs > 0) {
            awaitSettled(from, waitMs) { it is ConnectionState.Online || terminal(it) }
        }
        return live.get() ?: throw (lastError.get() ?: unreachable())
    }

    /**
     * Queue [event] and return the sequence number it was reduced at, so a waiter can tell its
     * own outcome apart from one that happened to be published first.
     */
    private suspend fun submit(event: ConnectionEvent): Long {
        val reduced = CompletableDeferred<Long>()
        post(Envelope(event, reduced = reduced))
        return reduced.await()
    }

    private suspend fun awaitSettled(
        fromSeq: Long,
        waitMs: Long,
        predicate: (ConnectionState) -> Boolean,
    ): ConnectionState? = withTimeoutOrNull(waitMs) {
        progress.first { it.seq >= fromSeq && predicate(it.state) }
    }?.state

    private fun settled(state: ConnectionState): Boolean = when (state) {
        is ConnectionState.Online, ConnectionState.Offline,
        is ConnectionState.Backoff, is ConnectionState.Rejected, ConnectionState.SignedOut,
        -> true
        else -> false
    }

    private fun terminal(state: ConnectionState): Boolean =
        state is ConnectionState.Rejected || state is ConnectionState.SignedOut

    private fun post(envelope: Envelope) {
        events.trySend(envelope)
    }

    private suspend fun drain() {
        for (envelope in events) {
            val event = envelope.event
            applyTransportEffects(event)
            val step = ConnectionPlanner.reduce(model, event, clock(), jitter())
            model = step.model
            adopt(envelope, step)
            _state.value = model.state
            progress.value = Progress(++sequence, model.state)
            envelope.reduced?.complete(sequence)
            runCatching { perform(step.action) }
        }
    }

    /**
     * OkHttp bookkeeping for the link itself, done before the reduction so cleanup meant for the
     * interface we just left can never cancel the attempt that replaces it.
     */
    private fun applyTransportEffects(event: ConnectionEvent) {
        if (event !is ConnectionEvent.NetworkChanged) return
        val previous = model.network
        val next = event.network
        // Nothing was ever bound to a link we have not seen, so the first observation is free.
        if (!previous.known || !next.known) return
        when {
            previous.online && !next.online -> {
                transport.evictPooledConnections()
                transport.cancelApiCalls()
            }
            previous.online && next.online && previous.id != next.id -> {
                // The old interface is gone for good: fail the media read too, so ExoPlayer
                // retries from its buffer instead of waiting out the read timeout.
                transport.evictPooledConnections()
                transport.cancelApiCalls()
                transport.cancelMediaCalls()
            }
            !previous.online && next.online -> transport.evictPooledConnections()
        }
    }

    /** Take ownership of a freshly opened socket, and let go of one we no longer want. */
    private fun adopt(envelope: Envelope, step: ConnectionStep) {
        val arrived = envelope.channel
        val state = step.model.state
        if (arrived != null) {
            if (state is ConnectionState.Online && state.generation == arrived.generation) {
                live.set(arrived)
                _session.value = envelope.session ?: arrived.session
                lastError.set(null)
                watchClose(arrived)
                libraryWatch?.cancel()
                libraryWatch = scope.launch {
                    arrived.calls.libraryChanges.collect { revision ->
                        if (revision > 0 && live.get() === arrived) {
                            arrived.calls.takeLibraryChange()?.let { dirtyLibrary.add(it) }
                            _libraryChanges.update { it + 1 }
                        }
                    }
                }
                val ready = _session.value ?: arrived.session
                scope.launch { runCatching { onConnected(ready) } }
            } else {
                arrived.close(IpcChannel.NORMAL_CLOSE, "superseded")
            }
        }
        val held = live.get() ?: return
        if (state !is ConnectionState.Online || state.generation != held.generation) {
            live.set(null)
            libraryWatch?.cancel()
            held.close(IpcChannel.NORMAL_CLOSE, "replaced")
        }
    }

    private fun watchClose(channel: IpcChannel) {
        scope.launch {
            val fault = runCatching { channel.closed.await() }.getOrDefault(ConnectionFault.TRANSPORT)
            post(Envelope(ConnectionEvent.SocketClosed(channel.generation, fault)))
        }
    }

    private fun perform(action: ConnectionAction) {
        when (action) {
            ConnectionAction.None -> Unit
            is ConnectionAction.Connect -> startAttempt(action)
            is ConnectionAction.Probe -> startProbe(action.generation)
            is ConnectionAction.ScheduleRetry -> scheduleRetry(action.delayMs)
            is ConnectionAction.Drop -> finishSignOut(action)
        }
    }

    private fun startAttempt(action: ConnectionAction.Connect) {
        retryJob?.cancel()
        probeJob?.cancel()
        connectJob?.cancel()
        connectJob = scope.launch { attempt(action.generation) }
    }

    private suspend fun attempt(generation: Long) {
        var opened: IpcChannel? = null
        try {
            transport.evictPooledConnections()
            val authenticated = authenticate()
            opened = channels.open(authenticated, generation)
            val ready = handshake(opened, authenticated)
            pendingLogin.set(null)
            post(Envelope(ConnectionEvent.Established(generation), opened, ready))
            opened = null
        } catch (cancelled: CancellationException) {
            opened?.close(IpcChannel.NORMAL_CLOSE, "connect cancelled")
            throw cancelled
        } catch (error: Throwable) {
            opened?.close(IpcChannel.NORMAL_CLOSE, "connect failed")
            val app = error as? AppError ?: AppError.fromThrowable(error)
            lastError.set(app)
            val fault = ConnectionFault.of(app)
            if (fault == ConnectionFault.REJECTED) {
                pendingLogin.set(null)
                // Record the error before dropping credentials: clearing the store posts
                // CredentialsChanged, and signing out must not cancel us before lastError is set.
                runCatching { store.clear() }
            }
            post(Envelope(ConnectionEvent.AttemptFailed(generation, fault, app.message)))
        }
    }

    private suspend fun authenticate(): SessionInfo {
        pendingLogin.get()?.let { credentials ->
            val info = authenticator.signIn(credentials.baseUrl, credentials.password)
            store.save(info.profile, info.token, credentials.password)
            return info
        }
        val stored = store.current() ?: throw AppError(ErrorKind.SESSION_EXPIRED, "Not signed in")
        return try {
            authenticator.resume(stored.profile, stored.token)
                .also { store.save(it.profile, it.token, stored.password) }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            val app = error as? AppError ?: AppError.fromThrowable(error)
            if (app.kind != ErrorKind.AUTH_FAILED && app.kind != ErrorKind.SESSION_EXPIRED) throw app
            if (stored.password.isBlank()) throw AppError(ErrorKind.AUTH_FAILED, "Session expired")
            try {
                authenticator.signIn(stored.profile.baseUrl, stored.password)
                    .also { store.save(it.profile, it.token, stored.password) }
            } catch (retry: Throwable) {
                if (retry is CancellationException) throw retry
                throw retry as? AppError ?: AppError.fromThrowable(retry)
            }
        }
    }

    /** Push registration is required; optional version metadata must not block a healthy session. */
    private suspend fun handshake(channel: IpcChannel, session: SessionInfo): SessionInfo {
        withTimeoutOrNull(HANDSHAKE_MS) {
            channel.calls.call(listOf("inited"))
            true
        } ?: throw IpcTimeoutException("Push initialization timed out")
        val reported = ignoreTimeout { channel.calls.call(listOf("getCurrentVersionInfo")) }
        val version = ((reported as? JsonObject)?.get("version") as? JsonPrimitive)?.content
        runCatching { authenticator.primeStreamToken(session) }
        return if (version.isNullOrBlank()) session
        else session.copy(profile = session.profile.copy(reportedVersion = version))
    }

    private suspend fun <T> ignoreTimeout(block: suspend () -> T): T? = try {
        withTimeoutOrNull(HANDSHAKE_MS) { block() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }

    private fun startProbe(generation: Long) {
        val channel = live.get()?.takeIf { it.generation == generation } ?: return
        probeJob?.cancel()
        probeJob = scope.launch {
            try {
                val answered = withTimeoutOrNull(PROBE_MS) {
                    channel.calls.call(listOf("getCurrentVersionInfo"))
                }
                if (answered == null) {
                    post(Envelope(ConnectionEvent.SocketClosed(generation, ConnectionFault.STALE)))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                post(Envelope(ConnectionEvent.SocketClosed(generation, ConnectionFault.STALE)))
            }
        }
    }

    private fun scheduleRetry(delayMs: Long) {
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(delayMs)
            post(Envelope(ConnectionEvent.RetryElapsed))
        }
    }

    private fun finishSignOut(action: ConnectionAction.Drop) {
        if (!action.logout) return
        connectJob?.cancel()
        retryJob?.cancel()
        probeJob?.cancel()
        live.getAndSet(null)?.close(ProtocolConstants.CLOSE_LOGOUT, "logout")
        _session.value = null
        // The reason we are signing out — a rejected password, say — must survive for the caller
        // waiting on this outcome; it is cleared on the next successful connect or sign-in.
        runCatching { store.clear() }
    }

    private fun unreachable() =
        AppError(ErrorKind.NETWORK_UNREACHABLE, "Server connection unavailable", retryable = true)

    companion object {
        const val CALL_WAIT_MS = 15_000L
        const val CONNECT_WAIT_MS = 30_000L
        const val SIGN_IN_WAIT_MS = 45_000L
        const val SIGN_OUT_WAIT_MS = 5_000L
        const val PROBE_MS = 5_000L
        const val HANDSHAKE_MS = 10_000L
        const val MAX_CALL_ATTEMPTS = 2
    }
}
