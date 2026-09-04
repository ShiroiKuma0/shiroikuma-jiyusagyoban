package com.opentasker.core.contexts

/** Tracks ACL-connected device identities and reports only the transition to zero devices. */
internal class BluetoothConnectionTracker {
    private val connectedDevices = mutableSetOf<String>()

    fun onConnected(identity: String): Boolean {
        if (identity.isBlank()) return false
        val wasEmpty = connectedDevices.isEmpty()
        connectedDevices += identity
        return wasEmpty
    }

    fun onDisconnected(identity: String): Boolean {
        if (identity.isBlank()) return false
        val wasNonEmpty = connectedDevices.isNotEmpty()
        connectedDevices -= identity
        return wasNonEmpty && connectedDevices.isEmpty()
    }

    fun connectedCount(): Int = connectedDevices.size

    /**
     * Forgets everything, for when the receiver stops and starts again.
     *
     * While no Bluetooth profile is enabled the receiver is unregistered, so disconnects happen
     * unseen. Keeping the old set meant the first connect after re-enabling reported no
     * "some connected" transition (the set was never empty) and the following disconnect reported
     * no "all disconnected" one, for the life of the process.
     */
    fun reset() {
        connectedDevices.clear()
    }
}
