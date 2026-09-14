package io.github.nutea.anylisten.core.data

import io.github.nutea.anylisten.core.data.connection.TransportReset
import okhttp3.ConnectionPool
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * The process's OkHttp clients.
 *
 * [api] and [media] share a cookie jar and a connection pool — the same TLS sessions and the same
 * auth cookies — but deliberately **not** a dispatcher. Cancelling API calls after a handover used
 * to run through the shared dispatcher and abort the audio stream as collateral damage, and
 * cancelling for a link that had already been replaced could abort the fresh restore too. Owning
 * one dispatcher per purpose makes "cancel everything bound to the old link" expressible without
 * that blast radius.
 */
class HttpClients private constructor(
    val api: OkHttpClient,
    val media: OkHttpClient,
) : TransportReset {

    override fun evictPooledConnections() {
        runCatching { api.connectionPool.evictAll() }
    }

    override fun cancelApiCalls() {
        runCatching { api.dispatcher.cancelAll() }
    }

    override fun cancelMediaCalls() {
        runCatching { media.dispatcher.cancelAll() }
    }

    companion object {
        const val WEBSOCKET_PING_INTERVAL_MS = 20_000L
        const val API_CALL_TIMEOUT_S = 45L

        /**
         * A stalled media read must surface long before the API deadline: ExoPlayer can only
         * retry once the read fails, and a handover that silently blackholes the old socket
         * otherwise leaves the user staring at a spinner.
         */
        const val MEDIA_READ_TIMEOUT_S = 20L

        fun create(): HttpClients {
            val pool = ConnectionPool()
            val jar = MemoryCookieJar()
            val api = OkHttpClient.Builder()
                .cookieJar(jar)
                .connectionPool(pool)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .pingInterval(Duration.ofMillis(WEBSOCKET_PING_INTERVAL_MS))
                .callTimeout(Duration.ofSeconds(API_CALL_TIMEOUT_S))
                .connectTimeout(Duration.ofSeconds(15))
                .readTimeout(Duration.ofSeconds(API_CALL_TIMEOUT_S))
                .build()
            val media = api.newBuilder()
                .dispatcher(Dispatcher())
                // A streaming response stays open for the length of the song, so there is no
                // whole-call deadline; the read timeout still catches a dead link.
                .callTimeout(Duration.ZERO)
                .readTimeout(Duration.ofSeconds(MEDIA_READ_TIMEOUT_S))
                .pingInterval(Duration.ZERO)
                .build()
            return HttpClients(api, media)
        }
    }
}

class MemoryCookieJar : CookieJar {
    private val store = ConcurrentHashMap<String, List<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        store.compute(url.host) { _, prior ->
            val replacements = cookies.map { Triple(it.name, it.domain, it.path) }.toSet()
            (prior.orEmpty().filterNot { Triple(it.name, it.domain, it.path) in replacements } + cookies)
                .filter { it.expiresAt > System.currentTimeMillis() }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = store[url.host].orEmpty()
        .filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }
}
