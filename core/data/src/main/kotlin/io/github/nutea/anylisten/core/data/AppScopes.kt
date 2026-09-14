package io.github.nutea.anylisten.core.data

import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext

/**
 * Long-lived scopes with an explicit failure policy.
 *
 * A `SupervisorJob` only stops siblings from being cancelled; an exception escaping `launch` with
 * no [CoroutineExceptionHandler] still reaches the thread's default handler, which on Android
 * kills the process. Background maintenance — persisting playback state, caching artwork,
 * adopting a finished stream — must never be able to do that to a music player, so every
 * long-lived scope in the app is built here with a handler attached.
 */
object AppScopes {
    private const val TAG = "AnyListen"

    fun handler(name: String): CoroutineExceptionHandler = CoroutineExceptionHandler { _, error ->
        Log.e(TAG, "Unhandled failure in $name", error)
    }

    fun create(name: String, context: CoroutineContext, job: Job = SupervisorJob()): CoroutineScope =
        CoroutineScope(job + context + handler(name))
}
