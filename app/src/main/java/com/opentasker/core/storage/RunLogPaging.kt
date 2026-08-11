package com.opentasker.core.storage

data class RunLogSnapshot(
    val query: RunLogQuery,
    val anchor: RunLogKey?,
    val snapshotMaxId: Long,
    val totalCount: Int,
)

data class RunLogPage(
    val entries: List<RunLogEntity>,
    val hasMore: Boolean,
)

suspend fun RunLogDao.openSnapshot(query: RunLogQuery): RunLogSnapshot {
    val snapshotMaxId = maximumId() ?: return RunLogSnapshot(query, null, 0, 0)
    val anchor = newestMatchingKey(
        status = query.status.name,
        taskId = query.taskId,
        minimumTimestamp = query.minimumTimestamp,
        maximumTimestamp = query.maximumTimestamp,
        escapedSearch = query.escapedSearch,
        snapshotMaxId = snapshotMaxId,
    ) ?: return RunLogSnapshot(query, null, snapshotMaxId, 0)
    val count = countMatchingAtAnchor(
        status = query.status.name,
        taskId = query.taskId,
        minimumTimestamp = query.minimumTimestamp,
        maximumTimestamp = query.maximumTimestamp,
        escapedSearch = query.escapedSearch,
        anchorTimestamp = anchor.timestamp,
        anchorId = anchor.id,
        snapshotMaxId = snapshotMaxId,
    )
    return RunLogSnapshot(query, anchor, snapshotMaxId, count)
}

suspend fun RunLogDao.loadPage(
    snapshot: RunLogSnapshot,
    before: RunLogKey? = null,
    pageSize: Int = DEFAULT_RUN_LOG_PAGE_SIZE,
): RunLogPage {
    val anchor = snapshot.anchor ?: return RunLogPage(emptyList(), false)
    require(pageSize in 1..MAX_RUN_LOG_PAGE_SIZE) { "pageSize must be between 1 and $MAX_RUN_LOG_PAGE_SIZE" }
    val rows = getPageAtAnchor(
        status = snapshot.query.status.name,
        taskId = snapshot.query.taskId,
        minimumTimestamp = snapshot.query.minimumTimestamp,
        maximumTimestamp = snapshot.query.maximumTimestamp,
        escapedSearch = snapshot.query.escapedSearch,
        anchorTimestamp = anchor.timestamp,
        anchorId = anchor.id,
        beforeTimestamp = before?.timestamp,
        beforeId = before?.id,
        snapshotMaxId = snapshot.snapshotMaxId,
        limit = pageSize + 1,
    )
    return RunLogPage(rows.take(pageSize), rows.size > pageSize)
}

fun RunLogEntity.key(): RunLogKey = RunLogKey(timestamp, id)

const val DEFAULT_RUN_LOG_PAGE_SIZE = 50
const val MAX_RUN_LOG_PAGE_SIZE = 500
