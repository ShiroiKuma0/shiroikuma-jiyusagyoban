package com.opentasker.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.readText

class LocalizationSourceTest {
    private val uiSourceRoot: Path = listOf(
        Path.of("src/main/java/com/opentasker/ui"),
        Path.of("app/src/main/java/com/opentasker/ui"),
    ).first(Files::exists)

    private val resRoot: Path = listOf(
        Path.of("src/main/res"),
        Path.of("app/src/main/res"),
    ).first(Files::exists)

    @Test
    fun coreUiScreensUseStringResourcesForVisibleCopy() {
        val localizedFiles = listOf(
            "components/PremiumComponents.kt",
            "screens/ActiveAutomationLists.kt",
            "screens/ActionEditorDialogs.kt",
            "screens/AutomationFlowScreen.kt",
            "screens/ContextEditorDialogs.kt",
            "screens/EditorDialogs.kt",
            "screens/SceneLibraryScreen.kt",
        )
        val forbiddenPatterns = mapOf(
            "Text literal" to Regex("""\bText\s*\(\s*""" + "\""),
            "Button text literal" to Regex("""\bButton\s*\([^)]*\)\s*\{\s*Text\s*\(\s*""" + "\"", RegexOption.DOT_MATCHES_ALL),
            "contentDescription literal" to Regex("""contentDescription\s*=\s*""" + "\""),
            "label text literal" to Regex("""label\s*=\s*\{\s*Text\s*\(\s*""" + "\""),
            "placeholder text literal" to Regex("""placeholder\s*=\s*\{\s*Text\s*\(\s*""" + "\""),
        )

        val offenders = localizedFiles.flatMap { relativePath ->
            val source = uiSourceRoot.resolve(relativePath).readText()
            forbiddenPatterns.mapNotNull { (name, pattern) ->
                if (pattern.containsMatchIn(source)) "$relativePath: $name" else null
            }
        }

        assertTrue(
            "Hardcoded user-facing Compose strings found; use stringResource/R.string instead: $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun localeDirectoriesRemainValidWeblateResourceTargets() {
        val sourceStrings = resRoot.resolve("values/strings.xml")
        assertTrue("Default string resource file is missing", Files.isRegularFile(sourceStrings))

        val localeFiles = Files.list(resRoot).use { paths ->
            paths
                .filter { Files.isDirectory(it) && it.fileName.toString().startsWith("values-") }
                .map { it.resolve("strings.xml") }
                .toList()
        }
        assertTrue("Expected locale resource directories for Weblate targets", localeFiles.isNotEmpty())

        val parserFactory = DocumentBuilderFactory.newInstance()
        val invalidFiles = (localeFiles + listOf(sourceStrings)).mapNotNull { file ->
            runCatching {
                val root = parserFactory.newDocumentBuilder().parse(file.toFile()).documentElement.nodeName
                if (root == "resources") null else "${resRoot.relativize(file)} root=$root"
            }.getOrElse { error -> "${resRoot.relativize(file)} ${error.message}" }
        }

        assertTrue("Invalid Android string resource XML: $invalidFiles", invalidFiles.isEmpty())
    }
}
