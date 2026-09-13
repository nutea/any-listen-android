package io.github.nutea.anylisten.core.data.gateway

import io.github.nutea.anylisten.core.model.AppError
import io.github.nutea.anylisten.core.model.ErrorKind
import io.github.nutea.anylisten.core.model.ProtocolConstants
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
