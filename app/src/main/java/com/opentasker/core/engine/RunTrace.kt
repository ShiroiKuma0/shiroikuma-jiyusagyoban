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
 * `/sdcard/Android/data/shiroikuma.jiyusagyoban/files/trace/run-trace.log`, readable with adb.
 * One line per event; the file rotates to `.1` at [MAX_BYTES], so at most two files exist.
 */
object RunTrace {
    private const val MAX_BYTES = 1_000_000L

    private val serial = AtomicLong()
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)

    @Volatile
    private var file: File? = null

    /** Idempotent; called on every run's way in, so the trace needs no Application hook. */
    fun attach(context: Context) {
        if (file != null) return
        val dir = context.applicationContext.getExternalFilesDir("trace") ?: return
        file = File(dir, "run-trace.log")
    }

    /** A fresh number for one run, so its queue / start / end lines can be matched up. */
    fun nextRun(): Long = serial.incrementAndGet()

    fun line(text: String) {
        val target = file ?: return
        val entry = "${synchronized(stamp) { stamp.format(Date()) }} [${Thread.currentThread().name}] $text\n"
        synchronized(this) {
            runCatching {
                if (target.length() > MAX_BYTES) target.renameTo(File(target.parentFile, target.name + ".1"))
                target.appendText(entry)
            }
        }
    }
}
