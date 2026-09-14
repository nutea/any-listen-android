package io.github.nutea.anylisten.core.data.gateway

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/**
 * Subset of message2call v2 frames used by Any Listen IPC.
 * REQUEST = [0, eventName, pathname, args, callbackIndexes]
 * RESPONSE = [1, eventName, error|null, result?]
 */
class Message2Call(
    private val json: Json,
    private val callTimeoutMs: Long = DEFAULT_CALL_TIMEOUT_MS,
    private val send: (String) -> Unit,
) {
    private val pending = ConcurrentHashMap<String, Continuation<JsonElement?>>()

    suspend fun call(pathname: List<String>, args: List<JsonElement> = emptyList()): JsonElement? =
        withTimeout(callTimeoutMs) {
            suspendCancellableCoroutine { cont ->
                val name = pathname.joinToString(".") + "_" + UUID.randomUUID().toString().replace("-", "").take(12)
                pending[name] = cont
                cont.invokeOnCancellation { pending.remove(name) }
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
                    pending.remove(name)
                    if (cont.isActive) cont.resumeWithException(error)
                }
            }
        }

    fun onMessage(raw: String) {
        if (raw == "ping") return
        val element = json.parseToJsonElement(raw)
        if (element !is JsonArray || element.isEmpty()) return
        val type = element[0].jsonPrimitive.content.toIntOrNull() ?: return
        if (type != 1 || element.size < 3) return
        val name = element[1].jsonPrimitive.content
        val cont = pending.remove(name) ?: return
        val err = element[2]
        if (err is JsonNull) {
            cont.resume(element.getOrNull(3))
        } else {
            val message = err.jsonObject["message"]?.jsonPrimitive?.contentOrNull ?: "IPC error"
            cont.resumeWithException(IllegalStateException(message))
        }
    }

    fun destroy(message: String = "disconnected") {
        val error = IllegalStateException(message)
        pending.values.forEach { it.resumeWithException(error) }
        pending.clear()
    }

    companion object {
        const val DEFAULT_CALL_TIMEOUT_MS = 20_000L

        fun obj(vararg pairs: Pair<String, JsonElement?>): JsonObject = JsonObject(
            pairs.mapNotNull { (k, v) -> v?.let { k to it } }.toMap(),
        )
    }
}
