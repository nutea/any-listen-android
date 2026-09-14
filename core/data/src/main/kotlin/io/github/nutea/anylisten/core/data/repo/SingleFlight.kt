package io.github.nutea.anylisten.core.data.repo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Collapses concurrent identical work into one call.
 *
 * Cancellation is the hard part and the old implementation got it wrong twice: it released the
 * slot with a suspending `withLock` inside `finally`, which itself throws once the owner is
 * cancelled, so the flight stayed occupied by an already-completed deferred *forever* — one
 * timed-out restore or media resolve permanently poisoned every later attempt. It also handed the
 * owner's `CancellationException` to joiners that were not cancelled at all.
 *
 * Now the slot is always released under [NonCancellable], and a cancelled owner hands the work
 * back to a waiting joiner instead of failing it.
 */
class SingleFlight<T> {
    private val mutex = Mutex()
    private var inFlight: CompletableDeferred<Result<T>>? = null

    suspend fun join(block: suspend () -> T): T {
        while (true) {
            val (deferred, owner) = mutex.withLock {
                val existing = inFlight
                if (existing != null) existing to false
                else CompletableDeferred<Result<T>>().also { inFlight = it } to true
            }
            if (!owner) {
                // A cancelled owner completes with OWNER_CANCELLED; retry as the new owner.
                val result = deferred.await()
                if (result.isOwnerCancelled()) continue
                return result.getOrThrow()
            }
            var outcome: Result<T>
            try {
                outcome = Result.success(block())
            } catch (cancelled: CancellationException) {
                release(deferred, Result.failure(OwnerCancelled(cancelled)))
                throw cancelled
            } catch (error: Throwable) {
                outcome = Result.failure(error)
            }
            release(deferred, outcome)
            return outcome.getOrThrow()
        }
    }

    private suspend fun release(deferred: CompletableDeferred<Result<T>>, outcome: Result<T>) {
        deferred.complete(outcome)
        withContext(NonCancellable) {
            mutex.withLock { if (inFlight === deferred) inFlight = null }
        }
    }

    private fun Result<T>.isOwnerCancelled(): Boolean = exceptionOrNull() is OwnerCancelled

    private class OwnerCancelled(cause: Throwable) : Exception(cause)
}
