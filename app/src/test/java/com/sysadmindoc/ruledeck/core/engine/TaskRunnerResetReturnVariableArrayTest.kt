package com.sysadmindoc.ruledeck.core.engine

import android.content.ContextWrapper
import com.sysadmindoc.ruledeck.core.actions.registerActionMetadata
import com.sysadmindoc.ruledeck.core.model.ActionSpec
import com.sysadmindoc.ruledeck.core.model.Task
import com.sysadmindoc.ruledeck.core.registerCoreRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Reset Return Variable on a name that is also an element of the caller's array. The called
 * task's array is its own copy, so changing it after the reset leaves the caller's alone.
 */
class TaskRunnerResetReturnVariableArrayTest {
    // FORK (2026-11-19): tests of upstream behaviour the fork does not share were removed from this
    // class — an unset variable left as written (here it reads as nothing), a called task that reads
    // its caller's locals (here a called task has locals of its own), Preflight's preview of those,
    // and Tasker's quirk of running on into an Else after a jump into the If branch.


    private val recorded = mutableListOf<String>()

    @Before
    fun setUp() {
        recorded.clear()
        registerActionMetadata()
        registerCoreRuntime()
        ActionRegistry.register(object : Action {
            override val id = "test.resetarray.record"
            override val category = ActionCategory.FLOW
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                val name = args.getValue("name")
                recorded += "${args["who"]}:$name=${ctx.variables.get(name) ?: "unset"}"
                return ActionResult.Success
            }
        })
    }

    private fun record(who: String, vararg names: String) =
        names.map { ActionSpec(type = "test.resetarray.record", args = mapOf("who" to who, "name" to it)) }

    private fun set(name: String, value: String) = ActionSpec(type = "var.set", args = mapOf("name" to name, "value" to value))

    private fun run(vararg tasks: Task): TaskRunReport = runBlocking {
        val byName = tasks.associateBy { it.name }
        TaskRunner(ActionContext(ContextWrapper(null), VariableStore()), resolveTask = { byName[it] }).run(tasks.first())
    }

    @Test
    fun theStoreKeepsTheCallersElementsWhenTheTaskWritesItsArray() {
        val store = VariableStore().apply {
            set("item1", "a")
            set("item2", "b")
        }
        store.pushScope()
        store.pushScope(holdsInputs = true)
        store.pushScope()
        store.clearForTask("item1")
        store.replaceNumberedItems("item", mapOf(1 to ("x" to false)))

        assertEquals(mapOf(1 to "x"), store.numberedItems("item"))
        store.popScope()
        store.popScope()
        assertEquals(mapOf(1 to "a", 2 to "b"), store.numberedItems("item"))
    }
}
