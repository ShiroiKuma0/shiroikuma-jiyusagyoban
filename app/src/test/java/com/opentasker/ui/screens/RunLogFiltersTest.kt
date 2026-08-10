package com.opentasker.ui.screens

import com.opentasker.core.storage.RunLogStatusQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RunLogFiltersTest {
    @Test
    fun defaultFilterProducesAnUnboundedSqlQuery() {
        val query = RunLogFilterState().toStorageQuery(nowMillis = 1234)

        assertEquals(RunLogStatusQuery.ALL, query.status)
        assertNull(query.taskId)
        assertNull(query.minimumTimestamp)
        assertNull(query.maximumTimestamp)
        assertEquals("", query.escapedSearch)
    }

    @Test
    fun storageQueryCarriesEveryFilterAndEscapesLikeWildcards() {
        val now = 10L * 24 * 60 * 60 * 1_000
        val query = RunLogFilterState(
            status = RunLogStatusFilter.Cancelled,
            taskId = 42,
            query = "100%_done",
            date = RunLogDateFilter.Week,
        ).toStorageQuery(now)

        assertEquals(RunLogStatusQuery.CANCELLED, query.status)
        assertEquals(42L, query.taskId)
        assertEquals(now - 7L * 24 * 60 * 60 * 1_000, query.minimumTimestamp)
        assertEquals("100\\%\\_done", query.escapedSearch)
    }

    @Test
    fun everyUiStatusMapsToTheMatchingSqlStatus() {
        val expected = mapOf(
            RunLogStatusFilter.All to RunLogStatusQuery.ALL,
            RunLogStatusFilter.Succeeded to RunLogStatusQuery.SUCCEEDED,
            RunLogStatusFilter.Failed to RunLogStatusQuery.FAILED,
            RunLogStatusFilter.Skipped to RunLogStatusQuery.SKIPPED,
            RunLogStatusFilter.Held to RunLogStatusQuery.HELD,
            RunLogStatusFilter.Cancelled to RunLogStatusQuery.CANCELLED,
        )

        expected.forEach { (ui, sql) ->
            assertEquals(sql, RunLogFilterState(status = ui).toStorageQuery().status)
        }
    }
}
