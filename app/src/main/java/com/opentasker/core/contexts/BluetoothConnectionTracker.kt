package com.opentasker.core.contexts

/**
 * Tracks ACL-connected device identities and reports only the transition to zero devices.
 *
 * Every method is synchronized because the two callers are on different threads: the broadcast
 * receiver delivers connects and disconnects on the main thread, while the engine clears the
 * tracker from `reloadProfiles` on a background dispatcher. An unguarded `HashSet` mutated from
 * both is a data race, and the visible symptom would be a lost or duplicated aggregate event
 * rather than an obvious crash.
 */
internal class BluetoothConnectionTracker {
    private val connectedDevices = mutableSetOf<String>()

    @Synchronized
    fun onConnected(identity: String): Boolean {
        if (identity.isBlank()) return false
        val wasEmpty = connectedDevices.isEmpty()
        connectedDevices += identity
        return wasEmpty
    }

    @Synchronized
    fun onDisconnected(identity: String): Boolean {
        if (identity.isBlank()) return false
        val wasNonEmpty = connectedDevices.isNotEmpty()
        connectedDevices -= identity
        return wasNonEmpty && connectedDevices.isEmpty()
    }

    @Synchronized
    fun connectedCount(): Int = connectedDevices.size

    /**
     * Forgets everything, for when the receiver stops and starts again.
     *
     * While no Bluetooth profile is enabled the receiver is unregistered, so disconnects happen
     * unseen. Keeping the old set meant the first connect after re-enabling reported no
     * "some connected" transition (the set was never empty) and the following disconnect reported
     * no "all disconnected" one, for the life of the process.
     */
    @Synchronized
    fun reset() {
        connectedDevices.clear()
    }
}
