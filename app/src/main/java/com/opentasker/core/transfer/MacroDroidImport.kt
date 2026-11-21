package com.opentasker.core.transfer

import com.opentasker.core.contexts.DaySchedule
import com.opentasker.core.engine.FlowControl
import com.opentasker.core.engine.variables.VariableExpander
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.ContextBooleanOperator
import com.opentasker.core.model.ContextExpressionNode
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task
import com.opentasker.core.model.Variable
import com.opentasker.core.model.VariableNamePolicy
import com.opentasker.core.validation.InputValidation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.math.BigDecimal
import java.util.Locale

const val MACRODROID_UNSUPPORTED_ACTION_ID = "macrodroid.unsupported"

data class MacroDroidImportReport(
    val bundle: OpenTaskerBundle,
    val sourceMacroCount: Int,
    val sourceVariableCount: Int,
    val sourceActionCount: Int,
    val sourceTriggerCount: Int,
    val sourceConstraintCount: Int,
    val mappedActions: List<MacroDroidMappedAction>,
    val unsupportedActions: List<MacroDroidUnsupportedAction>,
    val mappedTriggers: List<MacroDroidMappedTrigger>,
    val unsupportedTriggers: List<MacroDroidUnsupportedTrigger>,
    val unsupportedConstraints: List<MacroDroidUnsupportedConstraint>,
    val warnings: List<String> = emptyList(),
    val lossyWarnings: List<String> = emptyList(),
)

data class MacroDroidMappedAction(
    val macroName: String,
    val classType: String,
    val actionIndex: Int,
    val openTaskerActionIds: List<String>,
)

data class MacroDroidUnsupportedAction(
    val macroName: String,
    val classType: String,
    val actionIndex: Int,
    val reason: String,
)

data class MacroDroidMappedTrigger(
    val macroName: String,
    val classType: String,
    val triggerIndex: Int,
    val openTaskerContextTypes: List<ContextType>,
)

data class MacroDroidUnsupportedTrigger(
    val macroName: String,
    val classType: String,
    val triggerIndex: Int,
    val reason: String,
)

data class MacroDroidUnsupportedConstraint(
    val owner: String,
    val classType: String,
    val reason: String,
)

/** Converts MacroDroid full-backup `.mdr` and single-macro `.macro` JSON exports. */
object MacroDroidImporter {
    private val json = Json {
        ignoreUnknownKeys = true
        allowTrailingComma = true
    }

    fun parse(
        rawJson: String,
        appVersion: String,
        importedAtEpochMs: Long = System.currentTimeMillis(),
    ): MacroDroidImportReport = parse(
        rawJson = rawJson,
        appVersion = appVersion,
        importedAtEpochMs = importedAtEpochMs,
        budget = ImportResourceBudget.Default,
        sourceBudget = ImportResourceBudget.MacroDroidSource,
    )

