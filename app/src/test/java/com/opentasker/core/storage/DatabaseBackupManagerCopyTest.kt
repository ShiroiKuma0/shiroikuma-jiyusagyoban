package com.opentasker.core.storage

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseBackupManagerCopyTest {

    @Test
    fun boundedCopyAcceptsContentAtLimit() {
        val output = ByteArrayOutputStream()

        val copied = ByteArrayInputStream("abcd".toByteArray())
            .copyBoundedTo(output, maxBytes = 4)

        assertEquals(4, copied)
        assertEquals("abcd", output.toString())
    }

    @Test
    fun boundedCopyRejectsContentOverLimitBeforeWritingOverflowChunk() {
        val output = ByteArrayOutputStream()

        val error = runCatching {
            ByteArrayInputStream("abcde".toByteArray())
                .copyBoundedTo(output, maxBytes = 4)
        }.exceptionOrNull()

        assertTrue(error is IOException)
        assertTrue(error!!.message.orEmpty().contains("import limit"))
        assertEquals(0, output.size())
    }
}
