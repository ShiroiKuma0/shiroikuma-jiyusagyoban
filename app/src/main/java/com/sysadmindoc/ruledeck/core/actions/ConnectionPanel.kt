package com.sysadmindoc.ruledeck.core.actions

import android.bluetooth.BluetoothManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.wifi.WifiManager
import android.provider.Settings
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.PathParser
import com.sysadmindoc.ruledeck.core.engine.Action
import com.sysadmindoc.ruledeck.core.engine.ActionCategory
import com.sysadmindoc.ruledeck.core.engine.ActionContext
import com.sysadmindoc.ruledeck.core.engine.ActionResult
import com.sysadmindoc.ruledeck.core.shizuku.ShizukuShell
import com.sysadmindoc.ruledeck.core.telephony.DataSimSwitch
import com.sysadmindoc.ruledeck.i18n.tr
import com.sysadmindoc.ruledeck.ui.theme.ThemeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * `Connection Panel` — the connection switches in one place, for going on and off the mobile network
 * without leaving a trace of Wi-Fi or Bluetooth behind (白い熊, 2026-11-27).
 *
 * Two tiles for Wi-Fi and Bluetooth, one wide tile for airplane mode, two for SIM1 and SIM2, one wide
 * tile for mobile data, a boxed record of what was done, and Close. A yellow frame is on, a blue one
 * off, a muted yellow one cannot be used right now; the icons keep their own colours and dim when off.
 *
 * What a tile does beyond its one switch:
 *  - **Airplane mode on** first switches both SIMs off, then airplane mode on, then brings Bluetooth
 *    and Wi-Fi back when they were on — the phone itself takes Bluetooth down with airplane mode, and
 *    leaving `bluetooth` out of `airplane_mode_radios` is not honoured on the Mate XT.
 *  - The SIM tiles are the SIM management page's switches (Huawei's telephony service, through
 *    Shizuku), and are unavailable in airplane mode. Mobile data is unavailable with no SIM on.
 *
 * The panel stays until Close is tapped: a tap beside it does nothing. Every action flashes its result
 * and writes a line to the record, and the record is still there the next time the panel opens.
 */
class ConnectionPanelAction : Action {
    override val id = "connection.panel"
    override val category = ActionCategory.SETTINGS

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        if (!Settings.canDrawOverlays(ctx.app)) {
            return ActionResult.Failure("the connection panel needs the “display over other apps” permission")
        }
        val d = ConnectionPanel.Colours()
        val colours = ConnectionPanel.Colours(
            wifi = parseColorOr(args["wifi_color"], d.wifi),
            bluetooth = parseColorOr(args["bluetooth_color"], d.bluetooth),
            airplane = parseColorOr(args["airplane_color"], d.airplane),
            sim1 = parseColorOr(args["sim1_color"], d.sim1),
            sim2 = parseColorOr(args["sim2_color"], d.sim2),
            data = parseColorOr(args["data_color"], d.data),
        )
        withContext(Dispatchers.Main) { ConnectionPanel.show(ctx.app, colours) }
        ctx.logger("Connection panel shown")
        return ActionResult.Success
    }
}

internal object ConnectionPanel {
    /** The icons' own colours; the defaults are the ones 白い熊 picked on 2026-11-27. */
    data class Colours(
        val wifi: Int = 0xFF304FFE.toInt(),
        val bluetooth: Int = 0xFF2979FF.toInt(),
        val airplane: Int = 0xFFFFFF00.toInt(),
        val sim1: Int = 0xFF78C8FF.toInt(),
        val sim2: Int = 0xFFC8141E.toInt(),
        val data: Int = 0xFFFF3D3D.toInt(),
    )

    /** What the tiles show. A SIM is null when its switch cannot be read. */
    private data class State(
        val wifi: Boolean = false,
        val bluetooth: Boolean = false,
        val airplane: Boolean = false,
        val sim: List<Boolean?> = listOf(null, null),
        val data: Boolean = false,
    )

    private enum class Kind { WIFI, BLUETOOTH, AIRPLANE, SIM1, SIM2, DATA }

    private class Tile(val frame: LinearLayout, val icon: StrokeIcon, val name: TextView, val sub: TextView?, val status: TextView)

