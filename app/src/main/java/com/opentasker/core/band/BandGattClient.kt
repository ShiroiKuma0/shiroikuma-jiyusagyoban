package com.opentasker.core.band

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * ★ THE ONLY FILE IN core/band THAT IMPORTS android.bluetooth ★
 *
 * A dumb pipe with no protocol logic in it. Everything that decides anything — frame building,
 * paging, parsing, the census — is pure Kotlin elsewhere and unit-tested, because this repo has no
 * Robolectric and no MockK and so nothing that touches `android.*` can be tested at all.
 * BandSafetyGuardTest fails the build if a second file here starts importing bluetooth.
 *
 * **Connect, drain, disconnect. Never hold the link.** A standing Bluetooth session on this phone
 * once drained 1322 mAh in a day; a sync is seconds long and the link closes immediately after, on
 * every path.
 */
class BandGattClient(private val context: Context) {

    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    /** One outstanding GATT operation at a time. Android's stack does not tolerate overlap. */
    private val opLock = Mutex()

    private val connected = CompletableDeferred<Boolean>()
    private val servicesFound = CompletableDeferred<Boolean>()
    private val mtuSettled = CompletableDeferred<Int>()
    private val descriptorWritten = CompletableDeferred<Boolean>()

    /** Notification frames, in arrival order. UNLIMITED so a fast stream cannot deadlock the callback. */
    private val frames = Channel<ByteArray>(Channel.UNLIMITED)

