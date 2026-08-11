package com.opentasker.core.contexts

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedPushContractTest {
    private val repoRoot = sequenceOf(File("."), File(".."))
        .map(File::getAbsoluteFile)
        .first { File(it, "README.md").exists() && File(it, "app/build.gradle.kts").exists() }

    @Test
    fun serviceUsesOfficialConnectorAndLegacyReceiverRemainsAvailable() {
        val service = File(repoRoot, "app/src/main/java/com/opentasker/core/contexts/UnifiedPushService.kt").readText()
        val connector = File(repoRoot, "app/src/main/java/com/opentasker/core/contexts/UnifiedPushConnector.kt").readText()
        val manifest = File(repoRoot, "app/src/main/AndroidManifest.xml").readText()
        val build = File(repoRoot, "app/build.gradle.kts").readText()

        assertTrue(service.contains("PushService()"))
        assertTrue(service.contains("publishUnifiedPushMessage"))
        assertTrue(connector.contains("UnifiedPush.register"))
        assertTrue(connector.contains("UnifiedPush.unregister"))
        assertTrue(connector.contains("tryUseCurrentOrDefaultDistributor"))
        assertTrue(connector.contains(UnifiedPushConnector.DISTRIBUTOR_DISCOVERY_URI))
        assertTrue(build.contains("libs.unifiedpush.connector"))
        assertTrue(manifest.contains("org.unifiedpush.android.connector.PUSH_EVENT"))
        assertTrue(manifest.contains("com.opentasker.action.PUSH_EVENT"))
        assertFalse(service.contains("message.content.toString"))
    }
}
