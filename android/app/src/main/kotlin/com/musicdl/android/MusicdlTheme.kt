package com.musicdl.android

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** XXW Music: hard monochrome contrast, details over softness. */
internal val XxwDark = darkColorScheme(
    primary = Color(0xFFF4F4F2),
    onPrimary = Color(0xFF050506),
    primaryContainer = Color(0xFF181A1E),
    onPrimaryContainer = Color(0xFFF4F4F2),
    secondary = Color(0xFFDADADA),
    onSecondary = Color(0xFF050506),
    secondaryContainer = Color(0xFF1A1A1A),
    onSecondaryContainer = Color(0xFFF2F2F2),
    tertiary = Color(0xFFF4F4F2),
    onTertiary = Color(0xFF050506),
    tertiaryContainer = Color(0xFF191919),
    onTertiaryContainer = Color(0xFFF4F4F2),
    background = Color(0xFF050506),
    onBackground = Color(0xFFF4F4F2),
    surface = Color(0xFF050506),
    onSurface = Color(0xFFF4F4F2),
    surfaceVariant = Color(0xFF141414),
    onSurfaceVariant = Color(0xFF9A9EA7),
    surfaceContainerLowest = Color(0xFF050506),
    surfaceContainerLow = Color(0xFF080808),
    surfaceContainer = Color(0xFF0F0F0F),
    surfaceContainerHigh = Color(0xFF14161A),
    surfaceContainerHighest = Color(0xFF1F1F1F),
    outline = Color(0xFF5A5A5A),
    outlineVariant = Color(0xFF2A2A2A),
    error = Color(0xFFF4F4F2),
)

private val XxwShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(24.dp),
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MusicdlTheme(content: @Composable () -> Unit) {
    // Monochrome by default; the user may pick an accent colour (or wallpaper colours) in Settings.
    val context = androidx.compose.ui.platform.LocalContext.current
    val scheme = Appearance.appScheme(context)
    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = MotionScheme.expressive(),
        shapes = XxwShapes,
        content = content,
    )
}
