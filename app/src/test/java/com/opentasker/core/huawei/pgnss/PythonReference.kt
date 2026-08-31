package com.opentasker.core.huawei.pgnss

import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * The numbers `scripts/pgnss_orbit.py` produces, frozen into a fixture so the Kotlin port can be
 * graded against **the implementation that was measured against the outside world** rather than
 * against itself.
 *
 * That distinction is the whole point. The Python was checked against JPL Horizons for the Sun and
 * the Moon, against closed forms for the gravity field and the rotating frame, and against orbits
 * observed afterwards for the end-to-end prediction. A Kotlin port that agrees with it to machine
 * precision inherits all of that; a Kotlin port graded against its own integrator inherits nothing,
 * which is exactly how four catastrophic bugs survived in the generator this replaces.
 *
 * Regenerate with `.scratch/pgnss-kt/mkfixture.py` if the Python's model ever changes; a diff in this
 * file is then the loudest possible statement that it did.
 */
class PythonReference(
    val states: List<DoubleArray>,
    val srp: DoubleArray,
    val sun: List<Pair<Double, DoubleArray>>,
    val moon: List<Pair<Double, DoubleArray>>,
    val pole: List<Pair<Double, DoubleArray>>,
    val omega: List<Pair<Double, Double>>,
    val accel: Map<Pair<Double, Int>, DoubleArray>,
    val trajEpoch: Double,
    val trajState: DoubleArray,
    val traj: List<Pair<Double, DoubleArray>>,
) {
    companion object {

        /** GPS seconds of a calendar instant, with **no** leap-second offset — the SP3 convention. */
        fun gpsSeconds(y: Int, mo: Int, d: Int, h: Int, mi: Int): Double {
            val days = java.time.LocalDate.of(y, mo, d).toEpochDay()
            return ((days * 86400L + h * 3600L + mi * 60L) - 315964800L).toDouble()
        }

        fun load(): PythonReference {
            val states = ArrayList<DoubleArray>()
            var srp = DoubleArray(0)
            val sun = ArrayList<Pair<Double, DoubleArray>>()
            val moon = ArrayList<Pair<Double, DoubleArray>>()
            val pole = ArrayList<Pair<Double, DoubleArray>>()
            val omega = ArrayList<Pair<Double, Double>>()
            val accel = HashMap<Pair<Double, Int>, DoubleArray>()
            var trajEpoch = 0.0
            var trajState = DoubleArray(6)
            val traj = ArrayList<Pair<Double, DoubleArray>>()
            val stream = checkNotNull(PythonReference::class.java.getResourceAsStream("/pgnss/python-reference.txt")) {
                "missing fixture /pgnss/python-reference.txt"
            }
            BufferedReader(InputStreamReader(stream)).use { r ->
                var line = r.readLine()
                while (line != null) {
                    val f = line.trim().split(Regex("\\s+"))
                    when (f.getOrNull(0)) {
                        "state" -> states.add(DoubleArray(6) { f[2 + it].toDouble() })
                        "srp" -> srp = DoubleArray(f.size - 1) { f[1 + it].toDouble() }
                        "sun" -> sun.add(f[1].toDouble() to DoubleArray(3) { f[2 + it].toDouble() })
                        "moon" -> moon.add(f[1].toDouble() to DoubleArray(3) { f[2 + it].toDouble() })
                        "pole" -> pole.add(f[1].toDouble() to DoubleArray(2) { f[2 + it].toDouble() })
                        "omega" -> omega.add(f[1].toDouble() to f[2].toDouble())
                        "accel" -> accel[f[1].toDouble() to f[2].toInt()] = DoubleArray(3) { f[3 + it].toDouble() }
                        "trajepoch" -> trajEpoch = f[1].toDouble()
                        "trajstate" -> trajState = DoubleArray(6) { f[1 + it].toDouble() }
                        "traj" -> traj.add(f[1].toDouble() to DoubleArray(3) { f[2 + it].toDouble() })
                    }
                    line = r.readLine()
                }
            }
            return PythonReference(states, srp, sun, moon, pole, omega, accel, trajEpoch, trajState, traj)
        }
    }
}
