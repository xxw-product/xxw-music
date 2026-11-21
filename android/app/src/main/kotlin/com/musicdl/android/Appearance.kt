package com.musicdl.android

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Typeface

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

    /** Word-by-word fill and blurred neighbours in the lyrics carousel; off gives plain static lines. */
    var lyricsAnimated by mutableStateOf(true)
        private set

    fun setLyricsAnimated(context: Context, on: Boolean) {
        lyricsAnimated = on
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("lyrics_anim", on).apply()
    }

    // ---- typography
    var fontId by mutableStateOf("system"); private set
    var fontScale by mutableFloatStateOf(1f); private set

    // ---- general
    var blackBackground by mutableStateOf(false); private set
    var uiAnimations by mutableStateOf(true); private set

    // ---- bottom navigation pill
    var navLabels by mutableStateOf("none"); private set      // none | active | all
    var navSize by mutableStateOf("normal"); private set      // compact | normal | large
    var navShape by mutableStateOf("round"); private set      // round | soft | square
    var navFloating by mutableStateOf(true); private set
    var showWaveTab by mutableStateOf(true); private set
    var showArtistsTab by mutableStateOf(true); private set

    // ---- track lists
    var showCovers by mutableStateOf(true); private set
    var showTrackDetails by mutableStateOf(true); private set
    var compactCards by mutableStateOf(false); private set

    val fonts = listOf(
        "system" to "Системный", "light" to "Тонкий", "condensed" to "Узкий",
        "serif" to "С засечками", "mono" to "Моноширинный", "cursive" to "Рукописный",
    )

    /** Fonts the user imported; each is a file in the app's private storage. */
    var customFonts by mutableStateOf<List<String>>(emptyList()); private set
    private var fontsDir: java.io.File? = null
    private val customFamilies = HashMap<String, FontFamily>()

    fun customLabel(file: String) = file.substringBeforeLast('.').replace('_', ' ')

    /** Copies a picked .ttf/.otf into private storage after checking its header; returns the stored file name. */
    fun importFont(context: Context, uri: android.net.Uri): Result<String> = runCatching {
        val dir = fontsDir ?: java.io.File(context.filesDir, "fonts").also { it.mkdirs(); fontsDir = it }
        val shown = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "font.ttf"
        val ext = shown.substringAfterLast('.', "").lowercase()
        require(ext == "ttf" || ext == "otf") { "Нужен файл .ttf или .otf" }
        val name = shown.substringBeforeLast('.').replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").take(60).ifBlank { "font" } + "." + ext
        val target = java.io.File(dir, name)
        val temp = java.io.File(dir, "$name.part")
        context.contentResolver.openInputStream(uri)!!.use { input ->
            temp.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= 25L * 1024 * 1024) { "Файл больше 25 МБ" }
                    out.write(buffer, 0, read)
                }
            }
        }
        val magic = temp.inputStream().use { stream -> ByteArray(4).also { stream.read(it) } }
        val ok = magic.contentEquals(byteArrayOf(0, 1, 0, 0)) || String(magic, Charsets.ISO_8859_1) in setOf("OTTO", "true", "ttcf")
        if (!ok) { temp.delete(); error("Это не файл шрифта") }
        require(runCatching { android.graphics.Typeface.createFromFile(temp) }.isSuccess) { "Шрифт не читается" }
        target.delete()
        check(temp.renameTo(target)) { "Не удалось сохранить файл" }
        name
    }

    /** Makes an imported font the active one. */
    fun useCustomFont(context: Context, file: String) {
        customFamilies.remove(file)
        refreshCustomFonts()
        setFont(context, "custom:$file")
    }

    fun deleteCustomFont(context: Context, file: String) {
        java.io.File(fontsDir ?: return, file).delete()
        customFamilies.remove(file)
        refreshCustomFonts()
        if (fontId == "custom:$file") setFont(context, "system")
    }

    private fun refreshCustomFonts() {
        customFonts = fontsDir?.listFiles { f -> f.isFile && (f.name.endsWith(".ttf") || f.name.endsWith(".otf")) }
            ?.map { it.name }?.sorted().orEmpty()
    }

    private val lightFamily by lazy { FontFamily(Typeface(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL))) }
    private val condensedFamily by lazy { FontFamily(Typeface(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL))) }

    /** Null keeps the platform default. */
    val fontFamily: FontFamily?
        get() = when (fontId) {
            "light" -> lightFamily
            "condensed" -> condensedFamily
            "serif" -> FontFamily.Serif
            "mono" -> FontFamily.Monospace
            "cursive" -> FontFamily.Cursive
            else -> if (fontId.startsWith("custom:")) {
                val file = fontId.removePrefix("custom:")
                customFamilies.getOrPut(file) {
                    val f = java.io.File(fontsDir ?: return@getOrPut FontFamily.Default, file)
                    if (f.isFile) FontFamily(Font(f, androidx.compose.ui.text.font.FontWeight.Normal), Font(f, androidx.compose.ui.text.font.FontWeight.Bold))
                    else FontFamily.Default
                }
            } else null
        }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun setFont(context: Context, id: String) { fontId = id; prefs(context).edit().putString("font", id).apply() }
    fun setFontScale(context: Context, scale: Float) { fontScale = scale; prefs(context).edit().putFloat("font_scale", scale).apply() }
    fun setBlackBackground(context: Context, on: Boolean) { blackBackground = on; prefs(context).edit().putBoolean("black", on).apply() }
    fun setUiAnimations(context: Context, on: Boolean) { uiAnimations = on; prefs(context).edit().putBoolean("ui_anim", on).apply() }
    fun setNavLabels(context: Context, v: String) { navLabels = v; prefs(context).edit().putString("nav_labels", v).apply() }
    fun setNavSize(context: Context, v: String) { navSize = v; prefs(context).edit().putString("nav_size", v).apply() }
    fun setNavShape(context: Context, v: String) { navShape = v; prefs(context).edit().putString("nav_shape", v).apply() }
    fun setNavFloating(context: Context, on: Boolean) { navFloating = on; prefs(context).edit().putBoolean("nav_floating", on).apply() }
    fun setShowWaveTab(context: Context, on: Boolean) { showWaveTab = on; prefs(context).edit().putBoolean("tab_wave", on).apply() }
    fun setShowArtistsTab(context: Context, on: Boolean) { showArtistsTab = on; prefs(context).edit().putBoolean("tab_artists", on).apply() }
    fun setShowCovers(context: Context, on: Boolean) { showCovers = on; prefs(context).edit().putBoolean("covers", on).apply() }
    fun setShowTrackDetails(context: Context, on: Boolean) { showTrackDetails = on; prefs(context).edit().putBoolean("details", on).apply() }
    fun setCompactCards(context: Context, on: Boolean) { compactCards = on; prefs(context).edit().putBoolean("compact", on).apply() }

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
        lyricsAnimated = prefs.getBoolean("lyrics_anim", true)
        fontsDir = java.io.File(context.filesDir, "fonts").also { it.mkdirs() }
        refreshCustomFonts()
        fontId = prefs.getString("font", "system").orEmpty().takeIf { id ->
            fonts.any { it.first == id } || (id.startsWith("custom:") && id.removePrefix("custom:") in customFonts)
        } ?: "system"
        fontScale = prefs.getFloat("font_scale", 1f).coerceIn(0.8f, 1.4f)
        blackBackground = prefs.getBoolean("black", false)
        uiAnimations = prefs.getBoolean("ui_anim", true)
        navLabels = prefs.getString("nav_labels", "none").orEmpty()
        navSize = prefs.getString("nav_size", "normal").orEmpty()
        navShape = prefs.getString("nav_shape", "round").orEmpty()
        navFloating = prefs.getBoolean("nav_floating", true)
        showWaveTab = prefs.getBoolean("tab_wave", true)
        showArtistsTab = prefs.getBoolean("tab_artists", true)
        showCovers = prefs.getBoolean("covers", true)
        showTrackDetails = prefs.getBoolean("details", true)
        compactCards = prefs.getBoolean("compact", false)
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
        val base = when {
            accentId == DYNAMIC && dynamicAvailable -> runCatching { dynamicDarkColorScheme(context) }.getOrDefault(XxwDark)
            else -> accents.firstOrNull { it.id == accentId }?.hue?.let { schemeFromHue(it, 0.85f, tint = 0.22f) } ?: XxwDark
        }
        return if (blackBackground) base.copy(
            background = Color.Black, surface = Color.Black, surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color(0xFF070707),
        ) else base
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
