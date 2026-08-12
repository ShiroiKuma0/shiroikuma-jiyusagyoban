package com.opentasker.core.external

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertFalse
import org.junit.Test

class AppFunctionSurfaceContractTest {
    @Test
    fun dormantAppFunctionsSurfaceIsNotShipped() {
        val manifest = parseXml(repoFile("app/src/main/AndroidManifest.xml"))
        val services = manifest.getElementsByTagName("service")

        assertFalse((0 until services.length).any { index ->
            val service = services.item(index)
            service.attributes?.getNamedItem("android:permission")?.nodeValue ==
                BIND_APP_FUNCTION_SERVICE_PERMISSION ||
                service.hasDescendant("property", "android:name", APP_FUNCTIONS_PROPERTY) ||
                service.hasDescendant("action", "android:name", APP_FUNCTIONS_SERVICE_ACTION)
        })

        assertFalse(repoFileOrNull(APP_FUNCTION_SERVICE_SOURCE)?.exists() == true)
        assertFalse(repoFileOrNull(APP_FUNCTION_PROTOTYPE_SOURCE)?.exists() == true)
        assertFalse(repoFileOrNull(APP_FUNCTION_METADATA)?.exists() == true)
    }

    private fun org.w3c.dom.Node.hasDescendant(
        elementName: String,
        attributeName: String,
        expectedValue: String,
    ): Boolean {
        val elements = (this as org.w3c.dom.Element).getElementsByTagName(elementName)
        return (0 until elements.length).any { index ->
            elements.item(index).attributes?.getNamedItem(attributeName)?.nodeValue == expectedValue
        }
    }

    private fun parseXml(file: File) =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement

    private fun repoFile(path: String): File = requireNotNull(repoFileOrNull(path)) {
        "Repository file not found: $path"
    }

    private fun repoFileOrNull(path: String): File? {
        val modulePath = path.removePrefix("app/")
        return listOf(
            File(path),
            File(modulePath),
            File("../$path"),
            File("../$modulePath"),
        ).firstOrNull { it.exists() }
    }

    private companion object {
        const val BIND_APP_FUNCTION_SERVICE_PERMISSION =
            "android.permission.BIND_APP_FUNCTION_SERVICE"
        const val APP_FUNCTIONS_PROPERTY = "android.app.appfunctions"
        const val APP_FUNCTIONS_SERVICE_ACTION = "android.app.appfunctions.AppFunctionService"
        const val APP_FUNCTION_SERVICE_SOURCE =
            "app/src/main/java/com/opentasker/core/external/OpenTaskerAppFunctionService.kt"
        const val APP_FUNCTION_PROTOTYPE_SOURCE =
            "app/src/main/java/com/opentasker/core/external/AppFunctionPrototype.kt"
        const val APP_FUNCTION_METADATA = "app/src/main/assets/open_tasker_app_functions.xml"
    }
}
