package com.opentasker.core.engine.variables

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VariableGuardTest {
    @After
    fun clear() = VariableGuard.setForTest(emptySet(), emptySet())

    @Test
    fun anUnmarkedVariableIsFree() {
        assertEquals(VariableGuard.Level.NONE, VariableGuard.level("KtOwner"))
        assertFalse(VariableGuard.refusesImport("KtOwner"))
        assertFalse(VariableGuard.refusesTaskWrite("KtOwner", "a", "b"))
    }

    @Test
    fun anImportProtectedVariableRefusesImportsButNotTasks() {
        VariableGuard.setForTest(setOf("KtSeq0"), emptySet())
        assertTrue(VariableGuard.refusesImport("KtSeq0"))
        assertTrue(VariableGuard.refusesImport("%KtSeq0"))
        assertFalse(VariableGuard.refusesTaskWrite("KtSeq0", "5", "6"))
    }

    @Test
    fun aLockedVariableRefusesBoth_exceptAWriteOfTheValueItHolds() {
        VariableGuard.setForTest(emptySet(), setOf("KtOwner"))
        assertTrue(VariableGuard.refusesImport("KtOwner"))
        assertTrue(VariableGuard.refusesTaskWrite("KtOwner", "mine", "theirs"))
        assertTrue(VariableGuard.refusesTaskWrite("%KtOwner", "mine", null))
        assertTrue(VariableGuard.refusesTaskWrite("KtOwner", null, "theirs"))
        assertFalse(VariableGuard.refusesTaskWrite("KtOwner", "mine", "mine"))
    }

    @Test
    fun aPathIsGuardedByItsBaseName() {
        VariableGuard.setForTest(emptySet(), setOf("Config"))
        assertEquals(VariableGuard.Level.LOCKED, VariableGuard.level("Config.user.name"))
        assertEquals(VariableGuard.Level.LOCKED, VariableGuard.level("%Config[0]"))
    }

    @Test
    fun theStoreLeavesALockedVariableAsItIs() {
        val scope = PersistentGlobalScope
        scope.setByHand(0L, "GuardTestVar", "mine")
        VariableGuard.setForTest(emptySet(), setOf("GuardTestVar"))
        scope.set(0L, "GuardTestVar", "theirs")
        assertEquals("mine", scope.get(0L, "GuardTestVar"))
        scope.unset(0L, "GuardTestVar")
        assertEquals("mine", scope.get(0L, "GuardTestVar"))
        scope.setByHand(0L, "GuardTestVar", "changed by hand")
        assertEquals("changed by hand", scope.get(0L, "GuardTestVar"))
        scope.unsetByHand(0L, "GuardTestVar")
        assertEquals(null, scope.get(0L, "GuardTestVar"))
    }

    @Test
    fun raisingNeverLowers() {
        VariableGuard.setForTest(emptySet(), setOf("KtOwner"))
        assertFalse(VariableGuard.raise("KtOwner", VariableGuard.Level.IMPORT))
        assertEquals(VariableGuard.Level.LOCKED, VariableGuard.level("KtOwner"))
    }
}
