package io.github.nutea.anylisten.core.data.repo

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SingleFlight<T> {
    private val mutex = Mutex()
    private var inFlight: CompletableDeferred<T>? = null

    suspend fun join(block: suspend () -> T): T {
        val (deferred, owner) = mutex.withLock {
            val existing = inFlight
            if (existing != null) {
                existing to false
            } else {
                CompletableDeferred<T>().also { inFlight = it } to true
            }
        }
        if (!owner) return deferred.await()
        return try {
            val value = block()
            deferred.complete(value)
            value
        } catch (error: Throwable) {
            deferred.completeExceptionally(error)
            throw error
        } finally {
            mutex.withLock { if (inFlight === deferred) inFlight = null }
        }
    }
}
