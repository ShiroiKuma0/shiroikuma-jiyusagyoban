package com.opentasker.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentUriGrantSourceGuardTest {
    private val sourceRoot: Path = listOf(
        Path.of("src/main/java"),
        Path.of("app/src/main/java"),
    ).first(Files::exists)

    @Test
    fun debugVmPolicyEnablesTheAndroid17Detector() {
        val source = sourceRoot.resolve("com/opentasker/app/OpenTaskerApp_NoHilt.kt").readText()

        assertTrue(source.contains("Build.VERSION.SDK_INT >= 37"))
        assertTrue(source.contains("detectImplicitUriPermissionGrant()"))
    }

    @Test
    fun configurableUriDispatchKeepsTheGrantGuardAndBothExplicitFlags() {
        val policy = sourceRoot.resolve("com/opentasker/core/actions/IntentDispatch.kt").readText()
        val dispatch = sourceRoot.resolve("com/opentasker/core/actions/BuiltInActions.kt").readText()

        assertTrue(policy.contains("IntentUriGrantPolicy.violation"))
        assertTrue(policy.contains("URI-bearing intent requires explicit"))
        assertTrue(dispatch.contains("Intent.FLAG_GRANT_READ_URI_PERMISSION"))
        assertTrue(dispatch.contains("Intent.FLAG_GRANT_WRITE_URI_PERMISSION"))
    }

    @Test
    fun shareReceiverChecksUriAccessOffMainThreadAndSurfacesFailure() {
        val shareEvents = sourceRoot.resolve("com/opentasker/core/contexts/ShareContextEvents.kt").readText()
        val receiver = sourceRoot.resolve("com/opentasker/core/contexts/ShareReceiverActivity.kt").readText()

        assertTrue(shareEvents.contains("openInputStream"))
        assertTrue(receiver.contains("Dispatchers.IO"))
        assertTrue(receiver.contains("SharePublishResult.URI_NOT_READABLE"))
    }
}
