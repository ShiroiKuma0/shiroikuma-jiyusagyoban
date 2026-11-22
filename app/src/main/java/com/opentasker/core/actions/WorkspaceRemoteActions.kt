package com.opentasker.core.actions

import android.provider.Settings
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.capabilities.ProfileTurnOnChecks
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// The workspace of a phone nobody is holding (白い熊, 2026-11-22): what the 携帯 relay phones are
// asked over Jami after a bundle reached them — which projects, profiles and tasks are there, switch
// this profile on, run that task. Each action answers in variables, because the answer travels back
// as a message: a refusal has to say why, in words that make sense without the phone in hand.

/** Everything here that does not touch the database. */
internal object WorkspaceQuery {
    const val UNFILED = "Unfiled"

    data class Project(val id: Long, val name: String)

    /** One project, or the items that belong to none ([projectId] null). */
    data class Scope(val projectId: Long?, val name: String)

    /** A task or a profile, reduced to what a listing and a lookup need. */
    data class Item(
        val id: Long,
        val name: String,
        val projectId: Long?,
        val enabled: Boolean,
        /** A profile an import left waiting for its review in the app. */
        val needsReview: Boolean = false,
    )

    /** An exact project name wins, then one that differs only in case; "Unfiled" is the items with none. */
    fun scope(name: String, projects: List<Project>): Scope? {
        val wanted = name.trim()
        if (wanted.isEmpty()) return null
        projects.firstOrNull { it.name.trim() == wanted }?.let { return Scope(it.id, it.name) }
        projects.firstOrNull { it.name.trim().equals(wanted, ignoreCase = true) }?.let { return Scope(it.id, it.name) }
        return if (wanted.equals(UNFILED, ignoreCase = true)) Scope(null, UNFILED) else null
    }

    data class Ref(val name: String, val scope: Scope?)

    /**
     * `name @ project`, split at the LAST `@` and trimmed, so the spaces around it are optional. The
     * split happens only when what follows names a project — a name that carries an `@` of its own
     * stays whole.
     */
    fun ref(raw: String, projects: List<Project>): Ref {
        val text = raw.trim()
        val at = text.lastIndexOf('@')
        if (at > 0) {
            val name = text.substring(0, at).trim()
            val scope = scope(text.substring(at + 1), projects)
            if (scope != null && name.isNotEmpty()) return Ref(name, scope)
        }
        return Ref(text, null)
    }

    data class Filter(val scope: Scope?, val word: String)

    /**
     * What a listing is narrowed to: `@ project`, `word @ project`, `project`, `project word` (the
     * longest leading run of words that names a project), or just `word`.
     */
    fun filter(raw: String, projects: List<Project>): Filter {
        val text = raw.trim()
        if (text.isEmpty()) return Filter(null, "")
        val at = text.lastIndexOf('@')
        if (at >= 0) scope(text.substring(at + 1), projects)?.let { return Filter(it, text.substring(0, at).trim()) }
        scope(text, projects)?.let { return Filter(it, "") }
        for (cut in text.indices.reversed()) {
            if (!text[cut].isWhitespace()) continue
            scope(text.substring(0, cut), projects)?.let { return Filter(it, text.substring(cut).trim()) }
        }
        return Filter(null, text)
    }

    sealed interface Found {
        data class One(val item: Item, val project: String) : Found
        data class Refused(val message: String) : Found
    }

    private fun projectName(id: Long?, projects: List<Project>): String =
        projects.firstOrNull { it.id == id }?.name ?: UNFILED

