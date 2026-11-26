package com.opentasker.core.contexts

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.SimpleTimeZone
import java.util.TimeZone
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * The times Get Sunrise/Sunset Times stores, kept to the minute so a profile that reads them turns
 * on at the same minute on every phone.
 *
 * The times are NOAA's solar calculation spreadsheet, worked once for local noon of the day, and
 * every time is cut down to its whole minute. That isn't [SunEventCalculator]'s formula, so the two
 * can be a minute apart. Day or night is a separate, older formula (the one Android's night light
 * used), which counts the hours between its own -6 degree dawn and dusk as day.
 */
object SunTimeVariables {
    data class Times(
        val sunrise: Long,
        val sunset: Long,
        val solarNoon: Long,
        val civilDawn: Long,
        val civilDusk: Long,
        val nauticalDawn: Long,
        val nauticalDusk: Long,
        val astronomicalDawn: Long,
        val astronomicalDusk: Long,
        /** One per custom elevation, in the order they were given. */
        val customMorning: List<Long>,
        val customEvening: List<Long>,
        val isDay: Boolean,
        val sunlightMinutes: Int,
    )

    /**
     * The times for the day [epochMs] falls on in [zone], as epoch seconds. [customElevations] are
     * sun elevations in degrees, negative below the horizon, as the step's field takes them.
     */
    fun calculate(
        latitude: Double,
        longitude: Double,
        epochMs: Long,
        zone: TimeZone,
        customElevations: List<Double> = emptyList(),
    ): Times {
        val offsetHours = zone.getOffset(epochMs) / 1000.0 / 60.0 / 60.0
        val sun = SolarPosition.atLocalNoon(spreadsheetDay(epochMs, zone), offsetHours)
        val solarNoon = (720.0 - 4.0 * longitude - sun.equationOfTime + offsetHours * 60.0) / 1440.0

        fun crossing(zenith: Double) = Crossing.of(zenith, solarNoon, latitude, sun.declination)
        val sunriseSunset = crossing(SUNRISE_ZENITH)
        val civil = crossing(CIVIL_ZENITH)
        val nautical = crossing(NAUTICAL_ZENITH)
        val astronomical = crossing(ASTRONOMICAL_ZENITH)
        val custom = customElevations.map { crossing(90.0 - it) }

        fun at(fraction: Double) = clockMinute(fraction, epochMs, offsetHours) / 1000
        return Times(
            sunrise = at(sunriseSunset.morning),
            sunset = at(sunriseSunset.evening),
            solarNoon = at(solarNoon),
            civilDawn = at(civil.morning),
            civilDusk = at(civil.evening),
            nauticalDawn = at(nautical.morning),
            nauticalDusk = at(nautical.evening),
            astronomicalDawn = at(astronomical.morning),
            astronomicalDusk = at(astronomical.evening),
            customMorning = custom.map { at(it.morning) },
            customEvening = custom.map { at(it.evening) },
            isDay = isDay(epochMs, latitude, longitude),
            sunlightMinutes = (sunriseSunset.hourAngle * 8.0).roundToInt(),
        )
    }

    /** The sun's declination in degrees and the equation of time in minutes, from NOAA's spreadsheet. */
    private class SolarPosition(val declination: Double, val equationOfTime: Double) {
        companion object {
            /** For local noon of spreadsheet day [day], in a zone [offsetHours] ahead of UTC. */
            fun atLocalNoon(day: Double, offsetHours: Double): SolarPosition {
                val julianDay = day + 2415018.5 + NOON - offsetHours / 24.0
                val century = (julianDay - 2451545.0) / 36525.0
                val meanLongitude = mod(280.46646 + century * (36000.76983 + century * 3.032E-4), 360.0)
                val meanAnomaly = 357.52911 + century * (35999.05029 - 1.537E-4 * century)
                val eccentricity = 0.016708634 - century * (4.2037E-5 + 1.267E-7 * century)
                val anomaly = Math.toRadians(meanAnomaly)
                val center = sin(anomaly) * (1.914602 - century * (0.004817 + 1.4E-5 * century)) +
                    sin(2.0 * anomaly) * (0.019993 - 1.01E-4 * century) +
                    sin(3.0 * anomaly) * 2.89E-4
                val trueLongitude = meanLongitude + center
                val omega = 125.04 - 1934.136 * century
                val apparentLongitude = trueLongitude - 0.00569 - 0.00478 * sin(Math.toRadians(omega))
                val meanObliquity = 23.0 + (26.0 + (21.448 - century * (46.815 + century * (5.9E-4 - century * 0.001813))) / 60.0) / 60.0
                val obliquity = meanObliquity + 0.00256 * cos(Math.toRadians(omega))
                val declination = Math.toDegrees(asin(sin(Math.toRadians(obliquity)) * sin(Math.toRadians(apparentLongitude))))
                val y = tan(Math.toRadians(obliquity / 2.0)).pow(2.0)
                val longitude = Math.toRadians(meanLongitude)
                val equationOfTime = 4.0 * Math.toDegrees(
                    y * sin(2.0 * longitude) -
                        2.0 * eccentricity * sin(anomaly) +
                        4.0 * eccentricity * y * sin(anomaly) * cos(2.0 * longitude) -
                        0.5 * y * y * sin(4.0 * longitude) -
                        1.25 * eccentricity * eccentricity * sin(2.0 * anomaly),
                )
                return SolarPosition(declination, equationOfTime)
            }
        }
    }

