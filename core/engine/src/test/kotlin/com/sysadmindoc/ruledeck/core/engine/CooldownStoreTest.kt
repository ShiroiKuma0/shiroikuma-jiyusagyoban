package com.sysadmindoc.ruledeck.core.engine

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The persisted half of cooldown. `CooldownReservationsTest` covers the in-memory half; these
 * cover what survives process death, which is where a wrong answer is invisible until a profile
 * either re-fires during its cooldown or stays silent past it.
 */
class CooldownStoreTest {
    private val now = 1_700_000_000_000L

    @Test
    fun onlyDeadlinesStillInTheFutureAreLoaded() {
        val stored = mapOf(
            "cd_1" to now + 60_000L,
            "cd_2" to now - 1L,
            "cd_3" to now,
        )

        assertEquals(mapOf(1L to now + 60_000L), liveCooldowns(stored, now))
        assertEquals(listOf("cd_2", "cd_3").sorted(), expiredCooldownKeys(stored, now).sorted())
    }

    @Test
    fun unrelatedPreferenceKeysAndNonLongValuesAreIgnored() {
        val stored = mapOf<String, Any?>(
            "cd_1" to now + 5_000L,
            "some_other_setting" to now + 5_000L,
            "cd_notanumber" to now + 5_000L,
            "cd_2" to "not a deadline",
            "cd_3" to null,
        )

        assertEquals(mapOf(1L to now + 5_000L), liveCooldowns(stored, now))
        assertTrue(
            "keys outside the cooldown namespace must never be removed",
            expiredCooldownKeys(stored, now).all { it.startsWith("cd_") },
        )
        assertTrue("a non-numeric suffix is not a profile id", "cd_notanumber" !in expiredCooldownKeys(stored, now))
    }

    @Test
    fun pruningKeepsDeadlinesForProfilesThatStillExist() {
        // The regression this guards: pruning against *enabled* profiles deleted the deadline of a
        // profile the user had merely switched off, so whether the cooldown still applied after
        // re-enabling depended on whether the service happened to restart in between.
        val keys = setOf("cd_1", "cd_2", "cd_3", "unrelated")
        val existingProfiles = setOf(1L, 3L)

        assertEquals(listOf("cd_2"), staleCooldownKeys(keys, existingProfiles))
    }

    @Test
    fun pruningRemovesEverythingWhenNoProfilesRemain() {
        assertEquals(
            listOf("cd_1", "cd_2").sorted(),
            staleCooldownKeys(setOf("cd_1", "cd_2", "unrelated"), emptySet()).sorted(),
        )
    }

    @Test
    fun keysAreParsedOnlyForTheCooldownNamespace() {
        assertEquals(7L, profileIdForCooldownKey("cd_7"))
        assertEquals(null, profileIdForCooldownKey("cooldown_7"))
        assertEquals(null, profileIdForCooldownKey("cd_"))
        assertEquals(null, profileIdForCooldownKey("cd_seven"))
    }

    // The store itself, over an in-memory SharedPreferences, so a slip in the key it writes, a
    // prune that writes when it shouldn't, or a read that prunes would show up here.

    /**
     * A run a service stop canceled gives its cooldown back when its job ends, which can be after
     * the next service reserved a newer deadline. Only the deadline it reserved is put back.
     */
    @Test
    fun aLateRestorePutsThePreviousDeadlineBackOnlyOverItsOwnReservation() {
        val app = PrefsContext()
        val oldService = CooldownStore(app)

        oldService.set(1L, now + 60_000L)
        oldService.restore(1L, reservedMs = now + 60_000L, previousMs = now + 10_000L)
        assertEquals(now + 10_000L, CooldownStore(app).get(1L))

        oldService.set(2L, now + 60_000L)
        CooldownStore(app).set(2L, now + 90_000L)
        oldService.restore(2L, reservedMs = now + 60_000L, previousMs = 0L)
        assertEquals("the next service's deadline stays", now + 90_000L, CooldownStore(app).get(2L))

        oldService.set(3L, now + 60_000L)
        oldService.restore(3L, reservedMs = now + 60_000L, previousMs = 0L)
        assertEquals(setOf("cd_1", "cd_2"), app.prefs.values.keys)
    }