    /**
     * The one [kind] ("task" or "profile") that [raw] names. An exact name wins, then one that differs
     * only in case. A name that occurs more than once is refused with the projects it occurs in, and
     * a name that occurs nowhere with the names that contain it — never guessed at.
     */
    fun find(kind: String, raw: String, items: List<Item>, projects: List<Project>): Found {
        val ref = ref(raw, projects)
        if (ref.name.isEmpty()) return Found.Refused("no $kind name given")
        val pool = if (ref.scope == null) items else items.filter { it.projectId == ref.scope.projectId }
        val exact = pool.filter { it.name.trim() == ref.name }
        val matches = exact.ifEmpty { pool.filter { it.name.trim().equals(ref.name, ignoreCase = true) } }
        val where = ref.scope?.let { " in project '${it.name}'" }.orEmpty()
        return when (matches.size) {
            1 -> Found.One(matches.single(), projectName(matches.single().projectId, projects))
            0 -> {
                val at = ref.name.lastIndexOf('@')
                if (ref.scope == null && at > 0 && ref.name.substring(at + 1).isNotBlank()) {
                    val left = ref.name.substring(0, at).trim()
                    if (items.any { it.name.trim().equals(left, ignoreCase = true) }) {
                        return Found.Refused("no project is named '${ref.name.substring(at + 1).trim()}'")
                    }
                }
                val close = pool.filter { it.name.contains(ref.name, ignoreCase = true) }.map { it.name }.distinct()
                Found.Refused(
                    "no $kind is named '${ref.name}'$where" + when {
                        close.isEmpty() -> ""
                        close.size <= 6 -> " — close: " + close.joinToString(" · ")
                        else -> " — ${close.size} names contain it"
                    },
                )
            }
            else -> {
                val places = matches.map { projectName(it.projectId, projects) }.distinct()
                Found.Refused(
                    if (places.size > 1) {
                        "${matches.size} ${kind}s are named '${ref.name}', in " + places.joinToString(" · ") + " — add “@ project”"
                    } else {
                        "${matches.size} ${kind}s are named '${ref.name}'$where, so it isn't clear which one is meant"
                    },
                )
            }
        }
    }

    data class Listing(val count: Int, val lines: List<String>)

    /** [kind] is "projects", "profiles" or "tasks"; [projects] arrive in the order the app shows them. */
    fun listing(kind: String, filter: Filter, projects: List<Project>, tasks: List<Item>, profiles: List<Item>): Listing {
        val scopes = projects.map { Scope(it.id, it.name) } +
            listOfNotNull(Scope(null, UNFILED).takeIf { (tasks + profiles).any { item -> item.projectId == null } })
        val shown = if (filter.scope == null) scopes else scopes.filter { it.projectId == filter.scope.projectId }
        fun matches(name: String) = filter.word.isEmpty() || name.contains(filter.word, ignoreCase = true)
        val lines = mutableListOf<String>()
        var count = 0
        when (kind) {
            "projects" -> shown.filter { matches(it.name) }.forEach { scope ->
                val own = profiles.filter { it.projectId == scope.projectId }
                lines += "${scope.name} — tasks ${tasks.count { it.projectId == scope.projectId }} · " +
                    "profiles ${own.size} (on ${own.count { it.enabled }})"
                count++
            }
            "profiles" -> shown.forEach { scope ->
                val own = profiles.filter { it.projectId == scope.projectId && matches(it.name) }
                if (own.isEmpty()) return@forEach
                lines += "▸ ${scope.name} (${own.size}, on ${own.count { it.enabled }})"
                own.forEach { lines += (if (it.enabled) "● " else "○ ") + it.name + if (it.needsReview) " (needs review)" else "" }
                count += own.size
            }
            else -> shown.forEach { scope ->
                val own = tasks.filter { it.projectId == scope.projectId && matches(it.name) }
                if (own.isEmpty()) return@forEach
                lines += "▸ ${scope.name} (${own.size})"
                own.forEach { lines += "· " + it.name + if (it.enabled) "" else " (disabled)" }
                count += own.size
            }
        }
        return Listing(count, lines)
    }

    /**
     * [lines] packed into messages of at most [maxChars]: a long list is split, never cut off. Every
     * page after the first says which one it is.
     */
    fun pages(header: String, lines: List<String>, maxChars: Int): List<String> {
        val pages = mutableListOf<StringBuilder>()
        var current = StringBuilder(header)
        lines.forEach { raw ->
            val line = if (raw.length > maxChars) raw.take(maxChars - 1) + "…" else raw
            if (current.isNotEmpty() && current.length + 1 + line.length > maxChars) {
                pages += current
                current = StringBuilder()
            }
            if (current.isNotEmpty()) current.append('\n')
            current.append(line)
        }
        if (current.isNotEmpty()) pages += current
        if (pages.size < 2) return pages.map { it.toString() }
        return pages.mapIndexed { index, page -> if (index == 0) page.toString() else "(${index + 1}/${pages.size})\n$page" }
    }
}

