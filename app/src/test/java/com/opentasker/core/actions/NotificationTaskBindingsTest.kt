package com.opentasker.core.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationTaskBindingsTest {
    private val tasks = listOf(
        NotificationTaskCandidate(11, "Morning"),
        NotificationTaskCandidate(12, "Evening"),
    )

    @Test
    fun immutableIdSurvivesTaskRename() {
        val reference = NotificationTaskBindings.parse(mapOf("button1_task_id" to "11"), 1)!!
        val renamed = listOf(NotificationTaskCandidate(11, "Renamed"))

        val resolution = NotificationTaskBindings.resolve(reference, renamed)

        assertEquals(
            NotificationTaskResolution.Bound(renamed.single(), migratedFromLegacyName = false),
            resolution,
        )
    }

    @Test
    fun uniqueLegacyNameMigratesToImmutableId() {
        val reference = NotificationTaskBindings.parse(mapOf("button2_task" to "Evening"), 2)!!

        val resolution = NotificationTaskBindings.resolve(reference, tasks)

        assertEquals(
            NotificationTaskResolution.Bound(tasks[1], migratedFromLegacyName = true),
            resolution,
        )
    }

    @Test
    fun duplicateLegacyNameFailsClosed() {
        val reference = NotificationTaskReference.LegacyName("Duplicate")
        val candidates = listOf(
            NotificationTaskCandidate(1, "Duplicate"),
            NotificationTaskCandidate(2, "Duplicate"),
        )

        val resolution = NotificationTaskBindings.resolve(reference, candidates)

        assertEquals(NotificationTaskResolution.Ambiguous("Duplicate", 2), resolution)
        assertTrue(NotificationTaskBindings.failureMessage(resolution).contains("matches 2 tasks"))
    }

    @Test
    fun deletedIdProducesClearMissingOutcome() {
        val reference = NotificationTaskReference.Id(99)

        val resolution = NotificationTaskBindings.resolve(reference, tasks)

        assertEquals(NotificationTaskResolution.Missing(reference), resolution)
        assertTrue(NotificationTaskBindings.failureMessage(resolution).contains("no longer exists"))
    }

    @Test
    fun invalidIdNeverFallsBackToLegacyName() {
        val reference = NotificationTaskBindings.parse(
            mapOf("button1_task_id" to "not-an-id", "button1_task" to "Morning"),
            1,
        )

        assertEquals(NotificationTaskReference.Invalid("not-an-id"), reference)
        assertFalse(reference is NotificationTaskReference.LegacyName)
    }
}
