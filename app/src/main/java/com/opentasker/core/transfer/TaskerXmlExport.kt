package com.opentasker.core.transfer

import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.ProfileLifetime
import com.opentasker.core.model.ProfileOverflowPolicy
import com.opentasker.core.model.Task
import com.opentasker.core.model.Variable
import com.opentasker.core.diagnostics.ExportRedactionPolicy

data class TaskerXmlExportReport(
    val xml: String,
    val exportedTaskCount: Int,
    val exportedProfileCount: Int,
    val exportedVariableCount: Int,
    val skippedActions: List<SkippedExportAction>,
    val warnings: List<String>,
    val redactedActionFieldCount: Int = 0,
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
        val redactedActionFields = mutableListOf<String>()
        val taskMap = tasks.associateBy { it.id }
        val redactionContext = ExportRedactionPolicy.Context(
            secretNames = variables.filter(Variable::isSecret).mapTo(linkedSetOf()) { it.name },
            secretValues = variables.filter(Variable::isSecret).mapTo(linkedSetOf()) { it.value },
        )

        val sb = StringBuilder()
        sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.appendLine("""<TaskerData sr="" dvi="1" tv="6.3.13">""")

        tasks.forEach { task ->
            appendTask(sb, task, skipped, redactionContext, redactedActionFields, warnings)
        }

        profiles.forEach { profile ->
            appendProfile(sb, profile, taskMap, warnings)
        }

        val omittedSecretCount = variables.count { it.isSecret }
        if (omittedSecretCount > 0) {
            warnings += "$omittedSecretCount secret variable(s) were omitted and must be re-entered after import."
        }
        if (redactedActionFields.isNotEmpty()) {
            warnings += ExportRedactionPolicy.SENSITIVE_ACTION_WARNING
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
            redactedActionFieldCount = redactedActionFields.size,
        )
    }

    private fun appendTask(
        sb: StringBuilder,
        task: Task,
        skipped: MutableList<SkippedExportAction>,
        redactionContext: ExportRedactionPolicy.Context,
        redactedActionFields: MutableList<String>,
        warnings: MutableList<String>,
    ) {
        sb.appendLine("""  <Task sr="task${task.id}">""")
        sb.appendLine("    <cdate>${System.currentTimeMillis()}</cdate>")
        sb.appendLine("    <id>${task.id}</id>")
        sb.appendLine("    <nme>${escapeXml(task.name)}</nme>")
        sb.appendLine("    <pri>${task.priority}</pri>")

        task.actions.forEachIndexed { index, action ->
            val taskerCode = taskerCodeFor(action)
            if (taskerCode != null) {
                appendAction(sb, index, taskerCode, action, redactionContext, redactedActionFields, warnings)
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

    private fun appendAction(
        sb: StringBuilder,
        index: Int,
        code: String,
        action: ActionSpec,
        redactionContext: ExportRedactionPolicy.Context,
        redactedActionFields: MutableList<String>,
        warnings: MutableList<String>,
    ) {
        val sanitized = ExportRedactionPolicy.sanitizeActionArguments(action.type, action.args, redactionContext)
        sanitized.redactedFields.forEach { field -> redactedActionFields += "${action.type}.$field" }
        val args = sanitized.args
        sb.appendLine("""    <Action sr="act$index" ve="7">""")
        sb.appendLine("      <code>$code</code>")

        when (action.type) {
            "notify.show", "notify.cancel" -> {
                if (action.type == "notify.show") {
                    appendStr(sb, 0, args["title"] ?: "Notification")
                    appendStr(sb, 1, args["text"] ?: "")
                } else {
                    appendStr(sb, 0, args["tag"] ?: "")
                    args["id"]?.let { appendStr(sb, 1, it) }
                }
            }
            "flow.wait" -> {
                val millis = args["millis"]?.toLongOrNull() ?: 1000
                val seconds = millis / 1000
                val remainMs = millis % 1000
                appendInt(sb, 0, remainMs.toString())
                appendInt(sb, 1, seconds.toString())
            }
            "log" -> {
                appendStr(sb, 0, args["message"] ?: "")
            }
            "var.set" -> {
                appendStr(sb, 0, args["name"] ?: "%VAR")
                appendStr(sb, 1, args["value"] ?: "")
            }
            "tts.speak" -> appendStr(sb, 0, args["text"] ?: "")
            "vibrate" -> appendInt(sb, 0, args["millis"] ?: "100")
            "volume.set" -> {
                val level = args["level"] ?: "0"
                if (level == "mute" || level == "unmute") appendStr(sb, 0, level) else appendInt(sb, 0, level)
            }
            "torch.set" -> appendStr(sb, 0, args["state"] ?: "toggle")
            "sound.play" -> appendStr(sb, 0, args["path"] ?: "")
            "app.launch" -> appendStr(sb, 0, args["package"] ?: "")
            "url.open" -> appendStr(sb, 0, args["url"] ?: "")
            "flow.if" -> appendStr(sb, 0, args["condition"] ?: "true")
            "flow.foreach" -> {
                appendStr(sb, 0, args["list"] ?: "")
                appendStr(sb, 1, args["var"] ?: "item")
            }
            "task.run" -> appendStr(sb, 0, args["task"] ?: args["name"] ?: args["id"] ?: "")
            "brightness.set" -> appendStr(sb, 0, args["brightness"] ?: "auto")
            "screen.timeout" -> appendInt(sb, 0, args["millis"] ?: "60000")
        }

        appendConditionList(sb, action, redactionContext, warnings)

        sb.appendLine("    </Action>")
    }

    /**
     * Writes the action's "Run only if" guard (ActionSpec.condition) back as the sibling
     * `<ConditionList>` element real Tasker exports use for the same concept, so an imported
     * guard survives an export/import round trip on any action instead of silently becoming
     * unconditional. Guards this app's richer condition syntax cannot squeeze into a single
     * Tasker lhs/op/rhs triple (boolean chains, template expressions, <=/>=) are dropped with a
     * warning rather than exported wrong. A flow.if whose guard differs from its own
     * args["condition"] test is also warned and dropped: on import the ConditionList overwrites
     * that arg, so exporting the guard would replace the if's actual test.
     */
    private fun appendConditionList(
        sb: StringBuilder,
        action: ActionSpec,
        redactionContext: ExportRedactionPolicy.Context,
        warnings: MutableList<String>,
    ) {
        val guard = action.condition?.trim()?.takeIf { it.isNotBlank() } ?: return
        val ifTest = action.args["condition"]?.trim()?.takeIf { it.isNotBlank() }
        if (action.type == "flow.if" && ifTest != null && guard != ifTest) {
            warnings += "A run-only-if guard on a flow.if differs from the if's own test; " +
                "Tasker XML holds one condition per action, so the guard was dropped."
            return
        }
        if (ExportRedactionPolicy.redactText(guard, redactionContext.secretValues) != guard) {
            warnings += "A run-only-if guard on '${action.type}' contains a secret value and was omitted; " +
                "re-enter it after import."
            return
        }
        val condition = taskerConditionFor(guard)
        if (condition == null) {
            warnings += "The run-only-if guard on '${action.type}' ('$guard') has no Tasker " +
                "ConditionList equivalent and was dropped."
            return
        }
        sb.appendLine("""      <ConditionList sr="if">""")
        sb.appendLine("""        <Condition sr="c0" ve="3">""")
        sb.appendLine("          <lhs>${escapeXml(condition.lhs)}</lhs>")
        sb.appendLine("          <op>${condition.op}</op>")
        sb.appendLine("          <rhs>${escapeXml(condition.rhs)}</rhs>")
        sb.appendLine("        </Condition>")
        sb.appendLine("      </ConditionList>")
    }

    private data class TaskerCondition(val lhs: String, val op: String, val rhs: String)

    /**
     * Reverse of TaskerXmlImport.parseImportedCondition: turns this app's condition string back
     * into a single Tasker lhs/op/rhs triple when it has that shape. Anything the triple cannot
     * express (&&/|| chains, template expressions, unsupported operators) returns null and the
     * caller reports the loss.
     */
    private fun taskerConditionFor(condition: String): TaskerCondition? {
        if (condition.contains("&&") || condition.contains("||") || condition.contains("{{")) return null
        // Binary comparisons win over the unary is_set/not_set suffix, matching the evaluator:
        // "%status == is_set" is an equality check against the literal word, not an existence
        // check, so it must export as op 0 rather than op 12.
        for ((token, op) in BINARY_CONDITION_OPS) {
            val marker = " $token "
            val index = condition.indexOf(marker)
            if (index > 0) {
                val lhs = condition.substring(0, index).trim()
                val rhs = condition.substring(index + marker.length).trim()
                if (lhs.isEmpty()) return null
                return TaskerCondition(lhs, op, rhs)
            }
        }
        for ((suffix, op) in UNARY_CONDITION_OPS) {
            if (condition.endsWith(suffix, ignoreCase = true)) {
                val lhs = condition.dropLast(suffix.length).trim()
                if (lhs.isNotEmpty()) return TaskerCondition(lhs, op, "")
            }
        }
        return null
    }

    // App condition operators -> Tasker Condition <op> codes (the import table's canonical
    // inverse: regex ops 4/5 and numeric-equality ops 8/9 already collapsed on import). Two-char
    // tokens are matched before their one-char substrings; every token is whitespace-bounded, so
    // "a <= b" matches nothing here and is reported as unrepresentable instead of exported as "<".
    private val BINARY_CONDITION_OPS = listOf(
        "==" to "0",
        "!=" to "1",
        "!~" to "3",
        "~" to "2",
        "<" to "6",
        ">" to "7",
    )

    private val UNARY_CONDITION_OPS = listOf(
        " is_set" to "12",
        " not_set" to "13",
    )

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
