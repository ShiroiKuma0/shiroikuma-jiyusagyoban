package com.sysadmindoc.ruledeck.core.storage

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The run log pages over a snapshot: the newest matching row and the largest id when the list
 * opened, so runs logged while someone scrolls can't shift or repeat rows. [FakeRunLogDao] answers
 * the three paging queries the way their SQL in [RunLogDao] does (id ceiling, anchor, exclusive
 * before key, newest first with id breaking timestamp ties), which is the contract under test.
 */
class RunLogPagingTest {
    @Test
    fun anEmptyLogOpensAnEmptySnapshotAndAsksForNoPage() = runBlocking {
        val dao = FakeRunLogDao()

        val snapshot = dao.openSnapshot(RunLogQuery())

        assertEquals(RunLogSnapshot(RunLogQuery(), anchor = null, snapshotMaxId = 0, totalCount = 0), snapshot)
        assertEquals(RunLogPage(emptyList(), hasMore = false), dao.loadPage(snapshot))
        assertEquals("no anchor means no page query", 0, dao.pageQueries)
    }

    @Test
    fun pagesWalkEveryRowOnceNewestFirstThroughTimestampTies() = runBlocking {
        val dao = FakeRunLogDao()
        listOf(100L, 300L, 200L, 300L, 100L, 300L, 200L).forEachIndexed { index, timestamp ->
            dao.add(id = index + 1L, timestamp = timestamp)
        }

        val snapshot = dao.openSnapshot(RunLogQuery())
        val pages = walk(dao, snapshot, pageSize = 3)

        assertEquals(RunLogKey(timestamp = 300L, id = 6L), snapshot.anchor)
        assertEquals(7, snapshot.totalCount)
        assertEquals(listOf(3, 3, 1), pages.map { it.entries.size })
        assertEquals(listOf(true, true, false), pages.map { it.hasMore })
        assertEquals(listOf(6L, 4L, 2L, 7L, 3L, 5L, 1L), pages.flatMap { it.entries }.map { it.id })
    }

    @Test
    fun runsLoggedAfterTheSnapshotStayOutOfItsPages() = runBlocking {
        val dao = FakeRunLogDao()
        listOf(10L, 20L, 30L, 40L).forEachIndexed { index, timestamp -> dao.add(id = index + 1L, timestamp = timestamp) }
        val snapshot = dao.openSnapshot(RunLogQuery())

        // A newer run, and one whose clock reads older than rows already on screen: the anchor
        // alone would let that second one into a later page.
        dao.add(id = 5L, timestamp = 50L)
        dao.add(id = 6L, timestamp = 15L)

        assertEquals(listOf(4L, 3L, 2L, 1L), walk(dao, snapshot, pageSize = 2).flatMap { it.entries }.map { it.id })
        assertEquals("a fresh snapshot sees them", 6, dao.openSnapshot(RunLogQuery()).totalCount)
    }

    @Test
    fun everyPageKeepsTheSnapshotsFilter() = runBlocking {
        val dao = FakeRunLogDao()
        (1L..9L).forEach { id -> dao.add(id = id, timestamp = id * 10, taskId = if (id % 3 == 0L) 2L else 1L) }

        val snapshot = dao.openSnapshot(RunLogQuery(taskId = 2L))
        val rows = walk(dao, snapshot, pageSize = 1).flatMap { it.entries }

        assertEquals(3, snapshot.totalCount)
        assertEquals(listOf(9L, 6L, 3L), rows.map { it.id })
        assertTrue(rows.all { it.taskId == 2L })
    }