    var grantedMtu: Int = DEFAULT_ATT_MTU
        private set

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (!connected.isCompleted) connected.complete(status == BluetoothGatt.GATT_SUCCESS)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (!connected.isCompleted) connected.complete(false)
                frames.close()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (!servicesFound.isCompleted) servicesFound.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            grantedMtu = mtu
            if (!mtuSettled.isCompleted) mtuSettled.complete(mtu)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (!descriptorWritten.isCompleted) descriptorWritten.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        // Both overloads: API 33+ delivers ONLY the ByteArray variant, older devices only the other.
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            frames.trySend(value.copyOf())
        }

        @Suppress("DEPRECATION")
        @Deprecated("Pre-33 delivery path; still required at minSdk 26")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return // avoid double delivery
            c.value?.let { frames.trySend(it.copyOf()) }
        }
    }

    /** Frames as they arrive. The caller applies its own idle timeout per frame. */
    suspend fun nextFrame(timeoutMs: Long): ByteArray? =
        withTimeoutOrNull(timeoutMs) { runCatching { frames.receive() }.getOrNull() }

    /**
     * Connect and get ready to read, or return why not.
     *
     * No scan: the band is unbonded and addressed by MAC, so BLUETOOTH_SCAN is not needed by this
     * path at all — and `createBond()` is never called, because the band takes no pairing.
     */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION") // the TRANSPORT_LE overload; the newer one adds a PHY we do not want to pin
    suspend fun open(address: String): BandConnectResult {
        // The permission check is needed anyway to give a good failure message, which is also what
        // silences MissingPermission without a lint baseline — the LocationContextSourceImpl pattern.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return BandConnectResult.Failed("Bluetooth permission not granted — grant it in Setup")
        }
        val manager = ContextCompat.getSystemService(context, BluetoothManager::class.java)
            ?: return BandConnectResult.Failed("Bluetooth unavailable on this device")
        val adapter: BluetoothAdapter = manager.adapter
            ?: return BandConnectResult.Failed("Bluetooth unavailable on this device")
        if (!adapter.isEnabled) return BandConnectResult.Failed("Bluetooth is switched off")

        val device: BluetoothDevice = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
            ?: return BandConnectResult.Failed("not a usable address: $address")

        // autoConnect = false is a BATTERY decision, not a latency one: true parks a background
        // connection request indefinitely, which is precisely the standing session being avoided.
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: return BandConnectResult.Failed("could not open a GATT connection")

        val up = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { connected.await() } ?: false
        if (!up) return BandConnectResult.Failed("the band did not answer within ${CONNECT_TIMEOUT_MS / 1000}s")

        // Mandatory: the default ATT MTU of 23 gives a 20-byte payload, and sleep frames are 130.
        // Without this you receive truncated garbage that still parses into plausible numbers.
        gatt?.requestMtu(REQUESTED_MTU)
        withTimeoutOrNull(MTU_TIMEOUT_MS) { mtuSettled.await() }

        gatt?.discoverServices()
        val discovered = withTimeoutOrNull(DISCOVER_TIMEOUT_MS) { servicesFound.await() } ?: false
        if (!discovered) return BandConnectResult.Failed("the band's services did not appear")

        val service = gatt?.getService(SERVICE_UUID)
            ?: return BandConnectResult.Failed("service fff0 missing — is this the right device?")
        writeCharacteristic = service.getCharacteristic(WRITE_UUID)
            ?: return BandConnectResult.Failed("characteristic fff6 missing")
        val notify = service.getCharacteristic(NOTIFY_UUID)
            ?: return BandConnectResult.Failed("characteristic fff7 missing")

        gatt?.setCharacteristicNotification(notify, true)
        val cccd = notify.getDescriptor(CCCD_UUID)
            ?: return BandConnectResult.Failed("the notify descriptor is missing")
        writeCccd(cccd)
        withTimeoutOrNull(CCCD_TIMEOUT_MS) { descriptorWritten.await() }

        return BandConnectResult.Ready(grantedMtu)
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun writeCccd(cccd: BluetoothGattDescriptor) {
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt?.writeDescriptor(cccd, value)
        } else {
            cccd.value = value
            gatt?.writeDescriptor(cccd)
        }
    }

    /**
     * The single write chokepoint.
     *
     * There is deliberately no send(ByteArray) and no public writeCharacteristic wrapper, so a caller
     * has nothing to hand this but a value built from the two-member BandReadMode.
     */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    suspend fun send(command: BandCommand): Boolean = opLock.withLock {
        val frame = BandProtocol.encode(command)
        require(frame.size == BandProtocol.FRAME_SIZE) { "a command frame is 16 bytes" }
        require((frame[1].toInt() and 0xFF) in BandProtocol.ALLOWED_MODE_BYTES) {
            "refusing mode 0x%02X".format(frame[1])
        }
        require(frame[15] == BandProtocol.checksum(frame)) { "checksum mismatch" }

        val characteristic = writeCharacteristic ?: return@withLock false
        val g = gatt ?: return@withLock false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(
                characteristic,
                frame,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            ) == BluetoothGatt.GATT_SUCCESS
        } else {
            characteristic.value = frame
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            g.writeCharacteristic(characteristic)
        }
    }

    /**
     * Unconditionally, on every path. A leaked BluetoothGatt is the classic cause of status 133 on
     * the next connect, and this app connects several times a day.
     */
    @SuppressLint("MissingPermission")
    fun close() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        writeCharacteristic = null
        frames.close()
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        val WRITE_UUID: UUID = UUID.fromString("0000fff6-0000-1000-8000-00805f9b34fb")
        val NOTIFY_UUID: UUID = UUID.fromString("0000fff7-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val REQUESTED_MTU = 247
        const val DEFAULT_ATT_MTU = 23

        /** Below this a 130-byte sleep frame cannot arrive intact, so that stream is skipped. */
        const val MIN_USABLE_PAYLOAD = 130

        const val CONNECT_TIMEOUT_MS = 15_000L
        const val DISCOVER_TIMEOUT_MS = 10_000L
        const val MTU_TIMEOUT_MS = 5_000L
        const val CCCD_TIMEOUT_MS = 5_000L
        const val FRAME_IDLE_TIMEOUT_MS = 6_000L
    }
}

sealed interface BandConnectResult {
    data class Ready(val mtu: Int) : BandConnectResult
    data class Failed(val reason: String) : BandConnectResult
}
