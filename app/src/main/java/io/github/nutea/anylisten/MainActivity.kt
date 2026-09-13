package io.github.nutea.anylisten

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.core.view.WindowCompat
import io.github.nutea.anylisten.core.model.ThemeMode
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.nutea.anylisten.ui.AnyListenRoot
import io.github.nutea.anylisten.ui.theme.AnyListenTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as AnyListenApp
        setContent {
            val themeMode by app.container.settings.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            val dark = themeMode.isDark(isSystemInDarkTheme())
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            AnyListenTheme(darkTheme = dark) {
                AnyListenRoot(app.container, playLast = wantsPlayLast(intent))
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    companion object {
        const val EXTRA_PLAY_LAST = "play_last"

        fun wantsPlayLast(intent: Intent?): Boolean = intent?.getBooleanExtra(EXTRA_PLAY_LAST, false) == true
    }
}
