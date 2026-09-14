package io.github.nutea.anylisten.core.data

import android.content.Context
import io.github.nutea.anylisten.core.data.session.SESSION_FALLBACK_PREFS_NAME
import io.github.nutea.anylisten.core.data.session.SecureSessionStore
import io.github.nutea.anylisten.core.data.session.discardFallbackSessionStore
import io.github.nutea.anylisten.core.data.session.openWithRecovery
import io.github.nutea.anylisten.core.model.ServerProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

class OpenWithRecoveryTest {
    @Test
    fun returnsCreateWhenItSucceeds() {
        val value = openWithRecovery(create = { "ok" }, wipe = { error("wipe") }, fallback = { error("fallback") })
        assertEquals("ok", value)
    }

    @Test
    fun wipesAndRetriesAfterFirstFailure() {
        var attempts = 0
        var wiped = false
        val value = openWithRecovery(
            create = { if (attempts++ == 0) error("first") else "recovered" },
            wipe = { wiped = true },
            fallback = { error("fallback") },
        )
        assertEquals("recovered", value)
        assertEquals(true, wiped)
        assertEquals(2, attempts)
    }

    @Test
    fun usesFallbackWhenRetryAlsoFails() {
        val fallback = Any()
        val value = openWithRecovery(
            create = { error("always") },
            wipe = { },
            fallback = { fallback },
        )
        assertSame(fallback, value)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SecureSessionStoreTest {
    @Test
    fun constructorDoesNotThrowAndRoundTripsSession() {
        val store = SecureSessionStore(RuntimeEnvironment.getApplication())
        assertNull(store.current())
        store.save(
            ServerProfile(id = "p", baseUrl = "https://example.test", serverId = "s", serverName = "n", reportedVersion = "1"),
            token = "tok",
            password = "pw",
        )
        val stored = store.current()
        assertEquals("tok", stored?.token)
        assertEquals("pw", stored?.password)
        assertEquals("https://example.test", stored?.profile?.baseUrl)
        store.clear()
        assertNull(store.current())
    }

    @Test
    fun leftoverFallbackIsRemovedWhenEncryptedStoreOpens() {
        val app = RuntimeEnvironment.getApplication()
        seedFallback(
            app,
            token = "stale-token",
            password = "stale-pw",
            baseUrl = "https://stale.example",
        )

        val store = SecureSessionStore(app)
        val leftover = fallbackPrefs(app).getString("token", null)

        if (store.current()?.token == "stale-token") {
            assertEquals("stale-token", leftover)
        } else {
            assertNull(leftover)
            assertNull(store.current())
        }
    }

    @Test
    fun discardFallbackClearsPlaintextCredentials() {
        val app = RuntimeEnvironment.getApplication()
        seedFallback(app, token = "stale-token", password = "stale-pw")

        discardFallbackSessionStore(app)

        assertNull(fallbackPrefs(app).getString("token", null))
        assertNull(fallbackPrefs(app).getString("password", null))
    }

    @Test
    fun clearWipesFallbackCredentialsEvenIfEncryptedStoreIsActive() {
        val app = RuntimeEnvironment.getApplication()
        val store = SecureSessionStore(app)
        store.save(
            ServerProfile(id = "p", baseUrl = "https://example.test", serverId = "s", serverName = "n", reportedVersion = "1"),
            token = "tok",
            password = "pw",
        )
        seedFallback(app, token = "stale-token", password = "stale-pw")

        store.clear()

        assertNull(store.current())
        assertNull(fallbackPrefs(app).getString("token", null))
        assertNull(fallbackPrefs(app).getString("password", null))
    }

    private fun seedFallback(
        app: Context,
        token: String,
        password: String,
        baseUrl: String? = null,
    ) {
        fallbackPrefs(app).edit()
            .putString("token", token)
            .putString("password", password)
            .apply { if (baseUrl != null) putString("base_url", baseUrl) }
            .commit()
    }

    private fun fallbackPrefs(app: Context) =
        app.getSharedPreferences(SESSION_FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
}
