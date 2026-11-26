package com.sysadmindoc.ruledeck.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// The role table of the black-yellow theme (白い熊, 2026-11-21: “nothing hard-coded, everything
// settable via UI settings … for all items separately, in appropriate sections”).
//
// Every item 白い熊 can see as a separate thing has a role here: a button's frame and its label, a
// dialog's ground and its title, a switch that is on and one that is off, each icon colour of a menu.
// A role's DEFAULT is derived from the page's foundation colours (background, text, accent, …) or
// from another role, so a fresh install is black and yellow and a changed accent still carries
// through everything that was not set apart; a role that 白い熊 sets is stored as an override and
// stops following. The two primaries themselves are written once, in [ThemePrefs]'s companion.
//
// The mixed way (白い熊, 2026-11-22, as for 臨電話 and 考直): this fork is rebased onto every upstream
// commit, so upstream's screens keep naming a Material scheme entry — and each scheme entry is a
// role of its own here ([RoleSection.SCREENS]) instead of all of them sharing the accent. What is
// ours — pills, dialogs, switches, menus, rows, the UI page, the fork's own screens, the overlays —
// reads the per-item roles.

/** Where a role's swatch row sits on the UI page: the section of the item it colours. */
enum class RoleSection(val title: String) {
    TEXT("Text"),
    BUTTONS("Buttons"),
    DIALOGS("Dialogs"),
    SWITCHES("Switches"),
    MARKS("Check boxes and radio buttons"),
    MENUS("Menus"),
    LISTS("Lists and pickers"),
    PAGE("This page"),
    BARS("Bars and tabs"),
    SCENES("Scenes"),
    WIDGETS("Widgets and notifications"),
    PROGRESS("Progress panel"),
    CHARTS("「健康」 screens"),
    ISLANDS("「言語島」 screens"),
    SCREENS("Screens — shared entries"),
}

/** What a role's default is computed from: the foundation colours and the roles themselves. */
class RoleScope internal constructor(private val prefs: ThemePrefs) {
    val background get() = prefs.background
    val surface get() = prefs.surface
    val text get() = prefs.text
    val textSecondary get() = prefs.textSecondary
    val accent get() = prefs.accent
    val border get() = prefs.border

    /** Another role, as it stands now (its override, or its own default). */
    operator fun get(role: UiRole): Int = UiRoles.argb(role, prefs)

