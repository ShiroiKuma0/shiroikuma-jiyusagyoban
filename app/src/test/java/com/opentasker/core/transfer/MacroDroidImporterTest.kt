package com.opentasker.core.transfer

import com.opentasker.core.engine.FlowStructure
import com.opentasker.core.engine.VariableStore
import com.opentasker.core.model.ContextBooleanOperator
import com.opentasker.core.model.ContextType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroDroidImporterTest {
    @Test
    fun fullBackupProducesDisabledProfilesAndExactMigrationReport() {
        val report = MacroDroidImporter.parse(fullBackup(), "test", 123L)

        assertEquals(1, report.sourceMacroCount)
        assertEquals(1, report.sourceVariableCount)
        assertEquals(5, report.sourceActionCount)
        assertEquals(2, report.sourceTriggerCount)
        assertEquals(1, report.sourceConstraintCount)
        assertEquals(4, report.mappedActions.size)
        assertEquals(1, report.unsupportedActions.size)
        assertEquals(2, report.mappedTriggers.size)
        assertTrue(report.unsupportedTriggers.isEmpty())
        assertEquals(1, report.unsupportedConstraints.size)
        assertEquals(
            report.sourceActionCount,
            report.mappedActions.size + report.unsupportedActions.size,
        )

        val task = report.bundle.tasks.single()
        assertEquals("Morning setup", task.name)
        assertEquals(
            listOf("flow.wait", "var.set", MACRODROID_UNSUPPORTED_ACTION_ID, "text.split", "flow.foreach", "flow.endfor"),
            task.actions.map { it.type },
        )
        assertEquals("ShellScriptAction", task.actions[2].args["classType"])
        assertEquals("Output", task.actions[1].args["name"])

        val profile = report.bundle.profiles.single()
        assertFalse(profile.enabled)
        assertTrue(profile.requiresRiskAcknowledgement)
        assertEquals("Daily", profile.group)
        assertEquals(listOf(ContextType.EVENT, ContextType.STATE, ContextType.EVENT), profile.contexts.map { it.type })
        assertEquals(ContextBooleanOperator.AND, profile.contextExpression?.operator)
        assertEquals("Output", report.bundle.variables.single().name)
        assertTrue(report.lossyWarnings.any { it.contains("ShellScriptAction") })
        assertTrue(report.lossyWarnings.any { it.contains("WifiConstraint") })

        val preview = MacroDroidImportPlanner.preview(report)
        assertTrue(preview.canImport)
        assertEquals(1, preview.importProfileCount)
        assertEquals(4, preview.mappedActionCount)
        assertEquals(1, preview.unsupportedActionCount)
        assertTrue(preview.capabilityWarnings.any { it.contains(MACRODROID_UNSUPPORTED_ACTION_ID) })
        assertFalse(MacroDroidImportPlanner.confirmedBundle(report).profiles.single().enabled)
    }

    @Test
    fun singleMacroShareAndSignedGuidAreAccepted() {
        val report = MacroDroidImporter.parse(
            rawJson = """
                {
                  "macroExportVersion": 1,
                  "macro": {
                    "m_GUID": -9223372036854775808,
                    "m_name": "Screen off",
                    "m_enabled": true,
                    "m_category": "Display",
                    "m_isOrCondition": false,
                    "m_triggerList": [
                      {"m_classType":"ScreenOnOffTrigger","m_screenOn":false,"m_constraintList":[],"m_isDisabled":false}
                    ],
                    "m_actionList": [
                      {"m_classType":"SetAirplaneModeAction","m_state":1,"m_constraintList":[],"m_isDisabled":false}
                    ],
                    "m_constraintList": [],
                    "localVariables": []
                  }
                }
            """.trimIndent(),
            appVersion = "test",
            importedAtEpochMs = 0L,
        )

        assertEquals(1L, report.bundle.tasks.single().id)
        assertEquals("airplane.toggle", report.bundle.tasks.single().actions.single().type)
        assertEquals("off", report.bundle.tasks.single().actions.single().args["state"])
        assertEquals("off", report.bundle.profiles.single().contexts.single().config["value"])
    }

    // The action body is what MacroDroid 6.1.5 wrote for Fill Clipboard, only the text varies.
    private fun fillClipboardMacro(text: String): String = """
        {
          "macroExportVersion": 1,
          "macro": {
            "m_GUID": -5921165385892800725,
            "m_name": "Fill",
            "m_enabled": true,
            "m_isOrCondition": false,
            "m_triggerList": [
              {"m_classType":"ScreenOnOffTrigger","m_screenOn":false,"m_constraintList":[],"m_isDisabled":false}
            ],
            "m_actionList": [
              {"m_clipboardText":"$text","disableLogging":false,"m_SIGUID":-9016106706254424825,"m_classType":"ClipboardAction","m_comment":"","m_constraintList":[],"m_isDisabled":false,"m_isOrCondition":false}
            ],
            "m_constraintList": [],
            "localVariables": []
          }
        }
    """.trimIndent()

    @Test
    fun aMacrosLocalVariableIsCountedInTheWarning() {
        val oneLocal = fillClipboardMacro("hello").replace(
            "\"localVariables\": []",
            "\"localVariables\": [{\"m_name\":\"count\",\"m_type\":1,\"m_intValue\":0}]",
        )

        val report = MacroDroidImporter.parse(oneLocal, "test", 0L)

        assertTrue(report.lossyWarnings.contains("Macro 'Fill' has 1 local variable. Initial local values were not imported."))
    }

    @Test
    fun fillClipboardFromARealExportBecomesClipboardSet() {
        val report = MacroDroidImporter.parse(fillClipboardMacro("hello"), "test", 0L)

        val action = report.bundle.tasks.single().actions.single()
        assertEquals("clipboard.set", action.type)
        assertEquals("hello", action.args["text"])
        assertTrue(report.unsupportedActions.isEmpty())
        assertTrue(report.lossyWarnings.none { "ClipboardAction" in it })
    }

    @Test
    fun fillClipboardWithMagicTextKeepsTheTextAndSaysItWontBeFilledIn() {
        listOf("It is [battery] percent", "Hi {lv=name}", "Hi {v=name}").forEach { text ->
            val report = MacroDroidImporter.parse(fillClipboardMacro(text), "test", 0L)

            assertEquals(text, report.bundle.tasks.single().actions.single().args["text"])
            assertTrue(text, report.lossyWarnings.single { "ClipboardAction" in it }.contains("magic text"))
        }
    }

    @Test
    fun fillClipboardWithNoTextStaysAPlaceholder() {
        val report = MacroDroidImporter.parse(fillClipboardMacro(""), "test", 0L)

        assertEquals("macrodroid.unsupported", report.bundle.tasks.single().actions.single().type)
        assertEquals(1, report.unsupportedActions.size)
    }

    @Test
    fun unsupportedAndDisabledSourceItemsFailClosedAsPlaceholders() {
        val report = MacroDroidImporter.parse(
            rawJson = """
                {
                  "macroList": [{
                    "m_GUID": 7,
                    "m_name": "Guarded",
                    "m_triggerList": [
                      {"m_classType":"VariableTrigger","m_constraintList":[],"m_isDisabled":false}
                    ],
                    "m_actionList": [
                      {"m_classType":"PauseAction","m_delayInSeconds":1,"m_constraintList":[],"m_isDisabled":true},
                      {"m_classType":"FutureAction","m_constraintList":[],"m_isDisabled":false}
                    ],
                    "m_constraintList": [],
                    "localVariables": []
                  }],
                  "variables": []
                }
            """.trimIndent(),
            appVersion = "test",
            importedAtEpochMs = 0L,
        )

        assertEquals(2, report.unsupportedActions.size)
        assertEquals(1, report.unsupportedTriggers.size)
        assertTrue(report.bundle.tasks.single().actions.all { it.type == MACRODROID_UNSUPPORTED_ACTION_ID })
        assertEquals("macrodroid_unsupported", report.bundle.profiles.single().contexts.single().config["event"])
    }

    @Test
    fun sourceAndDecodedModelBudgetsApplyBeforeImport() {
        val raw = fullBackup()
        val rawError = runCatching {
            MacroDroidImporter.parse(raw, "test", 0L, ImportResourceBudget.Default.copy(maxJsonChars = 32))
        }.exceptionOrNull()
        assertNotNull(rawError)
        assertTrue(rawError!!.message.orEmpty().contains("JSON characters"))

        val actionError = runCatching {
            MacroDroidImporter.parse(raw, "test", 0L, ImportResourceBudget.Default.copy(maxActions = 2))
        }.exceptionOrNull()
        assertNotNull(actionError)
        assertTrue(actionError!!.message.orEmpty().contains("actions"))

        val entityError = runCatching {
            MacroDroidImporter.parse(raw, "test", 0L, ImportResourceBudget.Default.copy(maxEntities = 1))
        }.exceptionOrNull()
        assertNotNull(entityError)
        assertTrue(entityError!!.message.orEmpty().contains("entities"))
    }

    @Test
    fun unrelatedJsonIsRejectedAsNotMacroDroid() {
        val error = runCatching {
            MacroDroidImporter.parse("{\"schemaVersion\":2}", "test", 0L)
        }.exceptionOrNull()

        assertNotNull(error)
        assertTrue(error!!.message.orEmpty().contains("no macros"))
    }

    @Test
    fun ifElseIfElseChainBecomesNestedBlocksThatBalance() {
        val report = MacroDroidImporter.parse(
            blockMacro(
                step("IfConditionAction", condition(variable("mode", 2), "m_stringValue" to "\"home\"", "checkCase" to "true")),
                setVariable("branch", "one"),
                step("ElseIfConditionAction", condition(variable("away", 0), "m_booleanValue" to "true")),
                setVariable("branch", "two"),
                step("ElseAction"),
                setVariable("branch", "three"),
                step("EndIfAction"),
            ),
            "test",
            0L,
        )

        val actions = report.bundle.tasks.single().actions
        assertEquals(
            listOf("flow.if", "var.set", "flow.else", "flow.if", "var.set", "flow.else", "var.set", "flow.endif", "flow.endif"),
            actions.map { it.type },
        )
        assertNull(FlowStructure.analyze(actions).error)
        assertEquals("%mode == home", actions[0].args["condition"])
        assertEquals("%away == true", actions[3].args["condition"])
        // MacroDroid marks a local variable with isLocalVar, so it stays local here.
        assertEquals("branch", actions[1].args["name"])
        assertTrue(report.unsupportedActions.isEmpty())
        assertEquals(report.sourceActionCount, report.mappedActions.size)
    }

    @Test
    fun stringConditionsKeepMacroDroidsCaseRuleAndReadTheValueLiterally() {
        fun conditionFor(vararg fields: Pair<String, String>): String =
            onlyIfCondition(condition(variable("reply", 2, local = false), *fields))

        fun holds(condition: String, value: String?): Boolean =
            VariableStore().apply { value?.let { set("Reply", it) } }.evaluateCondition(condition)

        // Check case is off unless the user turned it on.
        val anyCase = conditionFor("m_stringValue" to "\"Yes\"")
        assertTrue(holds(anyCase, "yes"))
        assertTrue(holds(anyCase, "YES"))
        assertFalse(holds(anyCase, "yes please"))
        val exactCase = conditionFor("m_stringValue" to "\"Yes\"", "checkCase" to "true")
        assertEquals("%Reply == Yes", exactCase)
        assertTrue(holds(exactCase, "Yes"))
        assertFalse(holds(exactCase, "yes"))

        // Brackets, dots and a joiner in the value are only text.
        val contains = conditionFor("m_stringValue" to "\"(1.5) && x\"", "m_stringComparisonType" to "1")
        assertTrue(holds(contains, "got (1.5) && X here"))
        assertFalse(holds(contains, "got 1x5) && x"))
        val operatorText = conditionFor("m_stringValue" to "\"a == b\"", "checkCase" to "true")
        assertTrue(holds(operatorText, "a == b"))
        assertFalse(holds(operatorText, "a == bc"))

        val lacks = conditionFor("m_stringValue" to "\"error\"", "m_stringComparisonType" to "1", "m_stringEqual" to "false")
        assertTrue(holds(lacks, "all good"))
        assertFalse(holds(lacks, "an ERROR here"))

        val notEmpty = conditionFor("m_stringEqual" to "false")
        assertTrue(holds(notEmpty, "a"))
        assertFalse(holds(notEmpty, null))
        val empty = conditionFor()
        assertTrue(holds(empty, null))
        assertFalse(holds(empty, "a"))
    }

    @Test
    fun numberConditionsCompareAsNumbers() {
        fun holds(condition: String, value: String): Boolean =
            VariableStore().apply { set("Level", value) }.evaluateCondition(condition)

        val equals = onlyIfCondition(condition(variable("level", 1, local = false), "m_intValue" to "5"))
        assertTrue(holds(equals, "5"))
        assertTrue(holds(equals, "5.0"))
        assertFalse(holds(equals, "6"))
        val notEqual = onlyIfCondition(condition(variable("level", 1, local = false), "m_intValue" to "5", "m_intNotEqual" to "true"))
        assertTrue(holds(notEqual, "4"))
        assertFalse(holds(notEqual, "5.0"))
        val greater = onlyIfCondition(condition(variable("level", 1, local = false), "m_intValue" to "200", "m_intGreaterThan" to "true"))
        assertTrue(holds(greater, "201"))
        assertFalse(holds(greater, "200"))
        val less = onlyIfCondition(condition(variable("level", 1, local = false), "m_intValue" to "-3", "m_intLessThan" to "true"))
        assertTrue(holds(less, "-4"))
        assertFalse(holds(less, "-3"))
        val decimal = onlyIfCondition(condition(variable("level", 3, local = false), "m_doubleValue" to "2.50", "m_intGreaterThan" to "true"))
        assertEquals("%Level > 2.5", decimal)
    }

    @Test
    fun orConditionsJoinWithTheirOwnGroupsIntact() {
        val joined = onlyIfCondition(
            condition(variable("count", 1, local = false), "m_intValue" to "1"),
            condition(variable("Flag", 0, local = false), "m_booleanValue" to "true"),
            isOr = true,
        )

        assertEquals("(%Count <= 1 && %Count >= 1) || %Flag == true", joined)
        assertTrue(VariableStore().apply { set("Count", "1") }.evaluateCondition(joined))
        assertTrue(VariableStore().apply { set("Count", "2"); set("Flag", "true") }.evaluateCondition(joined))
        assertFalse(VariableStore().apply { set("Count", "2"); set("Flag", "false") }.evaluateCondition(joined))
    }

    @Test
    fun aBranchThatCantComeAcrossStopsTheTaskOnlyWhenItsReached() {
        val report = MacroDroidImporter.parse(
            blockMacro(
                step("IfConditionAction", condition(variable("ok", 0), "m_booleanValue" to "true")),
                setVariable("branch", "one"),
                step("ElseIfConditionAction", condition(variable("name", 2), "m_stringValue" to "\"^a+$\"", "enableRegex" to "true")),
                setVariable("branch", "two"),
                step("ElseAction"),
                setVariable("branch", "three"),
                step("EndIfAction", disabled = true),
            ),
            "test",
            0L,
        )

        val actions = report.bundle.tasks.single().actions
        assertEquals(
            listOf(
                "flow.if", "var.set", "flow.else", MACRODROID_UNSUPPORTED_ACTION_ID, "var.set",
                MACRODROID_UNSUPPORTED_ACTION_ID, "var.set", "flow.endif",
            ),
            actions.map { it.type },
        )
        assertNull(FlowStructure.analyze(actions).error)
        assertEquals(listOf("ElseIfConditionAction", "ElseAction"), report.unsupportedActions.map { it.classType })
        assertTrue(report.unsupportedActions[0].reason.contains("regular expression"))
        assertEquals(report.sourceActionCount, report.mappedActions.size + report.unsupportedActions.size)
    }

    @Test
    fun anIfThatCantComeAcrossTakesItsElseAndEndIfWithIt() {
        val report = MacroDroidImporter.parse(
            blockMacro(
                step("IfConditionAction", """{"m_classType":"WifiConstraint","m_constraintList":[],"m_isDisabled":false}"""),
                setVariable("branch", "one"),
                step("ElseAction"),
                setVariable("branch", "two"),
                step("EndIfAction"),
                step("IfConditionAction", condition(variable("cost", 2), "m_stringValue" to "\"50%\"")),
                step("EndIfAction"),
            ),
            "test",
            0L,
        )

        val actions = report.bundle.tasks.single().actions
        assertEquals(
            listOf(
                MACRODROID_UNSUPPORTED_ACTION_ID, "var.set", MACRODROID_UNSUPPORTED_ACTION_ID, "var.set",
                MACRODROID_UNSUPPORTED_ACTION_ID, MACRODROID_UNSUPPORTED_ACTION_ID, MACRODROID_UNSUPPORTED_ACTION_ID,
            ),
            actions.map { it.type },
        )
        assertNull(FlowStructure.analyze(actions).error)
        assertTrue(report.unsupportedActions[0].reason.contains("WifiConstraint"))
        assertTrue(report.unsupportedActions[3].reason.contains("fills in other text"))
    }

    @Test
    fun aLoopThatCantComeAcrossTakesItsBreakAndEndLoopWithIt() {
        val report = MacroDroidImporter.parse(
            blockMacro(
                """{"dictionaryKeys":{"keys":[]},"isArray":false,"variableName":"queue","m_fixedOptionCount":1,"m_option":0,"m_SIGUID":3,"m_classType":"IterateDictionaryAction","m_constraintList":[],"m_isDisabled":false,"m_isOrCondition":false}""",
                step("BreakFromLoopAction"),
                step("EndLoopAction"),
            ),
            "test",
            0L,
        )

        val actions = report.bundle.tasks.single().actions
        assertTrue(actions.all { it.type == MACRODROID_UNSUPPORTED_ACTION_ID })
        assertNull(FlowStructure.analyze(actions).error)
        assertEquals(3, report.unsupportedActions.size)
    }

    @Test
    fun breakInsideAnIfLeavesTheFixedLoopAroundIt() {
        val report = MacroDroidImporter.parse(
            blockMacro(
                """{"m_classType":"LoopAction","m_option":0,"m_fixedOptionCount":3,"m_constraintList":[],"m_isDisabled":false}""",
                step("IfConditionAction", condition(variable("done", 0), "m_booleanValue" to "true")),
                step("BreakFromLoopAction"),
                step("EndIfAction"),
                step("EndLoopAction"),
            ),
            "test",
            0L,
        )

        val actions = report.bundle.tasks.single().actions
        assertEquals(
            listOf("text.split", "flow.foreach", "flow.if", "flow.goto", "flow.endif", "flow.endfor"),
            actions.map { it.type },
        )
        assertEquals("loop_end", actions[3].args["to"])
        assertNull(FlowStructure.analyze(actions).error)
    }

    @Test
    fun anIfLeftOpenAtTheEndIsClosedAfterTheLastStep() {
        val report = MacroDroidImporter.parse(
            blockMacro(
                step("IfConditionAction", condition(variable("done", 0), "m_booleanValue" to "true")),
                setVariable("branch", "one"),
            ),
            "test",
            0L,
        )

        val actions = report.bundle.tasks.single().actions
        assertEquals(listOf("flow.if", "var.set", "flow.endif"), actions.map { it.type })
        assertNull(FlowStructure.analyze(actions).error)
        assertTrue(report.lossyWarnings.any { "left open" in it || "open at its end" in it })
    }

    private fun onlyIfCondition(vararg conditions: String, isOr: Boolean = false): String {
        val report = MacroDroidImporter.parse(
            blockMacro(step("IfConditionAction", *conditions, isOr = isOr), step("EndIfAction")),
            "test",
            0L,
        )
        assertTrue(report.unsupportedActions.toString(), report.unsupportedActions.isEmpty())
        return report.bundle.tasks.single().actions.first().args.getValue("condition")
    }

    // The block steps, variables and conditions below follow what MacroDroid 5.4x wrote in 2024.
    private fun blockMacro(vararg actions: String): String = """
        {
          "macroExportVersion": 1,
          "macro": {
            "m_GUID": 42,
            "m_name": "Blocks",
            "m_isOrCondition": false,
            "m_triggerList": [
              {"m_classType":"ScreenOnOffTrigger","m_screenOn":true,"m_constraintList":[],"m_isDisabled":false}
            ],
            "m_actionList": [${actions.joinToString(",")}],
            "m_constraintList": [],
            "localVariables": []
          }
        }
    """.trimIndent()

    private fun step(classType: String, vararg conditions: String, isOr: Boolean = false, disabled: Boolean = false): String =
        """{"disableLogging":false,"m_SIGUID":1,"m_classType":"$classType","m_constraintList":[${conditions.joinToString(",")}],"m_isDisabled":$disabled,"m_isOrCondition":$isOr}"""

    private fun setVariable(name: String, value: String): String =
        """{"m_classType":"SetVariableAction","m_newStringValue":"$value","m_variable":${variable(name, 2)},"m_constraintList":[],"m_isDisabled":false}"""

    private fun variable(name: String, type: Int, local: Boolean = true): String =
        """{"dictionary":{"entries":[],"isArray":false,"variableType":4,"type":"Dictionary"},"isActionBlockWorkingVar":false,"isLocalVar":$local,"isSecure":false,"m_booleanValue":false,"m_decimalValue":0.0,"m_intValue":0,"m_name":"$name","m_stringValue":"","m_type":$type,"supportsInput":true,"supportsOutput":true}"""

    private fun condition(variableJson: String, vararg fields: Pair<String, String>): String {
        val values = linkedMapOf(
            "checkCase" to "false",
            "dictionaryKeys" to """{"keys":[]}""",
            "dictionaryType" to "2",
            "enableRegex" to "false",
            "m_booleanValue" to "false",
            "m_doubleValue" to "0.0",
            "m_intCompareVariable" to "false",
            "m_intGreaterThan" to "false",
            "m_intLessThan" to "false",
            "m_intNotEqual" to "false",
            "m_intValue" to "0",
            "m_otherValueToCompare" to variable("other", 2),
            "m_stringComparisonType" to "0",
            "m_stringEqual" to "true",
            "m_stringValue" to "\"\"",
            "m_variable" to variableJson,
            "disableLogging" to "false",
            "m_SIGUID" to "2",
            "m_classType" to "\"MacroDroidVariableConstraint\"",
            "m_constraintList" to "[]",
            "m_isDisabled" to "false",
            "m_isOrCondition" to "false",
        )
        values.putAll(fields)
        return values.entries.joinToString(",", "{", "}") { (key, value) -> "\"$key\":$value" }
    }

    private fun fullBackup(): String = """
        {
          "exportFormat": 1,
          "macroList": [{
            "m_GUID": -5155100316473922741,
            "m_name": "Morning setup",
            "m_enabled": true,
            "m_category": "Daily",
            "m_description": "A source description",
            "m_isOrCondition": false,
            "m_triggerList": [
              {"m_classType":"BootTrigger","m_constraintList":[],"m_isDisabled":false},
              {"m_classType":"BatteryLevelTrigger","m_batteryLevel":85,"m_decreasesTo":false,"m_option":0,"m_constraintList":[],"m_isDisabled":false}
            ],
            "m_actionList": [
              {"m_classType":"PauseAction","m_delayInSeconds":7,"m_delayInMilliSeconds":0,"m_useAlarm":true,"m_constraintList":[],"m_isDisabled":false},
              {"m_classType":"SetVariableAction","m_newStringValue":"ready","m_variable":{"m_name":"output","m_type":2,"isLocal":false},"m_constraintList":[],"m_isDisabled":false},
              {"m_classType":"ShellScriptAction","m_script":"id","m_constraintList":[],"m_isDisabled":false},
              {"m_classType":"LoopAction","m_option":0,"m_fixedOptionCount":3,"m_constraintList":[],"m_isDisabled":false},
              {"m_classType":"EndLoopAction","m_constraintList":[],"m_isDisabled":false}
            ],
            "m_constraintList": [
              {"m_classType":"WifiConstraint","m_isDisabled":false}
            ],
            "localVariables": []
          }],
          "variables": [
            {"m_name":"output","m_type":2,"m_stringValue":"idle","m_booleanValue":false,"m_intValue":0,"m_decimalValue":0.0,"isLocal":false}
          ]
        }
    """.trimIndent()
}