    @Test
    fun aDeadlineSetByOneStoreIsReadByTheNextAndCountsDownToZero() {
        val app = PrefsContext()
        CooldownStore(app).set(5L, now + 60_000L)

        val afterRestart = CooldownStore(app)
        assertEquals("a new process must see the same deadline", now + 60_000L, afterRestart.get(5L))
        assertEquals(60_000L, afterRestart.remaining(5L, cooldownSec = 60, nowMs = now))
        assertEquals("time left never goes negative", 0L, afterRestart.remaining(5L, cooldownSec = 60, nowMs = now + 70_000L))
        assertEquals("a profile without a cooldown has none", 0L, afterRestart.get(6L))
        assertEquals(setOf("cd_5"), app.prefs.values.keys)

        afterRestart.remove(5L)
        assertEquals(0L, CooldownStore(app).get(5L))
        assertTrue(app.prefs.values.isEmpty())
    }

    /** What the trigger simulation shows, counted the way a trigger is held. */
    @Test
    fun theTimeLeftIsAtMostOneCurrentWindow() {
        val app = PrefsContext()
        CooldownStore(app).set(1L, now + 86_400_000L)

        assertEquals("a clock set back a day", 60_000L, CooldownStore(app).remaining(1L, cooldownSec = 60, nowMs = now))
        assertEquals("a cooldown since removed", 0L, CooldownStore(app).remaining(1L, cooldownSec = 0, nowMs = now))
    }

    @Test
    fun askingHowLongIsLeftNeverWritesEvenForAPassedDeadline() {
        val app = PrefsContext()
        CooldownStore(app).set(1L, now - 1_000L)
        val writesBefore = app.prefs.writes

        assertEquals(0L, CooldownStore(app).remaining(1L, cooldownSec = 60, nowMs = now))
        assertEquals(writesBefore, app.prefs.writes)
        assertTrue("only loadAll prunes", "cd_1" in app.prefs.values)
    }

    @Test
    fun loadingDropsPassedDeadlinesFromStorageAndLeavesEverythingElse() {
        val wallClock = System.currentTimeMillis()
        val app = PrefsContext()
        val store = CooldownStore(app)
        store.set(1L, wallClock - 60_000L)
        store.set(2L, wallClock + 3_600_000L)
        app.prefs.values["unrelated"] = wallClock - 60_000L

        assertEquals(mapOf(2L to wallClock + 3_600_000L), store.loadAll())
        assertEquals(setOf("cd_2", "unrelated"), app.prefs.values.keys)
    }

    @Test
    fun loadingWithNothingExpiredWritesNothing() {
        val app = PrefsContext()
        CooldownStore(app).set(2L, System.currentTimeMillis() + 3_600_000L)
        val writesBefore = app.prefs.writes

        CooldownStore(app).loadAll()

        assertEquals(writesBefore, app.prefs.writes)
    }

    @Test
    fun pruningDropsOnlyDeletedProfilesAndWritesNothingWhenNoneAre() {
        val app = PrefsContext()
        val store = CooldownStore(app)
        store.set(1L, now + 1L)
        store.set(2L, now + 1L)
        app.prefs.values["unrelated"] = 7L

        val writesBefore = app.prefs.writes
        store.pruneDeleted(setOf(1L, 2L))
        assertEquals("nothing stale, nothing written", writesBefore, app.prefs.writes)

        store.pruneDeleted(setOf(1L))
        assertEquals(setOf("cd_1", "unrelated"), app.prefs.values.keys)
    }

    private class PrefsContext : ContextWrapper(null) {
        val prefs = InMemoryPreferences()

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
            assertEquals("opentasker_cooldowns", name)
            assertEquals(Context.MODE_PRIVATE, mode)
            return prefs
        }
    }

    /** Just enough SharedPreferences for the store: values, and a count of applied edits. */
    private class InMemoryPreferences : SharedPreferences {
        val values = linkedMapOf<String, Any?>()
        var writes = 0

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(values)
        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (values[key] as? Set<String>)?.toMutableSet() ?: defValues

        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = key in values
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val staged = linkedMapOf<String, Any?>()
            private val removed = mutableSetOf<String>()
            private var clearFirst = false

            private fun put(key: String?, value: Any?): SharedPreferences.Editor {
                staged[requireNotNull(key)] = value
                return this
            }

            override fun putString(key: String?, value: String?) = put(key, value)
            override fun putStringSet(key: String?, values: MutableSet<String>?) = put(key, values?.toSet())
            override fun putInt(key: String?, value: Int) = put(key, value)
            override fun putLong(key: String?, value: Long) = put(key, value)
            override fun putFloat(key: String?, value: Float) = put(key, value)
            override fun putBoolean(key: String?, value: Boolean) = put(key, value)

            override fun remove(key: String?): SharedPreferences.Editor {
                removed += requireNotNull(key)
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                clearFirst = true
                return this
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearFirst) values.clear()
                removed.forEach { values.remove(it) }
                staged.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                writes++
            }
        }
    }
}