    private const val OFF_FRAME = 0xFF468CFF.toInt()
    private const val MUTED_FRAME = 0xFF8C8C00.toInt()
    private const val DIMMED = 0.42f
    private const val RECORD_LINES = 200
    private const val RECORD_FILE = "connection-record.txt"
    private const val SIM_NAMES = "connection_panel"

    private var root: View? = null
    private var scope: CoroutineScope? = null
    private val tiles = HashMap<Kind, Tile>()
    private var recordView: TextView? = null
    private var recordScroll: ScrollView? = null
    private var state = State()
    private var colours = Colours()
    private val busy = Mutex()
    private val record = ArrayList<String>()
    private var recordLoaded = false

    /** Main thread. A second call while the panel is open only brings the new colours in. */
    fun show(context: Context, colours: Colours) {
        val app = context.applicationContext
        this.colours = colours
        if (root != null) {
            render(app)
            return
        }
        loadRecord(app)
        if (!build(app)) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = s
        s.launch {
            // The state is read again every few seconds: a switch made in the system's own panel, or
            // a SIM that takes its time, must show here without a tap.
            while (isActive) {
                if (!busy.isLocked) {
                    state = readState(app)
                    render(app)
                }
                delay(2_500)
            }
        }
    }

    private fun close(context: Context) {
        scope?.cancel()
        scope = null
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        tiles.clear()
        recordView = null
        recordScroll = null
    }

    // --- reading -------------------------------------------------------------------------------