    @Test
    fun everyPageKeepsTheStatusTheSearchAndTheTimeWindow() = runBlocking {
        val dao = FakeRunLogDao()
        (1L..20L).forEach { id ->
            dao.add(
                id = id,
                timestamp = id * 10,
                success = id % 3 != 0L,
                message = if (id % 4 == 0L || id == 2L || id == 17L) "Alarm went off" else "ok",
            )
        }
        // In the window 40..170, succeeded, and mentioning an alarm: 4, 8, 16 and 17, with 4 and 17
        // on its two edges. Row 12 failed, row 2 is before the window and row 20 after it.
        val query = RunLogQuery(
            status = RunLogStatusQuery.SUCCEEDED,
            minimumTimestamp = 40,
            maximumTimestamp = 170,
            escapedSearch = "alarm",
        )

        val snapshot = dao.openSnapshot(query)
        val rows = walk(dao, snapshot, pageSize = 1).flatMap { it.entries }

        assertEquals(4, snapshot.totalCount)
        assertEquals(listOf(17L, 16L, 8L, 4L), rows.map { it.id })
    }

    @Test
    fun aPageThatIsExactlyFullHasNoMoreAndThePageSizeIsBounded() = runBlocking {
        val dao = FakeRunLogDao()
        (1L..3L).forEach { id -> dao.add(id = id, timestamp = id) }
        val snapshot = dao.openSnapshot(RunLogQuery())

        val page = dao.loadPage(snapshot, pageSize = 3)

        assertEquals(3, page.entries.size)
        assertEquals("three rows in three slots leave nothing after them", false, page.hasMore)
        assertEquals("one row past the page is how hasMore is known", 4, dao.lastLimit)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { dao.loadPage(snapshot, pageSize = 0) } }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { dao.loadPage(snapshot, pageSize = MAX_RUN_LOG_PAGE_SIZE + 1) }
        }
        assertEquals(3, dao.loadPage(snapshot, pageSize = MAX_RUN_LOG_PAGE_SIZE).entries.size)
    }

    private suspend fun walk(dao: RunLogDao, snapshot: RunLogSnapshot, pageSize: Int): List<RunLogPage> {
        val pages = mutableListOf<RunLogPage>()
        var before: RunLogKey? = null
        do {
            val page = dao.loadPage(snapshot, before, pageSize)
            pages += page
            before = page.entries.lastOrNull()?.key()
        } while (page.hasMore && pages.size < 50)
        return pages
    }

    private class FakeRunLogDao : RunLogDao {
        private val rows = mutableListOf<RunLogEntity>()
        var pageQueries = 0
        var lastLimit = -1

        fun add(id: Long, timestamp: Long, taskId: Long = 1L, success: Boolean = true, message: String = "ok") {
            rows += RunLogEntity(
                id = id,
                taskId = taskId,
                taskName = "Task $taskId",
                timestamp = timestamp,
                durationMs = 0,
                success = success,
                message = message,
            )
        }

        /** The WHERE clause the three paging queries share, ordered as their ORDER BY. */
        private fun matching(
            status: String,
            taskId: Long?,
            minimumTimestamp: Long?,
            maximumTimestamp: Long?,
            escapedSearch: String,
            snapshotMaxId: Long,
        ): List<RunLogEntity> {
            // Rows here are never held and carry no "decision:" text, so these three statuses are
            // the SQL's for them; the rest aren't modelled.
            val statusMatches: (RunLogEntity) -> Boolean = when (status) {
                "ALL" -> { _ -> true }
                "SUCCEEDED" -> { row -> row.success }
                "FAILED" -> { row -> !row.success }
                else -> error("this fake doesn't model status $status")
            }
            // The SQL unescapes `\%`, `\_` and `\\` (ESCAPE '\'); this fake doesn't, so it refuses them.
            require('\\' !in escapedSearch) { "this fake doesn't model LIKE escapes" }
            return rows
                .filter { row ->
                    row.id <= snapshotMaxId &&
                        (taskId == null || row.taskId == taskId) &&
                        (minimumTimestamp == null || row.timestamp >= minimumTimestamp) &&
                        (maximumTimestamp == null || row.timestamp <= maximumTimestamp) &&
                        // LIKE '%search%' with no wildcards in the search, case-blind for ASCII.
                        (escapedSearch.isEmpty() ||
                            row.taskName.contains(escapedSearch, ignoreCase = true) ||
                            row.message.contains(escapedSearch, ignoreCase = true)) &&
                        statusMatches(row)
                }
                .sortedWith(compareByDescending<RunLogEntity> { it.timestamp }.thenByDescending { it.id })
        }

        private fun RunLogEntity.atOrBefore(timestamp: Long, id: Long) =
            this.timestamp < timestamp || (this.timestamp == timestamp && this.id <= id)

        private fun RunLogEntity.strictlyBefore(timestamp: Long, id: Long?) =
            this.timestamp < timestamp || (this.timestamp == timestamp && id != null && this.id < id)

        override suspend fun maximumId(): Long? = rows.maxOfOrNull { it.id }

        override suspend fun newestMatchingKey(
            status: String,
            taskId: Long?,
            minimumTimestamp: Long?,
            maximumTimestamp: Long?,
            escapedSearch: String,
            snapshotMaxId: Long,
        ): RunLogKey? = matching(status, taskId, minimumTimestamp, maximumTimestamp, escapedSearch, snapshotMaxId).firstOrNull()?.key()

        override suspend fun countMatchingAtAnchor(
            status: String,
            taskId: Long?,
            minimumTimestamp: Long?,
            maximumTimestamp: Long?,
            escapedSearch: String,
            anchorTimestamp: Long,
            anchorId: Long,
            snapshotMaxId: Long,
        ): Int = matching(status, taskId, minimumTimestamp, maximumTimestamp, escapedSearch, snapshotMaxId)
            .count { it.atOrBefore(anchorTimestamp, anchorId) }

        override suspend fun getPageAtAnchor(
            status: String,
            taskId: Long?,
            minimumTimestamp: Long?,
            maximumTimestamp: Long?,
            escapedSearch: String,
            anchorTimestamp: Long,
            anchorId: Long,
            beforeTimestamp: Long?,
            beforeId: Long?,
            snapshotMaxId: Long,
            limit: Int,
        ): List<RunLogEntity> {
            pageQueries++
            lastLimit = limit
            return matching(status, taskId, minimumTimestamp, maximumTimestamp, escapedSearch, snapshotMaxId)
                .filter { it.atOrBefore(anchorTimestamp, anchorId) }
                .filter { beforeTimestamp == null || it.strictlyBefore(beforeTimestamp, beforeId) }
                .take(limit)
        }

        // Not part of paging.
        override suspend fun insert(e: RunLogEntity) = unused()
        override suspend fun getRecent(): List<RunLogEntity> = unused()
        override fun getRecentFlow(): Flow<List<RunLogEntity>> = unused()
        override suspend fun getById(id: Long): RunLogEntity? = unused()
        override suspend fun getByExecutionId(executionId: String): RunLogEntity? = unused()
        override suspend fun setStarred(id: Long, starred: Boolean) = unused()
        override suspend fun pruneRetention(maxEntries: Int, minimumTimestamp: Long): Int = unused()
        override suspend fun countHeldPrunable(maxHeldEntries: Int, minimumTimestamp: Long): Int = unused()
        override suspend fun pruneHeldRetention(maxHeldEntries: Int, minimumTimestamp: Long): Int = unused()
        override suspend fun clearUnpinned(): Int = unused()
        override suspend fun clearHeld(id: Long): Int = unused()
        override suspend fun count(): Int = unused()
        override fun getTaskOptionsFlow(): Flow<List<RunLogTaskOption>> = unused()
        override suspend fun oldestTimestamp(): Long? = unused()
        override suspend fun countPrunable(maxEntries: Int, minimumTimestamp: Long): Int = unused()
        override fun maximumIdFlow(): Flow<Long?> = unused()

        private fun unused(): Nothing = throw UnsupportedOperationException("not part of the paging contract")
    }
}
