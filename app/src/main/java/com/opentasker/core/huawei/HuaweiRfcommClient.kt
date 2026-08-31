package com.opentasker.core.huawei

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        /** How long to let a blocking RFCOMM connect run before aborting it by closing the socket. */
        private const val CONNECT_TIMEOUT_MS = 20_000L

        /**
         * How long a single write may sit in the kernel before the link is declared gone.
         *
         * A frame is at most a kilobyte and RFCOMM moves it in milliseconds; twenty seconds is not a
         * tuning value, it is "the far end has stopped draining and is not coming back".
         */
        private const val WRITE_TIMEOUT_MS = 20_000L

        /**
         * Where every watchdog in this class runs — deliberately NOT the scope of the call it guards.
         *
         * A watchdog launched as a child of its own caller is cancelled by the very cancellation it
         * exists to clean up after. Cancel a task while it is parked in a blocking `connect()` or
         * `write()` and the child watchdog dies first, leaving nothing able to close the socket:
         * `withContext` cannot return until its children AND its blocking body finish, so the
         * coroutine hangs uninterruptibly with the process-wide sync lock held. Killing the app is
         * then the only way out — and since the band serves one connection, everything else that
         * wants the band is stuck behind it.
         */
        private val watchdogs = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        // Drop anything this client is still holding before asking for a second link. The band
        // serves ONE connection, so a socket stranded by an earlier run does not merely leak — it
        // locks the band out from under its own owner, and every attempt after it fails identically.
        closeQuietly()
        runCatching {
            a.cancelDiscovery()
            val s = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
            // PUBLISHED BEFORE THE CONNECT, deliberately.
            //
            // `connect()` blocks with no timeout of its own and cannot be cancelled either; closing
            // the socket is the documented way to abort it, and is safe here in a way it is not for
            // [read]: a connect that has not completed has no session to lose. Assigning the field
            // only afterwards left a pending connect invisible to [close], so the session watchdog
            // one layer up had nothing to close and its "the socket close is what breaks a blocked
            // call" contract quietly did not hold during connect — the one place it is needed most.
            socket = s
            val watchdog = watchdogs.launch {
                delay(CONNECT_TIMEOUT_MS)
                runCatching { s.close() }
            }
            try { s.connect() } finally { watchdog.cancel() }
            input = s.inputStream
            output = s.outputStream
        }.exceptionOrNull()?.let { e ->
            closeQuietly()
            return@withContext "RFCOMM refused: ${e.message ?: e::class.java.simpleName}"
        }
        null
    }

    /** False once the link is gone, so a pump loop can tell a quiet band from a dead one. */
    override val isOpen: Boolean get() = socket != null

    /**
     * Write, with the socket closed under us if the write cannot get out.
     *
     * The third of this class's blocking calls, and the last to be guarded. `out.write()` blocks
     * with no timeout and is not interruptible: a coroutine parked in one never reaches a suspension
     * point, so a `withTimeout` around it waits for exactly the thing it is meant to interrupt —
     * the same defect the old blocking [read] had. It bites during a watch-face upload, where a
     * megabyte goes out in roughly a thousand frames and a band that stops draining its receive
     * buffer parks the sender indefinitely.
     *
     * One watchdog per frame is not free, but it is a coroutine `launch` and a `cancel` against a
     * kilobyte of Bluetooth: the radio costs orders of magnitude more.
     */
    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        val s = socket
        val out = output ?: throw IllegalStateException("not connected")
        val watchdog = watchdogs.launch {
            delay(WRITE_TIMEOUT_MS)
            runCatching { s?.close() }
        }
        try {
            out.write(data)
            out.flush()
        } finally {
            watchdog.cancel()
        }
    }

    /**
     * Read whatever has arrived. Returns null on timeout rather than throwing, because a quiet
     * band is normal — it only speaks when it has something to say.
     */
    /**
     * Read whatever is waiting, or give up at [timeoutMs].
     *
     * **Polls `available()` rather than blocking in `read()`.** The obvious version —
     * `withTimeoutOrNull { withContext(IO) { ins.read(buf) } }` — does not work, and fails in the
     * worst way: `withTimeoutOrNull` can only cancel at a suspension point, and a blocking JVM read
     * is not one. When the band goes quiet the read never returns, the timeout never fires, and the
     * coroutine hangs FOREVER still holding the process-wide sync mutex. Every later Huawei task
     * then reports "a sync is already running" and only restarting the app clears it. That is
     * exactly what happened on 2026-08-22, and it looked like a band fault rather than ours.
     *
     * `delay` IS a suspension point, so this version cancels properly — and it never closes the
     * socket to escape, which matters because a timeout here is the NORMAL case while serving the
     * band: we poll with short timeouts and usually expect nothing.
     */
    override suspend fun read(timeoutMs: Long): ByteArray? = withContext(Dispatchers.IO) {
        val ins = input ?: return@withContext null
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val ready = runCatching { ins.available() }.getOrDefault(-1)
            if (ready < 0) return@withContext null            // stream closed under us
            if (ready > 0) {
                val buf = ByteArray(4096)
                val n = runCatching { ins.read(buf) }.getOrDefault(-1)
                return@withContext if (n <= 0) null else buf.copyOf(n)
            }
            if (System.currentTimeMillis() >= deadline) return@withContext null
            kotlinx.coroutines.delay(20)
        }
        @Suppress("UNREACHABLE_CODE") null
    }

    /**
     * Hang up. **Must work in a coroutine that has already been cancelled**, which is why the
     * context is [NonCancellable].
     *
     * `withContext` calls `ensureActive()` before it runs anything, so a plain
     * `withContext(Dispatchers.IO) { … }` here throws instead of closing the moment the caller has
     * been cancelled — and every caller wraps this in `runCatching`, as cleanup normally is, so the
     * failure is silent. The socket then stays open for the life of the process. The band serves ONE
     * connection, so it is not a leak that costs a little memory: it is the band becoming
     * unreachable, identically, on every later attempt, until the app is force-stopped. Closing is
     * also what breaks a blocked read or write, so a close that quietly did nothing removed the only
     * escape from a hung transfer at the same time.
     */
    override suspend fun close() = withContext(NonCancellable + Dispatchers.IO) { closeQuietly() }

    private fun closeQuietly() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null
        output = null
        socket = null
    }
}