    private suspend fun readState(context: Context): State = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val wifi = runCatching { (context.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled }
            .getOrElse { Settings.Global.getInt(resolver, "wifi_on", 0) != 0 }
        val bluetooth = runCatching { context.getSystemService(BluetoothManager::class.java)!!.adapter!!.isEnabled }
            .getOrElse { Settings.Global.getInt(resolver, "bluetooth_on", 0) != 0 }
        val airplane = Settings.Global.getInt(resolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
        val telephony = context.getSystemService(TelephonyManager::class.java)
        val sims = (0..1).map { slot ->
            DataSimSwitch.slotEnabled(context, slot)
                // Without Shizuku the switch itself cannot be read; a card that is READY is on.
                ?: runCatching { telephony?.getSimState(slot) }.getOrNull()?.let {
                    when (it) {
                        TelephonyManager.SIM_STATE_READY -> true
                        TelephonyManager.SIM_STATE_ABSENT -> null
                        else -> false
                    }
                }
        }
        @Suppress("MissingPermission")
        val data = runCatching { telephony!!.isDataEnabled }
            .getOrElse { Settings.Global.getInt(resolver, "mobile_data", 0) != 0 }
        rememberSimNames(context)
        State(wifi, bluetooth, airplane, sims, data)
    }

    /** A SIM that is switched off is no longer listed, so its name is kept from when it was on. */
    private fun rememberSimNames(context: Context) {
        val live = DataSimSwitch.slots(context)
        if (live.isEmpty()) return
        val prefs = context.getSharedPreferences(SIM_NAMES, Context.MODE_PRIVATE)
        val edit = prefs.edit()
        live.forEach { if (it.carrier.isNotBlank()) edit.putString("sim${it.slotIndex}", it.carrier) }
        edit.apply()
    }

    private fun simName(context: Context, slot: Int): String =
        context.getSharedPreferences(SIM_NAMES, Context.MODE_PRIVATE).getString("sim$slot", "").orEmpty()

    // --- acting --------------------------------------------------------------------------------

    private fun tapped(context: Context, kind: Kind) {
        val s = scope ?: return
        if (!busy.tryLock()) {
            flash(context, tr("Still working — wait a moment."))
            return
        }
        s.launch {
            try {
                act(context, kind)
            } catch (t: Throwable) {
                note(context, "✗ ${t.message ?: t.javaClass.simpleName}")
            } finally {
                state = readState(context)
                render(context)
                busy.unlock()
            }
        }
    }

    private suspend fun act(context: Context, kind: Kind) {
        state = readState(context)
        if (!ShizukuShell.available()) {
            if (ShizukuShell.isRunning()) ShizukuShell.requestPermission()
            note(context, "✗ " + tr("Shizuku is not running or has not granted access."))
            return
        }
        when (kind) {
            Kind.WIFI -> switch(context, "Wi-Fi", !state.wifi, "svc wifi") { it.wifi }
            Kind.BLUETOOTH -> switch(context, "Bluetooth", !state.bluetooth, "svc bluetooth") { it.bluetooth }
            Kind.DATA -> {
                if (state.sim.none { it == true }) {
                    flash(context, tr("Switch a SIM on first."))
                    return
                }
                switch(context, tr("Mobile data"), !state.data, "svc data") { it.data }
            }
            Kind.SIM1, Kind.SIM2 -> {
                val slot = if (kind == Kind.SIM1) 0 else 1
                if (state.airplane) {
                    flash(context, tr("Switch airplane mode off first."))
                    return
                }
                switchSim(context, slot, state.sim[slot] != true)
            }
            Kind.AIRPLANE -> if (state.airplane) airplaneOff(context) else airplaneOn(context)
        }
    }

    /** One `svc … enable|disable` switch, read back until it shows. */
    private suspend fun switch(context: Context, name: String, on: Boolean, command: String, read: (State) -> Boolean): Boolean {
        shell("$command ${if (on) "enable" else "disable"}")
        val done = await(context, 8_000) { read(it) == on }
        report(context, name, on, done)
        return done
    }

    private suspend fun switchSim(context: Context, slot: Int, on: Boolean): Boolean {
        // The answer of the call is not the result: switching on throws and works all the same.
        DataSimSwitch.setSlotEnabled(context, slot, on)
        val done = await(context, 12_000) { it.sim[slot] == on }
        report(context, "SIM${slot + 1}", on, done)
        return done
    }

    private suspend fun airplaneOn(context: Context) {
        val wifiWasOn = state.wifi
        val bluetoothWasOn = state.bluetooth
        for (slot in 0..1) {
            if (state.sim[slot] == true && !switchSim(context, slot, false)) return
        }
        shell("cmd connectivity airplane-mode enable")
        val done = await(context, 8_000) { it.airplane }
        report(context, tr("Airplane mode"), true, done)
        if (!done) return
        // The phone takes Bluetooth down a moment after airplane mode comes on; asking too early is
        // undone by that, so wait for it to settle and ask again while it stays off.
        delay(1_500)
        restore(context, "Bluetooth", bluetoothWasOn, "svc bluetooth") { it.bluetooth }
        restore(context, "Wi-Fi", wifiWasOn, "svc wifi") { it.wifi }
    }

    private suspend fun airplaneOff(context: Context) {
        val wifiWasOn = state.wifi
        val bluetoothWasOn = state.bluetooth
        shell("cmd connectivity airplane-mode disable")
        val done = await(context, 8_000) { !it.airplane }
        report(context, tr("Airplane mode"), false, done)
        if (!done) return
        delay(1_000)
        restore(context, "Bluetooth", bluetoothWasOn, "svc bluetooth") { it.bluetooth }
        restore(context, "Wi-Fi", wifiWasOn, "svc wifi") { it.wifi }
    }

    /** After airplane mode changed: what was on is on again, and the record says which way it went. */
    private suspend fun restore(context: Context, name: String, wasOn: Boolean, command: String, read: (State) -> Boolean) {
        if (!wasOn) return
        state = readState(context)
        if (read(state)) {
            note(context, tr("%1\$s kept on.", name), flash = false)
            return
        }
        repeat(3) {
            shell("$command enable")
            if (await(context, 6_000) { read(it) }) {
                note(context, "✓ " + tr("%1\$s switched back on.", name))
                return
            }
        }
        note(context, "✗ " + tr("%1\$s could not be switched back on.", name))
    }

    private suspend fun shell(command: String) = withContext(Dispatchers.IO) {
        runCatching { ShizukuShell.exec(command) }
    }

    /** Reads the state every half second until [reached] or [timeoutMs] is over; the tiles follow. */
    private suspend fun await(context: Context, timeoutMs: Long, reached: (State) -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            state = readState(context)
            render(context)
            if (reached(state)) return true
            if (System.currentTimeMillis() >= end) return false
            delay(500)
        }
    }

    private fun report(context: Context, name: String, on: Boolean, done: Boolean) {
        val text = when {
            done && on -> "✓ " + tr("%1\$s switched on.", name)
            done -> "✓ " + tr("%1\$s switched off.", name)
            on -> "✗ " + tr("%1\$s could not be switched on.", name)
            else -> "✗ " + tr("%1\$s could not be switched off.", name)
        }
        note(context, text)
    }

    // --- the record ----------------------------------------------------------------------------

