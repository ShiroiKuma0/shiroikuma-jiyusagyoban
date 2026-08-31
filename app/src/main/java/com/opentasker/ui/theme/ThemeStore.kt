package com.opentasker.ui.theme

import android.content.Context
import com.opentasker.core.ocr.OcrTuning
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
    val advancedActionPicker: Boolean = true,   // full-screen, category-foldable action picker (fork default)
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
    val actionRowPadDp: Int = 2,                 // padding INSIDE an action row: label↔args gap + top/bottom [0, ACTION_ROW_PAD_MAX]
    val actionLabelSizeSp: Int = 13,             // folded action-label text size [ACTION_LABEL_MIN, ACTION_LABEL_MAX]
    val actionValueSizeSp: Int = 16,             // action name/value (the data) text size [ACTION_VALUE_MIN, ACTION_VALUE_MAX]
    val actionNameColor: Int = 0xFF7FB4FF.toInt(),   // the variable NAME colour (blue)
    val actionValueColor: Int = 0xFFFFFFFF.toInt(),  // action value colour
    val actionLabelFrameColor: Int = 0x4DFFFF00,     // folded-label frame border (dim yellow), ARGB incl. alpha
    val actionLabelFrameWidthDp: Int = 1,        // folded-label frame width; 0 = none [0, BORDER_WIDTH_MAX]
    val actionBorderColor: Int = 0x8CFFFF00.toInt(), // action card border (yellow ~55%), ARGB incl. alpha
    val actionBorderWidthDp: Int = 1,            // action card border width; 0 = none [0, BORDER_WIDTH_MAX]
    val selectionColor: Int = 0x33FFFF00,        // multi-select highlight fill (ARGB); yellow ~20%
    val groupHeaderVPadDp: Int = 8,              // vertical padding INSIDE a group header (lists) [0, GROUP_HEADER_VPAD_MAX]
    val groupHeaderColor: Int = 0x1AFFFF00,      // group-header background (ARGB); a slight yellow (~10%) — subtler than a selection, marks a group vs ungrouped
    val groupHeaderBorderColor: Int = YELLOW,    // group-header border colour (default yellow)
    val groupHeaderBorderWidthDp: Int = 1,       // group-header border width; 0 = none [0, GROUP_HEADER_BORDER_MAX]
    // ---- Variables tab ---------------------------------------------------------------------------
    // Mirrors the action-view name/value styling: defaults equal the action-view colours (blue name /
    // white value) and data size (16sp). Name and value colour + size are each independently settable.
    val varRowPadDp: Int = 2,                    // padding INSIDE a variable row: vertical + row spacing [0, ACTION_ROW_PAD_MAX]
    val varNameColor: Int = 0xFF7FB4FF.toInt(),  // var NAME colour (default = actionNameColor blue)
    val varValueColor: Int = 0xFFFFFFFF.toInt(), // var VALUE colour (default = actionValueColor white)
    val varNameSizeSp: Int = 16,                 // var NAME size (sp) [ACTION_VALUE_MIN, ACTION_VALUE_MAX]
    val varValueSizeSp: Int = 16,                // var VALUE size (sp) [ACTION_VALUE_MIN, ACTION_VALUE_MAX]
    // ---- Monitor ---------------------------------------------------------------------------------
    val monitorRowPadDp: Int = 2,                // vertical padding per Monitor task-activity row; 2 = tight [0, MONITOR_PAD_MAX]
    // ---- Freeze bubbles (Desktop re-freeze overlays) ---------------------------------------------
    val bubbleIconSizeDp: Int = 48,              // [BUBBLE_ICON_MIN, BUBBLE_ICON_MAX]
    val bubbleIconCornerDp: Int = 12,            // icon corner radius; 0 = square, up to BUBBLE_ICON_CORNER_MAX
    val bubbleLabelSizeSp: Int = 11,             // [BUBBLE_LABEL_MIN, BUBBLE_LABEL_MAX]
    val bubbleLabelWeight: Int = 700,            // 100..900 (Bold default)
    val bubbleFontFileName: String = "",         // "" = follow the app font; else MONOSPACE / a .ttf/.otf file
    // ---- Flash bubbles (通知明滅 Desktop icons; style shared with the freeze bubbles above) --------
    val flashTapBehavior: String = "open_kill",      // one of FLASH_BEHAVIORS
    val flashLongTapBehavior: String = "kill",       // one of FLASH_BEHAVIORS
    val flashKillTaskName: String = "通知明滅消灯",     // per-app kill task; run with %APP_PACKAGE = the bubble's app
    val flashKillAllTaskName: String = "通知明滅全消灯", // kill-all task (the flash-ongoing notification's tap task)
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
    // ---- Panel bars (the oval volume/brightness capsule slider — style:fill) ---------------------
    val ovalBarBorderWidthDp: Int = 2,           // default oval-bar border width; 0 = no border [0, OVAL_BAR_BORDER_MAX]
    val ovalBarBorderColor: Int = BLACK,         // default oval-bar border colour

    // ---- 「健康」 charts ---------------------------------------------------------------------
    val chartPreviewHeightDp: Int = 132,                    // preview card plot height
    val chartDetailHeightDp: Int = 320,                     // full-screen plot height
    val chartCardGapDp: Int = 12,                           // gap between dashboard cards
    val chartAxisTextSp: Int = 10,                          // axis label size
    val chartHeadlineSp: Int = 28,                          // the big number on a card
    val chartLineWidthDp: Int = 2,                          // line stroke width
    val chartDotSizeDp: Int = 3,                            // sample dot diameter; 0 = no dots
    val chartCapsuleWidthDp: Int = 8,                       // hourly capsule width
    val chartBarWidthDp: Int = 6,                           // step bar width
    val chartDumbbellWidthDp: Int = 5,                      // blood-pressure cap width
    val chartHypnogramBandPct: Int = 52,                    // stage block height, % of its row
    val chartCornerRadiusDp: Int = 2,                       // rounded data-end radius
    val chartGridOpacityPct: Int = 10,                      // grid line opacity
    val chartFillOpacityPct: Int = 28,                      // area fill under a line
    val chartGlowOpacityPct: Int = 18,                      // glow behind a stroke
    val chartGapTintPct: Int = 8,                           // tint over a stretch with no measurement
    val chartAxisTextColor: Int = 0xFF8A8A85.toInt(),       // axis + footnote ink
    val chartGridColor: Int = 0xFFFFFFFF.toInt(),           // grid line colour, before opacity
    // 文字認識 (OCR): which Japanese/English recogniser runs. On = PP-OCRv5 server (~81 MB), off =
    // mobile (~16 MB, ~2.5x faster). Measured on clean screenshot text the two are equivalent; the
    // server model's headroom is for photographed and handwritten text. Only affects Japanese and
    // English — PaddleOCR ships no server-sized Latin or Cyrillic recogniser.
    val ocrHighAccuracy: Boolean = true,
    // 文字認識 detection knobs. Integers because the sliders are integer sliders; OcrTuning owns what
    // each one means and clamps them. Every default is measured — see OcrTuning.
    val ocrDetectionLongSide: Int = OcrTuning.DEFAULT_LONG_SIDE,
    val ocrBinarisePercent: Int = OcrTuning.DEFAULT_BINARY_PERCENT,
    val ocrBoxScorePercent: Int = OcrTuning.DEFAULT_BOX_SCORE_PERCENT,
    val ocrUnclipTenths: Int = OcrTuning.DEFAULT_UNCLIP_TENTHS,
    // Where each ONNX weight file lives — a plain path or a picked document URI, empty when unset.
    // The weights are ~100 MB and no build is ever deleted from the phone, so they are supplied rather
    // than shipped; the dictionaries that must match them DO ship. See ModelSlot.
    val ocrModelDet: String = "",
    val ocrModelJpnServer: String = "",
    val ocrModelJpnMobile: String = "",
    val ocrModelLatin: String = "",
    val ocrModelEslav: String = "",

    val chartShowGrid: Boolean = true,                      // draw the grid at all
    val chartShowDots: Boolean = true,                      // draw the real samples over a line
    val chartShowRejected: Boolean = true,                  // the ✕ marks at flagged samples
    val chartShowGaps: Boolean = true,                      // tint stretches with no measurement
    val chartDefaultSpanHours: Int = 24,                    // how much a chart opens on
    val chartCurveMode: String = "PCHIP",                   // PCHIP / LINEAR / STEP
    // 白い熊, 2026-08-23: steps blue, heart rate red. The orange moved to plum to make room —
    // see the note in ChartPalette; a red heart rate cannot share a screen with a warm series.
    val chartColorHeartRate: Int = 0xFFE66767.toInt(),      // 心拍 — red
    val chartColorBandState: Int = 0xFFA96BAF.toInt(),      // バンド状態指数 — plum
    val chartColorSpo2: Int = 0xFF199E70.toInt(),           // 血中酸素
    val chartColorTemperature: Int = 0xFFC98500.toInt(),    // 体温
    val chartColorSteps: Int = 0xFF3987E5.toInt(),          // 歩数 — blue
    val chartColorRestingHr: Int = 0xFF9CCC65.toInt(),      // 安静時心拍 (Huawei) — lime
    val chartColorSystolic: Int = 0xFFD55181.toInt(),       // 収縮期 — magenta
    val chartColorDiastolic: Int = 0xFF8B6FD8.toInt(),      // 拡張期 — violet
    val chartColorSleepDeep: Int = 0xFF199E70.toInt(),      // 深い
    val chartColorSleepLight: Int = 0xFFC98500.toInt(),     // 浅い
    val chartColorSleepRem: Int = 0xFF3987E5.toInt(),       // REM
    val chartColorSleepAwake: Int = 0xFFD95926.toInt(),     // 覚醒
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
        const val ACTION_ROW_PAD_MAX = 24
        const val ACTION_LABEL_MIN = 8
        const val ACTION_LABEL_MAX = 24
        const val ACTION_VALUE_MIN = 10
        const val ACTION_VALUE_MAX = 30
        const val GROUP_HEADER_VPAD_MAX = 24
        const val GROUP_HEADER_BORDER_MAX = 8

        const val MONITOR_PAD_MAX = 24

        const val BUBBLE_ICON_MIN = 24
        const val BUBBLE_ICON_MAX = 96
        const val BUBBLE_ICON_CORNER_MAX = 48
        const val BUBBLE_LABEL_MIN = 8
        const val BUBBLE_LABEL_MAX = 24

        /** Flash-bubble gesture behaviors: open the app + kill its flash / kill only / open only / dismiss the icon only. */
        val FLASH_BEHAVIORS = setOf("open_kill", "kill", "open", "dismiss")

        const val PICKER_FONT_MIN = 11
        const val PICKER_FONT_MAX = 28
        const val PICKER_PAD_MAX = 24
        const val PICKER_INDENT_MAX = 40
        const val PICKER_CORNER_MAX = 28
        const val PICKER_BORDER_MAX = 4

        const val IMPORT_TEXT_MIN = 12
        const val IMPORT_TEXT_MAX = 34
        const val IMPORT_ROW_PAD_MAX = 24

        const val OVAL_BAR_BORDER_MAX = 16

        // ---- 「健康」 chart bounds ------------------------------------------------------------------
        const val CHART_PREVIEW_H_MIN = 60
        const val CHART_PREVIEW_H_MAX = 400
        const val CHART_DETAIL_H_MIN = 140
        const val CHART_DETAIL_H_MAX = 900
        const val CHART_GAP_MAX = 40
        const val CHART_AXIS_SP_MIN = 6
        const val CHART_AXIS_SP_MAX = 20
        const val CHART_HEADLINE_MIN = 14
        const val CHART_HEADLINE_MAX = 60
        const val CHART_LINE_MAX = 8
        const val CHART_DOT_MAX = 14
        const val CHART_MARK_W_MAX = 40
        const val CHART_CORNER_MAX = 12
        const val CHART_SPAN_MAX = 720
        val CHART_CURVES = listOf("PCHIP", "LINEAR", "STEP")

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
    private const val K_CHART_PREVIEW_H = "chart_preview_h"
    private const val K_CHART_DETAIL_H = "chart_detail_h"
    private const val K_CHART_CARD_GAP = "chart_card_gap"
    private const val K_CHART_AXIS_SP = "chart_axis_sp"
    private const val K_CHART_HEADLINE_SP = "chart_headline_sp"
    private const val K_CHART_LINE_W = "chart_line_w"
    private const val K_CHART_DOT = "chart_dot"
    private const val K_CHART_CAPSULE_W = "chart_capsule_w"
    private const val K_CHART_BAR_W = "chart_bar_w"
    private const val K_CHART_DUMBBELL_W = "chart_dumbbell_w"
    private const val K_CHART_HYPNO_PCT = "chart_hypno_pct"
    private const val K_CHART_CORNER = "chart_corner"
    private const val K_CHART_GRID_OP = "chart_grid_op"
    private const val K_CHART_FILL_OP = "chart_fill_op"
    private const val K_CHART_GLOW_OP = "chart_glow_op"
    private const val K_CHART_GAP_OP = "chart_gap_op"
    private const val K_CHART_AXIS_COLOR = "chart_axis_color"
    private const val K_CHART_GRID_COLOR = "chart_grid_color"
    private const val K_OCR_HIGH_ACCURACY = "ocr_high_accuracy"
    private const val K_OCR_LONG_SIDE = "ocr_detection_long_side"
    private const val K_OCR_BINARISE = "ocr_binarise_percent"
    private const val K_OCR_BOX_SCORE = "ocr_box_score_percent"
    private const val K_OCR_UNCLIP = "ocr_unclip_tenths"
    private const val K_OCR_MODEL_DET = "ocr_model_det"
    private const val K_OCR_MODEL_JPN_SERVER = "ocr_model_jpn_server"
    private const val K_OCR_MODEL_JPN_MOBILE = "ocr_model_jpn_mobile"
    private const val K_OCR_MODEL_LATIN = "ocr_model_latin"
    private const val K_OCR_MODEL_ESLAV = "ocr_model_eslav"
    private const val K_CHART_SHOW_GRID = "chart_show_grid"
    private const val K_CHART_SHOW_DOTS = "chart_show_dots"
    private const val K_CHART_SHOW_REJECTED = "chart_show_rejected"
    private const val K_CHART_SHOW_GAPS = "chart_show_gaps"
    private const val K_CHART_SPAN_H = "chart_span_h"
    private const val K_CHART_CURVE = "chart_curve"
    private const val K_CHART_C_HR = "chart_c_hr"
    private const val K_CHART_C_STATE = "chart_c_state"
    private const val K_CHART_C_SPO2 = "chart_c_spo2"
    private const val K_CHART_C_TEMP = "chart_c_temp"
    private const val K_CHART_C_STEPS = "chart_c_steps"
    private const val K_CHART_C_RHR = "chart_c_rhr"
    private const val K_CHART_C_SYS = "chart_c_sys"
    private const val K_CHART_C_DIA = "chart_c_dia"
    private const val K_CHART_C_DEEP = "chart_c_deep"
    private const val K_CHART_C_LIGHT = "chart_c_light"
    private const val K_CHART_C_REM = "chart_c_rem"
    private const val K_CHART_C_AWAKE = "chart_c_awake"
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
    private const val K_ACTION_ROW_PAD = "action_row_pad"
    private const val K_ACTION_LABEL_SIZE = "action_label_size"
    private const val K_ACTION_VALUE_SIZE = "action_value_size"
    private const val K_ACTION_NAME_COLOR = "action_name_color"
    private const val K_ACTION_VALUE_COLOR = "action_value_color"
    private const val K_ACTION_LABEL_FRAME_COLOR = "action_label_frame_color"
    private const val K_ACTION_LABEL_FRAME_WIDTH = "action_label_frame_width"
    private const val K_ACTION_BORDER_COLOR = "action_border_color"
    private const val K_ACTION_BORDER_WIDTH = "action_border_width"
    private const val K_SELECTION_COLOR = "selection_color"
    private const val K_VAR_ROW_PAD = "var_row_pad"
    private const val K_VAR_NAME_COLOR = "var_name_color"
    private const val K_VAR_VALUE_COLOR = "var_value_color"
    private const val K_VAR_NAME_SIZE = "var_name_size"
    private const val K_VAR_VALUE_SIZE = "var_value_size"
    private const val K_GH_MIGRATED = "gh_default_migrated"   // one-time move of the old group-header defaults
    private const val K_GROUP_HEADER_VPAD = "group_header_vpad"
    private const val K_GROUP_HEADER_COLOR = "group_header_color"
    private const val K_GROUP_HEADER_BORDER_COLOR = "group_header_border_color"
    private const val K_GROUP_HEADER_BORDER_WIDTH = "group_header_border_width"
    private const val K_MONITOR_PAD = "monitor_row_pad"
    private const val K_BUBBLE_ICON_SIZE = "bubble_icon_size"
    private const val K_BUBBLE_ICON_CORNER = "bubble_icon_corner"
    private const val K_BUBBLE_LABEL_SIZE = "bubble_label_size"
    private const val K_BUBBLE_LABEL_WEIGHT = "bubble_label_weight"
    private const val K_BUBBLE_FONT = "bubble_font"
    private const val K_FLASH_TAP = "flash_tap_behavior"
    private const val K_FLASH_LONG_TAP = "flash_long_tap_behavior"
    private const val K_FLASH_KILL_TASK = "flash_kill_task"
    private const val K_FLASH_KILL_ALL_TASK = "flash_kill_all_task"
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
    private const val K_OVAL_BAR_BORDER_WIDTH = "oval_bar_border_width"
    private const val K_OVAL_BAR_BORDER_COLOR = "oval_bar_border_color"

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
        // One-time: bump the earlier group-header defaults (olive / interim grey / black) to the current
        // default, then flag it done so load() reads the colour verbatim — it stays freely settable
        // afterwards (including deliberately to black or grey).
        if (!prefs.getBoolean(K_GH_MIGRATED, false)) {
            val gh = prefs.getInt(K_GROUP_HEADER_COLOR, ThemePrefs.DEFAULT.groupHeaderColor)
            if (gh == 0x29FFFF00 || gh == 0x1FFFFFFF || gh == 0xFF000000.toInt()) {
                prefs.edit { putInt(K_GROUP_HEADER_COLOR, ThemePrefs.DEFAULT.groupHeaderColor) }
            }
            prefs.edit { putBoolean(K_GH_MIGRATED, true) }
        }
        _state.value = load()
    }

    fun update(transform: (ThemePrefs) -> ThemePrefs) {
        val next = transform(_state.value).normalized()
        persist(next)
        _state.value = next
    }

    fun resetToDefault() = update { ThemePrefs.DEFAULT }

    /**
     * Put every 「健康」 chart knob back the way it shipped.
     *
     * Separate from [resetToDefault] on purpose: the chart colours were validated against
     * colour-blindness and contrast gates, so "put the charts back" is a thing worth being able to do
     * without also discarding a font, a border width, and everything else on the page.
     */
    fun resetChartsToDefault() = update { p ->
        val d = ThemePrefs.DEFAULT
        p.copy(
        chartPreviewHeightDp = d.chartPreviewHeightDp,
        chartDetailHeightDp = d.chartDetailHeightDp,
        chartCardGapDp = d.chartCardGapDp,
        chartAxisTextSp = d.chartAxisTextSp,
        chartHeadlineSp = d.chartHeadlineSp,
        chartLineWidthDp = d.chartLineWidthDp,
        chartDotSizeDp = d.chartDotSizeDp,
        chartCapsuleWidthDp = d.chartCapsuleWidthDp,
        chartBarWidthDp = d.chartBarWidthDp,
        chartDumbbellWidthDp = d.chartDumbbellWidthDp,
        chartHypnogramBandPct = d.chartHypnogramBandPct,
        chartCornerRadiusDp = d.chartCornerRadiusDp,
        chartGridOpacityPct = d.chartGridOpacityPct,
        chartFillOpacityPct = d.chartFillOpacityPct,
        chartGlowOpacityPct = d.chartGlowOpacityPct,
        chartGapTintPct = d.chartGapTintPct,
        chartAxisTextColor = d.chartAxisTextColor,
        chartGridColor = d.chartGridColor,
        ocrHighAccuracy = d.ocrHighAccuracy,
        ocrDetectionLongSide = d.ocrDetectionLongSide,
        ocrBinarisePercent = d.ocrBinarisePercent,
        ocrBoxScorePercent = d.ocrBoxScorePercent,
        ocrUnclipTenths = d.ocrUnclipTenths,
        ocrModelDet = d.ocrModelDet,
        ocrModelJpnServer = d.ocrModelJpnServer,
        ocrModelJpnMobile = d.ocrModelJpnMobile,
        ocrModelLatin = d.ocrModelLatin,
        ocrModelEslav = d.ocrModelEslav,
        chartShowGrid = d.chartShowGrid,
        chartShowDots = d.chartShowDots,
        chartShowRejected = d.chartShowRejected,
        chartShowGaps = d.chartShowGaps,
        chartDefaultSpanHours = d.chartDefaultSpanHours,
        chartCurveMode = d.chartCurveMode,
        chartColorHeartRate = d.chartColorHeartRate,
        chartColorBandState = d.chartColorBandState,
        chartColorSpo2 = d.chartColorSpo2,
        chartColorTemperature = d.chartColorTemperature,
        chartColorRestingHr = d.chartColorRestingHr,
        chartColorSteps = d.chartColorSteps,
        chartColorSystolic = d.chartColorSystolic,
        chartColorDiastolic = d.chartColorDiastolic,
        chartColorSleepDeep = d.chartColorSleepDeep,
        chartColorSleepLight = d.chartColorSleepLight,
        chartColorSleepRem = d.chartColorSleepRem,
        chartColorSleepAwake = d.chartColorSleepAwake,
        )
    }

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
        actionRowPadDp = actionRowPadDp.coerceIn(0, ThemePrefs.ACTION_ROW_PAD_MAX),
        actionLabelSizeSp = actionLabelSizeSp.coerceIn(ThemePrefs.ACTION_LABEL_MIN, ThemePrefs.ACTION_LABEL_MAX),
        actionValueSizeSp = actionValueSizeSp.coerceIn(ThemePrefs.ACTION_VALUE_MIN, ThemePrefs.ACTION_VALUE_MAX),
        varRowPadDp = varRowPadDp.coerceIn(0, ThemePrefs.ACTION_ROW_PAD_MAX),
        varNameSizeSp = varNameSizeSp.coerceIn(ThemePrefs.ACTION_VALUE_MIN, ThemePrefs.ACTION_VALUE_MAX),
        varValueSizeSp = varValueSizeSp.coerceIn(ThemePrefs.ACTION_VALUE_MIN, ThemePrefs.ACTION_VALUE_MAX),
        actionLabelFrameWidthDp = actionLabelFrameWidthDp.coerceIn(0, ThemePrefs.BORDER_WIDTH_MAX),
        actionBorderWidthDp = actionBorderWidthDp.coerceIn(0, ThemePrefs.BORDER_WIDTH_MAX),
        groupHeaderVPadDp = groupHeaderVPadDp.coerceIn(0, ThemePrefs.GROUP_HEADER_VPAD_MAX),
        groupHeaderBorderWidthDp = groupHeaderBorderWidthDp.coerceIn(0, ThemePrefs.GROUP_HEADER_BORDER_MAX),
        monitorRowPadDp = monitorRowPadDp.coerceIn(0, ThemePrefs.MONITOR_PAD_MAX),
        bubbleIconSizeDp = bubbleIconSizeDp.coerceIn(ThemePrefs.BUBBLE_ICON_MIN, ThemePrefs.BUBBLE_ICON_MAX),
        bubbleIconCornerDp = bubbleIconCornerDp.coerceIn(0, ThemePrefs.BUBBLE_ICON_CORNER_MAX),
        bubbleLabelSizeSp = bubbleLabelSizeSp.coerceIn(ThemePrefs.BUBBLE_LABEL_MIN, ThemePrefs.BUBBLE_LABEL_MAX),
        bubbleLabelWeight = bubbleLabelWeight.coerceIn(ThemePrefs.FONT_WEIGHT_MIN, ThemePrefs.FONT_WEIGHT_MAX),
        flashTapBehavior = flashTapBehavior.takeIf { it in ThemePrefs.FLASH_BEHAVIORS } ?: ThemePrefs.DEFAULT.flashTapBehavior,
        flashLongTapBehavior = flashLongTapBehavior.takeIf { it in ThemePrefs.FLASH_BEHAVIORS } ?: ThemePrefs.DEFAULT.flashLongTapBehavior,
        pickerFontSizeSp = pickerFontSizeSp.coerceIn(ThemePrefs.PICKER_FONT_MIN, ThemePrefs.PICKER_FONT_MAX),
        chartPreviewHeightDp = chartPreviewHeightDp.coerceIn(ThemePrefs.CHART_PREVIEW_H_MIN, ThemePrefs.CHART_PREVIEW_H_MAX),
        chartDetailHeightDp = chartDetailHeightDp.coerceIn(ThemePrefs.CHART_DETAIL_H_MIN, ThemePrefs.CHART_DETAIL_H_MAX),
        chartCardGapDp = chartCardGapDp.coerceIn(0, ThemePrefs.CHART_GAP_MAX),
        chartAxisTextSp = chartAxisTextSp.coerceIn(ThemePrefs.CHART_AXIS_SP_MIN, ThemePrefs.CHART_AXIS_SP_MAX),
        chartHeadlineSp = chartHeadlineSp.coerceIn(ThemePrefs.CHART_HEADLINE_MIN, ThemePrefs.CHART_HEADLINE_MAX),
        chartLineWidthDp = chartLineWidthDp.coerceIn(1, ThemePrefs.CHART_LINE_MAX),
        chartDotSizeDp = chartDotSizeDp.coerceIn(0, ThemePrefs.CHART_DOT_MAX),
        chartCapsuleWidthDp = chartCapsuleWidthDp.coerceIn(2, ThemePrefs.CHART_MARK_W_MAX),
        chartBarWidthDp = chartBarWidthDp.coerceIn(1, ThemePrefs.CHART_MARK_W_MAX),
        chartDumbbellWidthDp = chartDumbbellWidthDp.coerceIn(2, ThemePrefs.CHART_MARK_W_MAX),
        chartHypnogramBandPct = chartHypnogramBandPct.coerceIn(10, 100),
        chartCornerRadiusDp = chartCornerRadiusDp.coerceIn(0, ThemePrefs.CHART_CORNER_MAX),
        chartGridOpacityPct = chartGridOpacityPct.coerceIn(0, 100),
        chartFillOpacityPct = chartFillOpacityPct.coerceIn(0, 100),
        chartGlowOpacityPct = chartGlowOpacityPct.coerceIn(0, 100),
        chartGapTintPct = chartGapTintPct.coerceIn(0, 100),
        chartDefaultSpanHours = chartDefaultSpanHours.coerceIn(1, ThemePrefs.CHART_SPAN_MAX),
        chartCurveMode = chartCurveMode.takeIf { it in ThemePrefs.CHART_CURVES } ?: ThemePrefs.DEFAULT.chartCurveMode,
        pickerRowPadDp = pickerRowPadDp.coerceIn(0, ThemePrefs.PICKER_PAD_MAX),
        pickerIndentDp = pickerIndentDp.coerceIn(0, ThemePrefs.PICKER_INDENT_MAX),
        pickerGroupCornerDp = pickerGroupCornerDp.coerceIn(0, ThemePrefs.PICKER_CORNER_MAX),
        pickerGroupBorderDp = pickerGroupBorderDp.coerceIn(0, ThemePrefs.PICKER_BORDER_MAX),
        importHeaderSp = importHeaderSp.coerceIn(ThemePrefs.IMPORT_TEXT_MIN, ThemePrefs.IMPORT_TEXT_MAX),
        importSectionSp = importSectionSp.coerceIn(ThemePrefs.IMPORT_TEXT_MIN, ThemePrefs.IMPORT_TEXT_MAX),
        importItemSp = importItemSp.coerceIn(ThemePrefs.IMPORT_TEXT_MIN, ThemePrefs.IMPORT_TEXT_MAX),
        importWarnSp = importWarnSp.coerceIn(ThemePrefs.IMPORT_TEXT_MIN, ThemePrefs.IMPORT_TEXT_MAX),
        importRowPadDp = importRowPadDp.coerceIn(0, ThemePrefs.IMPORT_ROW_PAD_MAX),
        ovalBarBorderWidthDp = ovalBarBorderWidthDp.coerceIn(0, ThemePrefs.OVAL_BAR_BORDER_MAX),
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
            actionRowPadDp = prefs.getInt(K_ACTION_ROW_PAD, d.actionRowPadDp),
            actionLabelSizeSp = prefs.getInt(K_ACTION_LABEL_SIZE, d.actionLabelSizeSp),
            actionValueSizeSp = prefs.getInt(K_ACTION_VALUE_SIZE, d.actionValueSizeSp),
            actionNameColor = prefs.getInt(K_ACTION_NAME_COLOR, d.actionNameColor),
            actionValueColor = prefs.getInt(K_ACTION_VALUE_COLOR, d.actionValueColor),
            actionLabelFrameColor = prefs.getInt(K_ACTION_LABEL_FRAME_COLOR, d.actionLabelFrameColor),
            actionLabelFrameWidthDp = prefs.getInt(K_ACTION_LABEL_FRAME_WIDTH, d.actionLabelFrameWidthDp),
            actionBorderColor = prefs.getInt(K_ACTION_BORDER_COLOR, d.actionBorderColor),
            actionBorderWidthDp = prefs.getInt(K_ACTION_BORDER_WIDTH, d.actionBorderWidthDp),
            selectionColor = prefs.getInt(K_SELECTION_COLOR, d.selectionColor),
            varRowPadDp = prefs.getInt(K_VAR_ROW_PAD, d.varRowPadDp),
            varNameColor = prefs.getInt(K_VAR_NAME_COLOR, d.varNameColor),
            varValueColor = prefs.getInt(K_VAR_VALUE_COLOR, d.varValueColor),
            varNameSizeSp = prefs.getInt(K_VAR_NAME_SIZE, d.varNameSizeSp),
            varValueSizeSp = prefs.getInt(K_VAR_VALUE_SIZE, d.varValueSizeSp),
            groupHeaderVPadDp = prefs.getInt(K_GROUP_HEADER_VPAD, d.groupHeaderVPadDp),
            groupHeaderColor = prefs.getInt(K_GROUP_HEADER_COLOR, d.groupHeaderColor),
            groupHeaderBorderColor = prefs.getInt(K_GROUP_HEADER_BORDER_COLOR, d.groupHeaderBorderColor),
            groupHeaderBorderWidthDp = prefs.getInt(K_GROUP_HEADER_BORDER_WIDTH, d.groupHeaderBorderWidthDp),
            monitorRowPadDp = prefs.getInt(K_MONITOR_PAD, d.monitorRowPadDp),
            ovalBarBorderWidthDp = prefs.getInt(K_OVAL_BAR_BORDER_WIDTH, d.ovalBarBorderWidthDp),
            ovalBarBorderColor = prefs.getInt(K_OVAL_BAR_BORDER_COLOR, d.ovalBarBorderColor),
            bubbleIconSizeDp = prefs.getInt(K_BUBBLE_ICON_SIZE, d.bubbleIconSizeDp),
            bubbleIconCornerDp = prefs.getInt(K_BUBBLE_ICON_CORNER, d.bubbleIconCornerDp),
            bubbleLabelSizeSp = prefs.getInt(K_BUBBLE_LABEL_SIZE, d.bubbleLabelSizeSp),
            bubbleLabelWeight = prefs.getInt(K_BUBBLE_LABEL_WEIGHT, d.bubbleLabelWeight),
            bubbleFontFileName = prefs.getString(K_BUBBLE_FONT, d.bubbleFontFileName) ?: d.bubbleFontFileName,
            flashTapBehavior = prefs.getString(K_FLASH_TAP, d.flashTapBehavior) ?: d.flashTapBehavior,
            flashLongTapBehavior = prefs.getString(K_FLASH_LONG_TAP, d.flashLongTapBehavior) ?: d.flashLongTapBehavior,
            flashKillTaskName = prefs.getString(K_FLASH_KILL_TASK, d.flashKillTaskName) ?: d.flashKillTaskName,
            flashKillAllTaskName = prefs.getString(K_FLASH_KILL_ALL_TASK, d.flashKillAllTaskName) ?: d.flashKillAllTaskName,
            pickerFontSizeSp = prefs.getInt(K_PICKER_FONT_SIZE, d.pickerFontSizeSp),
            chartPreviewHeightDp = prefs.getInt(K_CHART_PREVIEW_H, d.chartPreviewHeightDp),
            chartDetailHeightDp = prefs.getInt(K_CHART_DETAIL_H, d.chartDetailHeightDp),
            chartCardGapDp = prefs.getInt(K_CHART_CARD_GAP, d.chartCardGapDp),
            chartAxisTextSp = prefs.getInt(K_CHART_AXIS_SP, d.chartAxisTextSp),
            chartHeadlineSp = prefs.getInt(K_CHART_HEADLINE_SP, d.chartHeadlineSp),
            chartLineWidthDp = prefs.getInt(K_CHART_LINE_W, d.chartLineWidthDp),
            chartDotSizeDp = prefs.getInt(K_CHART_DOT, d.chartDotSizeDp),
            chartCapsuleWidthDp = prefs.getInt(K_CHART_CAPSULE_W, d.chartCapsuleWidthDp),
            chartBarWidthDp = prefs.getInt(K_CHART_BAR_W, d.chartBarWidthDp),
            chartDumbbellWidthDp = prefs.getInt(K_CHART_DUMBBELL_W, d.chartDumbbellWidthDp),
            chartHypnogramBandPct = prefs.getInt(K_CHART_HYPNO_PCT, d.chartHypnogramBandPct),
            chartCornerRadiusDp = prefs.getInt(K_CHART_CORNER, d.chartCornerRadiusDp),
            chartGridOpacityPct = prefs.getInt(K_CHART_GRID_OP, d.chartGridOpacityPct),
            chartFillOpacityPct = prefs.getInt(K_CHART_FILL_OP, d.chartFillOpacityPct),
            chartGlowOpacityPct = prefs.getInt(K_CHART_GLOW_OP, d.chartGlowOpacityPct),
            chartGapTintPct = prefs.getInt(K_CHART_GAP_OP, d.chartGapTintPct),
            chartAxisTextColor = prefs.getInt(K_CHART_AXIS_COLOR, d.chartAxisTextColor),
            chartGridColor = prefs.getInt(K_CHART_GRID_COLOR, d.chartGridColor),
            ocrHighAccuracy = prefs.getBoolean(K_OCR_HIGH_ACCURACY, d.ocrHighAccuracy),
            ocrDetectionLongSide = prefs.getInt(K_OCR_LONG_SIDE, d.ocrDetectionLongSide),
            ocrBinarisePercent = prefs.getInt(K_OCR_BINARISE, d.ocrBinarisePercent),
            ocrBoxScorePercent = prefs.getInt(K_OCR_BOX_SCORE, d.ocrBoxScorePercent),
            ocrUnclipTenths = prefs.getInt(K_OCR_UNCLIP, d.ocrUnclipTenths),
            ocrModelDet = prefs.getString(K_OCR_MODEL_DET, d.ocrModelDet) ?: d.ocrModelDet,
            ocrModelJpnServer = prefs.getString(K_OCR_MODEL_JPN_SERVER, d.ocrModelJpnServer) ?: d.ocrModelJpnServer,
            ocrModelJpnMobile = prefs.getString(K_OCR_MODEL_JPN_MOBILE, d.ocrModelJpnMobile) ?: d.ocrModelJpnMobile,
            ocrModelLatin = prefs.getString(K_OCR_MODEL_LATIN, d.ocrModelLatin) ?: d.ocrModelLatin,
            ocrModelEslav = prefs.getString(K_OCR_MODEL_ESLAV, d.ocrModelEslav) ?: d.ocrModelEslav,
            chartShowGrid = prefs.getBoolean(K_CHART_SHOW_GRID, d.chartShowGrid),
            chartShowDots = prefs.getBoolean(K_CHART_SHOW_DOTS, d.chartShowDots),
            chartShowRejected = prefs.getBoolean(K_CHART_SHOW_REJECTED, d.chartShowRejected),
            chartShowGaps = prefs.getBoolean(K_CHART_SHOW_GAPS, d.chartShowGaps),
            chartDefaultSpanHours = prefs.getInt(K_CHART_SPAN_H, d.chartDefaultSpanHours),
            chartCurveMode = prefs.getString(K_CHART_CURVE, d.chartCurveMode) ?: d.chartCurveMode,
            chartColorHeartRate = prefs.getInt(K_CHART_C_HR, d.chartColorHeartRate),
            chartColorBandState = prefs.getInt(K_CHART_C_STATE, d.chartColorBandState),
            chartColorSpo2 = prefs.getInt(K_CHART_C_SPO2, d.chartColorSpo2),
            chartColorTemperature = prefs.getInt(K_CHART_C_TEMP, d.chartColorTemperature),
            chartColorRestingHr = prefs.getInt(K_CHART_C_RHR, d.chartColorRestingHr),
            chartColorSteps = prefs.getInt(K_CHART_C_STEPS, d.chartColorSteps),
            chartColorSystolic = prefs.getInt(K_CHART_C_SYS, d.chartColorSystolic),
            chartColorDiastolic = prefs.getInt(K_CHART_C_DIA, d.chartColorDiastolic),
            chartColorSleepDeep = prefs.getInt(K_CHART_C_DEEP, d.chartColorSleepDeep),
            chartColorSleepLight = prefs.getInt(K_CHART_C_LIGHT, d.chartColorSleepLight),
            chartColorSleepRem = prefs.getInt(K_CHART_C_REM, d.chartColorSleepRem),
            chartColorSleepAwake = prefs.getInt(K_CHART_C_AWAKE, d.chartColorSleepAwake),
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
            putInt(K_ACTION_ROW_PAD, p.actionRowPadDp)
            putInt(K_ACTION_LABEL_SIZE, p.actionLabelSizeSp)
            putInt(K_ACTION_VALUE_SIZE, p.actionValueSizeSp)
            putInt(K_ACTION_NAME_COLOR, p.actionNameColor)
            putInt(K_ACTION_VALUE_COLOR, p.actionValueColor)
            putInt(K_ACTION_LABEL_FRAME_COLOR, p.actionLabelFrameColor)
            putInt(K_ACTION_LABEL_FRAME_WIDTH, p.actionLabelFrameWidthDp)
            putInt(K_ACTION_BORDER_COLOR, p.actionBorderColor)
            putInt(K_ACTION_BORDER_WIDTH, p.actionBorderWidthDp)
            putInt(K_SELECTION_COLOR, p.selectionColor)
            putInt(K_VAR_ROW_PAD, p.varRowPadDp)
            putInt(K_VAR_NAME_COLOR, p.varNameColor)
            putInt(K_VAR_VALUE_COLOR, p.varValueColor)
            putInt(K_VAR_NAME_SIZE, p.varNameSizeSp)
            putInt(K_VAR_VALUE_SIZE, p.varValueSizeSp)
            putInt(K_GROUP_HEADER_VPAD, p.groupHeaderVPadDp)
            putInt(K_GROUP_HEADER_COLOR, p.groupHeaderColor)
            putInt(K_GROUP_HEADER_BORDER_COLOR, p.groupHeaderBorderColor)
            putInt(K_GROUP_HEADER_BORDER_WIDTH, p.groupHeaderBorderWidthDp)
            putInt(K_MONITOR_PAD, p.monitorRowPadDp)
            putInt(K_OVAL_BAR_BORDER_WIDTH, p.ovalBarBorderWidthDp)
            putInt(K_OVAL_BAR_BORDER_COLOR, p.ovalBarBorderColor)
            putInt(K_BUBBLE_ICON_SIZE, p.bubbleIconSizeDp)
            putInt(K_BUBBLE_ICON_CORNER, p.bubbleIconCornerDp)
            putInt(K_BUBBLE_LABEL_SIZE, p.bubbleLabelSizeSp)
            putInt(K_BUBBLE_LABEL_WEIGHT, p.bubbleLabelWeight)
            putString(K_BUBBLE_FONT, p.bubbleFontFileName)
            putString(K_FLASH_TAP, p.flashTapBehavior)
            putString(K_FLASH_LONG_TAP, p.flashLongTapBehavior)
            putString(K_FLASH_KILL_TASK, p.flashKillTaskName)
            putString(K_FLASH_KILL_ALL_TASK, p.flashKillAllTaskName)
            putInt(K_PICKER_FONT_SIZE, p.pickerFontSizeSp)
            putInt(K_CHART_PREVIEW_H, p.chartPreviewHeightDp)
            putInt(K_CHART_DETAIL_H, p.chartDetailHeightDp)
            putInt(K_CHART_CARD_GAP, p.chartCardGapDp)
            putInt(K_CHART_AXIS_SP, p.chartAxisTextSp)
            putInt(K_CHART_HEADLINE_SP, p.chartHeadlineSp)
            putInt(K_CHART_LINE_W, p.chartLineWidthDp)
            putInt(K_CHART_DOT, p.chartDotSizeDp)
            putInt(K_CHART_CAPSULE_W, p.chartCapsuleWidthDp)
            putInt(K_CHART_BAR_W, p.chartBarWidthDp)
            putInt(K_CHART_DUMBBELL_W, p.chartDumbbellWidthDp)
            putInt(K_CHART_HYPNO_PCT, p.chartHypnogramBandPct)
            putInt(K_CHART_CORNER, p.chartCornerRadiusDp)
            putInt(K_CHART_GRID_OP, p.chartGridOpacityPct)
            putInt(K_CHART_FILL_OP, p.chartFillOpacityPct)
            putInt(K_CHART_GLOW_OP, p.chartGlowOpacityPct)
            putInt(K_CHART_GAP_OP, p.chartGapTintPct)
            putInt(K_CHART_AXIS_COLOR, p.chartAxisTextColor)
            putInt(K_CHART_GRID_COLOR, p.chartGridColor)
            putBoolean(K_OCR_HIGH_ACCURACY, p.ocrHighAccuracy)
            putInt(K_OCR_LONG_SIDE, p.ocrDetectionLongSide)
            putInt(K_OCR_BINARISE, p.ocrBinarisePercent)
            putInt(K_OCR_BOX_SCORE, p.ocrBoxScorePercent)
            putInt(K_OCR_UNCLIP, p.ocrUnclipTenths)
            putString(K_OCR_MODEL_DET, p.ocrModelDet)
            putString(K_OCR_MODEL_JPN_SERVER, p.ocrModelJpnServer)
            putString(K_OCR_MODEL_JPN_MOBILE, p.ocrModelJpnMobile)
            putString(K_OCR_MODEL_LATIN, p.ocrModelLatin)
            putString(K_OCR_MODEL_ESLAV, p.ocrModelEslav)
            putBoolean(K_CHART_SHOW_GRID, p.chartShowGrid)
            putBoolean(K_CHART_SHOW_DOTS, p.chartShowDots)
            putBoolean(K_CHART_SHOW_REJECTED, p.chartShowRejected)
            putBoolean(K_CHART_SHOW_GAPS, p.chartShowGaps)
            putInt(K_CHART_SPAN_H, p.chartDefaultSpanHours)
            putString(K_CHART_CURVE, p.chartCurveMode)
            putInt(K_CHART_C_HR, p.chartColorHeartRate)
            putInt(K_CHART_C_STATE, p.chartColorBandState)
            putInt(K_CHART_C_SPO2, p.chartColorSpo2)
            putInt(K_CHART_C_TEMP, p.chartColorTemperature)
            putInt(K_CHART_C_RHR, p.chartColorRestingHr)
            putInt(K_CHART_C_STEPS, p.chartColorSteps)
            putInt(K_CHART_C_SYS, p.chartColorSystolic)
            putInt(K_CHART_C_DIA, p.chartColorDiastolic)
            putInt(K_CHART_C_DEEP, p.chartColorSleepDeep)
            putInt(K_CHART_C_LIGHT, p.chartColorSleepLight)
            putInt(K_CHART_C_REM, p.chartColorSleepRem)
            putInt(K_CHART_C_AWAKE, p.chartColorSleepAwake)
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

    /** The Compose FontFamily for a selection, or null for the platform default. Cached by name.
     *  Accepts an imported .ttf/.otf filename, or a built-in family keyword (serif/mincho/明朝 → Noto Serif
     *  CJK = Minchō; sans/gothic/ゴシック → the gothic default) — the same keywords [typeface] honours, so a
     *  scene can pick Minchō without importing a font. */
    fun fontFamily(fileName: String): FontFamily? = when {
        fileName.isEmpty() -> null
        fileName == MONOSPACE -> FontFamily.Monospace
        else -> fontFamilyCache.getOrPut(fileName) {
            when (fileName.trim().lowercase()) {
                "serif", "mincho", "minchō", "明朝" ->
                    FontFamily(androidx.compose.ui.text.font.Typeface(android.graphics.Typeface.SERIF))
                "sans", "sans-serif", "gothic", "ゴシック" ->
                    FontFamily(androidx.compose.ui.text.font.Typeface(android.graphics.Typeface.SANS_SERIF))
                else -> runCatching {
                    val file = File(fontsDir(), fileName)
                    if (file.exists()) FontFamily(Font(file)) else null
                }.getOrNull()
            }
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
