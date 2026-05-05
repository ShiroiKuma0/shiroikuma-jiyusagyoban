package com.opentasker.core.contexts

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

object SunEventCalculator {
    fun eventMinuteOfDay(
        date: LocalDate,
        latitude: Double,
        longitude: Double,
        event: String,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Int? {
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        val sunrise = event.equals("sunrise", ignoreCase = true)
        val sunset = event.equals("sunset", ignoreCase = true)
        if (!sunrise && !sunset) return null

        val day = date.dayOfYear.toDouble()
        val lngHour = longitude / 15.0
        val approximateTime = day + ((if (sunrise) 6.0 else 18.0) - lngHour) / 24.0
        val meanAnomaly = 0.9856 * approximateTime - 3.289
        val trueLongitude = normalizeDegrees(
            meanAnomaly +
                1.916 * sinDeg(meanAnomaly) +
                0.020 * sinDeg(2.0 * meanAnomaly) +
                282.634,
        )
        var rightAscension = normalizeDegrees(radToDeg(atan(0.91764 * tanDeg(trueLongitude))))
        val longitudeQuadrant = (trueLongitude / 90.0).toInt() * 90.0
        val ascensionQuadrant = (rightAscension / 90.0).toInt() * 90.0
        rightAscension = (rightAscension + longitudeQuadrant - ascensionQuadrant) / 15.0

        val sinDec = 0.39782 * sinDeg(trueLongitude)
        val cosDec = cos(asin(sinDec))
        val cosHour = (cosDeg(OFFICIAL_ZENITH_DEGREES) - sinDec * sinDeg(latitude)) / (cosDec * cosDeg(latitude))
        if (cosHour > 1.0 || cosHour < -1.0) return null

        val hourAngle = if (sunrise) {
            360.0 - radToDeg(acos(cosHour))
        } else {
            radToDeg(acos(cosHour))
        } / 15.0
        val localMeanTime = hourAngle + rightAscension - 0.06571 * approximateTime - 6.622
        val utcHour = normalizeHours(localMeanTime - lngHour)
        val offsetHours = ZonedDateTime.of(date, LocalTime.NOON, zone).offset.totalSeconds / 3600.0
        val localHour = normalizeHours(utcHour + offsetHours)
        return Math.floorMod((localHour * MINUTES_PER_HOUR).roundToInt(), MINUTES_PER_DAY)
    }

    private fun normalizeDegrees(value: Double): Double = ((value % 360.0) + 360.0) % 360.0
    private fun normalizeHours(value: Double): Double = ((value % 24.0) + 24.0) % 24.0
    private fun sinDeg(value: Double): Double = sin(Math.toRadians(value))
    private fun cosDeg(value: Double): Double = cos(Math.toRadians(value))
    private fun tanDeg(value: Double): Double = tan(Math.toRadians(value))
    private fun radToDeg(value: Double): Double = Math.toDegrees(value)

    private const val OFFICIAL_ZENITH_DEGREES = 90.833
    private const val MINUTES_PER_HOUR = 60
    private const val MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR
}
