package com.opentasker.core.location

import com.opentasker.core.contexts.ContextEvent
import com.opentasker.core.contexts.LocationContextEvents
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationDwellStateTrackerTest {
    private val config = mapOf(
        "latitude" to "40.7580",
        "longitude" to "-73.9855",
        "radiusMeters" to "150",
        "dwellSeconds" to "60",
    )

    @Test
    fun firstInsideSamplePersistsObservedTimeAsInsideSince() {
        val update = LocationDwellStateTracker.apply(
            config = config,
            metadata = mapOf(
                "latitude" to "40.7581",
                "longitude" to "-73.9856",
                "observedAtEpochMs" to "100000",
            ),
            existingInsideSinceEpochMs = null,
            nowEpochMs = 100_500L,
        )

        assertEquals("100000", update.metadata["insideSinceEpochMs"])
        assertEquals("inside", update.metadata["dwellState"])
        assertEquals(LocationDwellPersistence.Persist(100_000L), update.persistence)
    }

    @Test
    fun laterInsideSampleKeepsPersistedInsideSinceForDwell() {
        val update = LocationDwellStateTracker.apply(
            config = config,
            metadata = mapOf(
                "latitude" to "40.7581",
                "longitude" to "-73.9856",
                "observedAtEpochMs" to "160000",
            ),
            existingInsideSinceEpochMs = 100_000L,
            nowEpochMs = 160_000L,
        )

        assertEquals("100000", update.metadata["insideSinceEpochMs"])
        assertEquals(LocationDwellPersistence.Persist(100_000L), update.persistence)
    }

    @Test
    fun outsideSampleClearsPersistedInsideState() {
        val update = LocationDwellStateTracker.apply(
            config = config,
            metadata = mapOf(
                "latitude" to "40.7700",
                "longitude" to "-73.9700",
                "observedAtEpochMs" to "200000",
                "insideSinceEpochMs" to "100000",
            ),
            existingInsideSinceEpochMs = 100_000L,
            nowEpochMs = 200_000L,
        )

        assertFalse("inside-since metadata should be removed outside the radius", "insideSinceEpochMs" in update.metadata)
        assertEquals("outside", update.metadata["dwellState"])
        assertEquals(LocationDwellPersistence.Clear, update.persistence)
    }

    @Test
    fun lowAccuracySampleKeepsExistingStateButDoesNotPersistNewState() {
        val update = LocationDwellStateTracker.apply(
            config = config + ("maxAccuracyMeters" to "25"),
            metadata = mapOf(
                "latitude" to "40.7581",
                "longitude" to "-73.9856",
                "accuracyMeters" to "75",
                "observedAtEpochMs" to "150000",
            ),
            existingInsideSinceEpochMs = 100_000L,
            nowEpochMs = 150_000L,
        )

        assertEquals("100000", update.metadata["insideSinceEpochMs"])
        assertEquals("accuracy_blocked", update.metadata["dwellState"])
        assertEquals(LocationDwellPersistence.Keep, update.persistence)
    }

    @Test
    fun storageKeyChangesWhenGeofenceConfigChanges() {
        val first = LocationDwellStateKey.from(7, 1, config)
        val sameDifferentOrder = LocationDwellStateKey.from(7, 1, config.toList().reversed().toMap())
        val changedRadius = LocationDwellStateKey.from(7, 1, config + ("radiusMeters" to "250"))
        val profilePrefix = LocationDwellStateKey.profilePrefix(7)

        assertEquals(first, sameDifferentOrder)
        assertEquals("profile:7:", profilePrefix)
        assertTrue(first.storageKey.startsWith("${profilePrefix}context:1:"))
        assertTrue(first.storageKey.length > "profile:7:context:1:".length)
        assertFalse(first == changedRadius)
    }

    @Test
    fun storeSerializesReadModifyWriteAcrossConcurrentEnrichCalls() {
        val storage = BlockingLocationDwellStateStorage()
        val store = LocationDwellStateStore(storage) { 200_000L }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit(Callable {
                store.enrich(
                    profileId = 7,
                    contextIndex = 1,
                    spec = locationSpec(),
                    event = insideEvent(observedAt = 100_000L),
                )
            })
            assertTrue("First write did not reach the storage gate", storage.firstPutEntered.await(1, TimeUnit.SECONDS))

            val second = executor.submit(Callable {
                store.enrich(
                    profileId = 7,
                    contextIndex = 1,
                    spec = locationSpec(),
                    event = insideEvent(observedAt = 160_000L),
                )
            })

            Thread.sleep(100)
            assertFalse(
                "Second enrich read dwell state before the first read-modify-write completed",
                storage.secondGetEntered.get(),
            )

            storage.releaseFirstPut.countDown()
            assertEquals("100000", first.get(1, TimeUnit.SECONDS).metadata["insideSinceEpochMs"])
            assertEquals("100000", second.get(1, TimeUnit.SECONDS).metadata["insideSinceEpochMs"])
        } finally {
            storage.releaseFirstPut.countDown()
            executor.shutdownNow()
        }
    }

    private fun locationSpec(): ContextSpec =
        ContextSpec(
            type = ContextType.LOCATION,
            config = config,
        )

    private fun insideEvent(observedAt: Long): ContextEvent =
        ContextEvent(
            type = LocationContextEvents.TYPE,
            matched = true,
            metadata = mapOf(
                "latitude" to "40.7581",
                "longitude" to "-73.9856",
                "observedAtEpochMs" to observedAt.toString(),
            ),
        )

    private class BlockingLocationDwellStateStorage : LocationDwellStateStorage {
        private val values = ConcurrentHashMap<String, Long>()
        private val getCalls = AtomicInteger()
        private val blockNextPut = AtomicBoolean(true)
        val firstPutEntered = CountDownLatch(1)
        val releaseFirstPut = CountDownLatch(1)
        val secondGetEntered = AtomicBoolean(false)

        override fun getLong(key: String, defaultValue: Long): Long {
            if (getCalls.incrementAndGet() == 2) {
                secondGetEntered.set(true)
            }
            return values[key] ?: defaultValue
        }

        override fun putLong(key: String, value: Long) {
            if (blockNextPut.compareAndSet(true, false)) {
                firstPutEntered.countDown()
                assertTrue("Timed out waiting to release the first dwell write", releaseFirstPut.await(2, TimeUnit.SECONDS))
            }
            values[key] = value
        }

        override fun remove(key: String) {
            values.remove(key)
        }

        override fun keys(): Set<String> =
            values.keys.toSet()

        override fun removeAll(keys: Collection<String>) {
            keys.forEach(values::remove)
        }
    }
}
