package com.opentasker.core.contexts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Manifest NFC dispatch delivers a tag by starting the filtered activity. With the default
 * standard launch mode that means a brand new MainActivity on top of the task, so onNewIntent is
 * never called and each tap stacks another copy of the app: the armed tag-write editor stays in
 * the buried instance while the write result is delivered to an instance the user cannot see.
 *
 * singleTop is therefore a correctness requirement of the NFC filter, not a preference.
 */
class NfcDispatchManifestContractTest {
    @Test
    fun theActivityFilteringNfcTagsIsSingleTop() {
        val activities = loadMainManifest().getElementsByTagName("activity")
        val nfcActivities = (0 until activities.length)
            .asSequence()
            .map { activities.item(it) }
            .filter { activity ->
                val filters = activity.childNodes
                (0 until filters.length)
                    .asSequence()
                    .map { filters.item(it) }
                    .filter { it.nodeName == "intent-filter" }
                    .any { filter ->
                        val actions = filter.childNodes
                        (0 until actions.length)
                            .asSequence()
                            .map { actions.item(it) }
                            .filter { it.nodeName == "action" }
                            .any {
                                it.attributes.getNamedItem("android:name")?.nodeValue ==
                                    "android.nfc.action.TAG_DISCOVERED"
                            }
                    }
            }
            .toList()

        assertEquals("exactly one activity should filter NFC tag discovery", 1, nfcActivities.size)
        val attributes = nfcActivities.single().attributes
        assertTrue(
            "the NFC-filtered activity must set android:launchMode=\"singleTop\" so onNewIntent " +
                "receives the tag instead of a new activity instance being stacked",
            attributes.getNamedItem("android:launchMode")?.nodeValue in setOf("singleTop", "singleTask"),
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
