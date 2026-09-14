package io.github.nutea.anylisten.core.data.gateway

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** The socket carrying this call went away. Retryable once a new session exists. */
class IpcClosedException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The call deadline elapsed; the caller coroutine is left running so it can retry. */
class IpcTimeoutException(message: String) : IOException(message)

/** The server answered the call with an error. Not retryable by reconnecting. */
class IpcCallException(message: String) : IOException(message)

/**
 * Subset of message2call v2 frames used by Any Listen IPC.
 * REQUEST = [0, eventName, pathname, args, callbackIndexes]
 * RESPONSE = [1, eventName, error|null, result?]
 *
 * Each pending call owns a [CompletableDeferred]. Completing one is atomic and idempotent, so a
 * response arriving on the OkHttp reader thread at the same moment the socket is torn down can
 * no longer double-resume a continuation — the failure mode that used to kill the process with
 * `IllegalStateException: Already resumed` thrown from a WebSocket callback.
 *
 * A [Message2Call] belongs to exactly one socket generation and is single use: once [failAll]
 * runs, every later call fails immediately instead of waiting for a socket that is gone.
 */
class Message2Call(
    private val json: Json,
    private val callTimeoutMs: Long = DEFAULT_CALL_TIMEOUT_MS,
    private val send: (String) -> Unit,
) {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonElement?>>()
    private val closed = AtomicReference<IpcClosedException?>(null)

    private val dirtyLibrary = PendingLibraryChanges()
    fun takeLibraryChange() = dirtyLibrary.take()
    private val _libraryChanges = MutableStateFlow(0L)
    val libraryChanges = _libraryChanges.asStateFlow()

    val isClosed: Boolean get() = closed.get() != null

    suspend fun call(pathname: List<String>, args: List<JsonElement> = emptyList()): JsonElement? {
        closed.get()?.let { throw IpcClosedException(it.message.orEmpty(), it) }
        val name = pathname.joinToString(".") + "_" + UUID.randomUUID().toString().replace("-", "").take(12)
        val slot = CompletableDeferred<JsonElement?>()
        pending[name] = slot
        try {
            closed.get()?.let { slot.completeExceptionally(IpcClosedException(it.message.orEmpty(), it)) }
            if (slot.isActive) {
                val frame = buildJsonArray {
                    add(JsonPrimitive(0))
                    add(JsonPrimitive(name))
                    add(buildJsonArray { pathname.forEach { add(JsonPrimitive(it)) } })
                    add(buildJsonArray { args.forEach { add(it) } })
                    add(buildJsonArray { })
                }
                try {
                    send(frame.toString())
                } catch (error: Throwable) {
                    slot.completeExceptionally(error)
                }
            }
            return (withTimeoutOrNull(callTimeoutMs) { Result.success(slot.await()) }
                ?: throw IpcTimeoutException("IPC call timed out")).getOrThrow()
        } finally {
            pending.remove(name)
        }
    }

    /**
     * Feed one inbound frame. Never throws: this runs on the OkHttp reader thread, where an
     * escaping exception is rethrown by the dispatcher and terminates the process.
     */
    fun dispatch(raw: String) {
        try {
            if (raw == PING || isClosed) return
            val element = json.parseToJsonElement(raw)
            if (element !is JsonArray || element.isEmpty()) return
            if (element[0].jsonPrimitive.content.toIntOrNull() == 0) {
                dispatchRequest(element)
                return
            }
            if (element[0].jsonPrimitive.content.toIntOrNull() != RESPONSE || element.size < 3) return
            val slot = pending.remove(element[1].jsonPrimitive.content) ?: return
            val error = element[2]
            if (error is JsonNull) {
                slot.complete(element.getOrNull(3))
            } else {
                val message = error.jsonObject["message"]?.jsonPrimitive?.contentOrNull ?: "IPC error"
                slot.completeExceptionally(IpcCallException(message))
            }
        } catch (_: Throwable) {
            // A malformed frame must not take the reader thread, and therefore the app, down.
        }
    }

    private fun dispatchRequest(frame: JsonArray) {
        if (frame.size < 4) return
        val name = frame[1] as? JsonPrimitive ?: return
        val path = frame[2] as? JsonArray ?: return
        val args = frame[3] as? JsonArray ?: return
        val action = (args.firstOrNull() as? JsonObject)?.get("action") as? JsonPrimitive
        val supported = path == JsonArray(listOf(JsonPrimitive("listAction"))) &&
            action?.content?.startsWith("list_") == true
        if (supported) {
            dirtyLibrary.add(LibraryChange.from(args.first() as JsonObject))
            _libraryChanges.update { it + 1 }
        }
        // Server remoteQueueList waits for this acknowledgement before its next push.
        val error = if (supported) JsonNull else obj("message" to JsonPrimitive("Unsupported client call"))
        send(JsonArray(listOf(JsonPrimitive(RESPONSE), name, error, JsonNull)).toString())
    }

    /**
     * Fail every waiting call and refuse new ones. Idempotent and safe from any thread.
     *
     * The map is drained through its own iterator rather than snapshotted: a snapshot of a
     * concurrently shrinking [ConcurrentHashMap] can throw, and this runs on the OkHttp reader
     * thread where a throw is fatal. [call] re-checks [closed] after registering, so a call that
     * arrives mid-drain still fails instead of waiting for a socket that is gone.
     */
    fun failAll(message: String = "disconnected") {
        closed.compareAndSet(null, IpcClosedException(message))
        val reported = closed.get()?.message.orEmpty()
        val entries = pending.entries.iterator()
        while (entries.hasNext()) {
            val slot = entries.next().value
            entries.remove()
            slot.completeExceptionally(IpcClosedException(reported))
        }
    }

    companion object {
        const val DEFAULT_CALL_TIMEOUT_MS = 20_000L
        private const val PING = "ping"
        private const val RESPONSE = 1

        fun obj(vararg pairs: Pair<String, JsonElement?>): JsonObject = JsonObject(
            pairs.mapNotNull { (k, v) -> v?.let { k to it } }.toMap(),
        )
    }
}
