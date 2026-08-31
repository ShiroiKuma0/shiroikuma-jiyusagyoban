package com.opentasker.ui.screens

import com.opentasker.core.engine.SUB_TASK_ACTION_ID
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The action editor rebuilds `args` from its visible fields, so an arg no field declares is dropped by
 * the act of opening an action and pressing Save — silently, and destructively for the actions whose
 * behaviour hinges on it. [preservedActionArgs] is what carries those across.
 */
class ActionEditorArgPreservationTest {

    @Test
    fun keepsArgsNoFieldDeclares() {
        val preserved = preservedActionArgs(
            actionId = "scene.show",
            fieldKeys = listOf("scene", "position"),
            existing = mapOf("scene" to "音楽良", "position" to "left", "inset" to "%Ongaku_Btngoodx"),
        )

        assertEquals(mapOf("inset" to "%Ongaku_Btngoodx"), preserved)
    }

    @Test
    fun dropsFieldKeysSoTheFormOwnsThem() {
        // A field the user cleared must stay cleared rather than being restored from the old args.
        val preserved = preservedActionArgs(
            actionId = "scene.hide",
            fieldKeys = listOf("scene"),
            existing = mapOf("scene" to "音楽削"),
        )

        assertEquals(emptyMap<String, String>(), preserved)
    }

    @Test
    fun dropsTheLegacySpellingAFieldReadsThrough() {
        // The form seeded "brightness" from the old "level"; keeping "level" too would shadow the edit.
        val preserved = preservedActionArgs(
            actionId = "brightness.set",
            fieldKeys = listOf("brightness"),
            existing = mapOf("level" to "40", "unrelated" to "keep"),
        )

        assertEquals(mapOf("unrelated" to "keep"), preserved)
    }

    @Test
    fun leavesRunTaskParametersToTheirOwnEditor() {
        // Deleting a parameter in the Run Task section must not be undone by the rescue.
        val preserved = preservedActionArgs(
            actionId = SUB_TASK_ACTION_ID,
            fieldKeys = listOf("task"),
            existing = mapOf("task" to "音楽操作表示", "param:mode" to "quiet", "priority" to "5"),
        )

        assertEquals(mapOf("priority" to "5"), preserved)
    }

    @Test
    fun keepsDynamicReturnPayloads() {
        // task.return's ret:<name> keys have no field at all — they are the whole point of the action.
        val preserved = preservedActionArgs(
            actionId = "task.return",
            fieldKeys = listOf("value"),
            existing = mapOf("ret:line" to "%line", "ret:bytes" to "%bytes"),
        )

        assertEquals(mapOf("ret:line" to "%line", "ret:bytes" to "%bytes"), preserved)
    }
}
