package io.github.nutea.anylisten.core.data.repo

import io.github.nutea.anylisten.core.data.local.ListeningStatDao
import io.github.nutea.anylisten.core.model.ListeningDelta
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.channels.Channel

class ListeningRepository(private val dao: ListeningStatDao, scope: CoroutineScope) {
    val stats = dao.observe().map { rows -> rows.map { it.toModel() } }
    private val pending = Channel<Pair<ListeningDelta, Long>>(Channel.UNLIMITED)
    init { scope.launch(Dispatchers.IO) {
        for ((delta, time) in pending) {
            try { dao.record(delta.trackKey, delta.listenedMs, delta.plays, time) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* A storage failure must never interrupt audio. */ }
        }
    } }
    fun record(delta: ListeningDelta?) { if (delta != null) pending.trySend(delta to System.currentTimeMillis()) }
}
