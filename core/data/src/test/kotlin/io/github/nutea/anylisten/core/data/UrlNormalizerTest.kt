package io.github.nutea.anylisten.core.data

import io.github.nutea.anylisten.core.data.gateway.UrlNormalizer
import io.github.nutea.anylisten.core.model.AppError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlNormalizerTest {
    @Test
    fun rewritesLocalPublicMediaPath() {
        val url = UrlNormalizer.resolve(
            "https://example.test",
            "al-ps-host:/public/medias/abc123.flac",
        )
        assertEquals("https://example.test/public/medias/abc123.flac", url)
    }

    @Test
    fun rewritesVirtualPublicPath() {
        val url = UrlNormalizer.resolve(
            "https://example.test",
            "al-ps-host:/api/p_static/abc123.flac",
        )
        assertEquals("https://example.test/api/p_static/abc123.flac", url)
    }

    @Test
    fun rewritesVirtualProxyUrlPath() {
        val url = UrlNormalizer.resolve(
            "https://example.test/",
            "al-ps-host:/api/p_url/https%3A%2F%2Fcdn.example%2Fa.mp3",
        )
        assertEquals("https://example.test/api/p_url/https%3A%2F%2Fcdn.example%2Fa.mp3", url)
    }

    @Test
    fun rejectsNestedVirtualPath() {
        try {
            UrlNormalizer.resolve("https://example.test", "al-ps-host:/public/medias/a/b.mp3")
            throw AssertionError("expected failure")
        } catch (error: AppError) {
            assertEquals("Unsupported media path", error.message)
        }
    }

    @Test
    fun rejectsVirtualOpenPath() {
        try {
            UrlNormalizer.resolve("https://example.test", "al-ps-host://evil.test/x")
            throw AssertionError("expected failure")
        } catch (error: AppError) {
            assertEquals("Unsupported media path", error.message)
        }
    }

    @Test
    fun keepsHttpsAbsolute() {
        assertEquals(
            "https://cdn.example/a.mp3",
            UrlNormalizer.resolve("https://example.test", "https://cdn.example/a.mp3"),
        )
    }

    @Test
    fun sameHostAfterRewrite() {
        val media = UrlNormalizer.resolve("https://example.test", "al-ps-host:/api/p_static/x.mp3")
        assertTrue(UrlNormalizer.sameHost("https://example.test", media))
        assertFalse(UrlNormalizer.sameHost("https://example.test", "https://other.test/x"))
    }
}
