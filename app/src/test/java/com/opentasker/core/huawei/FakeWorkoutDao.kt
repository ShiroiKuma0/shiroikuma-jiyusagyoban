package com.opentasker.core.huawei

import com.opentasker.core.storage.HuaweiMapCutoutEntity
import com.opentasker.core.storage.HuaweiWorkoutBlobEntity
import com.opentasker.core.storage.HuaweiWorkoutDao
import com.opentasker.core.storage.HuaweiWorkoutEntity

/**
 * The workout tables in memory, so the store can be tested on the JVM.
 *
 * Room's own queries run on a device and are covered by the migration replay; what is worth testing
 * here is the STORE's contract — that a re-fetch carries the annotation forward, that the heart rate
 * decodes from the band's own blocks, that a legacy row falls back and a real one overrides it.
 * Those are decisions this app makes, and none of them needs SQLite to be exercised.
 */
class FakeWorkoutDao : HuaweiWorkoutDao {
    val rows = LinkedHashMap<Long, HuaweiWorkoutEntity>()
    val blobs = LinkedHashMap<Pair<Long, String>, ByteArray>()
    val cutouts = LinkedHashMap<String, HuaweiMapCutoutEntity>()

    override suspend fun all() = rows.values.sortedByDescending { it.startSeconds }
    override suspend fun ofSport(sportType: Int) = all().filter { it.sportType == sportType }
    override suspend fun byStart(startSeconds: Long) = rows[startSeconds]
    override suspend fun knownStarts() = rows.keys.toList()
    override suspend fun upsert(row: HuaweiWorkoutEntity) { rows[row.startSeconds] = row }
    override suspend fun delete(startSeconds: Long) { rows.remove(startSeconds) }

    override suspend fun annotate(startSeconds: Long, note: String?, stops: Int?) {
        rows[startSeconds]?.let { rows[startSeconds] = it.copy(note = note, stops = stops) }
    }

    override suspend fun recordMap(
        startSeconds: Long,
        trackId: String?,
        chizuJson: String?,
        cutoutKey: String?,
    ) {
        rows[startSeconds]?.let {
            rows[startSeconds] = it.copy(trackId = trackId, chizuJson = chizuJson, cutoutKey = cutoutKey)
        }
    }

    override suspend fun blob(startSeconds: Long, name: String) = blobs[startSeconds to name]
    override suspend fun blobNames(startSeconds: Long) =
        blobs.keys.filter { it.first == startSeconds }.map { it.second }.sorted()

    override suspend fun putBlob(row: HuaweiWorkoutBlobEntity) {
        blobs[row.startSeconds to row.name] = row.payload
    }

    override suspend fun deleteBlobs(startSeconds: Long) {
        blobs.keys.filter { it.first == startSeconds }.forEach { blobs.remove(it) }
    }

    override suspend fun cutout(key: String) = cutouts[key]?.png
    override suspend fun cutoutKeys() = cutouts.keys.toList()
    override suspend fun putCutout(row: HuaweiMapCutoutEntity) { cutouts[row.key] = row }
}