    internal fun parse(
        rawJson: String,
        appVersion: String,
        importedAtEpochMs: Long,
        budget: ImportResourceBudget,
        sourceBudget: ImportResourceBudget = budget,
    ): MacroDroidImportReport {
        val normalized = rawJson.removePrefix(BYTE_ORDER_MARK).trim()
        require(normalized.isNotEmpty()) { "MacroDroid import is empty." }
        // Checked before the scan: a file that was never JSON otherwise fails on token or depth
        // limits, which reads as "too big" when it's the wrong kind of file.
        require(normalized.startsWith("{")) { "MacroDroid import root must be a JSON object." }
        ImportResourceGuard.requireJsonPreflight(normalized, sourceBudget, allowComments = false)

        val root = json.parseToJsonElement(normalized) as? JsonObject
            ?: throw IllegalArgumentException("MacroDroid import root must be a JSON object.")
        val macros = root.macroObjects()
        require(macros.isNotEmpty()) { "MacroDroid import contains no macros." }
        val globalVariables = root.objectArray("variables")
        val localVariableCount = macros.sumOf { it.objectArray("localVariables").size }
        val sourceActionCount = macros.sumOf { it.objectArray("m_actionList").size }
        val sourceTriggerCount = macros.sumOf { it.objectArray("m_triggerList").size }
        val sourceConstraintCount = macros.sumOf { macro ->
            macro.objectArray("m_constraintList").size +
                macro.objectArray("m_actionList").sumOf { it.objectArray("m_constraintList").size } +
                macro.objectArray("m_triggerList").sumOf { it.objectArray("m_constraintList").size }
        }
        ImportResourceGuard.requireSourceCounts(
            entities = macros.size.toLong() * 2L + globalVariables.size + localVariableCount,
            actions = sourceActionCount.toLong(),
            contexts = sourceTriggerCount.toLong() + sourceConstraintCount,
            budget = budget,
        )

        val warnings = mutableListOf<String>()
        val lossyWarnings = mutableListOf<String>()
        val mappedActions = mutableListOf<MacroDroidMappedAction>()
        val unsupportedActions = mutableListOf<MacroDroidUnsupportedAction>()
        val mappedTriggers = mutableListOf<MacroDroidMappedTrigger>()
        val unsupportedTriggers = mutableListOf<MacroDroidUnsupportedTrigger>()
        val unsupportedConstraints = mutableListOf<MacroDroidUnsupportedConstraint>()
        val usedTaskIds = mutableSetOf<Long>()
        val usedProfileIds = mutableSetOf<Long>()

        val tasks = mutableListOf<Task>()
        val profiles = mutableListOf<Profile>()
        macros.forEachIndexed { macroIndex, macro ->
            val sourceName = macro.string("m_name").ifBlank { "MacroDroid macro ${macroIndex + 1}" }
            val name = sourceName.take(InputValidation.MAX_NAME_LENGTH)
            if (name != sourceName) {
                lossyWarnings += "Macro '$sourceName' had a long name. It was shortened to '$name'."
            }
            val guid = macro.long("m_GUID")
            val taskId = uniquePositiveId(guid, macroIndex, usedTaskIds)
            val profileId = uniquePositiveId(guid, macroIndex, usedProfileIds)
            val actionObjects = macro.objectArray("m_actionList")
            val blocks = OpenBlocks()
            val actions = (
                actionObjects.flatMapIndexed { actionIndex, action ->
                    parseAction(
                        macroName = name,
                        action = action,
                        actionIndex = actionIndex,
                        blocks = blocks,
                        mappedActions = mappedActions,
                        unsupportedActions = unsupportedActions,
                        unsupportedConstraints = unsupportedConstraints,
                        lossyWarnings = lossyWarnings,
                    )
                } + blocks.closeRemaining(name, lossyWarnings)
                ).ifEmpty {
                val reason = "The source macro has no actions."
                unsupportedActions += MacroDroidUnsupportedAction(name, "EmptyActionList", 0, reason)
                lossyWarnings += "Macro '$name' has no actions. A disabled placeholder step was added."
                listOf(unsupportedAction("EmptyActionList", reason))
            }
            tasks += Task(id = taskId, name = name, actions = actions)

            val contexts = mutableListOf<ContextSpec>()
            val triggerNodes = mutableListOf<ContextExpressionNode>()
            val triggerObjects = macro.objectArray("m_triggerList")
            triggerObjects.forEachIndexed { triggerIndex, trigger ->
                val mapping = parseTrigger(
                    macroName = name,
                    trigger = trigger,
                    triggerIndex = triggerIndex,
                    mappedTriggers = mappedTriggers,
                    unsupportedTriggers = unsupportedTriggers,
                    unsupportedConstraints = unsupportedConstraints,
                    lossyWarnings = lossyWarnings,
                )
                triggerNodes += contexts.appendAsAndGroup(mapping.contexts)
            }
            if (triggerObjects.isEmpty()) {
                val reason = "The source macro has no triggers."
                unsupportedTriggers += MacroDroidUnsupportedTrigger(name, "EmptyTriggerList", 0, reason)
                lossyWarnings += "Macro '$name' has no triggers. A non-matching placeholder context was added."
                triggerNodes += contexts.appendAsAndGroup(listOf(unsupportedContext("EmptyTriggerList")))
            }

            val constraintNodes = macro.objectArray("m_constraintList").mapIndexed { constraintIndex, constraint ->
                val classType = constraint.classType("UnknownConstraint")
                val owner = "Macro '$name' constraint ${constraintIndex + 1}"
                unsupportedConstraints += MacroDroidUnsupportedConstraint(
                    owner = owner,
                    classType = classType,
                    reason = "MacroDroid constraints do not have a safe OpenTasker mapping yet.",
                )
                lossyWarnings += "$owner ($classType) was preserved as a non-matching placeholder context."
                contexts.appendAsAndGroup(listOf(unsupportedContext(classType)))
            }

            val triggerExpression = triggerNodes.asGroup(ContextBooleanOperator.OR)
            val constraintExpression = constraintNodes.asGroup(
                if (macro.boolean("m_isOrCondition")) ContextBooleanOperator.OR else ContextBooleanOperator.AND,
            )
            val expression = listOfNotNull(triggerExpression, constraintExpression)
                .asGroup(ContextBooleanOperator.AND)
                ?.takeIf { contexts.size > 1 }

            val description = macro.string("m_description")
            if (description.isNotBlank()) {
                lossyWarnings += "Macro '$name' has a description that is not represented on OpenTasker profiles."
            }
            val localVariables = macro.objectArray("localVariables")
            if (localVariables.isNotEmpty()) {
                lossyWarnings += "Macro '$name' has ${localVariables.size} local variable(s). Initial local values were not imported."
            }
            profiles += Profile(
                id = profileId,
                name = name,
                enabled = false,
                contexts = contexts,
                enterTaskId = taskId,
                group = macro.string("m_category").takeIf(String::isNotBlank)?.take(InputValidation.MAX_NAME_LENGTH),
                requiresRiskAcknowledgement = true,
                contextExpression = expression,
            )
        }

        val variables = parseGlobalVariables(globalVariables, lossyWarnings)
        val bundle = OpenTaskerBundleCodec.build(
            appVersion = appVersion,
            exportedAtEpochMs = importedAtEpochMs,
            profiles = profiles,
            tasks = tasks,
            variables = variables,
            name = "MacroDroid Import",
            description = "Converted from a MacroDroid export. Review every placeholder and warning before enabling imported profiles.",
        )
        ImportResourceGuard.requireBundle(bundle, budget)
        val mergedWarnings = (bundle.metadata.warnings + warnings + lossyWarnings).distinct()
        val reportBundle = bundle.copy(metadata = bundle.metadata.copy(warnings = mergedWarnings))

        return MacroDroidImportReport(
            bundle = reportBundle,
            sourceMacroCount = macros.size,
            sourceVariableCount = globalVariables.size + localVariableCount,
            sourceActionCount = sourceActionCount,
            sourceTriggerCount = sourceTriggerCount,
            sourceConstraintCount = sourceConstraintCount,
            mappedActions = mappedActions,
            unsupportedActions = unsupportedActions,
            mappedTriggers = mappedTriggers,
            unsupportedTriggers = unsupportedTriggers,
            unsupportedConstraints = unsupportedConstraints,
            warnings = warnings.distinct(),
            lossyWarnings = lossyWarnings.distinct(),
        )
    }

