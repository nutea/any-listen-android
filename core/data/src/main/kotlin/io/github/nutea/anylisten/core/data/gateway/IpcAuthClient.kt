package io.github.nutea.anylisten.core.data.gateway

import io.github.nutea.anylisten.core.model.AppError
import io.github.nutea.anylisten.core.model.ErrorKind
import io.github.nutea.anylisten.core.model.ProtocolConstants
import io.github.nutea.anylisten.core.model.ServerProfile
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.util.UUID

class IpcAuthClient(
    private val http: OkHttpClient,
) {
    fun login(baseUrl: String, password: String): SessionInfo {
        val base = UrlNormalizer.httpsBase(baseUrl)
        val serverId = readServerId(base)
        val salt = UUID.randomUUID().toString().replace("-", "")
        val digest = sha256Hex(password + salt)
        val request = Request.Builder()
            .url(UrlNormalizer.resolve(base, ProtocolConstants.AUTH_PATH))
            .post(ByteArray(0).toRequestBody(null))
            .header("m", digest)
            .header("s", salt)
            .build()
        return executeAuth(base, serverId, request)
    }

    fun restore(profile: ServerProfile, token: String): SessionInfo {
        val base = UrlNormalizer.httpsBase(profile.baseUrl)
        val request = Request.Builder()
            .url(UrlNormalizer.resolve(base, ProtocolConstants.AUTH_PATH))
            .post(ByteArray(0).toRequestBody(null))
            .header("m", token)
            .build()
        return executeAuth(base, profile.serverId, request).copy(
            profile = profile.copy(
                serverId = profile.serverId.ifBlank { readServerId(base) },
            ),
        )
    }

    fun readHello(baseUrl: String): String {
        val base = UrlNormalizer.httpsBase(baseUrl)
        val request = Request.Builder().url(UrlNormalizer.resolve(base, ProtocolConstants.HELLO_PATH)).get().build()
        return execute(request).body?.string().orEmpty()
    }

    fun isHello(body: String): Boolean =
        body.lineSequence().firstOrNull()?.trim() == ProtocolConstants.HELLO_MSG

    private fun readServerId(base: String): String {
        val request = Request.Builder().url(UrlNormalizer.resolve(base, ProtocolConstants.ID_PATH)).get().build()
        val body = execute(request).body?.string().orEmpty()
        if (!body.startsWith(ProtocolConstants.ID_PREFIX)) {
            throw AppError(ErrorKind.UNKNOWN, "Server id prefix mismatch")
        }
        return body.removePrefix(ProtocolConstants.ID_PREFIX)
    }

    private fun executeAuth(base: String, serverId: String, request: Request): SessionInfo {
        val response = execute(request)
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            throw AppError.fromHttp(response.code, body)
        }
        val lines = body.split('\n')
        if (lines.firstOrNull() != ProtocolConstants.HELLO_MSG) {
            throw AppError(ErrorKind.AUTH_FAILED, "Unexpected auth response")
        }
        val token = response.header("token").orEmpty()
        if (token.isBlank()) throw AppError(ErrorKind.AUTH_FAILED, "Missing session token")
        val serverName = java.net.URLDecoder.decode(lines.getOrNull(1).orEmpty(), Charsets.UTF_8)
        return SessionInfo(
            profile = ServerProfile(
                id = serverId.ifBlank { UUID.randomUUID().toString() },
                baseUrl = base,
                serverId = serverId,
                serverName = serverName,
                reportedVersion = ProtocolConstants.TARGET_SERVER_VERSION,
            ),
            token = token,
        )
    }

    private fun execute(request: Request) = try {
        http.newCall(request).execute()
    } catch (error: Throwable) {
        throw AppError.fromThrowable(error)
    }

    companion object {
        fun sha256Hex(value: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
