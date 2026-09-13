package io.github.nutea.anylisten.core.data

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class MemoryCookieJarTest {
    @Test fun respectsSecurePathAndExpiry() {
        val jar = MemoryCookieJar()
        val url = "https://music.example.com/api/login".toHttpUrl()
        jar.saveFromResponse(url,listOf(Cookie.parse(url,"session=fixture; Path=/api; Secure")!!,
            Cookie.parse(url,"expired=fixture; Path=/; Max-Age=0")!!))
        assertEquals(1,jar.loadForRequest(url).size)
        assertTrue(jar.loadForRequest("http://music.example.com/api/pic".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("https://music.example.com/cover".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("https://other.example.com/api".toHttpUrl()).isEmpty())
    }
    @Test fun retainsUnrelatedCookiesAndReplacesSameIdentity() {
        val jar = MemoryCookieJar()
        val url = "https://music.example.com/".toHttpUrl()
        jar.saveFromResponse(url,listOf(Cookie.parse(url,"one=first; Path=/")!!))
        jar.saveFromResponse(url,listOf(Cookie.parse(url,"two=second; Path=/")!!))
        jar.saveFromResponse(url,listOf(Cookie.parse(url,"one=updated; Path=/")!!))
        assertEquals(mapOf("one" to "updated","two" to "second"),jar.loadForRequest(url).associate { it.name to it.value })
    }
}
