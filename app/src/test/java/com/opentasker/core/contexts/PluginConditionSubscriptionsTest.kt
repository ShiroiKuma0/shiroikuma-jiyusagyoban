package com.opentasker.core.contexts

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginConditionSubscriptionsTest {
    @After
    fun cleanup() {
        PluginConditionSubscriptions.clear()
    }

    @Test
    fun registerAndSnapshotReturnRegisteredSubscriptions() {
        val sub = PluginConditionSubscription("com.example.plugin", "{\"a\":\"1\"}", 5_000)
        PluginConditionSubscriptions.register(sub)
        assertEquals(listOf(sub), PluginConditionSubscriptions.snapshot())
    }

    @Test
    fun replaceAllClearsAndSetsNewSubscriptions() {
        PluginConditionSubscriptions.register(PluginConditionSubscription("com.old.plugin"))
        val newSubs = listOf(
            PluginConditionSubscription("com.new.a"),
            PluginConditionSubscription("com.new.b"),
        )
        PluginConditionSubscriptions.replaceAll(newSubs)
        assertEquals(newSubs.toSet(), PluginConditionSubscriptions.snapshot().toSet())
    }

    @Test
    fun clearRemovesAllSubscriptions() {
        PluginConditionSubscriptions.register(PluginConditionSubscription("com.example.plugin"))
        PluginConditionSubscriptions.clear()
        assertTrue(PluginConditionSubscriptions.snapshot().isEmpty())
    }

    @Test
    fun duplicateSubscriptionsAreDeduped() {
        val sub = PluginConditionSubscription("com.example.plugin", "{}", 5_000)
        PluginConditionSubscriptions.register(sub)
        PluginConditionSubscriptions.register(sub)
        assertEquals(1, PluginConditionSubscriptions.snapshot().size)
    }
}
