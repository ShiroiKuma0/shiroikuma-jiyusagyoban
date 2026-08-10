package com.opentasker.core.plugins.locale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class LocaleConditionPluginContractTest {
    @Test
    fun manifestExposesOneConditionEditorAndQueryReceiver() {
        val manifest = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.exists() })
            .documentElement
        val activities = manifest.getElementsByTagName("activity")
        val receivers = manifest.getElementsByTagName("receiver")
        val editor = findComponent(activities, "com.opentasker.core.plugins.locale.LocaleConditionEditActivity")
        val receiver = findComponent(receivers, "com.opentasker.core.plugins.locale.LocaleConditionQueryReceiver")

        assertEquals("true", editor.getAttribute("android:exported"))
        assertEquals("true", receiver.getAttribute("android:exported"))
        assertEquals("enforceIntentFilter", receiver.getAttribute("android:intentMatchingFlags"))
        assertTrue(componentHasAction(editor, LocalePluginContract.ACTION_EDIT_CONDITION))
        assertTrue(componentHasAction(receiver, LocalePluginContract.ACTION_QUERY_CONDITION))
    }

    @Test
    fun queryTargetUsesBoundedAsyncFailClosedPath() {
        val source = source("com/opentasker/core/plugins/locale/LocaleConditionQueryReceiver.kt")

        assertTrue(source.contains("goAsync()"))
        assertTrue(source.contains("withTimeoutOrNull(MAX_QUERY_MS)"))
        assertTrue(source.contains("LocalePluginConditionState.Unknown"))
        assertTrue(source.contains("LocalePluginContract.MAX_BUNDLE_JSON_BYTES"))
    }

    @Test
    fun editorOmitsSecretVariablesAndUsesTypedConditionBundles() {
        val source = source("com/opentasker/core/plugins/locale/LocaleConditionEditActivity.kt")

        assertTrue(source.contains("filterNot { it.isSecret }"))
        assertTrue(source.contains("LocaleConditionTarget.profileActive"))
        assertTrue(source.contains("LocaleConditionTarget.contextSatisfied"))
        assertTrue(source.contains("LocaleConditionTarget.variableCompare"))
    }

    private fun findComponent(components: org.w3c.dom.NodeList, name: String): Element {
        for (index in 0 until components.length) {
            val component = components.item(index) as Element
            if (component.getAttribute("android:name") == name) return component
        }
        error("Missing manifest component $name")
    }

    private fun componentHasAction(component: Element, action: String): Boolean =
        component.getElementsByTagName("action").let { actions ->
            (0 until actions.length).any { index ->
                (actions.item(index) as Element).getAttribute("android:name") == action
            }
        }

    private fun source(path: String): String = listOf(File("src/main/java/$path"), File("app/src/main/java/$path"))
        .first { it.exists() }
        .readText()
}
