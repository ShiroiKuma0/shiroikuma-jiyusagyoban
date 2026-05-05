package com.opentasker.core.power

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ShizukuManifestContractTest {
    @Test
    fun manifestQueriesShizukuManagerPackage() {
        val manifest = loadMainManifest()
        val queries = manifest.getElementsByTagName("queries")
        assertTrue("manifest must declare package visibility queries", queries.length > 0)

        val packages = manifest.getElementsByTagName("package")
        val queriedPackages = (0 until packages.length)
            .asSequence()
            .mapNotNull { packages.item(it).attributes.getNamedItem("android:name")?.nodeValue }

        assertTrue(
            "manifest must query Shizuku manager package",
            ShizukuPowerBackend.MANAGER_PACKAGE in queriedPackages,
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
