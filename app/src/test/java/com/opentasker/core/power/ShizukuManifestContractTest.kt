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
            .mapNotNull { packages.item(it).attributes.getNamedItem("android:name")?.nodeValue }
            .toSet()

        // EVERY manager we are willing to open, not just upstream's. A missing pin here does not fail
        // loudly: getLaunchIntentForPackage simply answers null on Android 11+ even when the app IS
        // installed, and "Open Shizuku" silently degrades to opening a web page — which is exactly the
        // bug this assertion now guards (白い熊's phone runs the fork, which was never pinned).
        for (pkg in ShizukuPowerBackend.MANAGER_PACKAGES) {
            assertTrue("manifest must query Shizuku manager package $pkg", pkg in queriedPackages)
        }
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
