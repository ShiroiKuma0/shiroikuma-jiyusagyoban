package com.opentasker.core.storage

import com.opentasker.core.model.Scene
import com.opentasker.core.model.SceneElement
import com.opentasker.core.model.SceneElementType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SceneEntityTest {
    @Test
    fun sceneEntityRoundTripPreservesElements() {
        val scene = Scene(
            id = 3,
            name = "Overlay",
            widthDp = 240,
            heightDp = 160,
            elements = listOf(
                SceneElement(
                    id = 1,
                    type = SceneElementType.BUTTON,
                    xDp = 10,
                    yDp = 20,
                    widthDp = 80,
                    heightDp = 40,
                    tapTaskId = 5,
                ),
            ),
        )

        val decoded = scene.toEntity().toDomainDecodeResult()
        assertEquals(scene, decoded.value)
        assertNull(decoded.issue)
    }

    @Test
    fun malformedElementsJsonReturnsFallbackWithDecodeIssue() {
        val entity = SceneEntity(
            id = 9,
            name = "Corrupted scene",
            widthDp = 200,
            heightDp = 120,
            elementsJson = "{not-json",
        )
        val result = entity.toDomainDecodeResult()

        assertEquals(emptyList<SceneElement>(), result.value.elements)
        val issue = result.issue
        assertNotNull(issue)
        issue!!
        assertEquals(StorageRecordType.SCENE, issue.recordType)
        assertEquals(9L, issue.recordId)
        assertEquals("elementsJson", issue.fieldName)
        assertThrows(CorruptStoredRecordException::class.java) { entity.toDomain() }
    }
}
