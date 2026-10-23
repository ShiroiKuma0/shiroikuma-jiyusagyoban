package com.opentasker.ui.charts.huawei

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The cuff readings written out as text, for handing to a doctor or keeping beside other records.
 *
 * Three layouts, one reading per line:
 *
 *  * [Format.PLAIN] — `2026-10-21 10:35 - 128 / 82, 73`, 白い熊's own layout and the default.
 *  * [Format.CLINICAL] — `2026-10-21 10:35  BP 128/82 mmHg, pulse 73/min`: the way a clinic
 *    writes it, every number labelled with what it is and its unit, so nobody has to ask.
 *  * [Format.CSV] — a header and comma-separated columns, for a spreadsheet.
 *
 * A reading with no pulse leaves the pulse out rather than writing an empty one.
 */
object HuaweiBpExport {
    enum class Format(val extension: String) { PLAIN("txt"), CLINICAL("txt"), CSV("csv") }

    private val WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val TIME = DateTimeFormatter.ofPattern("HH:mm")
    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")

    fun render(
        readings: List<HuaweiBpReading>,
        format: Format,
        newestFirst: Boolean = true,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val ordered = if (newestFirst) readings.sortedByDescending { it.tMs } else readings.sortedBy { it.tMs }
        val lines = ordered.map { r ->
            val at = Instant.ofEpochMilli(r.tMs).atZone(zone)
            val sys = r.systolic.toInt()
            val dia = r.diastolic.toInt()
            val pulse = r.pulse?.toInt()
            when (format) {
                Format.PLAIN -> "${WHEN.format(at)} - $sys / $dia" + (pulse?.let { ", $it" } ?: "")
                Format.CLINICAL -> "${WHEN.format(at)}  BP $sys/$dia mmHg" + (pulse?.let { ", pulse $it/min" } ?: "")
                Format.CSV -> "${DATE.format(at)},${TIME.format(at)},$sys,$dia,${pulse ?: ""}"
            }
        }
        val header = if (format == Format.CSV) listOf("date,time,systolic_mmHg,diastolic_mmHg,pulse_per_min") else emptyList()
        return (header + lines).joinToString("\n", postfix = "\n")
    }

    /** Writes the file into [dir] under a stamped name and returns it, or null if it could not. */
    fun write(
        readings: List<HuaweiBpReading>,
        format: Format,
        newestFirst: Boolean,
        dir: File,
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): File? = runCatching {
        dir.mkdirs()
        val name = "blood-pressure_${STAMP.format(Instant.ofEpochMilli(nowMs).atZone(zone))}.${format.extension}"
        File(dir, name).apply { writeText(render(readings, format, newestFirst, zone)) }
    }.getOrNull()
}
