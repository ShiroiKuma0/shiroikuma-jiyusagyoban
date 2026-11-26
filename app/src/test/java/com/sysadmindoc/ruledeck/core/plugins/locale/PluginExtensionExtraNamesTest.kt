package com.sysadmindoc.ruledeck.core.plugins.locale

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Installed plugins match the extension extras by their exact published names, so these values come
 * from the plugin protocol's documentation, not from the constants they check.
 */
class PluginExtensionExtraNamesTest {

    private val namespace = "net.dinglisch.android.tasker."

    @Test
    fun theExtensionExtrasKeepTheirPublishedNames() {
        assertEquals(namespace, LocalePluginContract.EXTENSION_NAMESPACE)
        assertEquals(
            mapOf(
                "VARIABLE_REPLACE_KEYS" to "${namespace}extras.VARIABLE_REPLACE_KEYS",
                "RELEVANT_VARIABLES" to "${namespace}RELEVANT_VARIABLES",
                "HOST_CAPABILITIES" to "${namespace}extras.HOST_CAPABILITIES",
                "REQUESTED_TIMEOUT" to "${namespace}extras.REQUESTED_TIMEOUT",
                "COMPLETION_INTENT" to "${namespace}extras.COMPLETION_INTENT",
                "RESULT_CODE" to "${namespace}extras.RESULT_CODE",
                "VARIABLES" to "${namespace}extras.VARIABLES",
                "HINTS" to "${namespace}extras.HINTS",
            ),
            mapOf(
                "VARIABLE_REPLACE_KEYS" to LocalePluginContract.EXTRA_VARIABLE_REPLACE_KEYS,
                "RELEVANT_VARIABLES" to LocalePluginContract.EXTRA_RELEVANT_VARIABLES,
                "HOST_CAPABILITIES" to LocalePluginContract.EXTRA_HOST_CAPABILITIES,
                "REQUESTED_TIMEOUT" to LocalePluginContract.EXTRA_REQUESTED_TIMEOUT,
                "COMPLETION_INTENT" to LocalePluginContract.EXTRA_COMPLETION_INTENT,
                "RESULT_CODE" to LocalePluginContract.EXTRA_RESULT_CODE,
                "VARIABLES" to LocalePluginContract.EXTRA_VARIABLES,
                "HINTS" to LocalePluginContract.EXTRA_HINTS,
            ),
        )
    }
}
