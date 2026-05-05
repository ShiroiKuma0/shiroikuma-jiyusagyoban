package com.opentasker.core.transfer

import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task
import com.opentasker.core.model.Variable
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

data class TaskerXmlImportReport(
    val bundle: OpenTaskerBundle,
    val sourceTaskCount: Int,
    val sourceProfileCount: Int,
    val sourceVariableCount: Int,
    val sourceSceneCount: Int,
    val mappedActions: List<TaskerMappedAction>,
    val unsupportedActions: List<TaskerUnsupportedAction>,
    val warnings: List<String> = emptyList(),
    val lossyWarnings: List<String> = emptyList(),
)

data class TaskerMappedAction(
    val taskName: String,
    val taskerCode: String,
    val openTaskerActionId: String,
)

data class TaskerUnsupportedAction(
    val taskName: String,
    val taskerCode: String,
    val actionIndex: Int,
)

object TaskerXmlImporter {
    fun parse(
        rawXml: String,
        appVersion: String,
        importedAtEpochMs: Long = System.currentTimeMillis(),
    ): TaskerXmlImportReport {
        val doc = parseDocument(rawXml)
        val warnings = mutableListOf<String>()
        val lossyWarnings = mutableListOf<String>()
        val mappedActions = mutableListOf<TaskerMappedAction>()
        val unsupportedActions = mutableListOf<TaskerUnsupportedAction>()

        val tasks = parseTasks(doc, mappedActions, unsupportedActions)
        val variables = parseVariables(doc, lossyWarnings)
        val profiles = parseProfiles(doc, tasks.map { it.id }.toSet(), lossyWarnings)
        val sceneCount = doc.elementsByTagName("Scene").size
        if (sceneCount > 0) {
            lossyWarnings += "Tasker scenes are not imported yet; $sceneCount scene(s) were excluded."
        }

        val bundle = OpenTaskerBundleCodec.build(
            appVersion = appVersion,
            exportedAtEpochMs = importedAtEpochMs,
            profiles = profiles,
            tasks = tasks,
            variables = variables,
            name = "Tasker XML Import",
            description = "Converted from a Tasker XML export. Review warnings before enabling imported profiles.",
        )
        val mergedWarnings = (bundle.metadata.warnings + warnings + lossyWarnings).distinct()
        val reportBundle = bundle.copy(
            metadata = bundle.metadata.copy(warnings = mergedWarnings),
        )

        return TaskerXmlImportReport(
            bundle = reportBundle,
            sourceTaskCount = doc.elementsByTagName("Task").size,
            sourceProfileCount = doc.elementsByTagName("Profile").size,
            sourceVariableCount = doc.elementsByTagName("Variable").size,
            sourceSceneCount = sceneCount,
            mappedActions = mappedActions,
            unsupportedActions = unsupportedActions,
            warnings = warnings.distinct(),
            lossyWarnings = lossyWarnings.distinct(),
        )
    }

    private fun parseTasks(
        doc: Document,
        mappedActions: MutableList<TaskerMappedAction>,
        unsupportedActions: MutableList<TaskerUnsupportedAction>,
    ): List<Task> {
        val usedIds = mutableSetOf<Long>()
        return doc.elementsByTagName("Task").mapIndexed { index, element ->
            val id = uniqueId(sourceId(element, index), usedIds)
            val name = element.childText("nme", "name").ifBlank { "Tasker Task $id" }
            val actions = element.directChildren("Action").mapIndexed { actionIndex, actionElement ->
                val parsed = parseAction(name, actionElement, actionIndex)
                parsed.mapped?.let(mappedActions::add)
                parsed.unsupported?.let(unsupportedActions::add)
                parsed.action
            }
            Task(id = id, name = name, actions = actions)
        }
    }

    private fun parseProfiles(
        doc: Document,
        taskIds: Set<Long>,
        lossyWarnings: MutableList<String>,
    ): List<Profile> {
        val usedIds = mutableSetOf<Long>()
        return doc.elementsByTagName("Profile").mapIndexedNotNull { index, element ->
            val id = uniqueId(sourceId(element, index), usedIds)
            val name = element.childText("nme", "name").ifBlank { "Tasker Profile $id" }
            val enterTaskId = element.childText("mid0", "task", "tid", "enterTaskId").toLongOrNull()
            if (enterTaskId == null || enterTaskId !in taskIds) {
                lossyWarnings += "Profile '$name' was skipped because its entry task could not be mapped."
                return@mapIndexedNotNull null
            }

            val contexts = parseContexts(element, name, lossyWarnings)
            if (contexts.isEmpty()) {
                lossyWarnings += "Profile '$name' was skipped because it has no supported Tasker contexts."
                return@mapIndexedNotNull null
            }

            Profile(
                id = id,
                name = name,
                enabled = !element.childText("off").equals("true", ignoreCase = true),
                enterTaskId = enterTaskId,
                exitTaskId = element.childText("mid1", "exitTaskId").toLongOrNull()?.takeIf { it in taskIds },
                contexts = contexts,
            )
        }
    }

