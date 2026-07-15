package com.opentasker.core.scripting

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class TermuxManifestContractTest {
    @Test
    fun manifestQueriesTermuxAndDeclaresRunCommandPermission() {
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
        val permissions = manifest.getElementsByTagName("uses-permission")
        val declaredPermissions = (0 until permissions.length)
            .asSequence()
            .mapNotNull { permissions.item(it).attributes.getNamedItem("android:name")?.nodeValue }
            .toSet()
        assertTrue(
            "manifest must request the Termux RUN_COMMAND permission",
            TermuxScriptBackend.RUN_COMMAND_PERMISSION in declaredPermissions,
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
