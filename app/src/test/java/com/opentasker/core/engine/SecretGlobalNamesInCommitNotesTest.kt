package com.opentasker.core.engine

import android.content.ContextWrapper
import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.core.registerCoreRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * When a run's globals can't be saved (a database error, or another run wrote them first), the run
 * log and app log name them. A global whose name was built from a secret is counted there instead,
 * the way a step's change list counts it, or `%Cache_%Pin` would write the PIN into both logs.
 */
class SecretGlobalNamesInCommitNotesTest {

    @Before
    fun setUp() {
        registerActionMetadata()
        registerCoreRuntime()
    }

    @Test
    fun aGlobalNamedFromASecretIsCountedNotNamed() {
        val store = VariableStore().apply { set("Pin", "4831", sensitive = true) }
        val step = ActionSpec(type = "var.set", args = mapOf("name" to "%Cache_%Pin", "value" to "x"))
        val report = runBlocking { TaskRunner(ActionContext(ContextWrapper(null), store)).run(Task(id = 1, name = "Cache", actions = listOf(step))) }
        assertTrue(report.results.toString(), report.success)
        val built = store.globalSnapshot().keys.single { it.startsWith("Cache_") }

        val shown = loggableGlobalNames(listOf("Count", built), store.globalSensitiveSnapshot())

        assertFalse(shown, "4831" in shown)
        assertEquals("Count, 1 hidden name", shown)
    }

    @Test
    fun aSecretThatCantBeReadBackIsCountedNotNamed() {
        // Saved secret, so it can come back unreadable (a restore, a lost Keystore key). The Variables
        // screen marks each one, so the app log only needs the count.
        val note = unreadableSecretsNote(setOf("Cache_4831", "ApiKey"))
        assertEquals("2 secret variables can't be read and need to be entered again on the Variables screen", note)
        assertEquals(
            "1 secret variable can't be read and needs to be entered again on the Variables screen",
            unreadableSecretsNote(setOf("ApiKey")),
        )
        assertEquals(null, unreadableSecretsNote(emptySet()))
    }

    @Test
    fun plainNamesStillShow() {
        assertEquals("Count, Log", loggableGlobalNames(listOf("Count", "Log"), setOf("Pin")))
        assertEquals("", loggableGlobalNames(emptyList(), setOf("Pin")))
    }
}
