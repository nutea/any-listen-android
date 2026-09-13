package io.github.nutea.anylisten.ui

import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
fun rememberMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
}

fun View.tickHaptic() {
    if (isHapticFeedbackEnabled) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
}

fun View.confirmHaptic() {
    if (!isHapticFeedbackEnabled) return
    if (Build.VERSION.SDK_INT >= 30) performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    else performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
}

fun playerEnter(enabled: Boolean): EnterTransition =
    if (enabled) slideInVertically(androidx.compose.animation.core.tween(300, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { it } else EnterTransition.None

fun playerExit(enabled: Boolean): ExitTransition =
    if (enabled) slideOutVertically(androidx.compose.animation.core.tween(260, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { it } else ExitTransition.None

fun crossfadeSpec(enabled: Boolean) =
    if (enabled) fadeIn() togetherWith fadeOut() else EnterTransition.None togetherWith ExitTransition.None
