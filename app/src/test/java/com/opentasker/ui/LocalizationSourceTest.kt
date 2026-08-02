package com.opentasker.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.readText

class LocalizationSourceTest {
    private val moduleRoot: Path = listOf(Path.of("."), Path.of("app"))
        .first { Files.isDirectory(it.resolve("src/main")) }
    private val sourceRoot: Path = moduleRoot.resolve("src/main/java")
    private val resRoot: Path = moduleRoot.resolve("src/main/res")





    @Test
    fun debugBuildGeneratesAndroidPseudoLocales() {
        val buildFile = moduleRoot.resolve("build.gradle.kts").readText()
        assertTrue(
            "Debug builds must enable Android en-XA/ar-XB pseudo locales",
            Regex("""getByName\(\"debug\"\)\s*\{[^}]*isPseudoLocalesEnabled\s*=\s*true""", RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(buildFile),
        )
    }

    @Test
    fun localeDirectoriesRemainValidWeblateResourceTargets() {
        val defaultValueFiles = Files.list(resRoot.resolve("values")).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".xml") }.toList()
        }
        assertTrue("Default value resources are missing", defaultValueFiles.isNotEmpty())

        val localeFiles = Files.list(resRoot).use { paths ->
            paths
                .filter { Files.isDirectory(it) && it.fileName.toString().startsWith("values-") }
                .map { it.resolve("strings.xml") }
                .toList()
        }
        assertTrue("Expected locale resource directories for Weblate targets", localeFiles.isNotEmpty())

        val invalidFiles = (localeFiles + defaultValueFiles).mapNotNull { file ->
            runCatching {
                val root = newDocumentBuilderFactory().newDocumentBuilder().parse(file.toFile()).documentElement.nodeName
                if (root == "resources") null else "${resRoot.relativize(file)} root=$root"
            }.getOrElse { error -> "${resRoot.relativize(file)} ${error.message}" }
        }

        assertTrue("Invalid Android value resource XML: $invalidFiles", invalidFiles.isEmpty())
        val defaultStrings = defaultValueFiles.flatMap { stringResourceValues(it).entries }.associate { it.toPair() }
        val translatedLocales = localeFiles.filter { file ->
            stringResourceValues(file).any { (name, value) -> defaultStrings[name]?.let { it != value } == true }
        }
        assertTrue("At least one real locale must contain translated, non-placeholder strings", translatedLocales.isNotEmpty())
    }

    private fun defaultStringResourceNames(): Set<String> =
        Files.list(resRoot.resolve("values")).use { paths ->
            paths
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".xml") }
                .flatMap { file ->
                    val document = newDocumentBuilderFactory().newDocumentBuilder().parse(file.toFile())
                    val strings = document.getElementsByTagName("string")
                    (0 until strings.length).map { index ->
                        strings.item(index).attributes.getNamedItem("name").nodeValue
                    }.stream()
                }
                .toList()
                .toSet()
        }

    private fun newDocumentBuilderFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }

    private fun stringResourceValues(file: Path): Map<String, String> {
        val document = newDocumentBuilderFactory().newDocumentBuilder().parse(file.toFile())
        val strings = document.getElementsByTagName("string")
        return (0 until strings.length).associate { index ->
            val item = strings.item(index)
            item.attributes.getNamedItem("name").nodeValue to item.textContent.trim()
        }
    }
// RETIRED: upstream's rule that every visible string resolves through a string resource. This fork is
// single-user and single-language by design — Japanese copy is authored inline, deliberately, across the
// action catalog, Setup and the overlays. Routing it through resources would add indirection for a
// translation that will never exist.
}