/** The workspace as [WorkspaceQuery] sees it. */
internal class WorkspaceSnapshot(
    val projects: List<WorkspaceQuery.Project>,
    val tasks: List<WorkspaceQuery.Item>,
    val profiles: List<WorkspaceQuery.Item>,
) {
    companion object {
        suspend fun read(): WorkspaceSnapshot = withContext(Dispatchers.IO) {
            val db = OpenTaskerApp_NoHilt.db
            WorkspaceSnapshot(
                projects = db.projectDao().getAll().map { WorkspaceQuery.Project(it.id, it.name) },
                tasks = db.taskDao().getAll().map { WorkspaceQuery.Item(it.id, it.name, it.projectId, it.enabled) },
                profiles = db.profileDao().getAll().map {
                    WorkspaceQuery.Item(it.id, it.name, it.projectId, it.enabled, it.requiresRiskAcknowledgement)
                },
            )
        }
    }
}

/**
 * `List Workspace` — the projects, profiles or tasks of this workspace as text, ready to be sent.
 *
 * Args:
 *   - "kind":      `projects` (one line each, with its counts), `profiles` (● on, ○ off) or `tasks`
 *   - "query":     narrows it — `project`, `word`, `project word`, `word @ project` or `@ project`;
 *                  the word is matched inside the names, ignoring case
 *   - "header":    a first line for the first page
 *   - "max_chars": the size of one page (default 1500) — a long list comes as several pages
 *   - "prefix":    result prefix (default `wl`): `%wl_ok`, `%wl_message`, `%wl_count`, `%wl_pages`,
 *                  and the pages as the array `%wl_page` (for a For Each; `%wl_page(0)` is the first)
 */
class WorkspaceListAction : Action {
    override val id = "workspace.list"
    override val category = ActionCategory.APP

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val prefix = args["prefix"]?.trim()?.ifBlank { null } ?: "wl"
        fun put(key: String, value: String) = ctx.variables.set("${prefix}_$key", value)
        put("ok", "false")
        put("message", "")
        put("count", "0")
        put("pages", "0")
        ctx.variables.removeArray("${prefix}_page")
        fun fail(message: String): ActionResult {
            put("message", message)
            return ActionResult.Failure(message)
        }
        val kind = args["kind"]?.trim()?.lowercase().orEmpty()
        if (kind !in KINDS) return fail("invalid kind: $kind (use projects, profiles or tasks)")
        val maxChars = args["max_chars"]?.trim()?.toIntOrNull()?.coerceIn(200, 8000) ?: 1500
        val snapshot = runCatching { WorkspaceSnapshot.read() }.getOrElse { return fail("could not read the workspace: ${it.message}") }
        val query = args["query"].orEmpty()
        val filter = WorkspaceQuery.filter(query, snapshot.projects)
        val at = query.lastIndexOf('@')
        if (at >= 0 && filter.scope == null) return fail("no project is named '${query.substring(at + 1).trim()}'")
        val listing = WorkspaceQuery.listing(kind, filter, snapshot.projects, snapshot.tasks, snapshot.profiles)
        put("count", listing.count.toString())
        if (listing.count == 0) {
            return fail(
                "no $kind" + filter.scope?.let { " in project '${it.name}'" }.orEmpty() +
                    if (filter.word.isEmpty()) "" else " with '${filter.word}' in the name",
            )
        }
        val pages = WorkspaceQuery.pages(args["header"].orEmpty().trim(), listing.lines, maxChars)
        ctx.variables.setArray("${prefix}_page", pages)
        put("pages", pages.size.toString())
        put("ok", "true")
        put("message", "${listing.count} $kind")
        ctx.logger("Listed ${listing.count} $kind in ${pages.size} page(s)")
        return ActionResult.Success
    }

    private companion object {
        val KINDS = setOf("projects", "profiles", "tasks")
    }
}

