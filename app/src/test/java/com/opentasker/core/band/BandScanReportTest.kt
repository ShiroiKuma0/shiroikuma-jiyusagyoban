package com.opentasker.core.band

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scan's judgement, tested where it can be: `BandScanReport` is pure Kotlin precisely so the
 * ranking is not something that has to be checked by walking round the flat with a phone.
 */
class BandScanReportTest {

    private fun device(
        address: String,
        name: String = "",
        rssi: Int = -60,
        services: List<String> = emptyList(),
        connectable: Boolean? = true,
    ) = BandScanDevice(
        address = address,
        name = name,
        rssi = rssi,
        serviceUuids = services,
        connectable = connectable,
    )

    @Test
    fun theFff0ServiceOutranksAPlausibleName() {
        val ranked = BandScanReport.rank(
            listOf(
                device("AA:AA:AA:AA:AA:AA", name = "My Fit Band", rssi = -40),
                device("BB:BB:BB:BB:BB:BB", services = listOf("fff0"), rssi = -80),
            ),
            configuredAddress = "",
            lang = "en-US",
        )
        assertEquals("BB:BB:BB:BB:BB:BB", ranked.first().device.address)
        assertEquals(BandVerdict.LIKELY, ranked.first().verdict)
        assertEquals(BandVerdict.POSSIBLE, ranked[1].verdict)
    }

    @Test
    fun theConfiguredAddressIsMarkedAndRanked() {
        val ranked = BandScanReport.rank(
            listOf(
                device("11:11:11:11:11:11", rssi = -30),
                device("D5:A7:06:DC:A1:3A", rssi = -70),
            ),
            configuredAddress = "d5:a7:06:dc:a1:3a",
            lang = "en-US",
        )
        val configured = ranked.first { it.device.address == "D5:A7:06:DC:A1:3A" }
        assertTrue("the configured band must be marked", configured.isConfigured)
        assertEquals("it must outrank a closer stranger", "D5:A7:06:DC:A1:3A", ranked.first().device.address)
    }

    @Test
    fun anOrdinaryDeviceIsUnlikelyAndNeverProbed() {
        val ranked = BandScanReport.rank(
            listOf(device("CC:CC:CC:CC:CC:CC", name = "LE-Headphones")),
            configuredAddress = "",
            lang = "en-US",
        )
        assertEquals(BandVerdict.UNLIKELY, ranked.first().verdict)
        assertTrue("an unlikely device must never cost a connection", BandScanReport.probeOrder(ranked, 3).isEmpty())
    }

    @Test
    fun bestAddressRefusesToGuessFromNothing() {
        val ranked = BandScanReport.rank(
            listOf(device("CC:CC:CC:CC:CC:CC", name = "LE-Headphones")),
            configuredAddress = "",
            lang = "en-US",
        )
        assertEquals("", BandScanReport.bestAddress(ranked))
    }

    @Test
    fun aConfirmedProbeWinsOverAStrongerAdvertisement() {
        val ranked = BandScanReport.rank(
            listOf(
                device("AA:AA:AA:AA:AA:AA", services = listOf("fff0"), rssi = -35),
                device("BB:BB:BB:BB:BB:BB", name = "band", rssi = -90),
            ),
            configuredAddress = "",
            lang = "en-US",
        )
        val probed = ranked.map {
            if (it.device.address == "BB:BB:BB:BB:BB:BB") {
                BandScanReport.applyProbe(it, confirmed = true, note = "answered on fff0/fff6/fff7")
            } else {
                it
            }
        }
        assertEquals("BB:BB:BB:BB:BB:BB", BandScanReport.bestAddress(probed))
    }

    @Test
    fun aRefusedProbeDoesNotDemoteTheAdvertisementButIsRecorded() {
        val ranked = BandScanReport.rank(
            listOf(device("AA:AA:AA:AA:AA:AA", services = listOf("fff0"))),
            configuredAddress = "",
            lang = "en-US",
        )
        val probed = BandScanReport.applyProbe(ranked.first(), confirmed = false, note = "the band did not answer")
        assertEquals(BandVerdict.LIKELY, probed.verdict)
        assertEquals("the failure must be shown, not swallowed", "the band did not answer", probed.probeNote)
    }

    @Test
    fun shortUuidCollapsesOnlyTheSigBase() {
        assertEquals("fff0", BandScanReport.shortUuid("0000FFF0-0000-1000-8000-00805f9b34fb"))
        assertEquals("fff6", BandScanReport.shortUuid("0000fff6-0000-1000-8000-00805f9b34fb"))
        val vendor = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
        assertEquals("a vendor UUID must keep its full form", vendor, BandScanReport.shortUuid(vendor))
    }

    @Test
    fun distanceIsRoughButOrdered() {
        val near = BandScanReport.approximateMetres(-45)!!
        val far = BandScanReport.approximateMetres(-85)!!
        assertTrue("a stronger signal must read as nearer", near < far)
        assertNull("an impossible reading yields no distance", BandScanReport.approximateMetres(0))
    }

