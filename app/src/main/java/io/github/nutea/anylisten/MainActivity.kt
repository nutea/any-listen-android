package io.github.nutea.anylisten

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
            AnyListenTheme {
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