    private fun parseVariables(doc: Document, lossyWarnings: MutableList<String>): List<Variable> =
        doc.elementsByTagName("Variable").mapNotNull { element ->
            val name = element.childText("nme", "name").ifBlank {
                lossyWarnings += "A Tasker variable was skipped because it had no name."
                return@mapNotNull null
            }
            Variable(
                name = name,
                value = element.childText("val", "value"),
                isGlobal = name.dropWhile { it == '%' }.firstOrNull()?.isUpperCase() == true,
            )
        }

    private fun parseContexts(
        profile: Element,
        profileName: String,
        lossyWarnings: MutableList<String>,
    ): List<ContextSpec> =
        profile.directChildElements().mapNotNull { child ->
            when (child.tagName.lowercase()) {
                "time" -> parseTimeContext(child, profileName, lossyWarnings)
                "day" -> child.childText("days", "day")
                    .takeIf { it.isNotBlank() }
                    ?.let { ContextSpec(ContextType.DAY, mapOf("days" to it)) }
                "application", "app" -> child.childText("package", "pkg", "app")
                    .takeIf { it.isNotBlank() }
                    ?.let { ContextSpec(ContextType.APPLICATION, mapOf("package" to it)) }
                "state" -> parseKeyValueContext(ContextType.STATE, child)
                "event" -> parseKeyValueContext(ContextType.EVENT, child)
                else -> {
                    if (child.tagName !in PROFILE_SCALAR_TAGS) {
                        lossyWarnings += "Profile '$profileName' has unsupported Tasker context '${child.tagName}'."
                    }
                    null
                }
            }
        }

    private fun parseTimeContext(
        element: Element,
        profileName: String,
        lossyWarnings: MutableList<String>,
    ): ContextSpec? {
        val start = element.childText("from", "start").ifBlank {
            clockFromParts(element.childText("fh").toIntOrNull(), element.childText("fm").toIntOrNull())
        }
        val end = element.childText("to", "end").ifBlank {
            clockFromParts(element.childText("th").toIntOrNull(), element.childText("tm").toIntOrNull())
        }
        if (start.isBlank() || end.isBlank()) {
            lossyWarnings += "Profile '$profileName' has a Tasker Time context without a supported start/end window."
            return null
        }
        return ContextSpec(ContextType.TIME, mapOf("start" to start, "end" to end))
    }

    private fun parseKeyValueContext(type: ContextType, element: Element): ContextSpec? {
        val config = buildMap {
            element.childText("event", "name", "key").takeIf { it.isNotBlank() }?.let { key ->
                if (type == ContextType.EVENT) put("event", key) else put("key", key)
            }
            element.childText("value", "val").takeIf { it.isNotBlank() }?.let { put("value", it) }
            element.childText("filter").takeIf { it.isNotBlank() }?.let { put("filter", it) }
        }
        return config.takeIf { it.isNotEmpty() }?.let { ContextSpec(type, it) }
    }

    private fun parseAction(taskName: String, element: Element, actionIndex: Int): ParsedTaskerAction {
        val code = element.childText("code").ifBlank { element.getAttribute("code") }.ifBlank { "unknown" }
        val strings = element.actionStrings()
        val ints = element.actionInts()
        val normalized = code.lowercase()
        val action = when (normalized) {
            "548", "notify", "notify.show" -> ActionSpec(
                type = "notify.show",
                label = "Tasker notification",
                args = mapOf(
                    "title" to strings.getOrElse(0) { "Tasker notification" },
                    "text" to strings.getOrElse(1) { strings.getOrElse(0) { "Imported from Tasker" } },
                ),
            )
            "547", "flash", "toast" -> ActionSpec(
                type = "notify.show",
                label = "Tasker flash",
                args = mapOf(
                    "title" to "Tasker",
                    "text" to strings.getOrElse(0) { "Imported Tasker flash action" },
                ),
            )
            "30", "wait", "flow.wait" -> ActionSpec(
                type = "flow.wait",
                label = "Tasker wait",
                args = mapOf("millis" to waitMillis(strings, ints).toString()),
            )
            "log" -> ActionSpec(
                type = "log",
                label = "Tasker log",
                args = mapOf("message" to strings.getOrElse(0) { "Imported Tasker log action" }),
            )
            "var.set", "variable.set" -> ActionSpec(
                type = "var.set",
                label = "Tasker variable set",
                args = mapOf(
                    "name" to strings.getOrElse(0) { "%IMPORTED" },
                    "value" to strings.getOrElse(1) { "" },
                ),
            )
            else -> unsupportedAction(code)
        }
        val unsupported = if (action.type == TASKER_UNSUPPORTED_ACTION_ID) {
            TaskerUnsupportedAction(taskName = taskName, taskerCode = code, actionIndex = actionIndex)
        } else {
            null
        }
        val mapped = if (unsupported == null) {
            TaskerMappedAction(taskName = taskName, taskerCode = code, openTaskerActionId = action.type)
        } else {
            null
        }
        return ParsedTaskerAction(action = action, mapped = mapped, unsupported = unsupported)
    }

