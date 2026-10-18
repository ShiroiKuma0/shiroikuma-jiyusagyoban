package com.opentasker.core.huawei

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

/**
 * The Watch D3's first walk (2026-10-18 11:55, 2.00 km, 37:02), its stored bytes against the
 * watch's own screens, photographed by 白い熊 the moment it ended.
 */
class HuaweiD3WalkTest {
    private val summaryB64 = "AgIAAQQEatSXiAUEatSgNxQBAgYEAAAAnAcEAAAH0AgEAAAMZgkEAAAIrgoCAAgMAlyHJAEADQQAAAAJDgQAAAAGDwQAAAAAEAQAAAAAEQIAeCABABIEAAAIrgsEAAABbxsEAAABSRwEAAAK0R0EAAAJ4SEEAAAAACIEAAAAACMBAEgBCUkCAAFNAgAATgIAAE8BAGMEAAAAAGYZd3dzcXRzdXBua2dmY2NjY2JiYmBeXV5gXecJaAQR4cruaQE55wloBBHhyu9pATDnCWgEEeHK+GkBMOcJaAQR4co1aQEx5w5oBBHhyq9pBjExMzAwMOcMaAQR4ctpaQQxNzcw5wtoBBHhy2ppAzQzNecJaAQR4ctraQEw5wloBBHhy2xpATDnCWgEEeHLbWkBMF0BAQ=="
    private val samples0B64 = "AgIAAQMCAAAEDgABAABq1JeNBQBWCAAnCAIAAAkEAAAAAAWFMAAAAAAAAApcXAAGAAAAClxdAAgJAAAKXGIABwgAAApcZgAIBwAACltoAAkHAAAKW2kACQgAAApaZwAJCAAAClpnAAoIAAAKWWgACgkAAApYaAAKCQAACldmAAoIAAAKVmYACggAAApUZgAKCAAAClJmAAgAAAAKUWYADQkAAApOZwAMCAAACkxnAAwIAAAKSmcADAgAAApIZgAMCAAACkZoAAwIAAAKRWgADAgAAApDZwAMCAAACkBmAAwJAAAKP2gADAkAAAo9aAAMCQAACjpoAAwJAAAKOWcADAgAAAo3ZwAMCAAACjVmAAwIAAAKNWcACwgAAAo0ZgAMCAAACjNlAAwIAAAKM2cADAgAAAozZwALCAAACjJnAAoIAAAKMmUACQcAAAoxZQAABQAACjBmAAAAAAAKMGcADwgAAAoxZQAOCQAACjJmAAwIAAAKMWgADAkAAAovZwALCQAACipnAAwJAAAKJ2kACwkAAAojagALCQAACiFqAAwJAAAKIGoADAkAAAofaAALCQAACh1oAAsJAAAKHGcADAkAAAobZQAMCQAAChlmAAwJAAAKGWkACwkAAAoYagALCAAAChdqAAsIAAAKFmoADAgAAAoVaAAMCQAAChRoAAsIAAAKFWoAAAcAAAoXagAAAAAAChpqAAAGAAAKG4QADwkAAAochwANCQAACht0AAsJAAAKGnQACgAAAAoZbAAMCAAAChlsAAwJAAAKGGwADAgAAAoVbAAMCAAAChFsAAwIAAAKDmwADAgAAAoLawAKCAAACglrAAoIAAAKCWsACggAAAoIbAALCAAACgdsAAsIAAAKB2wACwgAAAoFagALCQAACgNrAAsJAAAKAm4ACwkAAAoAbQALCAAACfxtAAwIAAAJ+m0ADAgAAAn1awALCQAACe8="

    private fun blob(b64: String) = listOf(HuaweiProtocol.Tlv(0x81, Base64.getDecoder().decode(b64)))

    @Test
    fun `the summary carries the watch's altitude, recovery, load, zones and active energy`() {
        val s = HuaweiWorkout.parseSummary(blob(summaryB64))!!
        assertEquals(2000, s.distanceMetres)
        assertEquals(2222L, s.durationSeconds)       // 37:02
        assertEquals(3174, s.steps)
        assertEquals(156, s.calories)
        assertEquals(367, s.elevationGainDm)          // gain 36.7 m
        assertEquals(329, s.descentDm)                // descent 32.9 m
        assertEquals(2769, s.maxAltitudeDm)           // 276.9 m
        assertEquals(2529, s.minAltitudeDm)           // 252.9 m
        assertEquals(120, s.recoveryMinutes)          // "2 h"
        assertEquals(9, s.trainingLoad)
        assertEquals(9, s.aerobicLoad)
        assertEquals(0, s.anaerobicLoad)
        assertEquals(113, s.activeKcal)
        assertEquals(listOf(1770, 435, 0, 0, 0), s.zoneSeconds)   // warm-up 29 min, fat-burning 7 min
    }

    @Test
    fun `the D3's 8-byte samples carry altitude after heart rate, speed and steps`() {
        val b = HuaweiWorkout.parseSamples(blob(samples0B64))!!
        assertEquals(5, b.intervalSeconds)
        assertEquals(b.heart.size, b.altitudeDm.size)
        assertEquals(listOf(0, 92, 93, 98), b.heart.take(4))
        assertEquals(2652, b.altitudeDm.first())     // 265.2 m at the start
        assertEquals(listOf(0, 6, 8, 7), b.speedDmS.take(4))
    }
}
