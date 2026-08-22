package com.opentasker.core.huawei

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * ★ THE ONLY FILE IN core/huawei THAT IMPORTS android.bluetooth ★
 *
 * A dumb pipe with no protocol logic in it, mirroring the rule already followed in `core/band`:
 * everything that decides anything lives in pure Kotlin elsewhere and is unit-tested, because this
 * repo has no Robolectric and no MockK, so nothing touching `android.*` can be tested at all.
 * `HuaweiSafetyGuardTest` fails the build if a second file here starts importing bluetooth.
 *
 * **Connect, sync, disconnect. Never hold the link.** A standing Bluetooth session on 白い熊's phone
 * once drained 1322 mAh in a day. The band is also single-connection: while we hold it, nothing else
 * can.
 *
 * Transport is **Bluetooth Classic RFCOMM**, not BLE — the band exposes the Huawei protocol on an
 * SDP record it calls "Private COM" ([SERVICE_UUID], channel 16 on this unit). Gadgetbridge
 * classifies this model as an LE device; on real hardware that is wrong.
 *
 * The band must be **bonded** first. An unbonded band refuses the RFCOMM connection outright, and
 * — separately — will never leave its out-of-box wizard without a real pairing.
 */
class HuaweiRfcommClient(private val context: Context) : HuaweiTransport {

    companion object {
        /** SDP "Private COM" — the Huawei protocol endpoint. */
        val SERVICE_UUID: UUID = UUID.fromString("82ff3820-8411-400c-b85a-55bdb32cf060")

        /** A second "Private COM" record exists on channel 17; we do not use it. */
        val ALT_SERVICE_UUID: UUID = UUID.fromString("65847aa1-102a-05e1-43b6-e2a3965be547")
    }

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /** Bonded devices whose name looks like a Huawei band, for the picker. */
    @SuppressLint("MissingPermission")
    fun bondedBands(): List<Pair<String, String>> =
        adapter?.bondedDevices.orEmpty()
            .filter { it.name?.contains("HUAWEI Band", ignoreCase = true) == true }
            .map { it.address to (it.name ?: it.address) }

    @SuppressLint("MissingPermission")
    fun isBonded(address: String): Boolean =
        adapter?.bondedDevices.orEmpty().any { it.address.equals(address, ignoreCase = true) }

    /**
     * Pair with the band, initiating from the phone, and wait for the bond to complete.
     *
     * The band must be bonded before it will serve the RFCOMM channel at all, and — separately — a
     * band that has been unpaired from its previous companion is waiting to be reached out to. It
     * does not advertise itself into a pairing; something has to start it.
     *
     * Two confirmations are needed and NEITHER can be suppressed:
     *  * one on the band, which is a plain yes/no — it never shows a six-digit code;
     *  * one on the phone, which Android raises itself.
     *
     * `setPairingConfirmation` would answer the phone's dialog for us, but it is guarded by
     * BLUETOOTH_PRIVILEGED, which only a system app holds. Pretending otherwise would mean a flow
     * that silently waits forever, so the timeout here is generous and the message says what to press.
     *
     * The moment the bond lands, provisioning must follow with no gap: the band gives its new
     * companion only seconds before abandoning its own flow. That is why this returns rather than
     * handing control back to a UI step.
     *
     * @return null once bonded, or a human-readable reason.
     */
    @SuppressLint("MissingPermission")
    suspend fun ensureBonded(
        address: String,
        timeoutMs: Long = 120_000,
        onState: (String) -> Unit = {},
    ): String? = withContext(Dispatchers.IO) {
        val a = adapter ?: return@withContext "no Bluetooth adapter"
        if (!a.isEnabled) return@withContext "Bluetooth is off"
        val device: BluetoothDevice = runCatching { a.getRemoteDevice(address) }.getOrNull()
            ?: return@withContext "bad address $address"
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            onState("already paired")
            return@withContext null
        }

        a.cancelDiscovery()
        onState("requesting")
        if (!device.createBond()) return@withContext "the phone would not start pairing"

        // POLLED, deliberately, rather than waiting on ACTION_BOND_STATE_CHANGED.
        //
        // That broadcast is sent by the system UID, so a receiver registered NOT_EXPORTED never
        // sees it on this phone — and the failure is silent and total: the bond completes, both
        // confirmations are accepted, and we sit waiting for an event that will never arrive while
        // the band gives up on us and returns to its out-of-box wizard. It cost two pairing runs.
        //
        // bondState is the same fact without the delivery policy in the way. Polling it is not a
        // workaround for a missing event; it is reading the state directly instead of subscribing
        // to an announcement about it.
        val deadline = System.currentTimeMillis() + timeoutMs
        var announced = false
        while (System.currentTimeMillis() < deadline) {
            when (device.bondState) {
                BluetoothDevice.BOND_BONDED -> {
                    onState("paired")
                    return@withContext null
                }
                BluetoothDevice.BOND_BONDING -> if (!announced) {
                    announced = true
                    onState("pairing")
                }
                // BOND_NONE after we asked, and after it had begun, means it was refused.
                BluetoothDevice.BOND_NONE -> if (announced) {
                    return@withContext "pairing was refused"
                }
            }
            delay(250)
        }
        "no answer — accept the request on the band, then on the phone"
    }

    /**
     * Open the RFCOMM channel.
     *
     * @return null on success, or a human-readable reason. A refusal here almost always means the
     *   band is not bonded, or is asleep — it stops serving the channel when it sleeps.
     */
    @SuppressLint("MissingPermission")
    suspend fun open(address: String): String? = withContext(Dispatchers.IO) {
        val a = adapter ?: return@withContext "no Bluetooth adapter"
        if (!a.isEnabled) return@withContext "Bluetooth is off"
        val device: BluetoothDevice = runCatching { a.getRemoteDevice(address) }.getOrNull()
            ?: return@withContext "bad address $address"
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            return@withContext "band is not paired — pair it first, accepting the request on the band"
        }
        runCatching {
            a.cancelDiscovery()
            val s = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
            s.connect()
            socket = s
            input = s.inputStream
            output = s.outputStream
        }.exceptionOrNull()?.let { e ->
            closeQuietly()
            return@withContext "RFCOMM refused: ${e.message ?: e::class.java.simpleName}"
        }
        null
    }

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        val out = output ?: throw IllegalStateException("not connected")
        out.write(data)
        out.flush()
    }

    /**
     * Read whatever has arrived. Returns null on timeout rather than throwing, because a quiet
     * band is normal — it only speaks when it has something to say.
     */
    override suspend fun read(timeoutMs: Long): ByteArray? = withTimeoutOrNull(timeoutMs) {
        withContext(Dispatchers.IO) {
            val ins = input ?: return@withContext null
            val buf = ByteArray(4096)
            val n = runCatching { ins.read(buf) }.getOrDefault(-1)
            if (n <= 0) null else buf.copyOf(n)
        }
    }

    override suspend fun close() = withContext(Dispatchers.IO) { closeQuietly() }

    private fun closeQuietly() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null
        output = null
        socket = null
    }
}
