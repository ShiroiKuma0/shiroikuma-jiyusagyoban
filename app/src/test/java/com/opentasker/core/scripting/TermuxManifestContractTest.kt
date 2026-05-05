package com.opentasker.core.scripting

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class TermuxManifestContractTest {
    @Test
    fun manifestQueriesTermuxPackages() {
        val manifest = loadMainManifest()
        val queries = manifest.getElementsByTagName("queries")
        assertTrue("manifest must declare package visibility queries", queries.length > 0)

        val packages = manifest.getElementsByTagName("package")
        val queriedPackages = (0 until packages.length)
            .asSequence()
            .mapNotNull { packages.item(it).attributes.getNamedItem("android:name")?.nodeValue }
            .toSet()

        assertTrue(
            "manifest must query Termux package",
            TermuxScriptBackend.TERMUX_PACKAGE in queriedPackages,
        )
        assertTrue(
            "manifest must query Termux:Tasker package",
            TermuxScriptBackend.TERMUX_TASKER_PACKAGE in queriedPackages,
        )
    }

    private fun loadMainManifest() =
        DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(
                listOf(
                    File("src/main/AndroidManifest.xml"),
                    File("app/src/main/AndroidManifest.xml"),
                ).first { it.exists() },
            )
            .documentElement
}
