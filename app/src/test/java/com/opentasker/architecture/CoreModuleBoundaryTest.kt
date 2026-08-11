package com.opentasker.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreModuleBoundaryTest {
    private val repoRoot: Path = listOf(Path.of("."), Path.of(".."))
        .map(Path::toAbsolutePath)
        .first { Files.exists(it.resolve("settings.gradle.kts")) }

    private fun source(path: String): String = repoRoot.resolve(path).readText()

    @Test
    fun settingsDeclaresCoreAndFeatureBoundaries() {
        val settings = source("settings.gradle.kts")
        listOf(
            "include(\":core:model\")",
            "include(\":core:common\")",
            "include(\":core:storage\")",
            "include(\":core:engine\")",
            "include(\":feature:automation\")",
        ).forEach { module -> assertTrue("Missing module declaration: $module", settings.contains(module)) }
    }

    @Test
    fun appDependsOnCoreAndFeatureModules() {
        val appBuild = source("app/build.gradle.kts")
        listOf(
            "project(\":core:model\")",
            "project(\":core:common\")",
            "project(\":core:storage\")",
            "project(\":core:engine\")",
            "project(\":feature:automation\")",
        ).forEach { dependency -> assertTrue("Missing app dependency: $dependency", appBuild.contains(dependency)) }
    }

    @Test
    fun coreAndFeatureBuildsDoNotDependBackOnTheApp() {
        listOf(
            "core/model/build.gradle.kts",
            "core/common/build.gradle.kts",
            "core/storage/build.gradle.kts",
            "core/engine/build.gradle.kts",
            "feature/automation/build.gradle.kts",
        ).forEach { buildPath ->
            assertFalse("$buildPath must not create an app-module cycle", source(buildPath).contains("project(\":app\")"))
        }
    }

    @Test
    fun appExcludesSourcesOwnedByCoreModules() {
        val appBuild = source("app/build.gradle.kts")
        listOf(
            "com/opentasker/core/model/ContextSpec.kt",
            "com/opentasker/core/logging/AppLogger.kt",
            "com/opentasker/core/storage/**",
            "com/opentasker/core/engine/ActiveExecutionRegistry.kt",
            "com/opentasker/core/engine/ExecutionEnvelope.kt",
        ).forEach { sourcePath ->
            assertTrue("App source exclusion missing: $sourcePath", appBuild.contains(sourcePath))
        }
    }

    @Test
    fun automationFeatureOwnsTheBlueprintFieldPresentation() {
        val featureSource = source("feature/automation/src/main/kotlin/com/opentasker/feature/automation/AutomationBlueprintInputField.kt")
        assertTrue(featureSource.contains("fun AutomationBlueprintInputField"))
        assertTrue(featureSource.contains("enum class AutomationInputKeyboard"))
        assertTrue(source("app/src/main/java/com/opentasker/ui/screens/EditorDialogs.kt").contains("AutomationBlueprintInputField"))
    }
}
