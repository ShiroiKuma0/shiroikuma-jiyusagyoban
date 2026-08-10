package com.opentasker.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableGlobalsTest {
    @Test
    fun changedGlobalsReturnsOnlyAddedOrModified() {
        val before = mapOf("A" to "1", "B" to "2")
        val after = mapOf("A" to "1", "B" to "9", "C" to "3")

        val changed = changedGlobals(before, after)

        // A unchanged (excluded); B modified; C added — sorted by name.
        assertEquals(listOf("B", "C"), changed.map { it.name })
        assertEquals(listOf("9", "3"), changed.map { it.value })
        assertTrue(changed.all { it.isGlobal })
    }

    @Test
    fun changedGlobalsIsEmptyWhenNothingChanged() {
        val same = mapOf("A" to "1", "B" to "2")
        assertEquals(emptyList<Any>(), changedGlobals(same, same))
    }

    @Test
    fun changedGlobalsPersistsSensitivityOnlyChanges() {
        val same = mapOf("TOKEN" to "value")

        val changed = changedGlobals(
            before = same,
            after = same,
            beforeSensitive = emptySet(),
            afterSensitive = setOf("TOKEN"),
        )

        assertEquals(listOf("TOKEN"), changed.map { it.name })
        assertTrue(changed.single().isSecret)
    }

    @Test
    fun seededGlobalsAreReadableAndSnapshotted() {
        val store = VariableStore()
        store.seedGlobals(mapOf("TOKEN" to "abc"))

        assertEquals("abc", store.get("TOKEN"))
        assertEquals(mapOf("TOKEN" to "abc"), store.globalSnapshot())
    }

    @Test
    fun globalsSetDuringRunAppearInSnapshotButLocalsDoNot() {
        val store = VariableStore()
        store.pushScope()
        store.set("COUNT", "5")   // uppercase -> global
        store.set("temp", "x")    // lowercase -> local scope

        val snapshot = store.globalSnapshot()
        assertEquals("5", snapshot["COUNT"])
        assertNull(snapshot["temp"])

        // A run's persistable delta excludes untouched seeded globals and all locals.
        val baseline = emptyMap<String, String>()
        assertEquals(listOf("COUNT"), changedGlobals(baseline, snapshot).map { it.name })
    }

    @Test
    fun persistCopyIntoGlobalNamespaceIsDurable() {
        val store = VariableStore()
        store.pushScope()
        store.set("local", "hello")
        // var.persist copies a local into the global namespace. ALL-CAPS, because a MixedCase name is
        // project-scoped and this store has no project — the scoping guard would keep it task-local.
        store.set("PERSISTED", store.get("local")!!)

        assertEquals("hello", store.globalSnapshot()["PERSISTED"])
    }
}
