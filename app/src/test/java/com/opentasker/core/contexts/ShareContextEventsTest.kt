package com.opentasker.core.contexts

import android.content.Intent
import android.os.Bundle
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareContextEventsTest {
    @After
    fun tearDown() {
        ShareContextEvents.resetForTests()
    }

    @Test
    fun textUrlAndMimeBecomeBoundedMatchableMetadata() {
        val event = ShareContextEvents.parseInput(
            ShareInput(
                action = Intent.ACTION_SEND,
                mime = "text/uri-list",
                textValue = "  https://example.test/tasks\n",
            ),
            nowMs = 10_000L,
        )

        requireNotNull(event)
        assertEquals("https://example.test/tasks", event.metadata["text"])
        assertEquals("https://example.test/tasks", event.metadata["uri"])
        assertEquals("text/uri-list", event.metadata["mime"])
        assertTrue(
            ContextMatchEvaluator.matches(
                com.opentasker.core.model.ContextSpec(
                    com.opentasker.core.model.ContextType.EVENT,
                    config = mapOf("event" to "share", "mime" to "text/*", "text" to "example.test"),
                ),
                event,
            ),
        )
    }

    @Test
    fun singleFileUriIsExposedWithoutOpeningIt() {
        val uri = "content://com.example.files/document/42"
        val event = ShareContextEvents.parseInput(
            ShareInput(
                action = Intent.ACTION_SEND,
                mime = "application/pdf",
                streamValue = uri,
            ),
        )

        requireNotNull(event)
        assertEquals(uri.toString(), event.metadata["uri"])
        assertEquals("1", event.metadata["count"])
        assertEquals("false", event.metadata["multiple"])
    }

    @Test
    fun multipleFilesAreBoundedAndDeduplicated() {
        val first = "content://com.example.files/document/1"
        val second = "content://com.example.files/document/2"
        val event = ShareContextEvents.parseInput(
            ShareInput(
                action = Intent.ACTION_SEND_MULTIPLE,
                mime = "text/plain",
                streamValue = arrayListOf(first, second, first),
            ),
        )

        requireNotNull(event)
        assertEquals("2", event.metadata["count"])
        assertEquals("true", event.metadata["multiple"])
        assertEquals("$first\n$second", event.metadata["uris"])
    }

    @Test
    fun oversizedAndArbitraryParcelableExtrasFailClosed() {
        val oversizedText = ShareInput(
            action = Intent.ACTION_SEND,
            mime = "text/plain",
            textValue = "x".repeat(ShareContextEvents.MAX_TEXT_CHARS + 1),
        )
        val arbitraryText = ShareInput(
            action = Intent.ACTION_SEND,
            mime = "text/plain",
            textValue = Bundle(),
        )
        val arbitraryStream = ShareInput(
            action = Intent.ACTION_SEND,
            mime = "application/octet-stream",
            streamValue = Bundle(),
        )
        val oversizedTextWithUri = ShareInput(
            action = Intent.ACTION_SEND,
            mime = "text/plain",
            textValue = "x".repeat(ShareContextEvents.MAX_TEXT_CHARS + 1),
            streamValue = "content://com.example.files/document/3",
        )

        assertNull(ShareContextEvents.parseInput(oversizedText))
        assertNull(ShareContextEvents.parseInput(arbitraryText))
        assertNull(ShareContextEvents.parseInput(arbitraryStream))
        assertNull(ShareContextEvents.parseInput(oversizedTextWithUri))
    }

    @Test
    fun unreadableContentUrisAreClassifiedBeforePublishing() {
        val event = requireNotNull(
            ShareContextEvents.parseInput(
                ShareInput(
                    action = Intent.ACTION_SEND,
                    streamValue = "content://com.example.files/document/99",
                ),
            ),
        )

        with(ShareContextEvents) {
            assertFalse(event.containsUnreadableContentUri { _: String -> true })
            assertTrue(event.containsUnreadableContentUri { _: String -> false })
        }
    }

    @Test
    fun unrelatedIntentIsRejected() {
        assertNull(ShareContextEvents.parseInput(ShareInput(Intent.ACTION_VIEW)))
    }
}
