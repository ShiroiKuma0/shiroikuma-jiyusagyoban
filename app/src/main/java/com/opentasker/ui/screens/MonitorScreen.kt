package com.opentasker.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.opentasker.core.engine.AutomationService
import com.opentasker.core.engine.EngineHeartbeat
import com.opentasker.core.engine.WidgetRefreshLog
import com.opentasker.core.engine.variables.PersistentGlobalScope
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Project
import com.opentasker.core.model.RunLogEntry
import com.opentasker.core.model.Task
import com.opentasker.core.storage.AutoStartSettings
import com.opentasker.scenes.SceneOverlayManager
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val GREEN = Color(0xFF00E676)
private val RED = Color(0xFFFF5252)
private val AMBER = Color(0xFFFFC107)
private val GREY = Color(0xFF9E9E9E)

/** One row of the Monitor's aggregated task-activity view: a task's run count + last run + failures. */
private data class TaskActivity(val name: String, val count: Int, val lastAt: Long, val fails: Int)
private val CLOCK_FMT = SimpleDateFormat("HH:mm:ss", Locale.US)

/**
 * Engine monitor — an honest "is everything that should be running, actually running?" view, plus the
 * auto-run-on-start list. Vitals / on-screen overlays / enabled profiles (colored by real activity) /
 * history, then a "Run on start" section to pick tasks (e.g. the master 起動) that run on every fresh
 * engine start. Refreshes once a second.
 */