    private fun note(context: Context, text: String, flash: Boolean = true) {
        val line = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + "  " + text
        record += line
        while (record.size > RECORD_LINES) record.removeAt(0)
        showRecord()
        val all = record.joinToString("\n")
        Thread { runCatching { File(context.filesDir, RECORD_FILE).writeText(all) } }.start()
        if (flash) flash(context, text)
    }

    private fun loadRecord(context: Context) {
        if (recordLoaded) return
        recordLoaded = true
        runCatching {
            File(context.filesDir, RECORD_FILE).takeIf { it.isFile }?.readLines()?.takeLast(RECORD_LINES)?.let(record::addAll)
        }
    }

    private fun showRecord() {
        recordView?.text = record.joinToString("\n")
        recordScroll?.post { recordScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun flash(context: Context, text: String) {
        val prefs = ThemeStore.state.value
        FlashOverlay.show(
            context = context,
            text = text,
            backgroundColor = prefs.flashBackground,
            textColor = prefs.flashText,
            borderColor = prefs.flashBorder,
            borderWidthDp = prefs.flashBorderWidthDp,
            cornerRadiusDp = prefs.flashCornerRadiusDp,
            textSizeSp = prefs.flashTextSizeSp,
            fontWeight = prefs.flashFontWeight,
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            xDp = 0,
            yDp = 48,
            longDuration = false,
        )
    }

    // --- drawing -------------------------------------------------------------------------------

    private fun render(context: Context) {
        if (root == null) return
        val accent = ThemeStore.state.value.flashBorder
        val anySim = state.sim.any { it == true }
        fun paint(kind: Kind, on: Boolean, available: Boolean, own: Int, sub: String? = null) {
            val tile = tiles[kind] ?: return
            val frame = when {
                !available -> MUTED_FRAME
                on -> accent
                else -> OFF_FRAME
            }
            val density = context.resources.displayMetrics.density
            tile.frame.background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = 14 * density
                setStroke(((if (on && available) 3f else 1.5f) * density).toInt(), frame)
            }
            tile.icon.color = if (on && available) own else dim(own)
            tile.icon.invalidateSelf()
            tile.name.setTextColor(frame)
            tile.name.typeface = if (on && available) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            tile.sub?.let {
                it.setTextColor(frame)
                it.text = sub.orEmpty()
                it.visibility = if (sub.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
            tile.status.setTextColor(frame)
            tile.status.text = when {
                !available -> tr("Unavailable")
                on -> tr("On")
                else -> tr("Off")
            }
        }
        paint(Kind.WIFI, state.wifi, true, colours.wifi)
        paint(Kind.BLUETOOTH, state.bluetooth, true, colours.bluetooth)
        paint(Kind.AIRPLANE, state.airplane, true, colours.airplane)
        paint(Kind.SIM1, state.sim[0] == true, !state.airplane && state.sim[0] != null, colours.sim1, simName(context, 0))
        paint(Kind.SIM2, state.sim[1] == true, !state.airplane && state.sim[1] != null, colours.sim2, simName(context, 1))
        paint(Kind.DATA, state.data && anySim, anySim, colours.data)
    }

    private fun dim(color: Int): Int = Color.rgb(
        (Color.red(color) * DIMMED).toInt(),
        (Color.green(color) * DIMMED).toInt(),
        (Color.blue(color) * DIMMED).toInt(),
    )

    private fun build(context: Context): Boolean {
        val metrics = context.resources.displayMetrics
        val density = metrics.density
        fun dp(v: Int) = (v * density).toInt()
        val accent = ThemeStore.state.value.flashBorder

        fun tile(kind: Kind, label: String, paths: List<String>, withSub: Boolean): LinearLayout {
            val icon = StrokeIcon(paths)
            val image = ImageView(context).apply {
                setImageDrawable(icon)
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(10) }
            }
            val name = TextView(context).apply {
                text = label
                textSize = 15f
                maxLines = 1
            }
            val sub = if (withSub) TextView(context).apply { textSize = 11f; maxLines = 1 } else null
            val status = TextView(context).apply { textSize = 11f; maxLines = 1 }
            val texts = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(name)
                sub?.let(::addView)
                addView(status)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val frame = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(6), dp(8), dp(6))
                minimumHeight = dp(62)
                addView(image)
                addView(texts)
                setOnClickListener { tapped(context, kind) }
            }
            tiles[kind] = Tile(frame, icon, name, sub, status)
            return frame
        }

        fun row(vararg views: View): LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
            views.forEachIndexed { index, view ->
                addView(view, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index > 0) marginStart = dp(8)
                })
            }
        }

        val title = TextView(context).apply {
            text = tr("Connections")
            setTextColor(accent)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(2), 0, 0, dp(10))
        }
        val recordText = TextView(context).apply {
            setTextColor(accent)
            textSize = 11f
            typeface = Typeface.MONOSPACE
        }
        val scroller = ScrollView(context).apply {
            addView(recordText)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), accent)
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(118)).apply {
                bottomMargin = dp(12)
            }
        }
        // The pill rule: outlined, never filled; the panel's one action, so the frame is thick.
        val closeButton = TextView(context).apply {
            text = tr("Close")
            setTextColor(accent)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(9), dp(28), dp(9))
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dp(100).toFloat()
                setStroke(dp(3), accent)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.CENTER_HORIZONTAL }
            setOnClickListener { close(context) }
        }
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = dp(22).toFloat()
                setStroke(dp(2), accent)
            }
            // A tap on the panel itself, between two tiles, is taken here and does nothing.
            isClickable = true
            addView(title)
            addView(row(tile(Kind.WIFI, "Wi-Fi", ICON_WIFI, false), tile(Kind.BLUETOOTH, "Bluetooth", ICON_BLUETOOTH, false)))
            addView(row(tile(Kind.AIRPLANE, tr("Airplane mode"), ICON_PLANE, false)))
            addView(row(tile(Kind.SIM1, "SIM1", ICON_SIM, true), tile(Kind.SIM2, "SIM2", ICON_SIM, true)))
            addView(row(tile(Kind.DATA, tr("Mobile data"), ICON_DATA, false)))
            addView(scroller)
            addView(closeButton)
        }
        // The whole screen is ours while the panel is open: a tap beside it lands on this dimmed
        // layer and is swallowed, so nothing closes the panel but its own button.
        val width = minOf(metrics.widthPixels - dp(24), dp(400))
        val scrim = FrameLayout(context).apply {
            setBackgroundColor(0x99000000.toInt())
            isClickable = true
            addView(panel, FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return try {
            wm.addView(scrim, params)
            root = scrim
            recordView = recordText
            recordScroll = scroller
            showRecord()
            render(context)
            true
        } catch (_: Exception) {
            tiles.clear()
            false
        }
    }

    /**
     * One of the panel's line icons, drawn from its 24 × 24 outline in whatever colour the tile
     * wants. The outlines are Tabler Icons (MIT licence): wifi, bluetooth, plane, device-sim,
     * mobiledata.
     */
    private class StrokeIcon(pathData: List<String>) : Drawable() {
        var color: Int = Color.WHITE
        private val paths: List<Path> = pathData.map { PathParser.createPathFromPathData(it) }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        override fun draw(canvas: Canvas) {
            val scale = minOf(bounds.width(), bounds.height()) / 24f
            paint.color = color
            canvas.save()
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            canvas.scale(scale, scale)
            paths.forEach { canvas.drawPath(it, paint) }
            canvas.restore()
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private val ICON_WIFI = listOf(
        "M12 18l.01 0",
        "M9.172 15.172a4 4 0 0 1 5.656 0",
        "M6.343 12.343a8 8 0 0 1 11.314 0",
        "M3.515 9.515c4.686 -4.687 12.284 -4.687 17 0",
    )
    private val ICON_BLUETOOTH = listOf("M7 8l10 8l-5 4l0 -16l5 4l-10 8")
    private val ICON_PLANE = listOf(
        "M16 10h4a2 2 0 0 1 0 4h-4l-4 7h-3l2 -7h-4l-2 2h-3l2 -4l-2 -4h3l2 2h4l-2 -7h3l4 7",
    )
    private val ICON_SIM = listOf(
        "M6 3h8.5l4.5 4.5v12.5a1 1 0 0 1 -1 1h-12a1 1 0 0 1 -1 -1v-16a1 1 0 0 1 1 -1",
        "M9 11h3v6",
        "M15 17v.01",
        "M15 14v.01",
        "M15 11v.01",
        "M9 14v.01",
        "M9 17v.01",
    )
    private val ICON_DATA = listOf("M16 12v-8", "M8 20v-8", "M13 7l3 -3l3 3", "M5 17l3 3l3 -3")
}
