package com.opentasker.core.transfer

import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.ProfileLifetime
import com.opentasker.core.model.ProfileOverflowPolicy
import com.opentasker.core.model.Task
import com.opentasker.core.model.Variable

data class TaskerXmlExportReport(
    val xml: String,
    val exportedTaskCount: Int,
    val exportedProfileCount: Int,
    val exportedVariableCount: Int,
    val skippedActions: List<SkippedExportAction>,
    val warnings: List<String>,
)

data class SkippedExportAction(
    val taskName: String,
    val actionType: String,
    val reason: String,
)

object TaskerXmlExporter {

    private val REVERSE_ACTION_MAP: Map<String, String> = mapOf(
        "notify.show" to "523",
        "notify.cancel" to "779",
        "flow.wait" to "30",
        "log" to "905",
        "var.set" to "547",
        "tts.speak" to "559",
        "vibrate" to "61",
        "torch.set" to "511",
        "sound.play" to "192",
        "sound.stop" to "449",
        "track.next" to "451",
        "track.previous" to "453",
        "home.go" to "25",
        "app.launch" to "20",
        "url.open" to "104",
        "screenshot.take" to "176",
        "flow.if" to "37",
        "flow.else" to "43",
        "flow.endif" to "38",
        "flow.foreach" to "39",
        "flow.endfor" to "40",
        "flow.stop" to "137",
        "task.run" to "130",
        "brightness.set" to "810",
        "screen.timeout" to "812",
    )

    fun export(
        profiles: List<Profile>,
        tasks: List<Task>,
        variables: List<Variable> = emptyList(),
    ): TaskerXmlExportReport {
        val warnings = mutableListOf<String>()
        val skipped = mutableListOf<SkippedExportAction>()
        val taskMap = tasks.associateBy { it.id }

        val sb = StringBuilder()
        sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.appendLine("""<TaskerData sr="" dvi="1" tv="6.3.13">""")

        tasks.forEach { task ->
            appendTask(sb, task, skipped)
        }

        profiles.forEach { profile ->
            appendProfile(sb, profile, taskMap, warnings)
        }

        val omittedSecretCount = variables.count { it.isSecret }
        if (omittedSecretCount > 0) {
            warnings += "$omittedSecretCount secret variable(s) were omitted and must be re-entered after import."
        }
        variables.filterNot { it.isSecret }.forEach { variable ->
            appendVariable(sb, variable)
        }

        sb.appendLine("</TaskerData>")

        return TaskerXmlExportReport(
            xml = sb.toString(),
            exportedTaskCount = tasks.size,
            exportedProfileCount = profiles.size,
            exportedVariableCount = variables.count { !it.isSecret },
            skippedActions = skipped,
            warnings = warnings,
        )
    }

    private fun appendTask(
        sb: StringBuilder,
        task: Task,
        skipped: MutableList<SkippedExportAction>,
    ) {
        sb.appendLine("""  <Task sr="task${task.id}">""")
        sb.appendLine("    <cdate>${System.currentTimeMillis()}</cdate>")
        sb.appendLine("    <id>${task.id}</id>")
        sb.appendLine("    <nme>${escapeXml(task.name)}</nme>")
        sb.appendLine("    <pri>${task.priority}</pri>")

        task.actions.forEachIndexed { index, action ->
            val taskerCode = taskerCodeFor(action)
            if (taskerCode != null) {
                appendAction(sb, index, taskerCode, action)
            } else {
                skipped += SkippedExportAction(
                    taskName = task.name,
                    actionType = action.type,
                    reason = "No Tasker equivalent for action type '${action.type}'",
                )
            }
        }

        sb.appendLine("  </Task>")
    }

    private fun appendAction(sb: StringBuilder, index: Int, code: String, action: ActionSpec) {
        sb.appendLine("""    <Action sr="act$index" ve="7">""")
        sb.appendLine("      <code>$code</code>")

        when (action.type) {
            "notify.show", "notify.cancel" -> {
                if (action.type == "notify.show") {
                    appendStr(sb, 0, action.args["title"] ?: "Notification")
                    appendStr(sb, 1, action.args["text"] ?: "")
                } else {
                    appendStr(sb, 0, action.args["tag"] ?: "")
                    action.args["id"]?.let { appendStr(sb, 1, it) }
                }
            }
            "flow.wait" -> {
                val millis = action.args["millis"]?.toLongOrNull() ?: 1000
                val seconds = millis / 1000
                val remainMs = millis % 1000
                appendInt(sb, 0, remainMs.toString())
                appendInt(sb, 1, seconds.toString())
            }
            "log" -> {
                appendStr(sb, 0, action.args["message"] ?: "")
            }
            "var.set" -> {
                appendStr(sb, 0, action.args["name"] ?: "%VAR")
                appendStr(sb, 1, action.args["value"] ?: "")
            }
            "tts.speak" -> appendStr(sb, 0, action.args["text"] ?: "")
            "vibrate" -> appendInt(sb, 0, action.args["millis"] ?: "100")
            "volume.set" -> {
                val level = action.args["level"] ?: "0"
                if (level == "mute" || level == "unmute") appendStr(sb, 0, level) else appendInt(sb, 0, level)
            }
            "torch.set" -> appendStr(sb, 0, action.args["state"] ?: "toggle")
            "sound.play" -> appendStr(sb, 0, action.args["path"] ?: "")
            "app.launch" -> appendStr(sb, 0, action.args["package"] ?: "")
            "url.open" -> appendStr(sb, 0, action.args["url"] ?: "")
            "flow.if" -> appendStr(sb, 0, action.args["condition"] ?: "true")
            "flow.foreach" -> {
                appendStr(sb, 0, action.args["list"] ?: "")
                appendStr(sb, 1, action.args["var"] ?: "item")
            }
            "task.run" -> appendStr(sb, 0, action.args["task"] ?: action.args["name"] ?: action.args["id"] ?: "")
            "brightness.set" -> appendStr(sb, 0, action.args["brightness"] ?: "auto")
            "screen.timeout" -> appendInt(sb, 0, action.args["millis"] ?: "60000")
        }

        sb.appendLine("    </Action>")
    }

