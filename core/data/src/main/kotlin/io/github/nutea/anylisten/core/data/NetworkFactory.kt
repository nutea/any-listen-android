package io.github.nutea.anylisten.core.data

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

object NetworkFactory {
    fun client(): OkHttpClient {
        val jar = MemoryCookieJar()
        return OkHttpClient.Builder()
            .cookieJar(jar)
            .followRedirects(true)
            .followSslRedirects(true)
            .callTimeout(Duration.ofSeconds(45))
            .connectTimeout(Duration.ofSeconds(15))
            .readTimeout(Duration.ofSeconds(45))
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                val response = chain.proceed(request)
                val priorHost = request.url.host
                val next = response.request.url.host
                if (!priorHost.equals(next, ignoreCase = true) && request.header("Cookie") != null) {
                    // CookieJar already scopes cookies by host; do not copy auth cookies across hosts.
                }
                response
            }
            .build()
    }
}

class MemoryCookieJar : CookieJar {
    private val store = ConcurrentHashMap<String, List<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        store.compute(url.host) { _, prior ->
            val replacements = cookies.map { Triple(it.name,it.domain,it.path) }.toSet()
            (prior.orEmpty().filterNot { Triple(it.name,it.domain,it.path) in replacements } + cookies)
                .filter { it.expiresAt > System.currentTimeMillis() }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = store[url.host].orEmpty()
        .filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }
}
