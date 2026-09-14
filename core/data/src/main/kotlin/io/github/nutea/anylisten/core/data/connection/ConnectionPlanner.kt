package io.github.nutea.anylisten.core.data.connection

import kotlin.math.min
import kotlin.math.pow

/**
 * Exponential backoff with jitter, the retry shape ExoPlayer's `DefaultLoadErrorHandlingPolicy`
 * uses: retry quickly right after a handover, then back off hard so a server that is really down
 * is not hammered and the radio is not kept awake.
 */
object ConnectionBackoff {
    const val BASE_MS = 1_000L
    const val MAX_MS = 60_000L

    /** [jitter] in `[0, 1)`; callers pass a random value, tests pass a fixed one. */
    fun delayMs(attempt: Int, jitter: Double = 0.0): Long {
        val exponent = (attempt - 1).coerceIn(0, 16)
        val ceiling = min(MAX_MS.toDouble(), BASE_MS * 2.0.pow(exponent))
        val floor = ceiling / 2
        return (floor + (ceiling - floor) * jitter.coerceIn(0.0, 0.999)).toLong()
    }
}

/**
 * The whole reconnect policy, as one pure reduction.
 *
 * Every rule the app used to spread across `PlaybackService`, `AppViewModel`,
 * `PlaybackReconnect` and `ProtocolAnyListenGateway` lives here, so the behaviour can be tested
 * exhaustively instead of being reasoned about across three threads:
 *
 *  * a healthy session is never rebuilt because `ConnectivityManager` spoke (0.1.1-beta.4);
 *  * a session whose link actually changed identity is rebuilt (0.1.1-beta.1);
 *  * a session whose link flapped and came back on the same network is *probed*, not rebuilt, so
 *    an in-flight stream is not dropped while the socket is still good;
 *  * anything reported by a socket generation we already replaced is ignored, which is what used
 *    to let a dying socket tear down its healthy successor.
 *
 * The owner reconciles ownership of the live socket after each step: if the resulting state is
 * not [ConnectionState.Online] for the socket it holds, that socket is closed. Actions therefore
 * only describe work the reduction cannot express by itself.
 */
object ConnectionPlanner {
    fun reduce(
        model: ConnectionModel,
        event: ConnectionEvent,
        nowElapsedMs: Long,
        jitter: Double = 0.0,
    ): ConnectionStep = when (event) {
        is ConnectionEvent.CredentialsChanged -> credentialsChanged(model, event.present)
        is ConnectionEvent.ConnectRequested -> connectRequested(model, event.force)
        is ConnectionEvent.NetworkChanged -> networkChanged(model, event.network)
        is ConnectionEvent.Established -> established(model, event.generation)
        is ConnectionEvent.AttemptFailed ->
            failed(model, event.generation, event.fault, event.message, nowElapsedMs, jitter)
        is ConnectionEvent.SocketClosed -> socketClosed(model, event.generation, event.fault, nowElapsedMs, jitter)
        ConnectionEvent.RetryElapsed -> retryElapsed(model)
        ConnectionEvent.SignOut -> signOut(model)
    }

    private fun credentialsChanged(model: ConnectionModel, present: Boolean): ConnectionStep {
        if (model.hasCredentials == present) return ConnectionStep(model, ConnectionAction.None)
        if (!present) return signOut(model)
        val state = if (model.state is ConnectionState.SignedOut) ConnectionState.Idle else model.state
        return ConnectionStep(model.copy(hasCredentials = true, state = state), ConnectionAction.None)
    }

    private fun connectRequested(model: ConnectionModel, force: Boolean): ConnectionStep {
        if (!model.hasCredentials && !force) {
            return ConnectionStep(model.copy(state = ConnectionState.SignedOut), ConnectionAction.None)
        }
        if (!force) {
            when (model.state) {
                // A live or half-built session already satisfies the request. Rebuilding here is
                // the "restore while healthy" race that used to kill the process after login.
                is ConnectionState.Online, is ConnectionState.Connecting ->
                    return ConnectionStep(model, ConnectionAction.None)
                // A retry is already pending; let it run instead of resetting the backoff.
                is ConnectionState.Backoff -> return ConnectionStep(model, ConnectionAction.None)
                else -> Unit
            }
            if (model.network.known && !model.network.online) {
                return ConnectionStep(model.copy(state = ConnectionState.Offline), ConnectionAction.None)
            }
        }
        // A forced sign-in connects with credentials the caller holds, not stored ones. Claiming
        // them here would make the store's own "no credentials" event look like a sign-out and
        // cancel the very attempt it belongs to.
        return connect(model, attempt = 1)
    }