    private fun parseAction(
        macroName: String,
        action: JsonObject,
        actionIndex: Int,
        blocks: OpenBlocks,
        mappedActions: MutableList<MacroDroidMappedAction>,
        unsupportedActions: MutableList<MacroDroidUnsupportedAction>,
        unsupportedConstraints: MutableList<MacroDroidUnsupportedConstraint>,
        lossyWarnings: MutableList<String>,
    ): List<ActionSpec> {
        val classType = action.classType("UnknownAction")
        val constraints = action.objectArray("m_constraintList")
        // An If or Else If keeps its own conditions in this list.
        val holdsConditions = classType in CONDITION_CLASSES
        if (!holdsConditions) {
            constraints.forEachIndexed { index, constraint ->
                unsupportedConstraints += MacroDroidUnsupportedConstraint(
                    owner = "Macro '$macroName' action ${actionIndex + 1} constraint ${index + 1}",
                    classType = constraint.classType("UnknownConstraint"),
                    reason = "Action-level MacroDroid constraints cannot be represented safely.",
                )
            }
        }
        val frame = blocks.frames.lastOrNull()
        val refusalReason = when {
            // A disabled Else or End If of an If that came across is still handled by mapIfStep,
            // since leaving it out would unbalance the block.
            classType in IF_BRANCH_CLASSES -> when {
                frame == null || frame.loop -> "It has no If block of its own to belong to."
                !frame.mapped -> "Its If was imported as a placeholder, so this step was too."
                else -> null
            }
            classType == "EndLoopAction" -> when {
                frame == null || !frame.loop -> "It has no loop of its own to close."
                !frame.mapped -> "Its loop was imported as a placeholder, so this step was too."
                else -> null
            }
            classType == "BreakFromLoopAction" && blocks.innermostLoop()?.mapped != true ->
                "Its loop was imported as a placeholder, so this step was too."
            action.boolean("m_isDisabled") -> "The source action is disabled."
            !holdsConditions && constraints.isNotEmpty() -> "The source action has unsupported action-level constraints."
            else -> null
        }
        val mapping = when {
            refusalReason != null -> null
            classType in IF_FAMILY_CLASSES -> mapIfStep(action, classType, frame)
            else -> mapAction(action, actionIndex)
        }
        val opened = mapping != null && mapping.unsupportedReason == null
        when (classType) {
            "IfConditionAction" -> blocks.frames.addLast(OpenBlocks.Frame(loop = false, mapped = opened))
            "LoopAction", "IterateDictionaryAction" -> blocks.frames.addLast(OpenBlocks.Frame(loop = true, mapped = opened))
            "EndIfAction" -> if (frame != null && !frame.loop) blocks.frames.removeLast()
            "EndLoopAction" -> if (frame != null && frame.loop) blocks.frames.removeLast()
        }
        if (mapping == null || mapping.unsupportedReason != null) {
            val reason = refusalReason ?: mapping?.unsupportedReason ?: "No reviewed OpenTasker mapping exists for this class."
            unsupportedActions += MacroDroidUnsupportedAction(macroName, classType, actionIndex, reason)
            lossyWarnings += "Macro '$macroName' action ${actionIndex + 1} ($classType) is an unsupported placeholder. $reason"
            return mapping?.actions ?: listOf(unsupportedAction(classType, reason))
        }

        mappedActions += MacroDroidMappedAction(
            macroName = macroName,
            classType = classType,
            actionIndex = actionIndex,
            openTaskerActionIds = mapping.actions.map(ActionSpec::type),
        )
        mapping.lossyWarning?.let {
            lossyWarnings += "Macro '$macroName' action ${actionIndex + 1} ($classType): $it."
        }
        return mapping.actions
    }