    private fun appendProfile(
        sb: StringBuilder,
        profile: Profile,
        taskMap: Map<Long, Task>,
        warnings: MutableList<String>,
    ) {
        sb.appendLine("""  <Profile sr="prof${profile.id}" ve="2">""")
        sb.appendLine("    <cdate>${System.currentTimeMillis()}</cdate>")
        sb.appendLine("    <id>${profile.id}</id>")
        sb.appendLine("    <mid0>${profile.enterTaskId}</mid0>")
        profile.exitTaskId?.let {
            sb.appendLine("    <mid1>$it</mid1>")
        }
        sb.appendLine("    <nme>${escapeXml(profile.name)}</nme>")
        if (profile.priority != 0) sb.appendLine("    <priority>${profile.priority}</priority>")
        if (profile.gracePeriodSec > 0) sb.appendLine("    <gracePeriodSec>${profile.gracePeriodSec}</gracePeriodSec>")
        if (profile.lifetime != ProfileLifetime.NEVER) {
            sb.appendLine("    <lifetime>${profile.lifetime.name}</lifetime>")
            profile.expiresAtMs?.let { sb.appendLine("    <expiresAtMs>$it</expiresAtMs>") }
        }
        profile.maxActiveExecutions?.let { sb.appendLine("    <maxActiveExecutions>$it</maxActiveExecutions>") }
        profile.burstLimit?.let { sb.appendLine("    <burstLimit>$it</burstLimit>") }
        if (profile.overflowPolicy != ProfileOverflowPolicy.LOG) {
            sb.appendLine("    <overflowPolicy>${profile.overflowPolicy.name}</overflowPolicy>")
        }

        if (profile.contextExpression != null) {
            warnings += "Profile '${profile.name}' uses nested context grouping; Tasker XML cannot represent the grouping, so only leaf contexts were exported."
        }

        profile.contexts.forEachIndexed { index, context ->
            val exported = exportContext(context, index)
            if (exported != null) {
                sb.appendLine(exported)
            } else {
                warnings += "Profile '${profile.name}' context ${context.type.name} has no Tasker equivalent."
            }
        }

        sb.appendLine("  </Profile>")
    }

    private fun exportContext(context: ContextSpec, index: Int): String? {
        return when (context.type) {
            ContextType.TIME -> {
                val start = context.config["start"] ?: return null
                val end = context.config["end"] ?: return null
                val (fh, fm) = parseClockParts(start) ?: return null
                val (th, tm) = parseClockParts(end) ?: return null
                buildString {
                    appendLine("""    <Time sr="con$index">""")
                    appendLine("      <fh>$fh</fh>")
                    appendLine("      <fm>$fm</fm>")
                    appendLine("      <th>$th</th>")
                    appendLine("      <tm>$tm</tm>")
                    append("    </Time>")
                }
            }
            ContextType.DAY -> {
                val days = context.config["days"] ?: return null
                buildString {
                    appendLine("""    <Day sr="con$index">""")
                    appendLine("      <days>${escapeXml(days)}</days>")
                    append("    </Day>")
                }
            }
            ContextType.APPLICATION -> {
                val pkg = context.config["package"] ?: return null
                buildString {
                    appendLine("""    <Application sr="con$index">""")
                    appendLine("      <package>${escapeXml(pkg)}</package>")
                    append("    </Application>")
                }
            }
            ContextType.STATE -> {
                val key = context.config["key"] ?: return null
                buildString {
                    appendLine("""    <State sr="con$index">""")
                    appendLine("      <name>${escapeXml(key)}</name>")
                    context.config["value"]?.let { appendLine("      <value>${escapeXml(it)}</value>") }
                    append("    </State>")
                }
            }
            ContextType.EVENT -> {
                val event = context.config["event"] ?: return null
                buildString {
                    appendLine("""    <Event sr="con$index">""")
                    appendLine("      <event>${escapeXml(event)}</event>")
                    context.config["value"]?.let { appendLine("      <value>${escapeXml(it)}</value>") }
                    append("    </Event>")
                }
            }
            else -> null
        }
    }

    private fun appendVariable(sb: StringBuilder, variable: Variable) {
        sb.appendLine("  <Variable sr=\"var\">")
        sb.appendLine("    <n>${escapeXml("%" + variable.name)}</n>")
        sb.appendLine("    <v>${escapeXml(variable.value)}</v>")
        sb.appendLine("  </Variable>")
    }

    private fun appendStr(sb: StringBuilder, index: Int, value: String) {
        sb.appendLine("""      <Str sr="arg$index" ve="3">${escapeXml(value)}</Str>""")
    }

    private fun appendInt(sb: StringBuilder, index: Int, value: String) {
        sb.appendLine("""      <Int sr="arg$index" val="${escapeXml(value)}"/>""")
    }

    private fun taskerCodeFor(action: ActionSpec): String? {
        if (action.type == "volume.set") {
            return if (action.args["stream"].orEmpty().lowercase() in setOf("music", "media")) "307" else null
        }
        return REVERSE_ACTION_MAP[action.type]
    }

    private fun parseClockParts(clock: String): Pair<Int, Int>? {
        val parts = clock.split(":")
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        return hour to minute
    }

    private fun escapeXml(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
}