    private fun networkChanged(model: ConnectionModel, network: NetworkSnapshot): ConnectionStep {
        val moved = model.copy(network = network)
        // "We have not looked yet" is not "there is no network": treating the monitor's initial
        // value as a link loss would abandon the very first connection attempt.
        if (!network.known) return ConnectionStep(moved, ConnectionAction.None)
        if (!network.online) return linkLost(moved)
        return when (val state = moved.state) {
            is ConnectionState.Online -> when {
                // Session opened before any network callback arrived: adopt the identity instead
                // of treating the first callback as a handover.
                state.networkId == NetworkSnapshot.UNKNOWN -> ConnectionStep(
                    moved.copy(state = state.copy(networkId = network.id, linkDown = false)),
                    ConnectionAction.None,
                )
                state.networkId != network.id -> connect(moved, attempt = 1)
                state.linkDown -> ConnectionStep(
                    moved.copy(state = state.copy(linkDown = false)),
                    ConnectionAction.Probe(state.generation),
                )
                else -> ConnectionStep(moved, ConnectionAction.None)
            }
            is ConnectionState.Connecting -> when {
                // Same as a live session: the first callback is an identity, not a handover.
                state.networkId == NetworkSnapshot.UNKNOWN -> ConnectionStep(
                    moved.copy(state = state.copy(networkId = network.id)),
                    ConnectionAction.None,
                )
                state.networkId == network.id -> ConnectionStep(moved, ConnectionAction.None)
                else -> connect(moved, attempt = 1)
            }
            is ConnectionState.Offline, is ConnectionState.Backoff ->
                if (moved.hasCredentials) connect(moved, attempt = 1) else ConnectionStep(moved, ConnectionAction.None)
            is ConnectionState.Idle, is ConnectionState.Rejected, ConnectionState.SignedOut ->
                ConnectionStep(moved, ConnectionAction.None)
        }
    }

    /**
     * The link went away. A live socket is kept: the same network may come straight back, and
     * OkHttp's ping interval fails the socket quickly when it does not. Tearing it down here is
     * what used to cancel an in-flight restore and drop a playing stream.
     */
    private fun linkLost(model: ConnectionModel): ConnectionStep = when (val state = model.state) {
        is ConnectionState.Online ->
            ConnectionStep(model.copy(state = state.copy(linkDown = true)), ConnectionAction.None)
        is ConnectionState.Connecting ->
            ConnectionStep(model.copy(state = ConnectionState.Offline), ConnectionAction.None)
        is ConnectionState.Backoff ->
            ConnectionStep(model.copy(state = ConnectionState.Offline), ConnectionAction.None)
        else -> ConnectionStep(model, ConnectionAction.None)
    }

    private fun established(model: ConnectionModel, generation: Long): ConnectionStep {
        // A late success from a generation we already replaced must not resurrect itself.
        if (generation != model.lastGeneration || model.state !is ConnectionState.Connecting) {
            return ConnectionStep(model, ConnectionAction.Drop(generation))
        }
        val online = ConnectionState.Online(
            generation = generation,
            networkId = model.network.id,
            linkDown = model.network.known && !model.network.online,
        )
        return ConnectionStep(model.copy(state = online), ConnectionAction.None)
    }

    private fun failed(
        model: ConnectionModel,
        generation: Long,
        fault: ConnectionFault,
        message: String,
        nowElapsedMs: Long,
        jitter: Double,
    ): ConnectionStep {
        val state = model.state
        if (generation != model.lastGeneration || state !is ConnectionState.Connecting) {
            return ConnectionStep(model, ConnectionAction.None)
        }
        if (fault == ConnectionFault.REJECTED) {
            return ConnectionStep(model.copy(state = ConnectionState.Rejected(message)), ConnectionAction.None)
        }
        return scheduleRetry(model, state.attempt + 1, fault, nowElapsedMs, jitter)
    }

    private fun socketClosed(
        model: ConnectionModel,
        generation: Long,
        fault: ConnectionFault,
        nowElapsedMs: Long,
        jitter: Double,
    ): ConnectionStep {
        val state = model.state
        if (state !is ConnectionState.Online || state.generation != generation) {
            // Stale generation: this socket was already replaced or dropped.
            return ConnectionStep(model, ConnectionAction.None)
        }
        if (fault == ConnectionFault.REJECTED) {
            return ConnectionStep(model.copy(state = ConnectionState.Rejected("")), ConnectionAction.None)
        }
        return scheduleRetry(model, attempt = 1, fault = fault, nowElapsedMs = nowElapsedMs, jitter = jitter)
    }

    private fun scheduleRetry(
        model: ConnectionModel,
        attempt: Int,
        fault: ConnectionFault,
        nowElapsedMs: Long,
        jitter: Double,
    ): ConnectionStep {
        if (model.network.known && !model.network.online) {
            return ConnectionStep(model.copy(state = ConnectionState.Offline), ConnectionAction.None)
        }
        val delay = ConnectionBackoff.delayMs(attempt, jitter)
        return ConnectionStep(
            model.copy(state = ConnectionState.Backoff(attempt, nowElapsedMs + delay, fault)),
            ConnectionAction.ScheduleRetry(delay),
        )
    }

    private fun retryElapsed(model: ConnectionModel): ConnectionStep {
        val state = model.state
        if (state !is ConnectionState.Backoff || !model.hasCredentials) {
            return ConnectionStep(model, ConnectionAction.None)
        }
        if (model.network.known && !model.network.online) {
            return ConnectionStep(model.copy(state = ConnectionState.Offline), ConnectionAction.None)
        }
        return connect(model, attempt = state.attempt)
    }

    private fun signOut(model: ConnectionModel): ConnectionStep = ConnectionStep(
        model.copy(state = ConnectionState.SignedOut, hasCredentials = false),
        ConnectionAction.Drop(model.activeGeneration, logout = true),
    )

    private fun connect(model: ConnectionModel, attempt: Int): ConnectionStep {
        val generation = model.lastGeneration + 1
        return ConnectionStep(
            model.copy(
                state = ConnectionState.Connecting(attempt, model.network.id),
                lastGeneration = generation,
            ),
            ConnectionAction.Connect(
                generation = generation,
                previousGeneration = model.activeGeneration,
                networkId = model.network.id,
            ),
        )
    }
}