@Composable
fun MonitorScreen(
    profiles: List<Profile>,
    tasks: List<Task>,
    projects: List<Project>,
    lastFired: Map<String, Long>,
    runLogs: List<RunLogEntry>,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { AutoStartSettings.load(context) }
    val autoIds by AutoStartSettings.ids.collectAsState()
    var showPicker by remember { mutableStateOf(false) }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    val running = AutomationService.isRunning
    val lastTick = EngineHeartbeat.lastTickAt
    val startedAt = EngineHeartbeat.engineStartedAt
    val sinceTick = if (lastTick > 0L) now - lastTick else -1L
    val stale = running && lastTick > 0L && sinceTick > EngineHeartbeat.STALE_THRESHOLD_MS
    val healthy = running && !stale
    val statusColor = if (healthy) GREEN else RED
    val events = EngineHeartbeat.events()
    val enabled = profiles.filter { it.enabled }
    val overlays = SceneOverlayManager.shownSceneNames()
    val autoTasks = autoIds.mapNotNull { id -> tasks.firstOrNull { it.id == id } }
    // Per-section fold state (default open). The engine vitals card stays always visible.
    val sectionOpen = remember { mutableStateMapOf<String, Boolean>() }
    fun toggle(key: String) { sectionOpen[key] = !(sectionOpen[key] ?: true) }
    val openOverlays = sectionOpen["overlays"] ?: true
    val openProfiles = sectionOpen["profiles"] ?: true
    val openHistory = sectionOpen["history"] ?: true
    val openGlobals = sectionOpen["globals"] ?: false   // verbose — default collapsed
    val openRuns = sectionOpen["runs"] ?: true
    val openWidgets = sectionOpen["widgets"] ?: true
    val openRunStart = sectionOpen["runstart"] ?: true

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 12.dp, end = 12.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Dot(statusColor, 14.dp)
                        Text(
                            when {
                                !running -> "Engine STOPPED"
                                stale -> "Engine STALLED"
                                else -> "Engine RUNNING"
                            },
                            fontWeight = FontWeight.Bold, fontSize = 18.sp, color = statusColor,
                        )
                    }
                    if (stale) {
                        Text("No tick for ${rel(sinceTick)} — auto-recovering within ~2.5 min.", color = RED, fontSize = 13.sp)
                    }
                    Vital("Last tick", if (lastTick <= 0L) "—" else "${rel(sinceTick)} ago", statusColor)
                    Vital("Uptime", if (startedAt <= 0L) "—" else rel(now - startedAt), null)
                    Vital("Overlays on screen", overlays.size.toString(), if (overlays.isEmpty()) AMBER else GREEN)
                    Vital("Enabled profiles", enabled.size.toString(), null)
                }
            }
        }

        // --- task activity: collapse the raw run log to ONE row per task (run count + most-recent run +
        //     failures), so the per-minute tickers (dt.tick, denchi.update) don't flood the list. This is
        //     the analytics view — "what's firing and is it healthy" — not a scrolling per-run feed.
        val runWindow = runLogs.sortedByDescending { it.timestamp }.take(500)
        val taskAgg = runWindow
            .groupBy { it.taskName.ifBlank { "task #${it.taskId}" } }
            .map { (name, runs) ->
                TaskActivity(name, runs.size, runs.maxOf { it.timestamp }, runs.count { !it.success })
            }
            .sortedByDescending { it.lastAt }
        item { FoldHeader("Task activity (${taskAgg.size} task${if (taskAgg.size == 1) "" else "s"})", openRuns) { toggle("runs") } }
        if (openRuns) {
            item { Hint("Each task collapsed across the last ${runWindow.size} runs: how many times it fired and when it last ran. Green = all OK; red = at least one run failed.") }
            if (taskAgg.isEmpty()) {
                item { Hint("No task runs recorded yet.") }
            }
            items(taskAgg, key = { it.name }) { a ->
                StatusRow(
                    color = if (a.fails > 0) RED else GREEN,
                    name = a.name,
                    detail = "${a.count}× · last ${rel(now - a.lastAt)} ago" + if (a.fails > 0) " · ${a.fails} failed" else "",
                    detailColor = if (a.fails > 0) RED else GREY,
                )
            }
        }

        // --- widget pulls: which placed widgets the last widget.refresh re-rendered (pull vs static) ---
        val widgetEntries = WidgetRefreshLog.entries
        val widgetAt = WidgetRefreshLog.lastAt
        item { FoldHeader("Widget pulls — last tick (${widgetEntries.size})", openWidgets) { toggle("widgets") } }
        if (openWidgets) {
            item { Hint("The per-minute tick runs Refresh Widgets, re-rendering every placed widget. A “pull” widget (template-bound, green) re-reads globals each tick; a “static” one (grey) keeps a fixed layout and never updates — that's why a clock/battery would stay frozen.") }
            if (widgetAt == 0L) {
                item { Hint("No Refresh Widgets has run this session yet. If the clock/battery stay stale, the per-minute tick isn't calling it — check the clock task fired under Recent task runs above.") }
            } else {
                item {
                    StatusRow(
                        color = if (running) GREEN else GREY,
                        name = "Last pull",
                        detail = "${rel(now - widgetAt)} ago · ${widgetEntries.count { it.isPull }} pull / ${widgetEntries.count { !it.isPull }} static",
                    )
                }
                items(widgetEntries.size) { idx ->
                    val e = widgetEntries[idx]
                    StatusRow(
                        color = if (e.isPull) GREEN else GREY,
                        name = e.label,
                        detail = if (e.isPull) "pull" else "static — won't update",
                    )
                }
            }
        }

        item { FoldHeader("On-screen overlays (${overlays.size})", openOverlays) { toggle("overlays") } }
        if (openOverlays) {
            if (overlays.isEmpty()) {
                item { Hint("Nothing is drawn on screen. After an app restart the overlays are gone until re-shown — run 起動完了 ⇨ 起動 (or set it to auto-run below).") }
            }
            items(overlays, key = { it }) { name -> StatusRow(GREEN, name, "shown") }
        }

        item { FoldHeader("Enabled profiles (${enabled.size})", openProfiles) { toggle("profiles") } }
        if (openProfiles) {
            item { Hint("Armed triggers. “ran” means the trigger fired (e.g. data updated) — it does NOT mean its overlay is on screen; that's the “On-screen overlays” section above.") }
            if (enabled.isEmpty()) {
                item { Hint("No enabled profiles — nothing is being watched.") }
            }
            items(enabled, key = { it.id }) { p ->
                val fired = lastFired[p.name]
                val ago = if (fired != null) now - fired else -1L
                // Grey, not green: a trigger firing is not the same as its overlay being drawn (the green
                // "fired" misread as "working" when the 電池線 line was actually gone). Red only if the
                // engine itself is down.
                StatusRow(
                    color = if (!running) RED else GREY,
                    name = p.name,
                    detail = if (fired == null) "idle · not run yet" else "ran ${rel(ago)} ago",
                    detailColor = GREY,
                )
            }
        }

        item { FoldHeader("History", openHistory) { toggle("history") } }
        if (openHistory) {
            if (events.isEmpty()) {
                item { Hint("No restarts or recoveries recorded yet.") }
            }
            items(events, key = { it.atMs }) { e ->
                val (label, color) = when (e.kind) {
                    EngineHeartbeat.HeartbeatEvent.Kind.STARTED -> "Engine started" to GREEN
                    EngineHeartbeat.HeartbeatEvent.Kind.REARMED -> "Re-armed — engine had died for ${rel(e.staleMs)}" to RED
                    EngineHeartbeat.HeartbeatEvent.Kind.RESURRECTED -> "Resurrected — process had been killed" to AMBER
                    EngineHeartbeat.HeartbeatEvent.Kind.MATCHER_RESTART -> "Recovered “${e.label}” (matcher restarted)" to AMBER
                }
                StatusRow(color, label, CLOCK_FMT.format(Date(e.atMs)), detailColor = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // --- device state: every super-global (%ALLCAPS), refreshed each second — so a stuck %CHARGING
        // or a stale %BATT (vs the real battery) is visible at a glance ---
        val globals = PersistentGlobalScope.snapshot(0L).toSortedMap()
        item { FoldHeader("Device state — globals (${globals.size})", openGlobals) { toggle("globals") } }
        if (openGlobals) {
            if (globals.isEmpty()) {
                item { Hint("No globals set yet.") }
            }
            items(globals.entries.toList(), key = { it.key }) { (k, v) ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("%$k", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(0.5f))
                    Text(v.replace("\n", " ⏎ ").take(60), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(0.5f))
                }
            }
        }

        // --- run-on-start: bordered box (plain black fill); the underlined heading folds the contents,
        // the border + heading stay visible. ---
        item { Spacer(Modifier.size(4.dp)) }
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().clickable { toggle("runstart") },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (openRunStart) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "Run on start",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (openRunStart) {
                    Text(
                        "These run automatically on every fresh engine start (after an app update, reboot, or auto-recovery), so your overlays/state come back without running 起動 by hand — add your master 起動完了 ⇨ 起動.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                    if (autoTasks.isEmpty()) {
                        Text("Nothing set to auto-run.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    }
                    autoTasks.forEach { t ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Dot(AMBER, 8.dp)
                            Text(t.name, color = MaterialTheme.colorScheme.onBackground, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            IconButton(onClick = { AutoStartSettings.remove(context, t.id) }) {
                                Icon(Icons.Filled.Close, contentDescription = "Remove", tint = RED)
                            }
                        }
                    }
                    TextButton(onClick = { showPicker = true }) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.size(4.dp))
                        Text("Add task…")
                    }
                }
            }
        }
    }

    if (showPicker) {
        val pickable = tasks.filter { it.id !in autoIds }
        val knownIds = projects.map { it.id }.toSet()
        // Group by project (display order); within each project keep the tasks' own order (their manual
        // `position` — so 71 before 37, etc.), NOT alphabetical. Unfiled bucket for the rest.
        val groups: List<Pair<String, List<Task>>> = buildList {
            projects.forEach { p ->
                val ts = pickable.filter { it.projectId == p.id }
                if (ts.isNotEmpty()) add(p.name to ts)
            }
            val unfiled = pickable.filter { it.projectId !in knownIds }
            if (unfiled.isNotEmpty()) add("Unfiled" to unfiled)
        }
        var expanded by remember { mutableStateOf(setOf<String>()) }
        val allOpen = groups.isNotEmpty() && expanded.size == groups.size
        // A plain Surface dialog (not AlertDialog) so we can draw the yellow border around it.
        Dialog(onDismissRequest = { showPicker = false }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(3.dp, MaterialTheme.colorScheme.primary),
                tonalElevation = 6.dp,
            ) {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Run on start — pick a task",
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { expanded = if (allOpen) emptySet() else groups.map { it.first }.toSet() }) {
                            Text(if (allOpen) "Collapse all" else "Expand all")
                        }
                    }
                    LazyColumn(Modifier.heightIn(max = 400.dp)) {
                        groups.forEach { (name, ts) ->
                            val open = name in expanded
                            item(key = "g-$name") {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { expanded = if (open) expanded - name else expanded + name }
                                        .padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(Modifier.size(6.dp))
                                    Text(name, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                                    Text("${ts.size}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                                }
                            }
                            if (open) {
                                items(ts, key = { "t-${it.id}" }) { t ->
                                    Text(
                                        t.name,
                                        fontSize = 15.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { AutoStartSettings.add(context, t.id); showPicker = false }
                                            .padding(start = 30.dp, top = 10.dp, bottom = 10.dp),
                                    )
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showPicker = false }) { Text("Close") }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusRow(color: Color, name: String, detail: String, detailColor: Color = GREY) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Dot(color, 10.dp)
        Text(name, color = MaterialTheme.colorScheme.onBackground, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(detail, color = detailColor, fontSize = 13.sp)
    }
}

@Composable
private fun Dot(color: Color, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

@Composable
private fun Vital(label: String, value: String, valueColor: Color?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        Text(value, color = valueColor ?: MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun FoldHeader(text: String, open: Boolean, onToggle: () -> Unit) {
    Spacer(Modifier.size(4.dp))
    Row(
        Modifier.fillMaxWidth().clickable { onToggle() }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.size(4.dp))
        Text(text, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
}

private fun rel(ms: Long): String {
    val s = ms / 1000
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m ${s % 60}s"
        else -> "${s / 3600}h ${(s % 3600) / 60}m"
    }
}
