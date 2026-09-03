package com.opentasker.core.data

import com.opentasker.ProductionSources
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * jsoup's XML tree builder carried a heap-exhaustion DoS, GHSA-65r4-943x-97jj / CVE-2026-75140,
 * published 2026-08-20. **It is fixed in 1.23.2**, which this project pins: the advisory names fix
 * commit `862ba2f` ("Optimize XML namespace scope tracking", jhy/jsoup#2556), and GitHub's compare
 * API puts that commit in the `jsoup-1.23.2` tag and not in `jsoup-1.23.1`. An earlier version of
 * this comment said the opposite, which was wrong.
 *
 * The guard stays anyway, and it was never really about that one CVE. OpenTasker parses one thing
 * with jsoup, in one place, with the HTML tree builder on already-bounded input. That is a property
 * of the code rather than of the pinned version, so nothing but a check keeps it true, and the XML
 * tree builder is the part of jsoup with the DoS history.
 */
class JsoupParserBoundaryTest {
    @Test
    fun productionCodeNeverReachesJsoupsXmlTreeBuilder() {
        val offenders = ProductionSources.allKotlinFiles()
            .filter { file ->
                val text = file.readText()
                text.contains("xmlParser(") || text.contains("XmlTreeBuilder")
            }
            .map { ProductionSources.repoRoot.relativize(it).toString() }

        assertTrue(
            "jsoup's XML tree builder is the part with the DoS history (CVE-2026-75140); parse XML " +
                "through the existing bounded javax.xml path instead. Offending files: $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun theOnlyJsoupEntryPointIsTheHtmlParser() {
        // Pins the exposure verdict to something observable. If a second call site appears, this
        // fails and the CVE question has to be answered again rather than assumed.
        val jsoupCallers = ProductionSources.allKotlinFiles()
            .filter { it.readText().contains("org.jsoup") }
            .map { ProductionSources.repoRoot.relativize(it).toString().replace('\\', '/') }

        assertEquals(
            listOf("app/src/main/java/com/opentasker/core/data/StructuredDataReader.kt"),
            jsoupCallers,
        )
        assertTrue(
            "The HTML reader must keep using the default HTML tree builder",
            ProductionSources.read("com/opentasker/core/data/StructuredDataReader.kt")
                .contains("Jsoup.parse(source)"),
        )
    }
}
