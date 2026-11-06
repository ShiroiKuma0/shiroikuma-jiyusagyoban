package com.opentasker.core.engine

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Fork: a plain-text trace of every task run and every collision decision, so an overlap that the
 * collision rules say cannot happen can be seen after the fact.
 *
 * Written because 携帯's 手元 送信 posted one Messēji message to Jami twice, 0.7 s apart
 * (2026-11-01 19:27), from two runs whose WAIT/ABORT_NEW rules should have serialised them, and
 * nothing recorded which trigger started each run. EMUI drops an app's info and warning lines from
 * logcat (only errors survive), so the trace goes to a file instead:
 * `/sdcard/Android/data/shiroikuma.jiyusagyoban/files/trace/run-trace.txt`, readable with adb.
 * One line per event; the file rotates to `run-trace.1.txt` at [MAX_BYTES], so at most two files exist.
 *
 * Never `.log`: EMUI's Phone Manager runs a nightly clean-up while charging that deletes `*.log`
 * under `Android/data` as junk (it took all of 地図's drive logs on 2026-11-04). The files of the
 * old name are moved over on [attach]. Every line opens the file afresh, so a trace deleted under
 * the app is simply recreated — no stream is held on an unlinked file.
 */
object RunTrace {
    private const val MAX_BYTES = 1_000_000L
    private const val CURRENT = "run-trace.txt"
    private const val ROTATED = "run-trace.1.txt"

    private val serial = AtomicLong()
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)

    @Volatile
    private var file: File? = null

    /** Idempotent; called on every run's way in, so the trace needs no Application hook. */
    fun attach(context: Context) {
        if (file != null) return
        val dir = context.applicationContext.getExternalFilesDir("trace") ?: return
        migrate(File(dir, "run-trace.log.1"), File(dir, ROTATED))
        migrate(File(dir, "run-trace.log"), File(dir, CURRENT))
        file = File(dir, CURRENT)
    }

    /** Rename a file of the old `.log` name; when the new one exists already, the old text goes first. */
    private fun migrate(old: File, new: File) {
        if (!old.isFile) return
        runCatching {
            if (!new.exists()) {
                if (old.renameTo(new)) return
            }
            new.writeBytes(old.readBytes() + (if (new.exists()) new.readBytes() else ByteArray(0)))
            old.delete()
        }
    }

    /** A fresh number for one run, so its queue / start / end lines can be matched up. */
    fun nextRun(): Long = serial.incrementAndGet()

    fun line(text: String) {
        val target = file ?: return
        val entry = "${synchronized(stamp) { stamp.format(Date()) }} [${Thread.currentThread().name}] $text\n"
        synchronized(this) {
            runCatching {
                if (target.length() > MAX_BYTES) target.renameTo(File(target.parentFile, ROTATED))
                target.appendText(entry)
            }
        }
    }
}
