package com.opentasker.ui.screens

import com.opentasker.core.model.Profile
import com.opentasker.core.model.Scene
import com.opentasker.core.model.Task
import com.opentasker.core.model.Variable

/**
 * Live classification of the super-global (projectId 0) namespace for the Variables-tab cleanup section.
 *  - [shadowCopies] — a super-global whose NAME also exists as a project-global (its live twin). Reads at
 *    task scope only ever hit the project bucket, and render-scope merges let the project override, so the
 *    super copy is never read: dead.
 *  - [orphans] — a super-global referenced/written by NO task, profile, scene, or widget template.
 *  - [properCount] — super-globals genuinely in use, kept untouched (incl. cross-project ones and any
 *    single-project ALL-CAPS still awaiting the rename-demotion).
 */
data class DeadGlobalsReport(
    val shadowCopies: List<Variable> = emptyList(),
    val orphans: List<Variable> = emptyList(),
    val properCount: Int = 0,
) {
    val deletable: List<Variable> get() = shadowCopies + orphans
    val deadCount: Int get() = shadowCopies.size + orphans.size
    val hasDead: Boolean get() = deadCount > 0
}

private val DG_VAR_REF = Regex("%([A-Za-z_][A-Za-z0-9_]*)")

/**
 * Compute a [DeadGlobalsReport] from the current workspace. [templateLayouts] are the widget templates'
 * raw layout JSON, scanned as text for `%refs` (so a widget-only variable isn't misflagged as an orphan).
 * A variable a task *writes* (a `var.set`/`var.clear` `name` arg) counts as "in use" too — we never call
 * a still-written global an orphan.
 */
fun analyzeDeadGlobals(
    variables: List<Variable>,
    tasks: List<Task>,
    profiles: List<Profile>,
    scenes: List<Scene>,
    templateLayouts: List<String>,
): DeadGlobalsReport {
    val supers = variables.filter { it.projectId == 0L }
    if (supers.isEmpty()) return DeadGlobalsReport()
    val projectNames = variables.asSequence().filter { it.projectId != 0L }.map { it.name }.toHashSet()

    val touched = HashSet<String>()
    fun scan(s: String?) { if (!s.isNullOrEmpty()) for (m in DG_VAR_REF.findAll(s)) touched += m.groupValues[1] }
    for (t in tasks) for (a in t.actions) {
        for (v in a.args.values) scan(v)
        scan(a.label); scan(a.condition)
        a.args["name"]?.let { touched += it.trim() }                       // a written variable is "in use"
        a.args["variable"]?.let { touched += it.trim().removePrefix("%") }
    }
    for (p in profiles) for (c in p.contexts) for (v in c.config.values) scan(v)
    for (s in scenes) for (e in s.elements) for (v in e.config.values) scan(v)
    for (layout in templateLayouts) scan(layout)

    val shadow = supers.filter { it.name in projectNames }
    val shadowSet = shadow.mapTo(HashSet()) { it.name }
    val rest = supers.filter { it.name !in shadowSet }
    val orphans = rest.filter { it.name !in touched }
    return DeadGlobalsReport(shadow, orphans, rest.size - orphans.size)
}
