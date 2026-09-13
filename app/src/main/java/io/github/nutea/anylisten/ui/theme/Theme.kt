package io.github.nutea.anylisten.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Light = lightColorScheme(
    primary = Color(0xFF3D54C4), onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE3F8), onPrimaryContainer = Color(0xFF1B2A6B),
    secondary = Color(0xFF5C6478), secondaryContainer = Color(0xFFE4E7F0), onSecondaryContainer = Color(0xFF2B3140),
    background = Color(0xFFF6F7FB), onBackground = Color(0xFF1D2026),
    surface = Color(0xFFF6F7FB), onSurface = Color(0xFF1D2026),
    surfaceVariant = Color(0xFFE8EAF1), onSurfaceVariant = Color(0xFF5C6370),
    surfaceContainer = Color.White, surfaceContainerLow = Color(0xFFEEF0F6),
    surfaceContainerHigh = Color(0xFFE4E7F0), outline = Color(0xFFB4B9C6),
    outlineVariant = Color(0xFFD8DCE6),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFB4C2FF), onPrimary = Color(0xFF1A2A6E),
    primaryContainer = Color(0xFF2D3C7A), onPrimaryContainer = Color(0xFFDDE3F8),
    secondary = Color(0xFFC5CAD6), secondaryContainer = Color(0xFF2C3140), onSecondaryContainer = Color(0xFFE4E7F0),
    background = Color(0xFF111318), onBackground = Color(0xFFE6E8EF),
    surface = Color(0xFF111318), onSurface = Color(0xFFE6E8EF),
    surfaceVariant = Color(0xFF262B38), onSurfaceVariant = Color(0xFFB3B9C6),
    surfaceContainer = Color(0xFF1A1E27), surfaceContainerLow = Color(0xFF161920),
    surfaceContainerHigh = Color(0xFF242836), outlineVariant = Color(0xFF353B4A),
)
private val Type = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
)

val LocalDarkTheme = staticCompositionLocalOf { false }

@Composable
fun AnyListenTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) Dark else Light,
        typography = Type,
        shapes = Shapes(small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(16.dp)),
        content = { CompositionLocalProvider(LocalDarkTheme provides darkTheme, content = content) },
    )
}
