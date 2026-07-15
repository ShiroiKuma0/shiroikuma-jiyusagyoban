package com.opentasker.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VariableNamePolicyTest {
    @Test
    fun normalizesOptionalSigilAndClassifiesAnyUppercaseLetterAsGlobal() {
        assertEquals("myVar", VariableNamePolicy.normalize("  %myVar "))
        assertTrue(VariableNamePolicy.isGlobal("myVar"))
        assertTrue(VariableNamePolicy.isGlobal("MYVAR"))
        assertFalse(VariableNamePolicy.isGlobal("myvar"))
    }

    @Test
    fun promotesLowercaseNamesAndRejectsInvalidOrMismatchedNames() {
        assertEquals("Myvar", VariableNamePolicy.promoteToGlobal("myvar"))
        assertEquals("myVar", VariableNamePolicy.promoteToGlobal("myVar"))
        assertEquals("local_name", VariableNamePolicy.normalizeForScope("local_name", isGlobal = false))
        assertNull(VariableNamePolicy.normalizeForScope("localName", isGlobal = false))
        assertNull(VariableNamePolicy.normalize("bad name"))
        assertNull(VariableNamePolicy.normalize("1name"))
    }
}
