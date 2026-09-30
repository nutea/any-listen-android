package io.github.nutea.anylisten

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import io.github.nutea.anylisten.core.model.ThemeMode
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.nutea.anylisten.ui.AnyListenRoot
import io.github.nutea.anylisten.ui.theme.AnyListenTheme

class MainActivity : ComponentActivity() {
    private var openPlayerRequest by mutableIntStateOf(0)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as AnyListenApp
        if (savedInstanceState == null && intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true) openPlayerRequest++
        setContent {
            val themeMode by app.container.settings.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            val dark = themeMode.isDark(isSystemInDarkTheme())
            AnyListenTheme(darkTheme = dark) {
                AnyListenRoot(app.container, playLast = wantsPlayLast(intent), openPlayerRequest = openPlayerRequest)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as AnyListenApp).container.onForeground()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)) openPlayerRequest++
    }

    companion object {
        const val EXTRA_PLAY_LAST = "play_last"
        const val EXTRA_OPEN_PLAYER = "open_player"

        fun wantsPlayLast(intent: Intent?): Boolean = intent?.getBooleanExtra(EXTRA_PLAY_LAST, false) == true
    }
}
