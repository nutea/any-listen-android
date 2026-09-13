package io.github.nutea.anylisten.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Brief, nonmodal feedback below the top toolbar, clear of every playback control. */
@Composable
internal fun OperationNotice(message: String?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val accessibility = LocalAccessibilityManager.current
    val dismiss by rememberUpdatedState(onDismiss)
    var lastMessage by remember { mutableStateOf("") }
    LaunchedEffect(message) {
        if (message == null) return@LaunchedEffect
        lastMessage = message
        val duration = accessibility?.calculateRecommendedTimeoutMillis(
            originalTimeoutMillis = 1400L, containsIcons = true, containsText = true, containsControls = false,
        ) ?: 1400L
        delay(duration)
        dismiss()
    }
    val motion = rememberMotionEnabled()
    Box(modifier.statusBarsPadding().padding(top = 60.dp, start = 24.dp, end = 24.dp)) {
        AnimatedVisibility(message != null,
            enter = fadeIn(tween(if (motion) 120 else 0)),
            exit = fadeOut(tween(if (motion) 100 else 0))) {
            Surface(shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 3.dp, shadowElevation = 3.dp,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.widthIn(max = 360.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.Info, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(message ?: lastMessage, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
