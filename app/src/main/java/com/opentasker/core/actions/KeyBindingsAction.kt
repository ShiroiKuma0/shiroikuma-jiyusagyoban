package com.opentasker.core.actions

import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.contexts.HardwareKeyContextEvents
import com.opentasker.core.contexts.contextConfigSummary
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile

// ---------------------------------------------------------------------------------------------
// key.bindings — what the physical keys are mapped to.
//
// The 物理鍵 project's key map is not a table anywhere: it is spread across one profile per gesture,
// each an EVENT context narrowed by `key` + `press`, several of them further qualified by a STATE
// context (Vol-Down short does one thing while recording and another while not). Reading it off the
// Profiles tab means opening eight profiles one at a time. This collects them into one sheet, the
// physical-key sibling of `scene.gestures`.
// ---------------------------------------------------------------------------------------------

/** Hardware keys, in listing order, with the heading to print for each in either language. */
private val KEY_LABELS: List<Triple<String, String, String>> = listOf(
    Triple(HardwareKeyContextEvents.KEY_VOLUME_UP, "音量上キー", "Volume up"),
    Triple(HardwareKeyContextEvents.KEY_VOLUME_DOWN, "音量下キー", "Volume down"),
    Triple(HardwareKeyContextEvents.KEY_POWER, "電源キー", "Power"),
)

/**
 * Press types, in listing order: the one-press gestures first — a press, then the same press held —
 * and the multi-taps after them (白い熊, 2026-10-11).
 *
 * `long_repeat` is not a double press with the second held: it is the key STILL held after `long`
 * fired, repeating until release (the camera zoom). Hence its place right after `long`, and a name
 * that says so instead of the raw constant.
 */
private val PRESS_LABELS: List<Triple<String, String, String>> = listOf(
    Triple(HardwareKeyContextEvents.PRESS_SHORT, "短押し", "Press"),
    Triple(HardwareKeyContextEvents.PRESS_LONG, "長押し", "Long press"),
    Triple(HardwareKeyContextEvents.PRESS_LONG_REPEAT, "長押し継続（反復）", "Long press, held (repeats)"),
    Triple(HardwareKeyContextEvents.PRESS_DOUBLE, "二度押し", "Double press"),
    Triple(HardwareKeyContextEvents.PRESS_TRIPLE, "三度押し", "Triple press"),
)

/** The screen states a key's bindings are split by, in listing order. */
private enum class Screen { ON, OFF }

private fun Screen.label(lang: SheetLang): String = when (this) {
    Screen.ON -> lang.of("画面点灯時", "Screen on")
    Screen.OFF -> lang.of("画面消灯時", "Screen off")
}

private val SCREEN_PREDICATE_RE = Regex("^\\s*screen\\s*=\\s*(on|off)\\s*$", RegexOption.IGNORE_CASE)

/** The screen state a STATE context demands, or null if it is about something else. */
private fun ContextSpec.screenState(): Screen? {
    if (type != ContextType.STATE) return null
    val on = SCREEN_PREDICATE_RE.find(config["predicate"].orEmpty())?.groupValues?.get(1)
        ?.lowercase()?.let { it == "on" } ?: return null
    return if (on != invert) Screen.ON else Screen.OFF
}

/**
 * The screen states [profile] can fire in: one, if a context pins it, else both. A profile pinned to
 * both at once (contradictory contexts) can never fire, and is listed under neither.
 */
private fun screensOf(profile: Profile): Set<Screen> {
    val pinned = profile.contexts.mapNotNull { it.screenState() }.toSet()
    return if (pinned.isEmpty()) Screen.entries.toSet() else if (pinned.size == 1) pinned else emptySet()
}

private const val ANY_KEY = "*"

/** A profile's hardware-key context, split into the keys and presses it actually fires on. */
private data class KeyTrigger(val keys: List<String>, val presses: List<String>)

private fun String.csv(): List<String> =
    split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }

/**
 * The hardware-key trigger of [profile], or null if it has none.
 *
 * Both narrowers are optional and both accept a CSV (`ContextMatchEvaluator` reads `key`/`keys` and
 * `press`/`presses` the same way), so a profile can legitimately cover several keys at once — it is
 * then listed under each of them rather than being filed under a guess.
 */
private fun triggerOf(profile: Profile): KeyTrigger? {
    val spec = profile.contexts.firstOrNull {
        it.type == ContextType.EVENT &&
            it.config["event"]?.trim()?.lowercase() == HardwareKeyContextEvents.EVENT
    } ?: return null
    val keys = (spec.config["key"] ?: spec.config["keys"]).orEmpty().csv().ifEmpty { listOf(ANY_KEY) }
    val presses = (spec.config["press"] ?: spec.config["presses"]).orEmpty().csv()
    return KeyTrigger(keys, presses)
}