    private fun unsupportedAction(code: String): ActionSpec =
        ActionSpec(
            type = TASKER_UNSUPPORTED_ACTION_ID,
            label = "Unsupported Tasker action $code",
            args = mapOf(
                "taskerCode" to code,
                "summary" to "This Tasker action was preserved as an unsupported placeholder during import.",
            ),
        )

    private fun parseDocument(rawXml: String): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
            setFeatureSafely("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeatureSafely("http://xml.org/sax/features/external-general-entities", false)
            setFeatureSafely("http://xml.org/sax/features/external-parameter-entities", false)
            setFeatureSafely("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        return factory.newDocumentBuilder().parse(InputSource(StringReader(rawXml)))
    }

    private fun Element.actionStrings(): List<String> =
        directChildren("Str")
            .sortedBy { it.argIndex() }
            .map { it.getAttribute("val").ifBlank { it.textContent.orEmpty() }.trim() }

    private fun Element.actionInts(): List<Int> =
        directChildren("Int")
            .sortedBy { it.argIndex() }
            .mapNotNull { it.getAttribute("val").ifBlank { it.textContent.orEmpty() }.trim().toIntOrNull() }

    private fun waitMillis(strings: List<String>, ints: List<Int>): Long {
        val explicit = strings.firstOrNull()?.trim()?.toLongOrNull()
        if (explicit != null) return explicit
        if (ints.isEmpty()) return 1_000L
        if (ints.size >= 2) {
            val milliseconds = ints.getOrElse(0) { 0 }.toLong()
            val seconds = ints.getOrElse(1) { 0 }.toLong()
            val minutes = ints.getOrElse(2) { 0 }.toLong()
            val hours = ints.getOrElse(3) { 0 }.toLong()
            val days = ints.getOrElse(4) { 0 }.toLong()
            return milliseconds + seconds * 1_000L + minutes * 60_000L + hours * 3_600_000L + days * 86_400_000L
        }
        val first = ints.first().toLong()
        return if (first > 1_000L) first else first * 1_000L
    }

    private fun sourceId(element: Element, index: Int): Long =
        element.childText("id").toLongOrNull()
            ?: element.getAttribute("sr").filter(Char::isDigit).toLongOrNull()
            ?: (index + 1L)

    private fun uniqueId(preferred: Long, used: MutableSet<Long>): Long {
        var candidate = preferred.takeIf { it > 0 } ?: 1L
        while (!used.add(candidate)) candidate++
        return candidate
    }

    private fun clockFromParts(hour: Int?, minute: Int?): String =
        if (hour != null && hour in 0..23) "%02d:%02d".format(hour, minute?.coerceIn(0, 59) ?: 0) else ""

    private fun Document.elementsByTagName(name: String): List<Element> =
        getElementsByTagName(name).asElementList()

    private fun Element.directChildren(name: String): List<Element> =
        directChildElements().filter { it.tagName.equals(name, ignoreCase = true) }

    private fun Element.directChildElements(): List<Element> =
        childNodes.asElementList()

    private fun Element.childText(vararg names: String): String =
        names.firstNotNullOfOrNull { name ->
            directChildren(name).firstOrNull()?.textContent?.trim()?.takeIf { it.isNotBlank() }
        }.orEmpty()

    private fun Element.argIndex(): Int =
        getAttribute("sr").filter(Char::isDigit).toIntOrNull() ?: Int.MAX_VALUE

    private fun org.w3c.dom.NodeList.asElementList(): List<Element> =
        (0 until length).mapNotNull { index -> item(index).takeIf { it.nodeType == Node.ELEMENT_NODE } as? Element }

    private fun DocumentBuilderFactory.setFeatureSafely(name: String, value: Boolean) {
        runCatching { setFeature(name, value) }
    }

    private data class ParsedTaskerAction(
        val action: ActionSpec,
        val mapped: TaskerMappedAction?,
        val unsupported: TaskerUnsupportedAction?,
    )

    private val PROFILE_SCALAR_TAGS = setOf(
        "cdate",
        "edate",
        "flags",
        "id",
        "mid0",
        "mid1",
        "nme",
        "name",
        "off",
        "pri",
    )

    const val TASKER_UNSUPPORTED_ACTION_ID = "tasker.unsupported"
}
