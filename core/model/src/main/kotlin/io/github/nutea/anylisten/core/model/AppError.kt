package io.github.nutea.anylisten.core.model

enum class ErrorKind {
    AUTH_FAILED,
    SESSION_EXPIRED,
    RATE_LIMITED,
    CERT_INVALID,
    NETWORK_UNREACHABLE,
    TRACK_UNAVAILABLE,
    FORMAT_UNSUPPORTED,
    DISK_FULL,
    INTEGRITY_FAILED,
    WRITE_UNCONFIRMED,
    OFFLINE_MUTATION,
    UNKNOWN,
}

data class AppError(
    val kind: ErrorKind,
    override val message: String,
    val retryable: Boolean = false,
    val causeMessage: String? = null,
) : Exception(message) {
    companion object {
        fun fromHttp(code: Int, body: String = ""): AppError = when (code) {
            401 -> AppError(ErrorKind.AUTH_FAILED, body.ifBlank { "Authentication failed" }, retryable = false)
            403 -> if (body.contains(ProtocolConstants.BLOCKED_IP, ignoreCase = true)) {
                AppError(ErrorKind.RATE_LIMITED, "Too many failed attempts. Wait and retry later.", retryable = false)
            } else {
                AppError(ErrorKind.AUTH_FAILED, body.ifBlank { "Forbidden" }, retryable = false)
            }
            404 -> AppError(ErrorKind.TRACK_UNAVAILABLE, "Track is no longer available", retryable = false)
            in 500..599 -> AppError(ErrorKind.NETWORK_UNREACHABLE, "Server error $code", retryable = true)
            else -> AppError(ErrorKind.UNKNOWN, body.ifBlank { "HTTP $code" }, retryable = code >= 500)
        }

        fun fromThrowable(error: Throwable): AppError {
            val text = error.message.orEmpty()
            val lowered = text.lowercase()
            return when {
                "cert" in lowered || "trust" in lowered || "pkix" in lowered ->
                    AppError(ErrorKind.CERT_INVALID, "Certificate validation failed", retryable = false, causeMessage = text)
                "unable to resolve" in lowered || "failed to connect" in lowered || "timeout" in lowered ->
                    AppError(ErrorKind.NETWORK_UNREACHABLE, "Network unreachable", retryable = true, causeMessage = text)
                "401" in lowered || ProtocolConstants.AUTH_FAILED.lowercase() in lowered ->
                    AppError(ErrorKind.AUTH_FAILED, "Authentication failed", retryable = false)
                else -> AppError(ErrorKind.UNKNOWN, text.ifBlank { "Unexpected error" }, retryable = false, causeMessage = text)
            }
        }
    }
}
