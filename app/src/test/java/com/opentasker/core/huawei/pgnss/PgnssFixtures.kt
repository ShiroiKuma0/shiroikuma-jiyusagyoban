package com.opentasker.core.huawei.pgnss

/**
 * The fixtures under `app/src/test/resources/pgnss/`, and the only thing this package grades itself
 * against.
 *
 * Every expected number and every expected byte in them was produced by `scripts/pgnss-build.py`
 * itself — the reference implementation — on the committed `mini.sp3`, which is a real slice of
 * CODE's five-day prediction with nothing altered. Doubles are carried as the sixteen hex digits of
 * their IEEE-754 bits rather than as decimal text, because the encoders quantise: one count of Ω0 is
 * 1.5e-9 rad, so a value that survived a decimal round trip could differ from the reference's by a
 * count and the byte comparison would be measuring the fixture, not the port.
 */
object PgnssFixtures {

    fun text(name: String): String =
        PgnssFixtures::class.java.classLoader!!.getResourceAsStream("pgnss/$name")
            ?.bufferedReader()?.readText()
            ?: error("missing fixture pgnss/$name")

    fun bytes(name: String): ByteArray =
        PgnssFixtures::class.java.classLoader!!.getResourceAsStream("pgnss/$name")
            ?.readBytes()
            ?: error("missing fixture pgnss/$name")

    /** Rows of whitespace-separated fields, comments and blank lines dropped. */
    fun rows(name: String): List<List<String>> =
        text(name).lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.trim().split(Regex("\\s+")) }
            .toList()

    /** A double, bit-exactly as the reference had it. */
    fun d(hex: String): Double = java.lang.Double.longBitsToDouble(java.lang.Long.parseUnsignedLong(hex, 16))

    fun elements(fields: List<String>, from: Int, toe: Double = 0.0): Orbit.Elements {
        val el = Orbit.Elements(DoubleArray(15) { d(fields[from + it]) })
        el.toe = toe
        return el
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun unhex(s: String): ByteArray =
        ByteArray(s.length / 2) { ((Character.digit(s[2 * it], 16) shl 4) or
            Character.digit(s[2 * it + 1], 16)).toByte() }

    val sp3: Map<String, Sp3.Arc> by lazy { Sp3.parse(text("mini.sp3")) }
}
