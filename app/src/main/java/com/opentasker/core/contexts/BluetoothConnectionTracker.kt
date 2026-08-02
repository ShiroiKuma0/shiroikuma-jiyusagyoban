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
}
