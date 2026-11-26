package com.sysadmindoc.ruledeck.core.plugins.locale

import com.opentasker.core.plugins.locale.LocalePluginTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalePluginTargetTest {

    @Test
    fun parseTaskIdReturnsNullForNullBundle() {
        assertNull(LocalePluginTarget.parseTaskId(null))
    }

    @Test
    fun parseTaskNameReturnsNullForNullBundle() {
        assertNull(LocalePluginTarget.parseTaskName(null))
    }

    @Test
    fun bundleKeysFollowRuleDeckNamespace() {
        assertTrue(LocalePluginTarget.BUNDLE_KEY_TASK_ID.startsWith("com.opentasker."))
        assertTrue(LocalePluginTarget.BUNDLE_KEY_TASK_NAME.startsWith("com.opentasker."))
    }
}