    private fun mapAction(action: JsonObject, actionIndex: Int): ActionMapping? = when (action.classType()) {
        "PauseAction" -> {
            val millis = (action.long("m_delayInSeconds") ?: 0L) * 1_000L +
                (action.long("m_delayInMilliSeconds") ?: 0L)
            ActionMapping(
                actions = listOf(
                    ActionSpec(
                        type = "flow.wait",
                        label = "MacroDroid pause",
                        args = mapOf("millis" to millis.coerceAtLeast(1L).toString()),
                    ),
                ),
                lossyWarning = if (action.boolean("m_useAlarm")) {
                    "the exact-alarm wake behavior was not imported"
                } else {
                    null
                },
            )
        }
        "SetVariableAction" -> mapSetVariableAction(action)
        "ClipboardAction" -> {
            val text = action.string("m_clipboardText")
            if (text.isEmpty()) return null
            ActionMapping(
                actions = listOf(
                    ActionSpec(
                        type = "clipboard.set",
                        label = "MacroDroid fill clipboard",
                        args = mapOf("text" to text),
                    ),
                ),
                lossyWarning = if (MACRODROID_MAGIC_TEXT.containsMatchIn(text)) {
                    "its magic text was kept as written, and OpenTasker won't fill it in"
                } else {
                    null
                },
            )
        }
        "SetAirplaneModeAction" -> {
            val state = when (action.int("m_state")) {
                0 -> "on"
                1 -> "off"
                2 -> "toggle"
                else -> return null
            }
            val omittedOptions = action.boolean("m_keepWifiOn") ||
                action.boolean("m_keepBluetoothOn") ||
                (action.int("mechanismOption") ?: 0) != 0
            ActionMapping(
                actions = listOf(
                    ActionSpec(
                        type = "airplane.toggle",
                        label = "MacroDroid airplane mode",
                        args = mapOf("state" to state),
                    ),
                ),
                lossyWarning = if (omittedOptions) {
                    "the MacroDroid mechanism and keep-Wi-Fi/Bluetooth options were not imported"
                } else {
                    null
                },
            )
        }
        "LoopAction" -> {
            if ((action.int("m_option") ?: 0) != 0 || action["m_fixedOptionVariable"].isPresentValue()) return null
            val count = action.int("m_fixedOptionCount") ?: return null
            if (count !in 1..MAX_FIXED_LOOP_COUNT) return null
            val arrayName = "macroLoop${actionIndex + 1}"
            ActionMapping(
                actions = listOf(
                    ActionSpec(
                        type = "text.split",
                        label = "MacroDroid fixed loop values",
                        args = mapOf(
                            "source" to (1..count).joinToString(LOOP_DELIMITER),
                            "delimiter" to LOOP_DELIMITER,
                            "var" to arrayName,
                        ),
                    ),
                    ActionSpec(
                        type = "flow.foreach",
                        label = "MacroDroid fixed loop",
                        args = mapOf("list" to arrayName, "var" to "macroLoopIndex${actionIndex + 1}"),
                    ),
                ),
                lossyWarning = "the loop counter uses an OpenTasker local variable named macroLoopIndex${actionIndex + 1}",
            )
        }
        "EndLoopAction" -> ActionMapping(
            actions = listOf(ActionSpec(type = FlowControl.ENDFOR, label = "MacroDroid end loop")),
        )
        "BreakFromLoopAction" -> ActionMapping(
            actions = listOf(
                ActionSpec(
                    type = FlowControl.GOTO,
                    label = "MacroDroid break from loop",
                    args = mapOf("to" to FlowControl.GOTO_LOOP_END),
                ),
            ),
        )
        "ControlMediaAction" -> {
            val target = when (action.string("m_option").lowercase(Locale.US)) {
                "next" -> "track.next"
                "previous" -> "track.previous"
                else -> return null
            }
            ActionMapping(
                actions = listOf(ActionSpec(type = target, label = "MacroDroid media control")),
                lossyWarning = action.string("m_packageName").takeIf(String::isNotBlank)?.let {
                    "the source app target '$it' was not imported"
                },
            )
        }
        else -> null
    }

    private fun mapSetVariableAction(action: JsonObject): ActionMapping? {
        if (
            action.boolean("m_userPrompt") ||
            action.boolean("m_intValueIncrement") ||
            action.boolean("m_intValueDecrement") ||
            action.boolean("m_booleanInvert") ||
            action.boolean("m_intRandom") ||
            action.boolean("m_intExpression") ||
            action.string("m_expression").isNotBlank() ||
            action["m_otherBooleanVariable"].isPresentValue()
        ) return null

        val variable = action.objectValue("m_variable") ?: return null
        val mappedName = mappedVariableName(variable) ?: return null
        val value = when (variable.int("m_type")) {
            0 -> action.boolean("m_newBooleanValue").toString()
            1 -> (action.long("m_newIntValue") ?: 0L).toString()
            2 -> action.string("m_newStringValue")
            3 -> (action.double("m_newDoubleValue") ?: 0.0).toString()
            else -> return null
        }
        return ActionMapping(
            actions = listOf(
                ActionSpec(
                    type = "var.set",
                    label = "MacroDroid set variable",
                    args = mapOf("name" to mappedName, "value" to value),
                ),
            ),
            lossyWarning = variable.string("m_name").takeIf { it != mappedName }?.let {
                "variable '$it' was normalized to '$mappedName'"
            },
        )
    }

    /**
     * An If, Else If, Else or End If. [frame] is the If's open block, which a branch updates: an
     * Else If becomes an Else holding a nested If, so End If closes those nested Ifs too. A branch
     * that can't come across still keeps its Else, so the steps above it run and skip it as before,
     * and the placeholder after the Else stops the task only when that branch is reached. Any later
     * branch of the same If is left out as a placeholder, since an If takes one Else.
     */
    private fun mapIfStep(action: JsonObject, classType: String, frame: OpenBlocks.Frame?): ActionMapping {
        if (classType == "IfConditionAction") {
            val condition = ifCondition(action)
            val text = condition.text ?: return placeholderMapping(classType, condition.refusal.orEmpty())
            return ActionMapping(
                actions = listOf(ActionSpec(type = FlowControl.IF, label = "MacroDroid if", args = mapOf("condition" to text))),
            )
        }
        val block = checkNotNull(frame) { "An If branch needs its If block." }
        if (classType == "EndIfAction") {
            return ActionMapping(
                actions = List(1 + block.nestedIfs) { ActionSpec(type = FlowControl.ENDIF, label = "MacroDroid end if") },
            )
        }
        if (block.branchesEnded) {
            return placeholderMapping(
                classType,
                "An earlier Else or Else If of the same If couldn't be imported, so this one was left out too.",
            )
        }
        val elseStep = ActionSpec(type = FlowControl.ELSE, label = if (classType == "ElseAction") "MacroDroid else" else "MacroDroid else if")
        val refusal = when {
            action.boolean("m_isDisabled") -> "The source action is disabled."
            classType == "ElseAction" -> return ActionMapping(actions = listOf(elseStep))
            else -> ifCondition(action).let { condition ->
                condition.text?.let { text ->
                    block.nestedIfs++
                    return ActionMapping(
                        actions = listOf(
                            elseStep,
                            ActionSpec(type = FlowControl.IF, label = "MacroDroid else if", args = mapOf("condition" to text)),
                        ),
                    )
                }
                condition.refusal.orEmpty()
            }
        }
        block.branchesEnded = true
        return ActionMapping(actions = listOf(elseStep, unsupportedAction(classType, refusal)), unsupportedReason = refusal)
    }

