package io.github.nutea.anylisten.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppErrorTest {
    @Test
    fun classifiesAuthAndCert() {
        assertEquals(ErrorKind.AUTH_FAILED, AppError.fromHttp(401).kind)
        assertEquals(ErrorKind.RATE_LIMITED, AppError.fromHttp(403, ProtocolConstants.BLOCKED_IP).kind)
        assertEquals(ErrorKind.CERT_INVALID, AppError.fromThrowable(IllegalStateException("pkix path building failed")).kind)
        assertFalse(AppError.fromHttp(401).retryable)
    }

    @Test
    fun intervalParser() {
        assertEquals(235_000L, IntervalParser.toMillis("03:55"))
        assertEquals(null, IntervalParser.toMillis("bad"))
    }
}
