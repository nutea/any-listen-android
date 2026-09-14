package io.github.nutea.anylisten.core.data.connection

import io.github.nutea.anylisten.core.data.gateway.IpcClosedException
import io.github.nutea.anylisten.core.data.gateway.Message2Call
import io.github.nutea.anylisten.core.data.gateway.ProtocolDtos
import io.github.nutea.anylisten.core.data.gateway.SessionInfo
import io.github.nutea.anylisten.core.data.gateway.UrlNormalizer
import io.github.nutea.anylisten.core.model.AppError
import io.github.nutea.anylisten.core.model.ErrorKind
import io.github.nutea.anylisten.core.model.ProtocolConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One live IPC socket. Immutable once opened; replaced, never mutated, on reconnect. */
interface IpcChannel {
    val generation: Long
    val session: SessionInfo
    val calls: Message2Call

    /** Completes exactly once, with the reason the socket ended. */
    val closed: CompletableDeferred<ConnectionFault>

    fun close(code: Int = NORMAL_CLOSE, reason: String = "replaced")

    companion object {
        const val NORMAL_CLOSE = 1000
    }
}

/** Opens [IpcChannel]s. Separated from the state machine so tests can supply a fake transport. */
fun interface IpcChannelFactory {
    suspend fun open(session: SessionInfo, generation: Long): IpcChannel
}

/**
 * WebSocket-backed [IpcChannel].
 *
 * Every listener callback is total: it never throws, and the only state it touches are
 * [CompletableDeferred]s, whose completion is atomic and idempotent. OkHttp rethrows anything
 * escaping a `WebSocketListener` on its dispatcher thread, where it becomes an uncaught exception
 * and kills the process, so "a callback cannot fail" is an invariant here rather than a hope.
 */
class WebSocketIpcChannel internal constructor(
    override val generation: Long,
    override val session: SessionInfo,
    override val calls: Message2Call,
    override val closed: CompletableDeferred<ConnectionFault>,
) : IpcChannel {
    private val socket = AtomicReference<WebSocket?>(null)
    private val closing = AtomicBoolean(false)

    internal fun attach(webSocket: WebSocket) {
        socket.set(webSocket)
        if (closing.get()) detach(IpcChannel.NORMAL_CLOSE, "closed")
    }

    internal fun send(text: String) {
        val live = socket.get() ?: throw IpcClosedException("WebSocket not connected")
        if (!live.send(text)) throw IpcClosedException("WebSocket send failed")
    }

    override fun close(code: Int, reason: String) {
        if (!closing.compareAndSet(false, true)) return
        calls.failAll(reason)
        closed.complete(ConnectionFault.TRANSPORT)
        detach(code, reason)
    }

    private fun detach(code: Int, reason: String) {
        val live = socket.getAndSet(null) ?: return
        runCatching { live.close(code, reason) }
        runCatching { live.cancel() }
    }

    class Factory(
        private val http: OkHttpClient,
        private val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
        private val callTimeoutMs: Long = Message2Call.DEFAULT_CALL_TIMEOUT_MS,
    ) : IpcChannelFactory {
        override suspend fun open(session: SessionInfo, generation: Long): IpcChannel {
            val token = URLEncoder.encode(session.token, Charsets.UTF_8.name())
            val url = UrlNormalizer.wsUrl(
                session.profile.baseUrl,
                "${ProtocolConstants.SOCKET_PATH}?m=$token&t=${ProtocolConstants.WIN_TYPE_MAIN}",
            )
            val opened = CompletableDeferred<Unit>()
            val closed = CompletableDeferred<ConnectionFault>()
            val holder = AtomicReference<WebSocketIpcChannel?>(null)
            val calls = Message2Call(ProtocolDtos.json, callTimeoutMs) { text ->
                holder.get()?.send(text) ?: throw IpcClosedException("WebSocket not connected")
            }
            val channel = WebSocketIpcChannel(generation, session, calls, closed)
            holder.set(channel)

            fun finish(fault: ConnectionFault, error: Throwable) {
                calls.failAll(error.message ?: "socket closed")
                closed.complete(fault)
                opened.completeExceptionally(error)
            }

            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened.complete(Unit)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    calls.dispatch(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    runCatching { webSocket.close(IpcChannel.NORMAL_CLOSE, null) }
                    finish(ConnectionFault.TRANSPORT, IpcClosedException("Socket closing ($code)"))
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    finish(ConnectionFault.TRANSPORT, IpcClosedException("Socket closed ($code)"))
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val rejected = response?.code == 401 || response?.code == 403
                    finish(
                        if (rejected) ConnectionFault.REJECTED else ConnectionFault.TRANSPORT,
                        if (rejected) AppError(ErrorKind.SESSION_EXPIRED, "WebSocket unauthorized") else t,
                    )
                }
            }

            channel.attach(http.newWebSocket(Request.Builder().url(url).build(), listener))
            try {
                withTimeout(connectTimeoutMs) { opened.await() }
            } catch (cancelled: CancellationException) {
                channel.close(IpcChannel.NORMAL_CLOSE, "connect cancelled")
                throw cancelled
            } catch (error: Throwable) {
                channel.close(IpcChannel.NORMAL_CLOSE, "connect failed")
                throw error
            }
            return channel
        }
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 20_000L
    }
}
