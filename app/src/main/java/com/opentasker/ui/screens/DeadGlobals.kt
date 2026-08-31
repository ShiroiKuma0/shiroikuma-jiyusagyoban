package com.opentasker.ui.screens

import com.opentasker.core.model.Profile
import com.opentasker.core.model.Scene
import com.opentasker.core.model.Task
import com.opentasker.core.model.Variable

/** A dead super-global that duplicates a live project-global: its super copy is never read. */
data class ShadowInfo(val variable: Variable, val twinProjectId: Long)

/**
 * Live classification of the super-global (projectId 0) namespace for the Variables-tab cleanup section.
 *  - [shadowCopies] — a super-global whose NAME also exists as a project-global (its live twin); the super
 *    copy is never read, so it's dead. Each carries the twin's projectId (the UI resolves it to a name).
 *  - [orphans] — a super-global referenced/written by NO task, profile, scene, or widget template.
 *  - [proper] — super-globals genuinely in use (kept untouched).
 */
data class DeadGlobalsReport(
    val shadowCopies: List<ShadowInfo> = emptyList(),
    val orphans: List<Variable> = emptyList(),
    // Project-globals whose projectId matches NO current project (its project was deleted/re-created), so
    // the row is dead: frozen-stale and invisible under any project chip. Carries the dead projectId.
    val dangling: List<Variable> = emptyList(),
    val proper: List<Variable> = emptyList(),
) {
    val deletable: List<Variable> get() = shadowCopies.map { it.variable } + orphans + dangling
    val deadCount: Int get() = shadowCopies.size + orphans.size + dangling.size
    val hasDead: Boolean get() = deadCount > 0
}

private val DG_VAR_REF = Regex("%([A-Za-z_][A-Za-z0-9_]*)")

/**
 * Compute a [DeadGlobalsReport] from the current workspace. [templateLayouts] are the widget templates'
 * raw layout JSON, scanned as text for `%refs` (so a widget-only variable isn't misflagged as an orphan).
 * A variable a task *writes* (a `var.set`/`var.clear` `name` arg) counts as "in use" too.
 */
fun analyzeDeadGlobals(
    variables: List<Variable>,
    tasks: List<Task>,
    profiles: List<Profile>,
    scenes: List<Scene>,
    templateLayouts: List<String>,
    validProjectIds: Set<Long>,
): DeadGlobalsReport {
    val supers = variables.filter { it.projectId == 0L }
    // A project-global whose projectId matches no current project is dead (its project was deleted/
    // re-created). It's frozen-stale and unreachable — collect it for cleanup.
    val dangling = variables.filter { it.projectId != 0L && it.projectId !in validProjectIds }.sortedBy { it.name }
    if (supers.isEmpty() && dangling.isEmpty()) return DeadGlobalsReport()
    // name -> projectId of a LIVE project-global with that name; a super whose name is here is a shadow.
    val twinByName = HashMap<String, Long>()
    for (v in variables) if (v.projectId != 0L && v.projectId in validProjectIds) twinByName.putIfAbsent(v.name, v.projectId)

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

    val shadow = supers.filter { it.name in twinByName }.map { ShadowInfo(it, twinByName.getValue(it.name)) }
    val shadowNames = shadow.mapTo(HashSet()) { it.variable.name }
    val rest = supers.filter { it.name !in shadowNames }
    val orphans = rest.filter { it.name !in touched }.sortedBy { it.name }
    val proper = rest.filter { it.name in touched }
    return DeadGlobalsReport(shadow.sortedBy { it.variable.name }, orphans, dangling, proper)
}
