package com.opentasker.core.external

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppFunctionPrototypeContractTest {
    @Test
    fun capabilityIsVersionedApprovedAndSecretFree() {
        val capabilities = AppFunctionPrototypeContract.capabilities
        assertEquals(1, capabilities.size)
        val capability = capabilities.single()

        assertEquals(AppFunctionPrototypeContract.SCHEMA_VERSION, 1)
        assertEquals(AppFunctionSideEffect.TASK_EXECUTION, capability.sideEffect)
        assertTrue(capability.requiresUserApproval)
        assertFalse(capability.acceptsSecretArguments)
        assertEquals(setOf(AppFunctionPrototypeContract.PARAMETER_TASK_ID), capability.parameterNames)
        assertTrue(capability.functionId.startsWith("com.opentasker.appfunctions."))
    }

    @Test
    fun apiGateReportsUnsupportedDevicesHonestly() {
        assertEquals(
            AppFunctionAvailability.UNSUPPORTED_API,
            AppFunctionSupport.availability(35),
        )
        assertEquals(
            AppFunctionAvailability.SUPPORTED,
            AppFunctionSupport.availability(36),
        )
        assertTrue(AppFunctionSupport.unsupportedMessage(35).contains("API 36"))
    }

    @Test
    fun executionPolicyRequiresBothEnablementAndTaskApproval() {
        val taskId = 42L
        assertEquals(
            AppFunctionExecutionGate.DISABLED,
            AppFunctionExecutionPolicy.evaluate(false, setOf(taskId), taskId),
        )
        assertEquals(
            AppFunctionExecutionGate.TASK_NOT_APPROVED,
            AppFunctionExecutionPolicy.evaluate(true, emptySet(), taskId),
        )
        assertEquals(
            AppFunctionExecutionGate.ALLOWED,
            AppFunctionExecutionPolicy.evaluate(true, setOf(taskId), taskId),
        )
        assertEquals(
            AppFunctionExecutionGate.INVALID_TASK_ID,
            AppFunctionExecutionPolicy.evaluate(true, setOf(taskId), 0L),
        )
    }

    @Test
    fun callerPolicyRequiresExecutePermissionAndRejectsSelfCalls() {
        assertTrue(AppFunctionCallerPolicy.isTrustedCaller(true, "", "com.opentasker"))
        assertTrue(AppFunctionCallerPolicy.isTrustedCaller(true, "assistant", "com.opentasker"))
        assertFalse(AppFunctionCallerPolicy.isTrustedCaller(false, "assistant", "com.opentasker"))
        assertFalse(AppFunctionCallerPolicy.isTrustedCaller(true, "com.opentasker", "com.opentasker"))
    }

    @Test
    fun manifestAndMetadataKeepTheSurfaceDisabledAndBounded() {
        val manifest = parseXml(repoFile("app/src/main/AndroidManifest.xml"))
        val services = manifest.getElementsByTagName("service")
        val service = (0 until services.length)
            .asSequence()
            .map { services.item(it) }
            .firstOrNull {
                it.attributes.getNamedItem("android:name")?.nodeValue ==
                    "com.opentasker.core.external.OpenTaskerAppFunctionService"
            }
        assertNotNull(service)
        assertEquals(
            "android.permission.BIND_APP_FUNCTION_SERVICE",
            service!!.attributes.getNamedItem("android:permission").nodeValue,
        )
        assertEquals("true", service.attributes.getNamedItem("android:exported").nodeValue)

        val properties = service.childNodes
        assertTrue((0 until properties.length).any { index ->
            properties.item(index).attributes?.getNamedItem("android:name")?.nodeValue ==
                "android.app.appfunctions"
        })

        val metadata = parseXml(repoFile("app/src/main/assets/open_tasker_app_functions.xml"))
        val function = metadata.getElementsByTagName("appfunction").item(0)
        assertEquals(
            AppFunctionPrototypeContract.FUNCTION_ID_RUN_APPROVED_TASK,
            function.childText("id"),
        )
        assertEquals("false", function.childText("enabledByDefault"))
        assertEquals("true", function.childText("restrictCallersWithExecuteAppFunctions"))
        assertEquals("1", function.childText("schemaVersion"))
        assertEquals(
            AppFunctionPrototypeContract.PARAMETER_TASK_ID,
            (function as org.w3c.dom.Element).getElementsByTagName("name").item(0).textContent,
        )
        assertFalse(metadata.textContent.contains("password", ignoreCase = true))
        assertFalse(metadata.textContent.contains("token", ignoreCase = true))
        assertFalse(metadata.textContent.contains("secret_value", ignoreCase = true))
    }

    @Test
    fun serviceMapsOnlyThroughTheExistingSignatureProtectedReceiver() {
        val source = repoFile(
            "app/src/main/java/com/opentasker/core/external/OpenTaskerAppFunctionService.kt",
        ).readText()
        assertTrue(source.contains("checkCallingPermission"))
        assertTrue(source.contains("EXECUTE_APP_FUNCTIONS_PERMISSION"))
        assertTrue(source.contains("AutomationTargetContract.internalRunTaskIntent"))
        assertTrue(source.contains("InternalTaskRunSource.APP_FUNCTION"))
        assertTrue(source.contains("sendBroadcast(taskIntent, AutomationTargetContract.PERMISSION)"))
        assertFalse(source.contains("DownloadManager"))
        assertFalse(source.contains("PackageInstaller"))
        assertFalse(source.contains("https://"))
    }

    private fun parseXml(file: File) =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement

    private fun repoFile(path: String): File {
        val modulePath = path.removePrefix("app/")
        return listOf(
            File(path),
            File(modulePath),
            File("../$path"),
            File("../$modulePath"),
        ).first { it.exists() }
    }

    private fun org.w3c.dom.Node.childText(name: String): String =
        (0 until childNodes.length)
            .asSequence()
            .map { childNodes.item(it) }
            .first { it.nodeName == name }
            .textContent
}