/** One condition a binding needs besides its key press: [body] as 白い熊 wrote it, or its negation. */
private data class Cond(val body: String, val invert: Boolean)

/**
 * Everything that has to hold BESIDES the key press. With [dropScreen] the screen condition is left
 * out, because the box the binding is listed in already says it.
 */
private fun conditionsOf(profile: Profile, dropScreen: Boolean): List<Cond> =
    profile.contexts.filterNot {
        (it.type == ContextType.EVENT &&
            it.config["event"]?.trim()?.lowercase() == HardwareKeyContextEvents.EVENT) ||
            (dropScreen && it.screenState() != null)
    }.map { spec ->
        val body = when (spec.type) {
            // A STATE context is written as a single predicate; print it as 白い熊 wrote it rather
            // than through a lookup of friendly names that would silently go stale.
            ContextType.STATE -> spec.config["predicate"]?.trim()?.takeIf { it.isNotEmpty() }
                ?: contextConfigSummary(spec.copy(invert = false))
            else -> contextConfigSummary(spec.copy(invert = false))
        }
        Cond(body, spec.invert)
    }

private fun Cond.phrase(lang: SheetLang): String =
    if (invert) lang.of("$body でない", "not $body") else body

/** Sort key so an unnamed press or key sinks below the ones with a defined order. */
private fun orderOf(list: List<Triple<String, String, String>>, value: String): Int =
    list.indexOfFirst { it.first == value }.let { if (it < 0) list.size else it }

private fun labelOf(list: List<Triple<String, String, String>>, value: String, lang: SheetLang): String =
    list.firstOrNull { it.first == value }?.let { lang.of(it.second, it.third) } ?: value

/** [entries] are already in press order; each press is written once with all its bindings. */
private fun appendPresses(out: StringBuilder, entries: List<Pair<String, Profile>>, lang: SheetLang, dropScreen: Boolean) {
    for ((press, group) in entries.groupBy({ it.first }, { it.second })) appendPress(out, press, group, lang, dropScreen)
}

private fun taskOf(profile: Profile): String = profile.enterTaskName.ifBlank { profile.enterTaskId.toString() }

/** The italic line under a binding: which profile to open to change it, and whether it is off. */
private fun profileNote(profile: Profile, lang: SheetLang): String =
    if (profile.enabled) profile.name else "${profile.name} ${lang.of("【無効】", "[disabled]")}"

/**
 * One press type, written ONCE however many bindings it has (白い熊, 2026-10-11: the same press
 * listed three times read as three different gestures).
 *
 * A press with one binding is one line, `**press** → __task__`. A press with several — Vol-Down short
 * is the 言語島 sentence while capturing, stop-recording while recording, and the plain action
 * otherwise — becomes the press label alone, then one line per binding led by WHEN it runs. The
 * binding whose conditions are exactly the negation of all the others' is labelled "otherwise",
 * which is what it means; one with no conditions at all beside conditional ones runs every time,
 * alongside whichever of them also matches, and says so.
 */
private fun appendPress(
    out: StringBuilder, press: String, profiles: List<Profile>, lang: SheetLang, dropScreen: Boolean,
) {
    val label = if (press == ANY_KEY) lang.of("どの押し方でも", "Any press") else labelOf(PRESS_LABELS, press, lang)
    val conds = profiles.associateWith { conditionsOf(it, dropScreen) }
    // Two leading spaces = one further indent step, so a note hangs under the line it belongs to.
    if (profiles.size == 1) {
        val profile = profiles.single()
        out.append("**").append(label).append("** → __").append(taskOf(profile)).append("__\n")
        val c = conds.getValue(profile)
        val whenText = if (c.isEmpty()) "" else lang.of("条件：", "only if ") +
            c.joinToString(lang.of(" かつ ", " and ")) { it.phrase(lang) } + " ・ "
        out.append("  *").append(whenText).append(profileNote(profile, lang)).append("*\n")
        return
    }
    val positives = conds.values.flatten().filter { !it.invert }.map { it.body }.toSet()
    fun isOtherwise(c: List<Cond>) =
        c.isNotEmpty() && c.all { it.invert } && c.map { it.body }.toSet() == positives
    val ordered = profiles.sortedWith(compareBy({
        val c = conds.getValue(it)
        when { isOtherwise(c) -> 2; c.isEmpty() -> 1; else -> 0 }
    }, { it.name }))
    out.append("**").append(label).append("**\n")
    for (profile in ordered) {
        val c = conds.getValue(profile)
        val whenText = when {
            isOtherwise(c) -> lang.of("それ以外", "otherwise")
            c.isEmpty() -> lang.of("常に（上と併せて）", "always (as well)")
            else -> lang.of("", "if ") + c.joinToString(lang.of(" かつ ", " and ")) { it.phrase(lang) } +
                lang.of(" のとき", "")
        }
        out.append("  ").append(whenText).append(" → __").append(taskOf(profile)).append("__\n")
        out.append("    *").append(profileNote(profile, lang)).append("*\n")
    }
    // With no catch-all binding, a press that matches none of the conditions does nothing — say it,
    // or the list reads as if one of the tasks above must run.
    if (ordered.none { conds.getValue(it).let { c -> c.isEmpty() || isOtherwise(c) } }) {
        out.append("  ").append(lang.of("それ以外 → 何もしない", "otherwise → nothing")).append('\n')
    }
}

