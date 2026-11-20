package com.musicdl.android

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

enum class PlayerTheme(val branded: Boolean = false) { DEFAULT, EXPRESSIVE, YANDEX(true), SPOTIFY(true), APPLE(true) }

/** One selectable theme colour; [hue] null means the original monochrome silver. */
data class AccentOption(val id: String, val label: String, val hue: Float?)

/**
 * User-chosen look: the app accent colour and the full-screen player theme. State is Compose state,
 * so a change repaints everything at once, and it persists in its own preferences file.
 */
object Appearance {
    const val DYNAMIC = "dynamic"
    val accents = listOf(
        AccentOption("silver", "Серебро", null),
        AccentOption("blue", "Синий", 215f),
        AccentOption("violet", "Фиолетовый", 265f),
        AccentOption("pink", "Розовый", 330f),
        AccentOption("red", "Красный", 5f),
        AccentOption("orange", "Оранжевый", 28f),
        AccentOption("green", "Зелёный", 140f),
        AccentOption("teal", "Бирюзовый", 175f),
    )

    private const val PREFS = "appearance"
    private val silver = Color(0xFFDDE0E5)
    private var dynamicPrimary: Color? = null

    var accentId by mutableStateOf("silver")
        private set
    var playerTheme by mutableStateOf(PlayerTheme.DEFAULT)
        private set

    val dynamicAvailable: Boolean get() = Build.VERSION.SDK_INT >= 31

    /** Foreground accent used across the app (formerly a fixed silver). */
    val accentColor: Color
        get() = when {
            accentId == DYNAMIC -> dynamicPrimary ?: silver
            else -> accents.firstOrNull { it.id == accentId }?.hue?.let { Color.hsl(it, 0.85f, 0.78f) } ?: silver
        }

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (dynamicAvailable) dynamicPrimary = runCatching { dynamicDarkColorScheme(context).primary }.getOrNull()
        val saved = prefs.getString("accent", "silver").orEmpty()
        accentId = if (saved == DYNAMIC && dynamicAvailable || accents.any { it.id == saved }) saved else "silver"
        playerTheme = runCatching { PlayerTheme.valueOf(prefs.getString("player", "DEFAULT").orEmpty()) }
            .getOrDefault(PlayerTheme.DEFAULT)
    }

    fun setAccent(context: Context, id: String) {
        accentId = id
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("accent", id).apply()
    }

    fun setPlayerTheme(context: Context, theme: PlayerTheme) {
        playerTheme = theme
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("player", theme.name).apply()
    }

    fun swatch(option: AccentOption): Color =
        option.hue?.let { Color.hsl(it, 0.85f, 0.78f) } ?: silver

    fun dynamicSwatch(): Color = dynamicPrimary ?: silver

    /** App colour scheme for the chosen accent. */
    fun appScheme(context: Context): ColorScheme {
        if (accentId == DYNAMIC && dynamicAvailable) return runCatching { dynamicDarkColorScheme(context) }.getOrDefault(XxwDark)
        val hue = accents.firstOrNull { it.id == accentId }?.hue ?: return XxwDark
        return schemeFromHue(hue, 0.85f, tint = 0.22f)
    }
}

/**
 * Dark Material scheme built around one hue. [tint] controls how much colour reaches the surfaces:
 * the app keeps nearly black surfaces, the expressive player lets the cover colour through.
 */
internal fun schemeFromHue(hue: Float, saturation: Float, tint: Float): ColorScheme {
    fun c(s: Float, l: Float, h: Float = hue) = Color.hsl(((h % 360f) + 360f) % 360f, s.coerceIn(0f, 1f), l)
    val s = saturation.coerceIn(0.35f, 0.9f)
    return darkColorScheme(
        primary = c(s, 0.78f), onPrimary = c(s * 0.6f, 0.12f),
        primaryContainer = c(s * 0.6f, 0.24f), onPrimaryContainer = c(s, 0.9f),
        secondary = c(s * 0.4f, 0.76f, hue + 20f), onSecondary = c(0.3f, 0.12f, hue + 20f),
        secondaryContainer = c(s * 0.3f, 0.24f, hue + 20f), onSecondaryContainer = c(s * 0.5f, 0.9f, hue + 20f),
        tertiary = c(s * 0.7f, 0.78f, hue + 60f), onTertiary = c(0.4f, 0.12f, hue + 60f),
        tertiaryContainer = c(s * 0.5f, 0.24f, hue + 60f), onTertiaryContainer = c(s * 0.7f, 0.9f, hue + 60f),
        background = c(tint * 0.5f, 0.03f + tint * 0.05f), onBackground = Color(0xFFF4F4F2),
        surface = c(tint * 0.5f, 0.04f + tint * 0.06f), onSurface = Color(0xFFF4F4F2),
        surfaceVariant = c(tint * 0.5f, 0.12f + tint * 0.05f), onSurfaceVariant = c(0.12f, 0.66f),
        surfaceContainerLowest = c(tint * 0.5f, 0.03f), surfaceContainerLow = c(tint * 0.5f, 0.06f + tint * 0.04f),
        surfaceContainer = c(tint * 0.5f, 0.08f + tint * 0.07f), surfaceContainerHigh = c(tint * 0.5f, 0.11f + tint * 0.08f),
        surfaceContainerHighest = c(tint * 0.5f, 0.15f + tint * 0.09f),
        outline = c(0.1f, 0.4f), outlineVariant = c(0.1f, 0.2f),
        error = Color(0xFFFFB4AB),
    )
}

/** Expressive player scheme from the cover's dominant colour. */
internal fun expressiveScheme(seed: Color): ColorScheme {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(android.graphics.Color.argb(255, (seed.red * 255).toInt(), (seed.green * 255).toInt(), (seed.blue * 255).toInt()), hsv)
    return schemeFromHue(hsv[0], (hsv[1] * 1.2f).coerceIn(0.45f, 0.9f), tint = 0.9f)
}
