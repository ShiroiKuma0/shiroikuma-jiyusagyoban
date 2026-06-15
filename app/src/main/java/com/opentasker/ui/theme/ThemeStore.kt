package com.opentasker.ui.theme

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * User-settable appearance for 白い熊 自由作業盤, mirroring the sister apps' "白い熊 … UI" page.
 *
 * Colours are packed ARGB ints. The defaults are the fork's signature look: a black background
 * with pure-yellow (#FFFF00) text, accent, and borders — set as the default so a fresh install
 * starts black-and-yellow without any user action. [fontFileName] selects the app-wide font:
 * "" = system default, [MONOSPACE] = monospace, otherwise a .ttf/.otf file imported into the
 * app's private fonts directory.
 */
data class ThemePrefs(
    val background: Int = BLACK,
    val text: Int = YELLOW,
    val textSecondary: Int = YELLOW,
    val accent: Int = YELLOW,
    val surface: Int = NEAR_BLACK,
    val border: Int = YELLOW,
    val borderWidthDp: Int = 1,
    val fontFileName: String = "",
    val fontWeight: Int = 0,        // 0 = leave each text style's own weight; else 100..900
    val fontScalePct: Int = 100,    // 100 = 1.0x; clamped to [SCALE_MIN, SCALE_MAX]
    val advancedActionPicker: Boolean = false,  // full-screen, category-foldable action picker
    // ---- Flash / toast (the transient snackbar that reports task results) -----------------------
    val flashBackground: Int = BLACK,            // opaque so content never shows through
    val flashText: Int = YELLOW,
    val flashBorder: Int = YELLOW,
    val flashBorderWidthDp: Int = 1,             // [0, FLASH_BORDER_WIDTH_MAX]
    val flashCornerRadiusDp: Int = 12,           // [0, FLASH_CORNER_MAX]
    val flashTextSizeSp: Int = 16,               // [FLASH_TEXT_MIN, FLASH_TEXT_MAX]
    val flashFontWeight: Int = 700,              // 100..900 (Bold by default — bigger & heavier)
) {
    companion object {
        const val BLACK = 0xFF000000.toInt()
        const val NEAR_BLACK = 0xFF0D0D0D.toInt()  // card/surface, subtly above the background
        const val YELLOW = 0xFFFFFF00.toInt()       // pure yellow, not material #FFEB3B

        const val SCALE_MIN = 80
        const val SCALE_MAX = 160
        const val BORDER_WIDTH_MAX = 8

        const val FLASH_BORDER_WIDTH_MAX = 8
        const val FLASH_CORNER_MAX = 28
        const val FLASH_TEXT_MIN = 12
        const val FLASH_TEXT_MAX = 30
        const val FONT_WEIGHT_MIN = 100
        const val FONT_WEIGHT_MAX = 900

        val DEFAULT = ThemePrefs()
    }
}

/** One option in the font picker. [fileName] is "" (system), [ThemeStore.MONOSPACE], or a file. */
data class FontOption(val displayName: String, val fileName: String)

/**
 * Process-wide, SharedPreferences-backed appearance store. [init] must run once in
 * Application.onCreate (before any Compose code reads the theme). UI reads [state] and edits via
 * [update]; the Compose theme rebuilds live on every change because [state] is a StateFlow.
 */
object ThemeStore {
    const val MONOSPACE = "@monospace"

    private const val PREFS_NAME = "shiroikuma_ui_theme"
    private const val K_SEEDED = "theme_seeded"
    private const val K_BACKGROUND = "background"
    private const val K_TEXT = "text"
    private const val K_TEXT_SECONDARY = "text_secondary"
    private const val K_ACCENT = "accent"
    private const val K_SURFACE = "surface"
    private const val K_BORDER = "border"
    private const val K_BORDER_WIDTH = "border_width"
    private const val K_FONT_FILE = "font_file"
    private const val K_FONT_WEIGHT = "font_weight"
    private const val K_FONT_SCALE = "font_scale"
    private const val K_ADVANCED_ACTION_PICKER = "advanced_action_picker"
    private const val K_FLASH_BACKGROUND = "flash_background"
    private const val K_FLASH_TEXT = "flash_text"
    private const val K_FLASH_BORDER = "flash_border"
    private const val K_FLASH_BORDER_WIDTH = "flash_border_width"
    private const val K_FLASH_CORNER = "flash_corner"
    private const val K_FLASH_TEXT_SIZE = "flash_text_size"
    private const val K_FLASH_FONT_WEIGHT = "flash_font_weight"

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences
    private val _state = MutableStateFlow(ThemePrefs.DEFAULT)
    val state: StateFlow<ThemePrefs> = _state.asStateFlow()

    private val fontFamilyCache = mutableMapOf<String, FontFamily?>()

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // First run: persist the black-yellow defaults concretely (parity with the sister apps'
        // seedBlackYellowThemeIfNeeded). load() also falls back to DEFAULT, so this is idempotent.
        if (!prefs.getBoolean(K_SEEDED, false)) {
            persist(ThemePrefs.DEFAULT)
            prefs.edit { putBoolean(K_SEEDED, true) }
        }
        _state.value = load()
    }

    fun update(transform: (ThemePrefs) -> ThemePrefs) {
        val next = transform(_state.value).normalized()
        persist(next)
        _state.value = next
    }

    fun resetToDefault() = update { ThemePrefs.DEFAULT }

    private fun ThemePrefs.normalized(): ThemePrefs = copy(
        borderWidthDp = borderWidthDp.coerceIn(0, ThemePrefs.BORDER_WIDTH_MAX),
        fontScalePct = fontScalePct.coerceIn(ThemePrefs.SCALE_MIN, ThemePrefs.SCALE_MAX),
        flashBorderWidthDp = flashBorderWidthDp.coerceIn(0, ThemePrefs.FLASH_BORDER_WIDTH_MAX),
        flashCornerRadiusDp = flashCornerRadiusDp.coerceIn(0, ThemePrefs.FLASH_CORNER_MAX),
        flashTextSizeSp = flashTextSizeSp.coerceIn(ThemePrefs.FLASH_TEXT_MIN, ThemePrefs.FLASH_TEXT_MAX),
        flashFontWeight = flashFontWeight.coerceIn(ThemePrefs.FONT_WEIGHT_MIN, ThemePrefs.FONT_WEIGHT_MAX),
    )

    private fun load(): ThemePrefs {
        val d = ThemePrefs.DEFAULT
        return ThemePrefs(
            background = prefs.getInt(K_BACKGROUND, d.background),
            text = prefs.getInt(K_TEXT, d.text),
            textSecondary = prefs.getInt(K_TEXT_SECONDARY, d.textSecondary),
            accent = prefs.getInt(K_ACCENT, d.accent),
            surface = prefs.getInt(K_SURFACE, d.surface),
            border = prefs.getInt(K_BORDER, d.border),
            borderWidthDp = prefs.getInt(K_BORDER_WIDTH, d.borderWidthDp),
            fontFileName = prefs.getString(K_FONT_FILE, d.fontFileName) ?: d.fontFileName,
            fontWeight = prefs.getInt(K_FONT_WEIGHT, d.fontWeight),
            fontScalePct = prefs.getInt(K_FONT_SCALE, d.fontScalePct),
            advancedActionPicker = prefs.getBoolean(K_ADVANCED_ACTION_PICKER, d.advancedActionPicker),
            flashBackground = prefs.getInt(K_FLASH_BACKGROUND, d.flashBackground),
            flashText = prefs.getInt(K_FLASH_TEXT, d.flashText),
            flashBorder = prefs.getInt(K_FLASH_BORDER, d.flashBorder),
            flashBorderWidthDp = prefs.getInt(K_FLASH_BORDER_WIDTH, d.flashBorderWidthDp),
            flashCornerRadiusDp = prefs.getInt(K_FLASH_CORNER, d.flashCornerRadiusDp),
            flashTextSizeSp = prefs.getInt(K_FLASH_TEXT_SIZE, d.flashTextSizeSp),
            flashFontWeight = prefs.getInt(K_FLASH_FONT_WEIGHT, d.flashFontWeight),
        ).normalized()
    }

    private fun persist(p: ThemePrefs) {
        prefs.edit {
            putInt(K_BACKGROUND, p.background)
            putInt(K_TEXT, p.text)
            putInt(K_TEXT_SECONDARY, p.textSecondary)
            putInt(K_ACCENT, p.accent)
            putInt(K_SURFACE, p.surface)
            putInt(K_BORDER, p.border)
            putInt(K_BORDER_WIDTH, p.borderWidthDp)
            putString(K_FONT_FILE, p.fontFileName)
            putInt(K_FONT_WEIGHT, p.fontWeight)
            putInt(K_FONT_SCALE, p.fontScalePct)
            putBoolean(K_ADVANCED_ACTION_PICKER, p.advancedActionPicker)
            putInt(K_FLASH_BACKGROUND, p.flashBackground)
            putInt(K_FLASH_TEXT, p.flashText)
            putInt(K_FLASH_BORDER, p.flashBorder)
            putInt(K_FLASH_BORDER_WIDTH, p.flashBorderWidthDp)
            putInt(K_FLASH_CORNER, p.flashCornerRadiusDp)
            putInt(K_FLASH_TEXT_SIZE, p.flashTextSizeSp)
            putInt(K_FLASH_FONT_WEIGHT, p.flashFontWeight)
        }
    }

    // ---- Fonts ----------------------------------------------------------------------------------

    private fun fontsDir(): File = File(appContext.filesDir, "fonts").apply { mkdirs() }

    /** System default + monospace + every imported .ttf/.otf, sorted by name. */
    fun availableFonts(): List<FontOption> {
        val options = mutableListOf(
            FontOption("System default", ""),
            FontOption("Monospace", MONOSPACE),
        )
        fontsDir().listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in FONT_EXTENSIONS }
            ?.sortedBy { it.name.lowercase() }
            ?.forEach { options.add(FontOption(it.nameWithoutExtension, it.name)) }
        return options
    }

    fun displayNameFor(fileName: String): String = when {
        fileName.isEmpty() -> "System default"
        fileName == MONOSPACE -> "Monospace"
        else -> File(fileName).nameWithoutExtension
    }

    private val typefaceCache = mutableMapOf<String, android.graphics.Typeface?>()

    /** An android.graphics.Typeface for an imported font file (for Canvas drawing, e.g. widgets). */
    fun typeface(fileName: String): android.graphics.Typeface? = when {
        fileName.isEmpty() || fileName == MONOSPACE -> null
        else -> typefaceCache.getOrPut(fileName) {
            runCatching {
                val file = File(fontsDir(), fileName)
                if (file.exists()) android.graphics.Typeface.createFromFile(file) else null
            }.getOrNull()
        }
    }

    /** The Compose FontFamily for a selection, or null for the platform default. Cached by name. */
    fun fontFamily(fileName: String): FontFamily? = when {
        fileName.isEmpty() -> null
        fileName == MONOSPACE -> FontFamily.Monospace
        else -> fontFamilyCache.getOrPut(fileName) {
            runCatching {
                val file = File(fontsDir(), fileName)
                if (file.exists()) FontFamily(Font(file)) else null
            }.getOrNull()
        }
    }

    /** Copies a picked .ttf/.otf into the private fonts dir; returns the stored filename or null. */
    fun importFont(uri: Uri): String? = runCatching {
        val rawName = queryDisplayName(uri) ?: "font-${kotlin.math.abs(uri.hashCode())}.ttf"
        val ext = rawName.substringAfterLast('.', "").lowercase()
        require(ext in FONT_EXTENSIONS) { "not a font file: $rawName" }
        val dest = File(fontsDir(), sanitize(rawName))
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: error("cannot open $uri")
        // Validate it actually parses as a typeface before keeping it.
        if (runCatching { android.graphics.Typeface.createFromFile(dest) }.isFailure) {
            dest.delete()
            error("corrupt font: $rawName")
        }
        fontFamilyCache.remove(dest.name)
        dest.name
    }.getOrNull()

    private fun queryDisplayName(uri: Uri): String? =
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun sanitize(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private val FONT_EXTENSIONS = setOf("ttf", "otf")
}
