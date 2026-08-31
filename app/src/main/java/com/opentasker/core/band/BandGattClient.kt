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
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
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

sealed interface BandScanOutcome {
    /** The window completed. An empty list is a real answer, not an error. */
    data class Heard(val devices: List<BandScanDevice>) : BandScanOutcome
    data class Failed(val reason: String) : BandScanOutcome
}

/**
 * The listening half of the radio.
 *
 * It lives in this file for one reason: `BandSafetyGuardTest.exactlyOneFileTouchesBluetooth` allows
 * `android.bluetooth` in `BandGattClient.kt` and nowhere else in `core/band`. Putting the scanner in
 * its own file would have meant either weakening that test or moving the radio outside the package
 * that owns it — so the scan sits beside the connect, and everything it *concludes* lives in
 * `BandScanReport`, which a JVM test can reach.
 *
 * Same battery discipline as the rest of this file: a fixed window, `stopScan` in a `finally`, and
 * no callback left registered on any path. `BandSyncEngine` never calls this — a sync is addressed
 * by MAC and needs no scan at all, which is why the app asked for BLUETOOTH_SCAN only now.
 */
class BandScanner(private val context: Context) {

    /**
     * @param onTick called about four times a second with the elapsed time and everything heard so
     *   far — the devices themselves, not just a count, so a caller can show each one the moment it
     *   arrives instead of a number that goes up. Returning false stops the scan early, which is how
     *   a 中止 button reaches a scan already in flight.
     */
    @SuppressLint("MissingPermission")
    suspend fun scan(
        seconds: Int,
        onTick: (elapsedMs: Long, heard: List<BandScanDevice>) -> Boolean = { _, _ -> true },
    ): BandScanOutcome {
        // Checked rather than assumed, so the failure names the fix. BLUETOOTH_SCAN is declared
        // neverForLocation, so on S+ no location permission is involved; below S the platform
        // derives scanning from the location permission instead, hence the two branches.
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_SCAN
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (ContextCompat.checkSelfPermission(context, needed) != PackageManager.PERMISSION_GRANTED) {
            return BandScanOutcome.Failed("Nearby-devices permission not granted — grant it in Setup")
        }

        val manager = ContextCompat.getSystemService(context, BluetoothManager::class.java)
            ?: return BandScanOutcome.Failed("Bluetooth unavailable on this device")
        val adapter: BluetoothAdapter = manager.adapter
            ?: return BandScanOutcome.Failed("Bluetooth unavailable on this device")
        if (!adapter.isEnabled) return BandScanOutcome.Failed("Bluetooth is switched off")
        val scanner = adapter.bluetoothLeScanner
            ?: return BandScanOutcome.Failed("the Bluetooth scanner is unavailable right now")

        // Merged by address as sightings arrive: one device advertises many times in a window, and
        // the fields differ between packets — the name often appears only in the scan response.
        val seen = LinkedHashMap<String, BandScanDevice>()
        val failure = CompletableDeferred<String>()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                val found = result ?: return
                val address = runCatching { found.device?.address }.getOrNull() ?: return
                seen[address] = merge(seen[address], found, address)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                results?.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                if (!failure.isCompleted) failure.complete(describeScanFailure(errorCode))
            }
        }

        val settings = ScanSettings.Builder()
            // The band is a plain BLE peripheral, so legacy advertising is what it uses and the
            // default (legacy-only) is the right net. Low latency because the window is seconds
            // long and ends by itself — this is not a background scan.
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)
            .build()

        return try {
            // No filters: the whole point is to find a band whose address is not yet known, and a
            // ScanFilter on fff0 would hide any band that keeps its service UUID out of the
            // advertisement — which is common, and would look exactly like "no band here".
            scanner.startScan(emptyList(), settings, callback)
            // Sliced rather than one long await: the window is the only part of this that takes real
            // time, and a caller with nothing to show for eight seconds is indistinguishable from a
            // wedged one. Each slice also re-asks whether to carry on, which is what makes a running
            // scan cancellable at all.
            val windowMs = seconds.coerceIn(MIN_SCAN_SEC, MAX_SCAN_SEC) * 1000L
            var elapsed = 0L
            var failed: String? = null
            while (elapsed < windowMs) {
                val slice = minOf(TICK_MS, windowMs - elapsed)
                failed = withTimeoutOrNull(slice) { failure.await() }
                if (failed != null) break
                elapsed += slice
                if (!onTick(elapsed, seen.values.toList())) break
            }
            if (failed != null) BandScanOutcome.Failed(failed) else BandScanOutcome.Heard(seen.values.toList())
        } catch (t: Throwable) {
            BandScanOutcome.Failed(t.message ?: "the scan could not be started")
        } finally {
            runCatching { scanner.stopScan(callback) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun merge(previous: BandScanDevice?, result: ScanResult, address: String): BandScanDevice {
        val record = result.scanRecord
        // scanRecord.deviceName, never device.name: the latter reads the bonded-name cache and needs
        // BLUETOOTH_CONNECT, which a scan is not required to hold.
        val name = record?.deviceName?.trim().orEmpty().ifBlank { previous?.name.orEmpty() }
        val services = record?.serviceUuids.orEmpty()
            .map { BandScanReport.shortUuid(it.uuid.toString()) }
            .let { fresh -> (previous?.serviceUuids.orEmpty() + fresh).distinct() }

        val manufacturer = record?.manufacturerSpecificData
        val ids = buildList {
            addAll(previous?.manufacturerIds.orEmpty())
            if (manufacturer != null) for (index in 0 until manufacturer.size()) add(manufacturer.keyAt(index))
        }.distinct()
        val hex = manufacturer?.takeIf { it.size() > 0 }?.valueAt(0)
            ?.let { BandScanReport.hex(it) }
            ?: previous?.manufacturerHex.orEmpty()

        val txPower = record?.txPowerLevel
            ?.takeIf { it != NO_TX_POWER }
            ?: previous?.txPower
        val connectable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            result.isConnectable || (previous?.connectable == true)
        } else {
            previous?.connectable
        }

        return BandScanDevice(
            address = address,
            name = name,
            // Strongest, not latest: a single weak packet from a device otherwise heard loudly would
            // otherwise push it down the list and out of the probe order.
            rssi = maxOf(result.rssi, previous?.rssi ?: Int.MIN_VALUE),
            serviceUuids = services,
            manufacturerIds = ids,
            manufacturerHex = hex,
            txPower = txPower,
            connectable = connectable,
            sightings = (previous?.sightings ?: 0) + 1,
        )
    }

    private fun describeScanFailure(errorCode: Int): String = when (errorCode) {
        ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "a scan is already running"
        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "the system refused to register the scan"
        ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "the Bluetooth stack reported an internal error"
        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "this device does not support BLE scanning"
        SCAN_FAILED_TOO_FREQUENT -> "Android is rate-limiting scans — wait about 30 seconds and try again"
        else -> "the scan failed (code $errorCode)"
    }

    companion object {
        const val MIN_SCAN_SEC = 1
        const val MAX_SCAN_SEC = 60
        const val DEFAULT_SCAN_SEC = 8

        /** How often the window is interrupted to report progress and re-ask about cancellation. */
        const val TICK_MS = 250L

        /** `ScanRecord.TX_POWER_NOT_PRESENT`, which is `@hide` on some API levels. */
        private const val NO_TX_POWER = Int.MIN_VALUE

        /**
         * `SCAN_FAILED_SCANNING_TOO_FREQUENTLY` (6). Android allows five scans per 30 seconds per
         * app and then silently refuses; naming it turns a mystifying empty result into a wait.
         */
        private const val SCAN_FAILED_TOO_FREQUENT = 6
    }
}
