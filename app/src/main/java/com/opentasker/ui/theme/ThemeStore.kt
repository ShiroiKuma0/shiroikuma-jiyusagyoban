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
    // ---- Task list -------------------------------------------------------------------------------
    val taskIconSizeDp: Int = 32,                // size of a task's custom icon on its card [TASK_ICON_MIN, TASK_ICON_MAX]
    val taskCardGapDp: Int = 6,                  // gap BETWEEN task cards (Tasks tab) [0, TASK_CARD_GAP_MAX]
    val taskCardVPadDp: Int = 8,                 // vertical padding INSIDE a task pill (Tasks tab) [0, TASK_CARD_VPAD_MAX]
    val groupHeaderVPadDp: Int = 8,              // vertical padding INSIDE a group header (lists) [0, GROUP_HEADER_VPAD_MAX]
    // ---- Monitor ---------------------------------------------------------------------------------
    val monitorRowPadDp: Int = 2,                // vertical padding per Monitor task-activity row; 2 = tight [0, MONITOR_PAD_MAX]
    // ---- Freeze bubbles (Desktop re-freeze overlays) ---------------------------------------------
    val bubbleIconSizeDp: Int = 48,              // [BUBBLE_ICON_MIN, BUBBLE_ICON_MAX]
    val bubbleIconCornerDp: Int = 12,            // icon corner radius; 0 = square, up to BUBBLE_ICON_CORNER_MAX
    val bubbleLabelSizeSp: Int = 11,             // [BUBBLE_LABEL_MIN, BUBBLE_LABEL_MAX]
    val bubbleLabelWeight: Int = 700,            // 100..900 (Bold default)
    val bubbleFontFileName: String = "",         // "" = follow the app font; else MONOSPACE / a .ttf/.otf file
    // ---- Launcher "add task shortcut" picker (projects → folder-boxes → tasks) --------------------
    val pickerFontSizeSp: Int = 15,              // [PICKER_FONT_MIN, PICKER_FONT_MAX]
    val pickerRowPadDp: Int = 3,                 // vertical padding per row; 0 = tightest [0, PICKER_PAD_MAX]
    val pickerIndentDp: Int = 14,                // indent per nesting level [0, PICKER_INDENT_MAX]
    val pickerGroupCornerDp: Int = 12,           // group folder-box corner radius [0, PICKER_CORNER_MAX]
    val pickerGroupBorderDp: Int = 1,            // group folder-box border width; 0 = no box [0, PICKER_BORDER_MAX]
    val pickerFontFileName: String = "",         // "" = follow the app font; else MONOSPACE / a .ttf/.otf file
    // ---- Review-import screen ---------------------------------------------------------------------
    val importHeaderSp: Int = 20,                // header title + stats line [IMPORT_TEXT_MIN, IMPORT_TEXT_MAX]
    val importSectionSp: Int = 16,               // section titles + the radio-group labels
    val importItemSp: Int = 15,                  // item rows in the tree
    val importWarnSp: Int = 17,                  // the Warnings text — bigger than body by default
    val importRowPadDp: Int = 2,                 // vertical padding between tree rows; 2 = tight [0, IMPORT_ROW_PAD_MAX]
    val importConflictColor: Int = 0xFF87CEEB.toInt(),  // readable sky blue (replaces the unreadable 0xFF0000FF)
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

        const val TASK_ICON_MIN = 16
        const val TASK_ICON_MAX = 96
        const val TASK_CARD_GAP_MAX = 24
        const val TASK_CARD_VPAD_MAX = 24
        const val GROUP_HEADER_VPAD_MAX = 24

        const val MONITOR_PAD_MAX = 24

        const val BUBBLE_ICON_MIN = 24
        const val BUBBLE_ICON_MAX = 96
        const val BUBBLE_ICON_CORNER_MAX = 48
        const val BUBBLE_LABEL_MIN = 8
        const val BUBBLE_LABEL_MAX = 24

        const val PICKER_FONT_MIN = 11
        const val PICKER_FONT_MAX = 28
        const val PICKER_PAD_MAX = 24
        const val PICKER_INDENT_MAX = 40
        const val PICKER_CORNER_MAX = 28
        const val PICKER_BORDER_MAX = 4

        const val IMPORT_TEXT_MIN = 12
        const val IMPORT_TEXT_MAX = 34
        const val IMPORT_ROW_PAD_MAX = 24

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
    private const val K_TASK_ICON_SIZE = "task_icon_size"
    private const val K_TASK_CARD_GAP = "task_card_gap"
    private const val K_TASK_CARD_VPAD = "task_card_vpad"
    private const val K_GROUP_HEADER_VPAD = "group_header_vpad"
    private const val K_MONITOR_PAD = "monitor_row_pad"
    private const val K_BUBBLE_ICON_SIZE = "bubble_icon_size"
    private const val K_BUBBLE_ICON_CORNER = "bubble_icon_corner"
    private const val K_BUBBLE_LABEL_SIZE = "bubble_label_size"
    private const val K_BUBBLE_LABEL_WEIGHT = "bubble_label_weight"
    private const val K_BUBBLE_FONT = "bubble_font"
    private const val K_PICKER_FONT_SIZE = "picker_font_size"
    private const val K_PICKER_ROW_PAD = "picker_row_pad"
    private const val K_PICKER_INDENT = "picker_indent"
    private const val K_PICKER_GROUP_CORNER = "picker_group_corner"
    private const val K_PICKER_GROUP_BORDER = "picker_group_border"
    private const val K_PICKER_FONT = "picker_font"
    private const val K_IMPORT_HEADER_SP = "import_header_sp"
    private const val K_IMPORT_SECTION_SP = "import_section_sp"
    private const val K_IMPORT_ITEM_SP = "import_item_sp"
    private const val K_IMPORT_WARN_SP = "import_warn_sp"
    private const val K_IMPORT_ROW_PAD = "import_row_pad"
    private const val K_IMPORT_CONFLICT_COLOR = "import_conflict_color"

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
        taskIconSizeDp = taskIconSizeDp.coerceIn(ThemePrefs.TASK_ICON_MIN, ThemePrefs.TASK_ICON_MAX),
        taskCardGapDp = taskCardGapDp.coerceIn(0, ThemePrefs.TASK_CARD_GAP_MAX),
        taskCardVPadDp = taskCardVPadDp.coerceIn(0, ThemePrefs.TASK_CARD_VPAD_MAX),
        groupHeaderVPadDp = groupHeaderVPadDp.coerceIn(0, ThemePrefs.GROUP_HEADER_VPAD_MAX),
        monitorRowPadDp = monitorRowPadDp.coerceIn(0, ThemePrefs.MONITOR_PAD_MAX),
        bubbleIconSizeDp = bubbleIconSizeDp.coerceIn(ThemePrefs.BUBBLE_ICON_MIN, ThemePrefs.BUBBLE_ICON_MAX),
        bubbleIconCornerDp = bubbleIconCornerDp.coerceIn(0, ThemePrefs.BUBBLE_ICON_CORNER_MAX),
        bubbleLabelSizeSp = bubbleLabelSizeSp.coerceIn(ThemePrefs.BUBBLE_LABEL_MIN, ThemePrefs.BUBBLE_LABEL_MAX),
        bubbleLabelWeight = bubbleLabelWeight.coerceIn(ThemePrefs.FONT_WEIGHT_MIN, ThemePrefs.FONT_WEIGHT_MAX),
        pickerFontSizeSp = pickerFontSizeSp.coerceIn(ThemePrefs.PICKER_FONT_MIN, ThemePrefs.PICKER_FONT_MAX),
        pickerRowPadDp = pickerRowPadDp.coerceIn(0, ThemePrefs.PICKER_PAD_MAX),
        pickerIndentDp = pickerIndentDp.coerceIn(0, ThemePrefs.PICKER_INDENT_MAX),
        pickerGroupCornerDp = pickerGroupCornerDp.coerceIn(0, ThemePrefs.PICKER_CORNER_MAX),
        pickerGroupBorderDp = pickerGroupBorderDp.coerceIn(0, ThemePrefs.PICKER_BORDER_MAX),
        importHeaderSp = importHeaderSp.coerceIn(ThemePrefs.IMPORT_TEXT_MIN, ThemePrefs.IMPORT_TEXT_MAX),
        importSectionSp = importSectionSp.coerceIn(ThemePrefs.IMPORT_TEXT_MIN, ThemePrefs.IMPORT_TEXT_MAX),
        importItemSp = importItemSp.coerceIn(ThemePrefs.IMPORT_TEXT_MIN, ThemePrefs.IMPORT_TEXT_MAX),
        importWarnSp = importWarnSp.coerceIn(ThemePrefs.IMPORT_TEXT_MIN, ThemePrefs.IMPORT_TEXT_MAX),
        importRowPadDp = importRowPadDp.coerceIn(0, ThemePrefs.IMPORT_ROW_PAD_MAX),
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
            taskIconSizeDp = prefs.getInt(K_TASK_ICON_SIZE, d.taskIconSizeDp),
            taskCardGapDp = prefs.getInt(K_TASK_CARD_GAP, d.taskCardGapDp),
            taskCardVPadDp = prefs.getInt(K_TASK_CARD_VPAD, d.taskCardVPadDp),
            groupHeaderVPadDp = prefs.getInt(K_GROUP_HEADER_VPAD, d.groupHeaderVPadDp),
            monitorRowPadDp = prefs.getInt(K_MONITOR_PAD, d.monitorRowPadDp),
            bubbleIconSizeDp = prefs.getInt(K_BUBBLE_ICON_SIZE, d.bubbleIconSizeDp),
            bubbleIconCornerDp = prefs.getInt(K_BUBBLE_ICON_CORNER, d.bubbleIconCornerDp),
            bubbleLabelSizeSp = prefs.getInt(K_BUBBLE_LABEL_SIZE, d.bubbleLabelSizeSp),
            bubbleLabelWeight = prefs.getInt(K_BUBBLE_LABEL_WEIGHT, d.bubbleLabelWeight),
            bubbleFontFileName = prefs.getString(K_BUBBLE_FONT, d.bubbleFontFileName) ?: d.bubbleFontFileName,
            pickerFontSizeSp = prefs.getInt(K_PICKER_FONT_SIZE, d.pickerFontSizeSp),
            pickerRowPadDp = prefs.getInt(K_PICKER_ROW_PAD, d.pickerRowPadDp),
            pickerIndentDp = prefs.getInt(K_PICKER_INDENT, d.pickerIndentDp),
            pickerGroupCornerDp = prefs.getInt(K_PICKER_GROUP_CORNER, d.pickerGroupCornerDp),
            pickerGroupBorderDp = prefs.getInt(K_PICKER_GROUP_BORDER, d.pickerGroupBorderDp),
            pickerFontFileName = prefs.getString(K_PICKER_FONT, d.pickerFontFileName) ?: d.pickerFontFileName,
            importHeaderSp = prefs.getInt(K_IMPORT_HEADER_SP, d.importHeaderSp),
            importSectionSp = prefs.getInt(K_IMPORT_SECTION_SP, d.importSectionSp),
            importItemSp = prefs.getInt(K_IMPORT_ITEM_SP, d.importItemSp),
            importWarnSp = prefs.getInt(K_IMPORT_WARN_SP, d.importWarnSp),
            importRowPadDp = prefs.getInt(K_IMPORT_ROW_PAD, d.importRowPadDp),
            importConflictColor = prefs.getInt(K_IMPORT_CONFLICT_COLOR, d.importConflictColor),
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
            putInt(K_TASK_ICON_SIZE, p.taskIconSizeDp)
            putInt(K_TASK_CARD_GAP, p.taskCardGapDp)
            putInt(K_TASK_CARD_VPAD, p.taskCardVPadDp)
            putInt(K_GROUP_HEADER_VPAD, p.groupHeaderVPadDp)
            putInt(K_MONITOR_PAD, p.monitorRowPadDp)
            putInt(K_BUBBLE_ICON_SIZE, p.bubbleIconSizeDp)
            putInt(K_BUBBLE_ICON_CORNER, p.bubbleIconCornerDp)
            putInt(K_BUBBLE_LABEL_SIZE, p.bubbleLabelSizeSp)
            putInt(K_BUBBLE_LABEL_WEIGHT, p.bubbleLabelWeight)
            putString(K_BUBBLE_FONT, p.bubbleFontFileName)
            putInt(K_PICKER_FONT_SIZE, p.pickerFontSizeSp)
            putInt(K_PICKER_ROW_PAD, p.pickerRowPadDp)
            putInt(K_PICKER_INDENT, p.pickerIndentDp)
            putInt(K_PICKER_GROUP_CORNER, p.pickerGroupCornerDp)
            putInt(K_PICKER_GROUP_BORDER, p.pickerGroupBorderDp)
            putString(K_PICKER_FONT, p.pickerFontFileName)
            putInt(K_IMPORT_HEADER_SP, p.importHeaderSp)
            putInt(K_IMPORT_SECTION_SP, p.importSectionSp)
            putInt(K_IMPORT_ITEM_SP, p.importItemSp)
            putInt(K_IMPORT_WARN_SP, p.importWarnSp)
            putInt(K_IMPORT_ROW_PAD, p.importRowPadDp)
            putInt(K_IMPORT_CONFLICT_COLOR, p.importConflictColor)
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
    fun typeface(fileName: String): android.graphics.Typeface? {
        val requested = fileName.trim()
        if (requested.isEmpty() || requested == MONOSPACE) return null
        // Built-in family keywords (e.g. widgets that want Minchō without importing a font): on Android,
        // SERIF resolves CJK glyphs to Noto Serif CJK (= 明朝/Minchō); SANS_SERIF to the gothic default.
        when (requested.lowercase()) {
            "serif", "mincho", "minchō", "明朝" -> return android.graphics.Typeface.SERIF
            "sans", "sans-serif", "gothic", "ゴシック" -> return android.graphics.Typeface.SANS_SERIF
        }
        return typefaceCache.getOrPut(requested) {
            runCatching {
                val file = File(fontsDir(), requested)
                if (file.exists()) android.graphics.Typeface.createFromFile(file) else null
            }.getOrNull()
        }
    }

    /** Delete an imported font file. If it was the selected font, fall back to the system default. */
    fun deleteFont(fileName: String): Boolean {
        if (fileName.isEmpty() || fileName == MONOSPACE) return false
        val deleted = runCatching { File(fontsDir(), fileName).delete() }.getOrDefault(false)
        typefaceCache.remove(fileName)
        fontFamilyCache.remove(fileName)
        if (_state.value.fontFileName == fileName) update { it.copy(fontFileName = "") }
        return deleted
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

    // Keep UTF-8 (kanji, accents, spaces) in font file names; only neutralise path separators and
    // control characters, which are the only things actually unsafe in a filename.
    private fun sanitize(name: String): String =
        name.trim().map { c -> if (c == '/' || c == '\\' || c.code < 0x20) '_' else c }
            .joinToString("").ifBlank { "font" }

    private val FONT_EXTENSIONS = setOf("ttf", "otf")
}
