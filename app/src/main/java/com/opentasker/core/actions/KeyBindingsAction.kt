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

/** Press types, in listing order — shortest gesture first, then the holds. */
private val PRESS_LABELS: List<Triple<String, String, String>> = listOf(
    Triple(HardwareKeyContextEvents.PRESS_SHORT, "単押し", "Single press"),
    Triple(HardwareKeyContextEvents.PRESS_DOUBLE, "二度押し", "Double press"),
    Triple(HardwareKeyContextEvents.PRESS_TRIPLE, "三度押し", "Triple press"),
    Triple(HardwareKeyContextEvents.PRESS_LONG, "長押し", "Long press"),
)

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

/** Everything that has to hold BESIDES the key press, phrased for a reader. */
private fun conditionsOf(profile: Profile, lang: SheetLang): List<String> =
    profile.contexts.filterNot {
        it.type == ContextType.EVENT &&
            it.config["event"]?.trim()?.lowercase() == HardwareKeyContextEvents.EVENT
    }.map { spec ->
        val body = when (spec.type) {
            // A STATE context is written as a single predicate; print it as 白い熊 wrote it rather
            // than through a lookup of friendly names that would silently go stale.
            ContextType.STATE -> spec.config["predicate"]?.trim()?.takeIf { it.isNotEmpty() }
                ?: contextConfigSummary(spec.copy(invert = false))
            else -> contextConfigSummary(spec.copy(invert = false))
        }
        if (spec.invert) lang.of("$body でない", "not $body") else body
    }

/** Sort key so an unnamed press or key sinks below the ones with a defined order. */
private fun orderOf(list: List<Triple<String, String, String>>, value: String): Int =
    list.indexOfFirst { it.first == value }.let { if (it < 0) list.size else it }

private fun labelOf(list: List<Triple<String, String, String>>, value: String, lang: SheetLang): String =
    list.firstOrNull { it.first == value }?.let { lang.of(it.second, it.third) } ?: value

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
                trigger.presses.ifEmpty { listOf(ANY_KEY) }.map { press -> Triple(press, profile, trigger) }
            }.sortedWith(compareBy({ orderOf(PRESS_LABELS, it.first) }, { it.second.name }))
            for ((press, profile, _) in entries) {
                listed++
                val task = profile.enterTaskName.ifBlank { profile.enterTaskId.toString() }
                out.append("**")
                    .append(
                        if (press == ANY_KEY) lang.of("どの押し方でも", "Any press")
                        else labelOf(PRESS_LABELS, press, lang),
                    )
                    .append("** → __").append(task).append("__\n")
                // The second line is where a binding stops being obvious: which profile to open to
                // change it, what else has to be true, and whether it is switched off at all.
                // Brackets, not nested `**bold**`: the whole line is one italic run, and the markup
                // reader takes the outermost `*…*` first, so a bold span inside it would be eaten.
                val notes = buildList {
                    add(profile.name)
                    addAll(conditionsOf(profile, lang))
                    if (!profile.enabled) add(lang.of("【無効】", "[disabled]"))
                }
                // Two leading spaces = one further indent step, so the note hangs under its binding
                // instead of sitting level with it.
                out.append("  *").append(notes.joinToString(" ・ ")).append("*\n")
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