/**
 * `Key Bindings` — write a ready-to-show listing of which physical-key gesture runs which task.
 *
 * Reads the profiles rather than any stored table, so the sheet is the live mapping: a profile
 * switched off says so, and a second binding on the same press (the same key doing different things
 * under different conditions) is listed with the condition that picks it.
 */
class KeyBindingsAction : Action {
    override val id = "key.bindings"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val store = args["store"]?.trim()?.removePrefix("%")?.takeIf { it.isNotEmpty() } ?: "bindings"
        val lang = sheetLangOf(args["lang"])
        // Default to the calling task's own project — the sheet belongs to the project that owns the
        // key map. "all" reaches across every project, for a whole-device overview.
        val allProjects = args["scope"]?.trim()?.lowercase() == "all"
        val profiles = OpenTaskerApp_NoHilt.db.profileDao().getAll()
            .map { it.toDomain() }
            .filter { allProjects || (it.projectId ?: 0L) == ctx.variables.projectId }

        val bound = profiles.mapNotNull { profile -> triggerOf(profile)?.let { profile to it } }
        val out = StringBuilder()

        var listed = 0
        val keysPresent = bound.flatMap { it.second.keys }.distinct()
            .sortedBy { orderOf(KEY_LABELS, it) }
        for (key in keysPresent) {
            val forKey = bound.filter { key in it.second.keys }
            if (forKey.isEmpty()) continue
            out.append("## ")
                .append(if (key == ANY_KEY) lang.of("すべてのキー", "Any key") else labelOf(KEY_LABELS, key, lang))
                .append('\n')
            // One entry per (press, profile). A profile with no press narrower fires on every press,
            // so it is listed once under a heading that says exactly that.
            val entries = forKey.flatMap { (profile, trigger) ->
                trigger.presses.ifEmpty { listOf(ANY_KEY) }.map { press -> press to profile }
            }.sortedWith(compareBy({ orderOf(PRESS_LABELS, it.first) }, { it.second.name }))
            // Split by screen only where it decides something. A key whose every binding fires either
            // way would print the same list twice; it stays one list.
            val split = entries.any { screensOf(it.second).size == 1 }
            if (!split) {
                listed += entries.size
                appendPresses(out, entries, lang, dropScreen = false)
            } else {
                // A binding that ignores the screen is a binding in BOTH boxes: each box is the whole
                // map for that state, so it can be read on its own. It still counts once.
                listed += entries.size
                for (screen in Screen.entries) {
                    val inBox = entries.filter { screen in screensOf(it.second) }
                    if (inBox.isEmpty()) continue
                    out.append("::: ").append(screen.label(lang)).append('\n')
                    appendPresses(out, inBox, lang, dropScreen = true)
                    out.append(":::\n\n")
                }
            }
            out.append('\n')
        }
        if (listed == 0) {
            out.append(
                lang.pick(args["empty_text"]).takeIf { it.isNotEmpty() }
                    ?: lang.of("物理キーは割り当てられていません。", "No physical key is mapped."),
            )
        }
        appendSheetFooter(out, args["footer"], lang, listed)

        ctx.variables.set(store, out.toString().trimEnd())
        ctx.variables.set("${store}_count", listed.toString())
        ctx.variables.set("${store}_title", lang.pick(args["title"]))
        ctx.logger("Key sheet: $listed binding(s) → %$store")
        return ActionResult.Success
    }
}
