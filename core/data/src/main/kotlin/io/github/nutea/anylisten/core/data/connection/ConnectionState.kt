package io.github.nutea.anylisten.core.data.connection

import io.github.nutea.anylisten.core.model.AppError
import io.github.nutea.anylisten.core.model.ErrorKind

/** Why a connection attempt or a live socket ended. */
enum class ConnectionFault {
    /** The server refused the credentials or the session token. Retrying cannot help. */
    REJECTED,

    /** The socket, the TLS handshake or the auth request failed. Retrying can help. */
    TRANSPORT,

    /** No usable default network while we wanted one. */
    NO_NETWORK,

    /** A live socket stopped answering after the link came back. */
    STALE,
    ;

    companion object {
        fun of(error: Throwable): ConnectionFault {
            val kind = (error as? AppError)?.kind ?: AppError.fromThrowable(error).kind
            return if (kind == ErrorKind.AUTH_FAILED || kind == ErrorKind.SESSION_EXPIRED) REJECTED else TRANSPORT
        }
    }
}

/**
 * Observable state of the server session.
 *
 * Exactly one of these is true at a time and only [ConnectionPlanner] may produce them, so the
 * "connected but also reconnecting" combinations the old code could reach are unrepresentable.
 */
sealed interface ConnectionState {
    /** No stored credentials. Nothing to connect to. */
    data object SignedOut : ConnectionState

    /** Credentials exist; nobody has asked for a connection yet. */
    data object Idle : ConnectionState

    /** We want a session but there is no usable default network. */
    data object Offline : ConnectionState

    data class Connecting(val attempt: Int, val networkId: Long) : ConnectionState

    /**
     * A live IPC session. [generation] identifies the socket that owns it: every callback and
     * every RPC failure carries its generation, and anything from an older one is ignored.
     *
     * [linkDown] means the default network went away while this socket was still open. The socket
     * is probed, not torn down, when the link returns on the same network.
     */
    data class Online(
        val generation: Long,
        val networkId: Long,
        val linkDown: Boolean = false,
    ) : ConnectionState

    /** A retry is scheduled. */
    data class Backoff(
        val attempt: Int,
        val retryAtElapsedMs: Long,
        val fault: ConnectionFault,
    ) : ConnectionState

    /** The server rejected us. Only new credentials or an explicit retry can move us on. */
    data class Rejected(val message: String) : ConnectionState

    val isOnline: Boolean get() = this is Online
}

/** Events the connection state machine reacts to. All of them are facts, never intentions. */
sealed interface ConnectionEvent {
    /** Stored credentials appeared or disappeared. */
    data class CredentialsChanged(val present: Boolean) : ConnectionEvent

    /**
     * Somebody needs a session. [force] is set for an explicit sign-in, which must replace a live
     * session; an ordinary request never disturbs a healthy one.
     */
    data class ConnectRequested(val force: Boolean = false) : ConnectionEvent

    data class NetworkChanged(val network: NetworkSnapshot) : ConnectionEvent

    data class Established(val generation: Long) : ConnectionEvent

    data class AttemptFailed(val generation: Long, val fault: ConnectionFault, val message: String = "") :
        ConnectionEvent

    /** A socket that had been established went away. */
    data class SocketClosed(val generation: Long, val fault: ConnectionFault) : ConnectionEvent

    /** The scheduled backoff delay elapsed. */
    data object RetryElapsed : ConnectionEvent

    data object SignOut : ConnectionEvent
}

/** Side effect the owner must perform after a reduction. Pure data so reductions stay testable. */
sealed interface ConnectionAction {
    data object None : ConnectionAction

    /** Drop [previousGeneration] if any, then authenticate and open socket [generation]. */
    data class Connect(
        val generation: Long,
        val previousGeneration: Long,
        val networkId: Long,
    ) : ConnectionAction

    /** Ask the live socket a cheap question; report [ConnectionEvent.SocketClosed] if it fails. */
    data class Probe(val generation: Long) : ConnectionAction

    /** Emit [ConnectionEvent.RetryElapsed] after [delayMs]. */
    data class ScheduleRetry(val delayMs: Long) : ConnectionAction

    /** Close and forget [generation]. */
    data class Drop(val generation: Long, val logout: Boolean = false) : ConnectionAction
}

/** Full machine state: the public [state] plus the inputs reductions depend on. */
data class ConnectionModel(
    val state: ConnectionState = ConnectionState.SignedOut,
    val network: NetworkSnapshot = NetworkSnapshot.Unknown,
    val hasCredentials: Boolean = false,
    val lastGeneration: Long = 0L,
) {
    /** Generation of the socket currently being built or in use, or 0. */
    val activeGeneration: Long
        get() = when (val current = state) {
            is ConnectionState.Online -> current.generation
            is ConnectionState.Connecting -> lastGeneration
            else -> 0L
        }
}

data class ConnectionStep(val model: ConnectionModel, val action: ConnectionAction)