    private fun placeholderMapping(classType: String, reason: String): ActionMapping =
        ActionMapping(actions = listOf(unsupportedAction(classType, reason)), unsupportedReason = reason)

    /** An If's or Else If's conditions as one OpenTasker condition, or why they can't be. */
    private fun ifCondition(action: JsonObject): IfCondition {
        val constraints = action.objectArray("m_constraintList")
        if (constraints.isEmpty()) return IfCondition(refusal = "It has no conditions to check.")
        val clauses = constraints.map { constraint ->
            val classType = constraint.classType("UnknownConstraint")
            if (classType != VARIABLE_CONSTRAINT_CLASS) {
                return IfCondition(refusal = "It checks a $classType condition, which has no reviewed OpenTasker mapping yet.")
            }
            variableClause(constraint).let { it.clause ?: return IfCondition(refusal = it.refusal) }
        }
        val joiner = if (action.boolean("m_isOrCondition")) "||" else "&&"
        val text = if (clauses.size == 1) {
            clauses.single().text
        } else {
            clauses.joinToString(" $joiner ") { if (it.parts.size > 1) "(${it.text})" else it.text }
        }
        // Read back the way the task will run it, so a value can't add a joiner or an operator.
        val (topJoiner, topParts) = conditionGrammar.conditionParts(text)
        val readsBack = if (clauses.size == 1) {
            clauses.single().readsBack(text)
        } else {
            topJoiner == joiner && topParts.size == clauses.size &&
                topParts.zip(clauses).all { (part, clause) -> clause.readsBack(part) }
        }
        return if (readsBack) {
            IfCondition(text = text)
        } else {
            IfCondition(refusal = "Its value can't be written as an OpenTasker condition that reads the same way.")
        }
    }

    /** One MacroDroid variable condition as an OpenTasker clause, or why it can't be. */
    private fun variableClause(constraint: JsonObject): ClauseResult {
        fun refuse(reason: String) = ClauseResult(refusal = reason)
        val variable = constraint.objectValue("m_variable") ?: return refuse("A condition names no variable.")
        val rawName = variable.string("m_name")
        when {
            constraint.boolean("m_isDisabled") -> return refuse("One of its conditions is turned off.")
            constraint.objectArray("m_constraintList").isNotEmpty() -> return refuse("One of its conditions holds conditions of its own.")
            variable.boolean("isActionBlockWorkingVar") -> return refuse("It checks action block variable '$rawName'.")
            constraint.boolean("m_intCompareVariable") || constraint["workingOtherVarToCompare"].isPresentValue() ->
                return refuse("It compares '$rawName' with another variable.")
            constraint.string("m_expression").isNotBlank() -> return refuse("It compares '$rawName' with an expression.")
            constraint.boolean("enableRegex") -> return refuse("It matches '$rawName' against a MacroDroid regular expression.")
            (constraint["dictionaryKeys"] as? JsonObject)?.let { (it["keys"] as? JsonArray)?.isNotEmpty() } == true ->
                return refuse("It checks a dictionary entry of '$rawName'.")
        }
        val name = mappedVariableName(variable) ?: return refuse("Variable '$rawName' has a name OpenTasker can't use.")
        val ref = "%$name"
        return when (variable.int("m_type")) {
            0 -> {
                val op = if (constraint.boolean("m_booleanValue")) "==" else "!="
                ClauseResult(ConditionClause.compare(ref, op, "true"))
            }
            1, 3 -> {
                val value = if (variable.int("m_type") == 1) {
                    constraint.long("m_intValue")?.toString()
                } else {
                    constraint.double("m_doubleValue")?.takeIf(Double::isFinite)?.let {
                        BigDecimal.valueOf(it).stripTrailingZeros().toPlainString()
                    }
                } ?: return refuse("Its number for '$rawName' is missing.")
                ClauseResult(
                    when {
                        constraint.boolean("m_intGreaterThan") -> ConditionClause.compare(ref, ">", value)
                        constraint.boolean("m_intLessThan") -> ConditionClause.compare(ref, "<", value)
                        // Equal and not equal compare as numbers, since a stored decimal reads 2.0.
                        constraint.boolean("m_intNotEqual") -> ConditionClause.join("||", ref to "<", ref to ">", value)
                        else -> ConditionClause.join("&&", ref to "<=", ref to ">=", value)
                    },
                )
            }
            2 -> stringClause(constraint, ref, rawName)
            else -> refuse("It checks '$rawName', a dictionary or array variable.")
        }
    }