/**
 * `Find in Workspace` — the one task or profile a typed name means, or why there is no such one.
 *
 * Args:
 *   - "kind":   `task` or `profile`
 *   - "query":  `name`, or `name @ project` when the name occurs in more than one project
 *   - "prefix": result prefix (default `wf`): `%wf_ok`, `%wf_message`, `%wf_id`, `%wf_name` (as
 *               stored), `%wf_project`, `%wf_enabled` (true/false)
 */
class WorkspaceFindAction : Action {
    override val id = "workspace.find"
    override val category = ActionCategory.APP

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val prefix = args["prefix"]?.trim()?.ifBlank { null } ?: "wf"
        fun put(key: String, value: String) = ctx.variables.set("${prefix}_$key", value)
        listOf("message", "id", "name", "project", "enabled").forEach { put(it, "") }
        put("ok", "false")
        fun fail(message: String): ActionResult {
            put("message", message)
            return ActionResult.Failure(message)
        }
        val kind = args["kind"]?.trim()?.lowercase().orEmpty()
        if (kind != "task" && kind != "profile") return fail("invalid kind: $kind (use task or profile)")
        val snapshot = runCatching { WorkspaceSnapshot.read() }.getOrElse { return fail("could not read the workspace: ${it.message}") }
        val items = if (kind == "task") snapshot.tasks else snapshot.profiles
        return when (val found = WorkspaceQuery.find(kind, args["query"].orEmpty(), items, snapshot.projects)) {
            is WorkspaceQuery.Found.Refused -> fail(found.message)
            is WorkspaceQuery.Found.One -> {
                put("id", found.item.id.toString())
                put("name", found.item.name)
                put("project", found.project)
                put("enabled", found.item.enabled.toString())
                put("ok", "true")
                ActionResult.Success
            }
        }
    }
}

/**
 * `Switch Profile` — Profile Status for a typed name: the profile may be given as `name @ project`,
 * and the outcome comes back in variables. Turning a profile on runs the checks its own switch in
 * the app runs ([ProfileTurnOnChecks]); turning one off is never refused.
 *
 * Args:
 *   - "query":  `name` or `name @ project`
 *   - "state":  `on`, `off`, `toggle`, or `show` (changes nothing)
 *   - "prefix": result prefix (default `wp`): `%wp_ok`, `%wp_message`, `%wp_name`, `%wp_project`,
 *               `%wp_was` and `%wp_now` (on/off)
 */
class WorkspaceProfileAction : Action {
    override val id = "workspace.profile"
    override val category = ActionCategory.APP

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val prefix = args["prefix"]?.trim()?.ifBlank { null } ?: "wp"
        fun put(key: String, value: String) = ctx.variables.set("${prefix}_$key", value)
        listOf("message", "name", "project", "was", "now").forEach { put(it, "") }
        put("ok", "false")
        fun fail(message: String): ActionResult {
            put("message", message)
            return ActionResult.Failure(message)
        }
        val state = args["state"]?.trim()?.lowercase().orEmpty()
        if (state != "show" && state !in ProfileSwitch.STATES) return fail("invalid state: $state (use on, off, toggle or show)")
        val snapshot = runCatching { WorkspaceSnapshot.read() }.getOrElse { return fail("could not read the workspace: ${it.message}") }
        val found = when (val found = WorkspaceQuery.find("profile", args["query"].orEmpty(), snapshot.profiles, snapshot.projects)) {
            is WorkspaceQuery.Found.Refused -> return fail(found.message)
            is WorkspaceQuery.Found.One -> found
        }
        fun word(on: Boolean) = if (on) "on" else "off"
        put("name", found.item.name)
        put("project", found.project)
        put("was", word(found.item.enabled))
        put("now", word(found.item.enabled))
        if (state == "show") {
            put("ok", "true")
            return ActionResult.Success
        }
        val enabled = ProfileSwitch.next(state, found.item.enabled)
        val refusal = withContext(Dispatchers.IO) {
            ProfileTurnOnChecks.switchSaved(OpenTaskerApp_NoHilt.db, found.item.id, enabled, Settings.System.canWrite(ctx.app))
        }
        if (refusal != null) return fail(refusal)
        put("now", word(enabled))
        put("ok", "true")
        ctx.logger("Profile '${found.item.name}' turned ${word(enabled)}")
        return ActionResult.Success
    }
}
