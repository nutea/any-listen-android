package io.github.nutea.anylisten.core.data.gateway

import io.github.nutea.anylisten.core.model.AppError
import io.github.nutea.anylisten.core.model.ErrorKind
import io.github.nutea.anylisten.core.model.ProtocolConstants
import java.net.InetAddress
import java.net.URI

object UrlNormalizer {
    fun httpsBase(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        val uri = runCatching { URI(trimmed) }.getOrElse {
            throw AppError(ErrorKind.UNKNOWN, "Invalid server URL")
        }
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            throw AppError(ErrorKind.CERT_INVALID, "Only HTTPS server URLs are allowed")
        }
        return trimmed
    }

    fun resolve(base: String, pathOrUrl: String): String {
        val raw = pathOrUrl.trim()
        if (raw.startsWith(ProtocolConstants.VIRTUAL_PROTOCOL, ignoreCase = true)) {
            val path = raw.substring(ProtocolConstants.VIRTUAL_PROTOCOL.length)
            if (!isAllowedProxyPath(path)) {
                throw AppError(ErrorKind.TRACK_UNAVAILABLE, "Unsupported media path")
            }
            return httpsBase(base) + path
        }
        if (raw.startsWith("https://", ignoreCase = true) || raw.startsWith("http://", ignoreCase = true)) {
            return raw
        }
        val path = if (raw.startsWith("/")) raw else "/$raw"
        return httpsBase(base) + path
    }

    /** Keep the provider scheme. HTTP covers are fetched only after HTTPS is tried. */
    fun resolveArtwork(base: String, raw: String): String = resolve(base, raw)

    fun artworkFetchUrls(url: String): List<String> {
        val trimmed = url.trim()
        // This is used by image state collection on the UI thread: never resolve DNS here.
        val host = hostOf(trimmed)?.removePrefix("[")?.removeSuffix("]")
        val localLiteral = host == "127.0.0.1" || host == "::1" || host.equals("localhost", ignoreCase = true)
        if (trimmed.startsWith("http://", ignoreCase = true) && !localLiteral) {
            return listOf("https://" + trimmed.substring(7), trimmed)
        }
        return listOf(trimmed)
    }

    fun requireEncryptedOrLocal(url: String) {
        if (!url.startsWith("http://", ignoreCase = true)) return
        if (isLoopback(url)) return
        throw AppError(ErrorKind.CERT_INVALID, "Cleartext URLs are only allowed for artwork")
    }

    fun isLoopback(url: String): Boolean {
        val host = hostOf(url)?.removePrefix("[")?.removeSuffix("]") ?: return false
        if (host.equals("localhost", ignoreCase = true) || host == "127.0.0.1" || host == "::1") {
            return true
        }
        return runCatching { InetAddress.getByName(host).isLoopbackAddress }.getOrDefault(false)
    }

    fun wsUrl(httpBase: String, pathAndQuery: String): String {
        val https = resolve(httpBase, pathAndQuery)
        return https.replaceFirst("https://", "wss://")
    }

    fun hostOf(url: String): String? = runCatching { URI(url).host }.getOrNull()

    fun sameHost(left: String, right: String): Boolean {
        val a = hostOf(left) ?: return false
        val b = hostOf(right) ?: return false
        return a.equals(b, ignoreCase = true)
    }

    fun isAllowedProxyPath(path: String): Boolean {
        if (!path.startsWith("/") || path.startsWith("//")) return false
        if (".." in path) return false
        val prefix = allowedPrefixes.firstOrNull { path.startsWith(it) } ?: return false
        val rest = path.removePrefix(prefix)
        return rest.isNotEmpty() && '/' !in rest
    }

    private val allowedPrefixes = listOf(
        ProtocolConstants.PUBLIC_MEDIA_PREFIX,
        ProtocolConstants.P_STATIC_PREFIX,
        ProtocolConstants.P_URL_PREFIX,
    )
}