    private fun stringClause(constraint: JsonObject, ref: String, rawName: String): ClauseResult {
        val value = constraint.string("m_stringValue")
        val equal = (constraint["m_stringEqual"] as? JsonPrimitive)?.booleanOrNull
            ?: return ClauseResult(refusal = "Its comparison for '$rawName' is missing.")
        val reason = when {
            '%' in value || "{{" in value || MACRODROID_MAGIC_TEXT.containsMatchIn(value) ->
                "Its value for '$rawName' fills in other text, which OpenTasker reads differently."
            value != value.trim() -> "Its value for '$rawName' starts or ends with a space, which OpenTasker trims."
            else -> null
        }
        if (reason != null) return ClauseResult(refusal = reason)
        // MacroDroid ignores case unless Check case is on.
        val ignoreCase = !constraint.boolean("checkCase") && value.any(Char::isLetter)
        return when (constraint.int("m_stringComparisonType") ?: 0) {
            0 -> ClauseResult(
                when {
                    value.isEmpty() -> ConditionClause.unary(ref, if (equal) "not_set" else "is_set")
                    !ignoreCase && plainValue(value) -> ConditionClause.compare(ref, if (equal) "==" else "!=", value)
                    else -> ConditionClause.compare(ref, if (equal) "~R" else "!~R", regexFor(value, ignoreCase, whole = true))
                },
            )
            1 -> if (value.isEmpty()) {
                // Everything contains nothing.
                ClauseResult(ConditionClause.compare(ref, if (equal) "~R" else "!~R", "^"))
            } else {
                ClauseResult(ConditionClause.compare(ref, if (equal) "~R" else "!~R", regexFor(value, ignoreCase, whole = false)))
            }
            else -> ClauseResult(refusal = "It compares '$rawName' in a way OpenTasker doesn't have yet.")
        }
    }

    /** True when [value] has nothing a condition reads as an operator, joiner or bracket. */
    private fun plainValue(value: String): Boolean = value.none { it in "=!<>~&|()" }

    /**
     * [value] as a regular expression that finds it literally. Brackets and `&` are written as
     * hex escapes, so they can't change how the condition around it is split.
     */
    private fun regexFor(value: String, ignoreCase: Boolean, whole: Boolean): String = buildString {
        if (ignoreCase) append("(?i)")
        if (whole) append('^')
        value.forEach { c ->
            when (c) {
                '(', ')', '&' -> append("\\x").append(String.format(Locale.US, "%02x", c.code))
                in REGEX_SPECIAL -> append('\\').append(c)
                else -> append(c)
            }
        }
        if (whole) append('$')
    }

    private class IfCondition(val text: String? = null, val refusal: String? = null)

    private class ClauseResult(val clause: ConditionClause? = null, val refusal: String? = null)

    /** A clause's text and the comparisons the condition grammar should find in it. */
    private class ConditionClause(val text: String, val joiner: String?, val parts: List<Triple<String, String, String>?>, private val unaryText: String? = null) {
        fun readsBack(written: String): Boolean {
            val (foundJoiner, found) = conditionGrammar.conditionParts(written)
            if (foundJoiner != joiner || found.size != parts.size) return false
            return found.zip(parts).all { (part, expected) ->
                if (expected == null) part == unaryText && conditionGrammar.comparisonParts(part) == null
                else conditionGrammar.comparisonParts(part) == expected
            }
        }

        companion object {
            fun compare(left: String, op: String, right: String) =
                ConditionClause("$left $op $right", null, listOf(Triple(left, op, right)))

            fun unary(operand: String, op: String) = "$operand $op".let { ConditionClause(it, null, listOf(null), unaryText = it) }

            fun join(joiner: String, first: Pair<String, String>, second: Pair<String, String>, value: String): ConditionClause {
                val parts = listOf(first, second).map { (left, op) -> Triple(left, op, value) }
                return ConditionClause(parts.joinToString(" $joiner ") { "${it.first} ${it.second} ${it.third}" }, joiner, parts)
            }
        }
    }

    /**
     * The If and loop blocks open at the current step. A block whose opening step came across as
     * a placeholder makes its Else, End If, End Loop and Break placeholders too, so the task never
     * holds a closing step with nothing to close.
     */
    private class OpenBlocks {
        class Frame(val loop: Boolean, val mapped: Boolean) {
            var nestedIfs = 0
            var branchesEnded = false
        }

        val frames = ArrayDeque<Frame>()

        fun innermostLoop(): Frame? = frames.lastOrNull { it.loop }

        /** Closing steps for blocks the macro never closed, so the task's blocks still balance. */
        fun closeRemaining(macroName: String, lossyWarnings: MutableList<String>): List<ActionSpec> {
            val closers = frames.reversed().filter { it.mapped }.flatMap { frame ->
                if (frame.loop) {
                    listOf(ActionSpec(type = FlowControl.ENDFOR, label = "MacroDroid end loop"))
                } else {
                    List(1 + frame.nestedIfs) { ActionSpec(type = FlowControl.ENDIF, label = "MacroDroid end if") }
                }
            }
            if (closers.isNotEmpty()) {
                lossyWarnings += "Macro '$macroName' leaves an If or loop open at its end. OpenTasker closes it after the last step."
            }
            frames.clear()
            return closers
        }
    }

