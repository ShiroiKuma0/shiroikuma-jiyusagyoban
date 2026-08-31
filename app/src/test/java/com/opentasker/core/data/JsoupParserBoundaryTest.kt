package com.opentasker.core.data

import com.opentasker.ProductionSources
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * jsoup's XML tree builder carries an unfixed heap-exhaustion DoS
 * (GHSA-65r4-943x-97jj / CVE-2026-75140, published 2026-08-20), which affects every release
 * through 1.23.2 with no fixed version available. OpenTasker is not exposed, because the one place
 * it parses with jsoup uses the HTML tree builder on already-bounded input.
 *
 * That is a property of the code rather than of the pinned version, so it needs a guard. Reaching
 * for the XML parser would silently opt this app into an unpatched DoS against user-supplied files.
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
            "jsoup's XML parser is unpatched for CVE-2026-75140; parse XML through the existing " +
                "bounded javax.xml path instead. Offending files: $offenders",
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