    /** Where the sun crosses a zenith angle, as fractions of the local day. */
    private class Crossing(val hourAngle: Double, val morning: Double, val evening: Double) {
        companion object {
            /** The crossing of [zenith] degrees from straight up, around [noon]. */
            fun of(zenith: Double, noon: Double, latitude: Double, declination: Double): Crossing {
                val latitudeRadians = Math.toRadians(latitude)
                val declinationRadians = Math.toRadians(declination)
                val cosine = cos(Math.toRadians(zenith)) / (cos(latitudeRadians) * cos(declinationRadians)) -
                    tan(latitudeRadians) * tan(declinationRadians)
                // Past the poles' limits the sun never crosses: a whole day around noon, or none.
                val hourAngle = when {
                    cosine < -1.0 -> 180.0
                    cosine > 1.0 -> 0.0
                    else -> Math.toDegrees(acos(cosine))
                }
                val half = hourAngle * 4.0 / 1440.0
                return Crossing(hourAngle, morning = noon - half, evening = noon + half)
            }
        }
    }

    /**
     * The day number NOAA's spreadsheet uses (Excel's, which counts a 29 February 1900 that never
     * was), counted as whole days between two local midnights. While the clock is further ahead
     * than the zone had it in 1900, summer time included, that comes out a day short and the sun is
     * worked out for the day before. The times still land on the right day, a minute or so off the
     * true ones.
     */
    private fun spreadsheetDay(epochMs: Long, zone: TimeZone): Double {
        fun calendar() = GregorianCalendar(zone)
        val midnight = calendar().apply {
            timeInMillis = epochMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        fun dayIn1900(month: Int, day: Int) = calendar().apply {
            set(1900, month, day, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val days = (midnight.timeInMillis - dayIn1900(Calendar.JANUARY, 1).timeInMillis) / DAY_MS
        // Day 1 is 1 January 1900, and every day after the phantom 29 February counts one more.
        val pastLeapDay = midnight.after(dayIn1900(Calendar.FEBRUARY, 28))
        return (days + if (pastLeapDay) 2 else 1).toDouble()
    }

    /** [fraction] of the day [epochMs] falls on, as a clock time cut down to its minute. */
    private fun clockMinute(fraction: Double, epochMs: Long, offsetHours: Double): Long =
        GregorianCalendar(SimpleTimeZone((offsetHours * 3_600_000.0).toInt(), "Solar")).apply {
            timeInMillis = epochMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.MINUTE, (fraction * 1440.0).toInt())
        }.timeInMillis

    /**
     * Whether [epochMs] falls between dawn and dusk by the formula of Android's TwilightCalculator,
     * worked in single precision where that one is. The days since 2000 keep their fraction. A sun
     * that never sets counts as day and one that never rises as night.
     */
    private fun isDay(epochMs: Long, latitude: Double, longitude: Double): Boolean {
        val daysSince2000 = (epochMs - J2000_MS).toFloat() / DAY_MS
        val meanAnomaly = 6.24006f + daysSince2000 * 0.01720197f
        val trueAnomaly = meanAnomaly + C1 * sin(meanAnomaly.toDouble()) +
            C2 * sin((2 * meanAnomaly).toDouble()) +
            C3 * sin((3 * meanAnomaly).toDouble())
        val eclipticLongitude = trueAnomaly + 1.796593063 + Math.PI
        val arcLongitude = -longitude / 360.0
        // The whole day count goes through a Float, as TwilightCalculator keeps it.
        val transitDay = Math.round((daysSince2000 - J0).toDouble() - arcLongitude).toFloat()
        val transit = (transitDay + J0).toDouble() + arcLongitude +
            0.0053 * sin(meanAnomaly.toDouble()) +
            -0.0069 * sin(2.0 * eclipticLongitude)
        val declination = asin(sin(eclipticLongitude) * sin(OBLIQUITY_RADIANS))
        val latitudeRadians = latitude * DEGREES_TO_RADIANS
        val cosine = (sin(TWILIGHT_RADIANS) - sin(latitudeRadians) * sin(declination)) /
            (cos(latitudeRadians) * cos(declination))
        if (cosine >= 1.0) return false
        if (cosine <= -1.0) return true
        val half = (acos(cosine) / (2.0 * Math.PI)).toFloat()
        val dusk = Math.round((transit + half) * DAY_MS) + J2000_MS
        val dawn = Math.round((transit - half) * DAY_MS) + J2000_MS
        return dawn < epochMs && dusk > epochMs
    }

    private fun mod(value: Double, divisor: Double): Double {
        val rest = value % divisor
        return if (rest < 0.0) rest + divisor else rest
    }

    private const val NOON = 0.5
    private const val SUNRISE_ZENITH = 90.833
    private const val CIVIL_ZENITH = 96.0
    private const val NAUTICAL_ZENITH = 102.0
    private const val ASTRONOMICAL_ZENITH = 108.0
    private const val DAY_MS = 86_400_000L
    private const val J2000_MS = 946_728_000_000L

    // TwilightCalculator's constants, in its own Float precision.
    private const val J0 = 9.0E-4f
    private const val C1 = 0.0334196f
    private const val C2 = 3.49066E-4f
    private const val C3 = 5.236E-6f
    private const val DEGREES_TO_RADIANS = 0.017453292f
    private const val OBLIQUITY_RADIANS = 0.4092797040939331
    private const val TWILIGHT_RADIANS = -0.10471975803375244
}