    private fun parseTrigger(
        macroName: String,
        trigger: JsonObject,
        triggerIndex: Int,
        mappedTriggers: MutableList<MacroDroidMappedTrigger>,
        unsupportedTriggers: MutableList<MacroDroidUnsupportedTrigger>,
        unsupportedConstraints: MutableList<MacroDroidUnsupportedConstraint>,
        lossyWarnings: MutableList<String>,
    ): TriggerMapping {
        val classType = trigger.classType("UnknownTrigger")
        val constraints = trigger.objectArray("m_constraintList")
        constraints.forEachIndexed { index, constraint ->
            unsupportedConstraints += MacroDroidUnsupportedConstraint(
                owner = "Macro '$macroName' trigger ${triggerIndex + 1} constraint ${index + 1}",
                classType = constraint.classType("UnknownConstraint"),
                reason = "Trigger-level MacroDroid constraints cannot be represented safely.",
            )
        }
        val refusalReason = when {
            trigger.boolean("m_isDisabled") -> "The source trigger is disabled."
            constraints.isNotEmpty() -> "The source trigger has unsupported trigger-level constraints."
            else -> null
        }
        val mapping = if (refusalReason == null) mapTrigger(trigger) else null
        if (mapping == null) {
            val reason = refusalReason ?: "No reviewed OpenTasker mapping exists for this class."
            unsupportedTriggers += MacroDroidUnsupportedTrigger(macroName, classType, triggerIndex, reason)
            lossyWarnings += "Macro '$macroName' trigger ${triggerIndex + 1} ($classType) is a non-matching placeholder. $reason"
            return TriggerMapping(listOf(unsupportedContext(classType)))
        }

        mappedTriggers += MacroDroidMappedTrigger(
            macroName = macroName,
            classType = classType,
            triggerIndex = triggerIndex,
            openTaskerContextTypes = mapping.contexts.map(ContextSpec::type),
        )
        mapping.lossyWarning?.let {
            lossyWarnings += "Macro '$macroName' trigger ${triggerIndex + 1} ($classType): $it."
        }
        return mapping
    }

    private fun mapTrigger(trigger: JsonObject): TriggerMapping? = when (trigger.classType()) {
        "BootTrigger" -> TriggerMapping(
            contexts = listOf(ContextSpec(ContextType.EVENT, mapOf("event" to "boot_completed"))),
        )
        "BatteryLevelTrigger" -> {
            if ((trigger.int("m_option") ?: 0) != 0) return null
            val level = trigger.int("m_batteryLevel")?.takeIf { it in 0..100 } ?: return null
            TriggerMapping(
                contexts = listOf(
                    ContextSpec(
                        ContextType.STATE,
                        mapOf(
                            "key" to "battery_level",
                            "operator" to if (trigger.boolean("m_decreasesTo")) "<=" else ">=",
                            "value" to level.toString(),
                        ),
                    ),
                ),
                lossyWarning = "a threshold crossing was converted to a battery state predicate",
            )
        }
        "ScreenOnOffTrigger" -> TriggerMapping(
            contexts = listOf(
                ContextSpec(
                    ContextType.STATE,
                    mapOf("key" to "screen", "value" to if (trigger.boolean("m_screenOn")) "on" else "off"),
                ),
            ),
            lossyWarning = "a screen transition was converted to a screen state predicate",
        )
        "DeviceUnlockedTrigger" -> TriggerMapping(
            contexts = listOf(ContextSpec(ContextType.STATE, mapOf("key" to "unlocked", "value" to "true"))),
            lossyWarning = "an unlock transition was converted to an unlocked state predicate",
        )
        "TimerTrigger" -> mapTimerTrigger(trigger)
        else -> null
    }

    private fun mapTimerTrigger(trigger: JsonObject): TriggerMapping? {
        val hour = trigger.int("m_hour")?.takeIf { it in 0..23 } ?: return null
        val minute = trigger.int("m_minute")?.takeIf { it in 0..59 } ?: return null
        val startMinutes = hour * 60 + minute
        val endMinutes = (startMinutes + 1) % (24 * 60)
        val contexts = mutableListOf(
            ContextSpec(
                ContextType.TIME,
                mapOf("start" to clock(startMinutes), "end" to clock(endMinutes)),
            ),
        )
        val selectedDays = trigger.booleanArray("m_daysOfWeek")
            .mapIndexedNotNull { index, selected -> DaySchedule.orderedDays.getOrNull(index)?.takeIf { selected } }
        if (selectedDays.isNotEmpty() && selectedDays.size < DaySchedule.orderedDays.size) {
            contexts += ContextSpec(ContextType.DAY, mapOf("days" to selectedDays.joinToString(",")))
        }
        val warningParts = buildList {
            if ((trigger.int("m_second") ?: 0) != 0) add("seconds were rounded down to the minute")
            if (selectedDays.isEmpty()) add("no selected weekdays were present, so the timer was imported as daily")
        }
        return TriggerMapping(
            contexts = contexts,
            lossyWarning = warningParts.joinToString("; ").takeIf(String::isNotBlank),
        )
    }

    private fun parseGlobalVariables(
        source: List<JsonObject>,
        lossyWarnings: MutableList<String>,
    ): List<Variable> {
        val seenNames = mutableSetOf<String>()
        return source.mapIndexedNotNull { index, variable ->
            val rawName = variable.string("m_name")
            val name = VariableNamePolicy.promoteToGlobal(rawName)
            if (name == null) {
                lossyWarnings += "MacroDroid global variable ${index + 1} ('$rawName') has an invalid name and was not imported."
                return@mapIndexedNotNull null
            }
            if (!seenNames.add(name)) {
                lossyWarnings += "MacroDroid global variable '$rawName' maps to duplicate name '$name' and was not imported twice."
                return@mapIndexedNotNull null
            }
            val value = when (variable.int("m_type")) {
                0 -> variable.boolean("m_booleanValue").toString()
                1 -> (variable.long("m_intValue") ?: 0L).toString()
                2 -> variable.string("m_stringValue")
                3 -> (variable.double("m_decimalValue") ?: 0.0).toString()
                else -> {
                    lossyWarnings += "MacroDroid global variable '$rawName' uses an unsupported dictionary or array type."
                    return@mapIndexedNotNull null
                }
            }
            if (rawName != name) {
                lossyWarnings += "MacroDroid global variable '$rawName' was normalized to OpenTasker global '$name'."
            }
            Variable(name = name, value = value, isGlobal = true)
        }
    }