    /** [fraction] of the way from [from] to [to], opaque — a darker accent, never a faded one. */
    fun mix(from: Int, to: Int, fraction: Float): Int {
        fun channel(shift: Int): Int {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            return (a + (b - a) * fraction).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    /** Black or white, whichever reads on [argb]. */
    fun on(argb: Int): Int {
        val luminance = 0.299f * ((argb shr 16) and 0xFF) + 0.587f * ((argb shr 8) and 0xFF) + 0.114f * (argb and 0xFF)
        return if (luminance > 127.5f) ThemePrefs.BLACK else 0xFFFFFFFF.toInt()
    }
}

/**
 * One settable colour. [key] is what is stored and exported, so it never changes; [label] and [note]
 * are what the UI page shows.
 */
enum class UiRole(
    val key: String,
    val section: RoleSection,
    val label: String,
    val note: String,
    internal val default: RoleScope.() -> Int,
) {
    // ---- text
    DIM_GOLD("dim_gold", RoleSection.TEXT, "Explanations", "The description under a setting, and the rule at its left", { ThemePrefs.DIM_GOLD }),
    HEADING("heading", RoleSection.TEXT, "Section headings", "The heading of a section on a settings page", { accent }),

    // ---- buttons
    BUTTON_MAIN_FRAME("button_main_frame", RoleSection.BUTTONS, "Main button · frame", "The thick frame of a block's main action", { accent }),
    BUTTON_MAIN_LABEL("button_main_label", RoleSection.BUTTONS, "Main button · label", "The bold label of a block's main action", { accent }),
    BUTTON_FRAME("button_frame", RoleSection.BUTTONS, "Button · frame", "The thin frame of every other action", { accent }),
    BUTTON_LABEL("button_label", RoleSection.BUTTONS, "Button · label", "The label of every other action", { accent }),
    DELETE("delete", RoleSection.BUTTONS, "Delete", "Frame and label of an action that deletes, and the icon of such a menu row", { ThemePrefs.DELETE_RED }),
    FAB_RING("fab_ring", RoleSection.BUTTONS, "Floating button · ring", "The ring of the round + button", { accent }),
    FAB_GLYPH("fab_glyph", RoleSection.BUTTONS, "Floating button · glyph", "The + inside the round button", { accent }),
    FAB_GROUND("fab_ground", RoleSection.BUTTONS, "Floating button · ground", "What shows inside the ring", { background }),

    // ---- dialogs
    DIALOG_GROUND("dialog_ground", RoleSection.DIALOGS, "Dialog · ground", "The panel of every dialog", { background }),
    DIALOG_FRAME("dialog_frame", RoleSection.DIALOGS, "Dialog · frame", "The line round every dialog", { accent }),
    DIALOG_TITLE("dialog_title", RoleSection.DIALOGS, "Dialog · title", "A dialog's heading", { text }),
    DIALOG_BODY("dialog_body", RoleSection.DIALOGS, "Dialog · text", "A dialog's own text", { text }),
    DIALOG_ICON("dialog_icon", RoleSection.DIALOGS, "Dialog · icon", "The icon above a dialog's title", { accent }),

    // ---- switches
    SWITCH_ON_FRAME("switch_on_frame", RoleSection.SWITCHES, "On · outline", "The outline of a switch that is on", { accent }),
    SWITCH_ON_DOT("switch_on_dot", RoleSection.SWITCHES, "On · dot", "The dot of a switch that is on", { accent }),
    SWITCH_OFF_FRAME("switch_off_frame", RoleSection.SWITCHES, "Off · outline", "The outline of a switch that is off — an opaque darker yellow, not a faded one", { mix(background, accent, 0.55f) }),
    SWITCH_OFF_DOT("switch_off_dot", RoleSection.SWITCHES, "Off · dot", "The dot of a switch that is off", { this[SWITCH_OFF_FRAME] }),

    // ---- selection marks
    CHECK_ON("check_on", RoleSection.MARKS, "Check box · chosen", "The box of a chosen entry", { accent }),
    CHECK_MARK("check_mark", RoleSection.MARKS, "Check box · mark", "The tick inside a chosen box", { on(accent) }),
    CHECK_OFF("check_off", RoleSection.MARKS, "Check box · not chosen", "The empty box", { mix(background, accent, 0.55f) }),
    RADIO_ON("radio_on", RoleSection.MARKS, "Radio button · chosen", "The chosen one of a group", { accent }),
    RADIO_OFF("radio_off", RoleSection.MARKS, "Radio button · not chosen", "The others of a group", { mix(background, accent, 0.55f) }),

    // ---- menus
    MENU_GROUND("menu_ground", RoleSection.MENUS, "Menu · ground", "The panel of a popup menu", { background }),
    MENU_FRAME("menu_frame", RoleSection.MENUS, "Menu · frame", "The line round a popup menu", { accent }),
    MENU_ROW_FRAME("menu_row_frame", RoleSection.MENUS, "Menu row · frame", "The pill of an action row", { accent }),
    MENU_LABEL("menu_label", RoleSection.MENUS, "Menu row · label", "The words of a menu row", { accent }),
    MENU_DIVIDER("menu_divider", RoleSection.MENUS, "Menu · divider", "The line between two groups of rows", { mix(background, accent, 0.7f) }),
    ICON_CONNECT("icon_connect", RoleSection.MENUS, "Icon · run, start, connect", "Rows that run or start something, place a call, load", { ThemePrefs.HEAVY_GREEN }),
    ICON_MESSAGE("icon_message", RoleSection.MENUS, "Icon · write, edit", "Rows that write, rename, edit or reply", { ThemePrefs.HEAVY_BLUE }),
    ICON_CONTACT("icon_contact", RoleSection.MENUS, "Icon · person, group, project", "Rows about a person, a group or a project", { ThemePrefs.LIGHT_BLUE }),
    ICON_SHARE("icon_share", RoleSection.MENUS, "Icon · copy, share, export", "Rows that copy, move, share, export or import", { ThemePrefs.ORANGE }),
    ICON_VIEW("icon_view", RoleSection.MENUS, "Icon · view, open, settings", "Rows that view or open a page, or lead to settings", { accent }),
    ICON_BLOCK("icon_block", RoleSection.MENUS, "Icon · stop, disable, leave", "Rows that stop, disable, block, close or leave", { ThemePrefs.ROSE }),

    // ---- lists and pickers
    LIST_DIVIDER("list_divider", RoleSection.LISTS, "List · divider", "The line between two groups of a list or a picker", { mix(background, accent, 0.7f) }),
    LIST_ROW_FRAME("list_row_frame", RoleSection.LISTS, "Picker row · frame", "The frame of a row in a picker", { accent }),
    LIST_LABEL("list_label", RoleSection.LISTS, "List · label", "The first line of a list row", { text }),
    LIST_NOTE("list_note", RoleSection.LISTS, "List · second line", "The second line of a list row", { this[DIM_GOLD] }),
    LIST_SELECTED("list_selected", RoleSection.LISTS, "List · chosen mark", "The ✓ beside the chosen entry of a choice list", { accent }),
    BADGE_GROUND("badge_ground", RoleSection.LISTS, "Badge · ground", "The ground of a badge in a form or a list when it is given no colour", { mix(background, text, 0.5f) }),
    WARNING("warning", RoleSection.LISTS, "Warning", "A warning tag or note: needs setup, unsupported, a conflict", { 0xFFFF6B6B.toInt() }),

    // ---- this page
    PAGE_DIVIDER("page_divider", RoleSection.PAGE, "Section divider", "The thin line above each section", { mix(background, accent, 0.7f) }),
    PAGE_CHIP_FRAME("page_chip_frame", RoleSection.PAGE, "Index chip · frame", "The chips of the index at the top", { accent }),
    PAGE_CHIP_LABEL("page_chip_label", RoleSection.PAGE, "Index chip · label", "The words on the index chips", { accent }),
    PAGE_LABEL("page_label", RoleSection.PAGE, "Setting · label", "The bold label of a setting", { accent }),
    PAGE_VALUE("page_value", RoleSection.PAGE, "Setting · value", "The value shown at a setting's right", { text }),

    // The icon of a section's heading and of its chip says what KIND of thing the section is. These
    // seven are not the menu icon roles: a menu's green means "connect", here it means "a control".
    SECTION_BASE("section_base", RoleSection.PAGE, "Section icon · the base", "Sections about what the app is, and its settings", { accent }),
    SECTION_CONTROLS("section_controls", RoleSection.PAGE, "Section icon · controls", "Sections about what you operate: buttons, switches, marks", { ThemePrefs.HEAVY_GREEN }),
    SECTION_WORDS("section_words", RoleSection.PAGE, "Section icon · words", "Sections about what is written and read: text, fonts, editors", { ThemePrefs.LIGHT_BLUE }),
    SECTION_FRAME("section_frame", RoleSection.PAGE, "Section icon · the frame", "Sections about the frame and its boxes: lists, bars, borders", { ThemePrefs.HEAVY_BLUE }),
    SECTION_REACH("section_reach", RoleSection.PAGE, "Section icon · reaching out", "Sections where the app asks, offers or hands over: dialogs, menus, export", { ThemePrefs.ORANGE }),
    SECTION_WARNING("section_warning", RoleSection.PAGE, "Section icon · warning", "Sections that reset or wipe", { ThemePrefs.DELETE_RED }),
    SECTION_FLASH("section_flash", RoleSection.PAGE, "Section icon · a brief flash", "The one section about what flashes up and goes", { ThemePrefs.ROSE }),

    // ---- bars and tabs
    TAB_INK("tab_ink", RoleSection.BARS, "Tabs · ink", "Lines and labels of the paper tabs", { accent }),
    TAB_GROUND("tab_ground", RoleSection.BARS, "Tabs · ground", "The paper of the tabs", { background }),
    BAR_TITLE("bar_title", RoleSection.BARS, "Top bar · title", "The title in the top bar", { text }),
    BAR_ICON("bar_icon", RoleSection.BARS, "Top bar · icons", "The glyph buttons of the top bar", { accent }),

    // ---- scenes: what an element looks like when its scene gives it no colour
    SCENE_TEXT("scene_text", RoleSection.SCENES, "Scene · text", "Text of a scene element that sets no colour of its own", { text }),
    SCENE_GROUND("scene_ground", RoleSection.SCENES, "Scene · ground", "The ground of a scene that sets none", { background }),
    SCENE_PANEL("scene_panel", RoleSection.SCENES, "Scene · element ground", "The ground of an element that sets none", { surface }),
    SCENE_BUTTON_FRAME("scene_button_frame", RoleSection.SCENES, "Scene · button frame", "The pill of a scene button that sets no colour", { accent }),
    SCENE_BUTTON_LABEL("scene_button_label", RoleSection.SCENES, "Scene · button label", "The label of a scene button that sets no colour", { accent }),
    SCENE_BORDER("scene_border", RoleSection.SCENES, "Scene · border", "The border of a scene or an element that asks for one and names no colour", { border }),
    SCENE_SLIDER("scene_slider", RoleSection.SCENES, "Scene · slider fill", "The filled part of a slider that sets no colour", { accent }),
    SCENE_NOTE("scene_note", RoleSection.SCENES, "Scene · note", "The note shown for an element the overlay cannot draw", { this[DIM_GOLD] }),

    // ---- widgets and notifications
    NOTIFICATION_ACCENT("notification_accent", RoleSection.WIDGETS, "Notification · accent", "The colour Android gives this app's notifications", { accent }),
    WIDGET_GROUND("widget_ground", RoleSection.WIDGETS, "Task widget · ground", "The ground of the home-screen task widget", { background }),
    WIDGET_FRAME("widget_frame", RoleSection.WIDGETS, "Task widget · frame", "The frame of the home-screen task widget", { accent }),
    WIDGET_TEXT("widget_text", RoleSection.WIDGETS, "Task widget · text", "The task's name on the widget", { text }),
    WIDGET_NOTE("widget_note", RoleSection.WIDGETS, "Task widget · second line", "The smaller line on the widget", { this[DIM_GOLD] }),
    WIDGET_ICON("widget_icon", RoleSection.WIDGETS, "Task widget · icon", "The run icon on the widget", { accent }),

    // ---- the progress panel
    PROGRESS_GROUND("progress_ground", RoleSection.PROGRESS, "Panel · ground", "The ground of the progress panel", { background }),
    PROGRESS_FRAME("progress_frame", RoleSection.PROGRESS, "Panel · frame", "The frame of the progress panel", { accent }),
    PROGRESS_TITLE("progress_title", RoleSection.PROGRESS, "Panel · title", "The heading of the progress panel", { text }),
    PROGRESS_TEXT("progress_text", RoleSection.PROGRESS, "Panel · text", "Lines of the progress panel", { text }),
    PROGRESS_BAR("progress_bar", RoleSection.PROGRESS, "Panel · active row", "The wash behind the row that is running, drawn at one fifth strength", { accent }),

    PROGRESS_FAIL("progress_fail", RoleSection.PROGRESS, "Panel · failure", "A line of the panel that reports a failure", { this[WARNING] }),

    // ---- 健康: the chrome of the health screens. Series, grades and stages are data and are set under 「健康」 charts.
    ANNOTATION_INK("annotation_ink", RoleSection.CHARTS, "Annotation · ink", "Frame and words of everything 白い熊 wrote on a chart: notes, marks, sessions", { accent }),
    ANNOTATION_GROUND("annotation_ground", RoleSection.CHARTS, "Annotation · ground", "The ground an annotation brings with it, so that it reads on any colour", { background }),
    CHART_NOTE("chart_note", RoleSection.CHARTS, "Explanations", "The explanation ink of the health screens — a soft cyan, apart from every series colour", { 0xFF8FCBE8.toInt() }),
    CHART_TRACK("chart_track", RoleSection.CHARTS, "Tracks and quiet grids", "The empty track behind a bar and the quiet grid of a card, drawn faintly", { 0xFFFFFFFF.toInt() }),

    // ---- 言語島
    ISLAND_CHIP_GROUND("island_chip_ground", RoleSection.ISLANDS, "Language chip · ground", "The filled chip that marks the second language, and a count badge", { accent }),
    ISLAND_CHIP_LABEL("island_chip_label", RoleSection.ISLANDS, "Language chip · label", "The letters on that chip", { on(this[ISLAND_CHIP_GROUND]) }),
    ISLAND_TILE_FRAME("island_tile_frame", RoleSection.ISLANDS, "Board tile · frame", "The frame of an ordinary tile on the board; a wide tile takes the accent", { 0xFF8A8A85.toInt() }),

    // ---- screens: the Material scheme entries that upstream's screens name (the mixed way)
    SCHEME_PRIMARY("scheme_primary", RoleSection.SCREENS, "Primary", "Accent of upstream's screens: icons, marks, highlighted text", { accent }),
    SCHEME_ON_PRIMARY("scheme_on_primary", RoleSection.SCREENS, "On primary", "What is drawn on a primary-coloured ground", { on(this[SCHEME_PRIMARY]) }),
    SCHEME_PRIMARY_CONTAINER("scheme_primary_container", RoleSection.SCREENS, "Primary container", "A panel that stands for the accent", { surface }),
    SCHEME_ON_PRIMARY_CONTAINER("scheme_on_primary_container", RoleSection.SCREENS, "On primary container", "What is drawn on such a panel", { accent }),
    SCHEME_SECONDARY("scheme_secondary", RoleSection.SCREENS, "Secondary", "The second accent of upstream's screens", { accent }),
    SCHEME_ON_SECONDARY("scheme_on_secondary", RoleSection.SCREENS, "On secondary", "What is drawn on a secondary-coloured ground", { on(this[SCHEME_SECONDARY]) }),
    SCHEME_SECONDARY_CONTAINER("scheme_secondary_container", RoleSection.SCREENS, "Secondary container", "A panel that stands for the second accent", { surface }),
    SCHEME_ON_SECONDARY_CONTAINER("scheme_on_secondary_container", RoleSection.SCREENS, "On secondary container", "What is drawn on such a panel", { accent }),
    SCHEME_TERTIARY("scheme_tertiary", RoleSection.SCREENS, "Tertiary", "The third accent of upstream's screens", { accent }),
    SCHEME_ON_TERTIARY("scheme_on_tertiary", RoleSection.SCREENS, "On tertiary", "What is drawn on a tertiary-coloured ground", { on(this[SCHEME_TERTIARY]) }),
    SCHEME_TERTIARY_CONTAINER("scheme_tertiary_container", RoleSection.SCREENS, "Tertiary container", "A panel that stands for the third accent", { surface }),
    SCHEME_ON_TERTIARY_CONTAINER("scheme_on_tertiary_container", RoleSection.SCREENS, "On tertiary container", "What is drawn on such a panel", { accent }),
    SCHEME_ERROR("scheme_error", RoleSection.SCREENS, "Error", "Errors and failures on upstream's screens", { this[WARNING] }),
    SCHEME_ON_ERROR("scheme_on_error", RoleSection.SCREENS, "On error", "What is drawn on an error-coloured ground", { on(this[SCHEME_ERROR]) }),
    SCHEME_ERROR_CONTAINER("scheme_error_container", RoleSection.SCREENS, "Error container", "A panel that reports an error", { surface }),
    SCHEME_ON_ERROR_CONTAINER("scheme_on_error_container", RoleSection.SCREENS, "On error container", "What is drawn on such a panel", { this[SCHEME_ERROR] }),
    SCHEME_ON_BACKGROUND("scheme_on_background", RoleSection.SCREENS, "On background", "Text drawn straight on the background", { text }),
    SCHEME_ON_SURFACE("scheme_on_surface", RoleSection.SCREENS, "On surface", "Text on cards and panels", { text }),
    SCHEME_SURFACE_VARIANT("scheme_surface_variant", RoleSection.SCREENS, "Surface variant", "The ground of chips, fields and quiet panels", { surface }),
    SCHEME_ON_SURFACE_VARIANT("scheme_on_surface_variant", RoleSection.SCREENS, "On surface variant", "Second lines, hints and icon tints of upstream's screens", { text }),
    SCHEME_CONTAINER("scheme_container", RoleSection.SCREENS, "Raised panels", "The ground of popups, sheets and other raised panels", { background }),
    SCHEME_INVERSE_SURFACE("scheme_inverse_surface", RoleSection.SCREENS, "Inverse surface", "The ground of a snackbar", { text }),
    SCHEME_INVERSE_ON_SURFACE("scheme_inverse_on_surface", RoleSection.SCREENS, "Inverse on surface", "Text on a snackbar", { background }),
    SCHEME_OUTLINE("scheme_outline", RoleSection.SCREENS, "Outline", "Frames of fields and cards", { border }),
    SCHEME_OUTLINE_VARIANT("scheme_outline_variant", RoleSection.SCREENS, "Outline variant", "Quiet dividers and frames", { border }),
    SCHEME_SCRIM("scheme_scrim", RoleSection.SCREENS, "Scrim", "The shade behind a dialog", { ThemePrefs.BLACK }),
    ;

    companion object {
        private val byKey = entries.associateBy { it.key }
        fun byKey(key: String): UiRole? = byKey[key]
    }
}

/** Reading and writing roles. Colours are packed ARGB ints, as everywhere in [ThemePrefs]. */
object UiRoles {
    /** [role] as [prefs] has it: its override, or its default. */
    fun argb(role: UiRole, prefs: ThemePrefs): Int =
        prefs.roleOverrides[role.key] ?: role.default(RoleScope(prefs))

    /** [role] as it stands now — for code that is not a composable (overlays, widgets, notifications). */
    fun argb(role: UiRole): Int = argb(role, ThemeStore.state.value)

    /** What [role] is when nothing is set for it. */
    fun defaultArgb(role: UiRole, prefs: ThemePrefs = ThemeStore.state.value): Int =
        role.default(RoleScope(prefs.copy(roleOverrides = prefs.roleOverrides - role.key)))

    fun isSet(role: UiRole): Boolean = role.key in ThemeStore.state.value.roleOverrides

    fun set(role: UiRole, argb: Int) = ThemeStore.update { it.copy(roleOverrides = it.roleOverrides + (role.key to argb)) }

    /** Back to its default, following the foundation colours again. */
    fun reset(role: UiRole) = ThemeStore.update { it.copy(roleOverrides = it.roleOverrides - role.key) }

    /** Every role back to its default. The foundation colours and everything else on the page stay. */
    fun resetAll() = ThemeStore.update { it.copy(roleOverrides = emptyMap()) }

    internal fun encode(overrides: Map<String, Int>): String =
        overrides.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${Integer.toHexString(it.value)}" }

    internal fun decode(stored: String): Map<String, Int> = stored.split(';').mapNotNull { pair ->
        val key = pair.substringBefore('=', "").trim()
        val value = pair.substringAfter('=', "").trim().toLongOrNull(16)?.toInt()
        if (key.isEmpty() || value == null || UiRole.byKey(key) == null) null else key to value
    }.toMap()
}

/** The roles resolved against one [ThemePrefs]; what composables read through [role]. */
class RolePalette(private val prefs: ThemePrefs) {
    private val cache = arrayOfNulls<Color>(UiRole.entries.size)

    operator fun get(role: UiRole): Color =
        cache[role.ordinal] ?: Color(UiRoles.argb(role, prefs)).also { cache[role.ordinal] = it }
}

val LocalRolePalette = staticCompositionLocalOf { RolePalette(ThemePrefs.DEFAULT) }

/** [role]'s colour inside the app's theme. */
@Composable
@ReadOnlyComposable
fun role(role: UiRole): Color = LocalRolePalette.current[role]
