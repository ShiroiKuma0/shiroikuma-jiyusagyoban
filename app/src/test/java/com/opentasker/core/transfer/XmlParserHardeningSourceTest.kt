package com.opentasker.core.transfer

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XmlParserHardeningSourceTest {
    private val sourceRoot: Path = listOf(
        Path.of("src/main/java/com/opentasker"),
        Path.of("app/src/main/java/com/opentasker"),
    ).first(Files::exists)

    @Test
    fun xmlParsersShareOneBestEffortHardeningHelper() {
        val helper = sourceRoot.resolve("core/transfer/XmlParserFeatures.kt").readText()
        assertTrue(helper.contains("fun SAXParserFactory.applyImportHardening"))
        assertTrue(helper.contains("fun DocumentBuilderFactory.applyImportHardening"))
        assertTrue(helper.contains("runCatching { setFeature(name, value) }"))

        val callers = listOf(
            "core/transfer/ImportResourceBudget.kt",
            "core/transfer/TaskerXmlImport.kt",
            "core/data/StructuredDataReader.kt",
        )
        callers.forEach { relative ->
            val source = sourceRoot.resolve(relative).readText()
            assertTrue("$relative must call applyImportHardening", source.contains("applyImportHardening()"))
            assertFalse(
                "$relative must not keep a private setFeatureSafely copy",
                source.contains("private fun SAXParserFactory.setFeatureSafely") ||
                    source.contains("private fun DocumentBuilderFactory.setFeatureSafely"),
            )
        }
    }
}
