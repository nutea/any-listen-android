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
    fun artworkKeepsHttpAndListsHttpsFirst() {
        val raw = "http://imge.kugou.com/stdmusic/480/cover.jpg?size=480"
        assertEquals(raw, UrlNormalizer.resolveArtwork("https://example.test", raw))
        assertEquals(raw, UrlNormalizer.resolve("https://example.test", raw))
        assertEquals(
            listOf("https://imge.kugou.com/stdmusic/480/cover.jpg?size=480", raw),
            UrlNormalizer.artworkFetchUrls(raw),
        )
        assertEquals(
            listOf("https://cdn.example/cover.jpg"),
            UrlNormalizer.artworkFetchUrls("https://cdn.example/cover.jpg"),
        )
        assertEquals(
            listOf("http://127.0.0.1:9/cover"),
            UrlNormalizer.artworkFetchUrls("http://127.0.0.1:9/cover"),
        )
        assertEquals("https://example.test/api/p_static/cover.jpg",
            UrlNormalizer.resolveArtwork("https://example.test", "al-ps-host:/api/p_static/cover.jpg"))
    }

    @Test
    fun rejectsCleartextExceptLoopback() {
        try {
            UrlNormalizer.httpsBase("http://music.example")
            throw AssertionError("expected failure")
        } catch (error: AppError) {
            assertEquals("Only HTTPS server URLs are allowed", error.message)
        }
        try {
            UrlNormalizer.requireEncryptedOrLocal("http://cdn.example/a.mp3")
            throw AssertionError("expected failure")
        } catch (error: AppError) {
            assertEquals("Cleartext URLs are only allowed for artwork", error.message)
        }
        UrlNormalizer.requireEncryptedOrLocal("http://127.0.0.1:8080/a.mp3")
        UrlNormalizer.requireEncryptedOrLocal("http://localhost/a.mp3")
        UrlNormalizer.requireEncryptedOrLocal("https://cdn.example/a.mp3")
    }

    @Test
    fun sameHostAfterRewrite() {
        val media = UrlNormalizer.resolve("https://example.test", "al-ps-host:/api/p_static/x.mp3")
        assertTrue(UrlNormalizer.sameHost("https://example.test", media))
        assertFalse(UrlNormalizer.sameHost("https://example.test", "https://other.test/x"))
    }
}
