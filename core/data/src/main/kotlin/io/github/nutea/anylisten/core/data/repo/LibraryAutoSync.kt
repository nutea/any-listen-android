package io.github.nutea.anylisten.core.data.repo

import io.github.nutea.anylisten.core.data.gateway.LibraryChange
import io.github.nutea.anylisten.core.data.gateway.PendingLibraryChanges
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Push-driven partial sync, with throttled reconciliation to recover missed notifications. */
class LibraryAutoSync(
    scope: CoroutineScope,
    private val online: () -> Boolean,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val refresh: suspend (Set<String>?) -> Unit,
) {
    private val pending = Channel<Unit>(Channel.CONFLATED)
    private val changes = PendingLibraryChanges()
    @Volatile private var lastFullSync: Long? = null
    @Volatile private var fullInFlight = false

    init {
        scope.launch {
            for (ignored in pending) {
                delay(250)
                pending.tryReceive()
                val change = changes.take() ?: continue
                fullInFlight = change.playlistIds == null
                var retryMs = 1_000L
                try {
                    while (online()) {
                        try {
                            refresh(change.playlistIds)
                            if (change.playlistIds == null) lastFullSync = clock()
                            break
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            delay(retryMs)
                            retryMs = (retryMs * 2).coerceAtMost(30_000L)
                        }
                    }
                } finally { fullInFlight = false }
            }
        }
        scope.launch {
            while (true) {
                val last = lastFullSync
                val waitMs = if (!online() || fullInFlight || last == null) RECONCILE_INTERVAL_MS
                    else (RECONCILE_INTERVAL_MS - (clock() - last)).coerceIn(250L, RECONCILE_INTERVAL_MS)
                delay(waitMs)
                reconcileIfDue(RECONCILE_INTERVAL_MS)
            }
        }
    }

    fun request(change: LibraryChange = LibraryChange()) {
        changes.add(change)
        pending.trySend(Unit)
    }

    fun onForeground() = reconcileIfDue(FOREGROUND_MIN_INTERVAL_MS)

    private fun reconcileIfDue(interval: Long) {
        val last = lastFullSync
        if (online() && !fullInFlight && (last == null || clock() - last >= interval)) request()
    }

    companion object {
        const val FOREGROUND_MIN_INTERVAL_MS = 60_000L
        const val RECONCILE_INTERVAL_MS = 5 * 60_000L
    }
}