    private fun mappedVariableName(variable: JsonObject): String? {
        val rawName = variable.string("m_name")
        val normalized = VariableNamePolicy.normalize(rawName) ?: return null
        // MacroDroid writes isLocalVar; a few older exports say isLocal.
        return if (variable.boolean("isLocalVar") || variable.boolean("isLocal")) {
            normalized.lowercase(Locale.US)
        } else {
            VariableNamePolicy.promoteToGlobal(normalized)
        }
    }

    private fun unsupportedAction(classType: String, reason: String): ActionSpec = ActionSpec(
        type = MACRODROID_UNSUPPORTED_ACTION_ID,
        label = "Unsupported MacroDroid action $classType",
        args = mapOf(
            "classType" to classType,
            "summary" to reason,
        ),
    )

    private fun unsupportedContext(classType: String): ContextSpec = ContextSpec(
        type = ContextType.EVENT,
        config = mapOf(
            "event" to UNSUPPORTED_EVENT_NAME,
            "sourceClass" to classType,
        ),
    )

    private fun MutableList<ContextSpec>.appendAsAndGroup(values: List<ContextSpec>): ContextExpressionNode {
        val start = size
        addAll(values)
        val leaves = values.indices.map { ContextExpressionNode.leaf(start + it) }
        return leaves.asGroup(ContextBooleanOperator.AND) ?: error("Context mapping must contain a context")
    }

    private fun List<ContextExpressionNode>.asGroup(operator: ContextBooleanOperator): ContextExpressionNode? = when (size) {
        0 -> null
        1 -> first()
        else -> ContextExpressionNode.group(operator, this)
    }

    private fun uniquePositiveId(source: Long?, index: Int, used: MutableSet<Long>): Long {
        var candidate = source?.and(Long.MAX_VALUE)?.takeIf { it > 0L } ?: (index + 1L)
        while (!used.add(candidate)) {
            candidate = if (candidate == Long.MAX_VALUE) 1L else candidate + 1L
        }
        return candidate
    }

    private fun JsonObject.macroObjects(): List<JsonObject> {
        val list = this["macroList"]
        if (list != null && list !is JsonNull) return objectArray("macroList")
        val macro = objectValue("macro")
        return listOfNotNull(macro)
    }

    private fun JsonObject.objectArray(key: String): List<JsonObject> {
        val value = this[key] ?: return emptyList()
        if (value is JsonNull) return emptyList()
        val array = value as? JsonArray
            ?: throw IllegalArgumentException("MacroDroid field '$key' must be an array.")
        return array.mapIndexed { index, element ->
            element as? JsonObject
                ?: throw IllegalArgumentException("MacroDroid field '$key' item ${index + 1} must be an object.")
        }
    }

    private fun JsonObject.objectValue(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.classType(fallback: String = ""): String = string("m_classType").ifBlank { fallback }

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.boolean(key: String): Boolean {
        val value = this[key] as? JsonPrimitive ?: return false
        return value.booleanOrNull ?: value.contentOrNull?.toBooleanStrictOrNull() ?: false
    }

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

    private fun JsonObject.booleanArray(key: String): List<Boolean> =
        (this[key] as? JsonArray).orEmpty().map { (it as? JsonPrimitive)?.booleanOrNull ?: false }

    private fun Any?.isPresentValue(): Boolean = this != null && this !is JsonNull

    private fun clock(totalMinutes: Int): String = String.format(
        Locale.US,
        "%02d:%02d",
        totalMinutes / 60,
        totalMinutes % 60,
    )

    /**
     * [unsupportedReason] marks a step that came across as a placeholder but still needs the
     * [actions] it carries, like the Else in front of a branch that couldn't be imported.
     */
    private data class ActionMapping(
        val actions: List<ActionSpec>,
        val lossyWarning: String? = null,
        val unsupportedReason: String? = null,
    )

    private data class TriggerMapping(
        val contexts: List<ContextSpec>,
        val lossyWarning: String? = null,
    )

    private const val BYTE_ORDER_MARK = "\uFEFF"
    private const val LOOP_DELIMITER = "|"
    private const val MAX_FIXED_LOOP_COUNT = 1_000
    private const val UNSUPPORTED_EVENT_NAME = "macrodroid_unsupported"
    private const val VARIABLE_CONSTRAINT_CLASS = "MacroDroidVariableConstraint"
    private val MACRODROID_MAGIC_TEXT = Regex("""\[[^\]\s]+\]|\{l?v=[^}]+\}""")
    private val REGEX_SPECIAL = setOf('\\', '.', '^', '$', '|', '?', '*', '+', '[', ']', '{', '}')

    /** Actions that keep their own conditions in `m_constraintList`. */
    private val CONDITION_CLASSES = setOf("IfConditionAction", "ElseIfConditionAction")
    private val IF_BRANCH_CLASSES = setOf("ElseIfConditionAction", "ElseAction", "EndIfAction")
    private val IF_FAMILY_CLASSES = IF_BRANCH_CLASSES + "IfConditionAction"
    private val conditionGrammar = VariableExpander()
}