    @Test
    fun anEmptyScanExplainsItselfRatherThanSayingNothing() {
        val text = BandScanReport.describe(emptyList(), seconds = 8, lang = "en-US", showAll = true, probed = 0)
        assertTrue("it must name the charger case", text.contains("charger"))
        assertTrue("it must name Hume's own app holding the link", text.contains("Hume's own app"))
    }

    @Test
    fun theReportCarriesTheEvidenceNotJustTheVerdict() {
        val ranked = BandScanReport.rank(
            listOf(device("D5:A7:06:DC:A1:3A", name = "Hume", services = listOf("fff0"), rssi = -50)),
            configuredAddress = "D5:A7:06:DC:A1:3A",
            lang = "en-US",
        )
        val text = BandScanReport.describe(ranked, seconds = 8, lang = "en-US", showAll = true, probed = 0)
        assertTrue(text.contains("D5:A7:06:DC:A1:3A"))
        assertTrue("the fff0 evidence must be stated", text.contains("advertises the band's fff0 service"))
        assertTrue("the configured marker must be visible", text.contains("configured"))
    }

    @Test
    fun japaneseIsAWholeReportNotAMixture() {
        val ranked = BandScanReport.rank(
            listOf(device("D5:A7:06:DC:A1:3A", services = listOf("fff0"))),
            configuredAddress = "",
            lang = "ja-JP",
        )
        val text = BandScanReport.describe(ranked, seconds = 8, lang = "ja-JP", showAll = true, probed = 1)
        assertTrue(text.contains("バンド探索"))
        assertTrue(text.contains("根拠"))
        assertFalse("no English section headings may leak into the Japanese report", text.contains("Band scan"))
    }

    @Test
    fun omittingTheRestStillSaysHowManyWereOmitted() {
        val ranked = BandScanReport.rank(
            listOf(
                device("D5:A7:06:DC:A1:3A", services = listOf("fff0")),
                device("CC:CC:CC:CC:CC:C1", name = "LE-Headphones"),
                device("CC:CC:CC:CC:CC:C2", name = "TV"),
            ),
            configuredAddress = "",
            lang = "en-US",
        )
        val text = BandScanReport.describe(ranked, seconds = 8, lang = "en-US", showAll = false, probed = 0)
        assertTrue("the count of hidden devices must survive", text.contains("2 other device(s)"))
        assertFalse(text.contains("CC:CC:CC:CC:CC:C1"))
    }

    @Test
    fun theModelsRealAdvertisedNameReachesLikelyWithoutAProbe() {
        // Measured from 白い熊's own band on 2026-08-11: "Hume Band V2 A13A", where A13A is the last
        // two octets of D5:A7:06:DC:A1:3A. A replacement band differs only in those four digits.
        val ranked = BandScanReport.rank(
            listOf(device("D5:A7:06:DC:A1:3A", name = "Hume Band V2 A13A")),
            configuredAddress = "",
            lang = "en-US",
        )
        assertEquals(BandVerdict.LIKELY, ranked.first().verdict)
        assertEquals("D5:A7:06:DC:A1:3A", BandScanReport.bestAddress(ranked))
    }

    @Test
    fun aBrandNewBandIsFoundByNameAloneBeforeAnyProbe() {
        val ranked = BandScanReport.rank(
            listOf(
                device("AA:BB:CC:DD:EE:FF", name = "Hume Band V2 EEFF", rssi = -55),
                device("11:22:33:44:55:66", name = "Mi Band 7", rssi = -40),
            ),
            configuredAddress = "D5:A7:06:DC:A1:3A",
            lang = "en-US",
        )
        assertEquals("the unknown Hume band must outrank a closer look-alike", "AA:BB:CC:DD:EE:FF", ranked.first().device.address)
        val text = BandScanReport.describe(ranked, seconds = 8, lang = "en-US", showAll = true, probed = 0)
        assertTrue("the name evidence must be quoted", text.contains("this model's advertised name"))
    }

    @Test
    fun aGenericWristbandNameStaysPossible() {
        val ranked = BandScanReport.rank(
            listOf(device("11:22:33:44:55:66", name = "Mi Band 7")),
            configuredAddress = "",
            lang = "en-US",
        )
        assertEquals(BandVerdict.POSSIBLE, ranked.first().verdict)
        assertEquals("possible is not enough to hand back an address", "", BandScanReport.bestAddress(ranked))
    }

    @Test
    fun hexIsBoundedSoADialogCannotBeFlooded() {
        val long = ByteArray(31) { it.toByte() }
        val text = BandScanReport.hex(long)
        assertTrue("it must say how much it withheld", text.contains("(31 B)"))
        assertTrue(text.length < 40)
    }
}
