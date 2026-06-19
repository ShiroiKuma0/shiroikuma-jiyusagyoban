package com.opentasker.core.transfer

import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
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
        "notify.show" to "548",
        "flow.wait" to "30",
        "log" to "905",
        "var.set" to "547",
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

        variables.forEach { variable ->
            appendVariable(sb, variable)
        }

        sb.appendLine("</TaskerData>")

        return TaskerXmlExportReport(
            xml = sb.toString(),
            exportedTaskCount = tasks.size,
            exportedProfileCount = profiles.size,
            exportedVariableCount = variables.size,
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
            val taskerCode = REVERSE_ACTION_MAP[action.type]
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
            "notify.show" -> {
                appendStr(sb, 0, action.args["title"] ?: "Notification")
                appendStr(sb, 1, action.args["text"] ?: "")
            }
            "flow.wait" -> {
                val millis = action.args["millis"]?.toLongOrNull() ?: 1000
                val seconds = millis / 1000
                val remainMs = millis % 1000
                appendStr(sb, 0, seconds.toString())
                appendStr(sb, 1, remainMs.toString())
            }
            "log" -> {
                appendStr(sb, 0, action.args["message"] ?: "")
            }
            "var.set" -> {
                appendStr(sb, 0, action.args["name"] ?: "%VAR")
                appendStr(sb, 1, action.args["value"] ?: "")
            }
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
