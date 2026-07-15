import java.net.URLEncoder

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.ksp)
}

val releaseKeystorePath = System.getenv("OPEN_TASKER_RELEASE_KEYSTORE")
val releaseKeystorePassword = System.getenv("OPEN_TASKER_RELEASE_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("OPEN_TASKER_RELEASE_KEY_ALIAS")
val releaseKeyPassword = System.getenv("OPEN_TASKER_RELEASE_KEY_PASSWORD")
val appVersionCode = 77
val appVersionName = "0.2.75"
val allowedDistributions = setOf("standard", "fdroid", "play")
val selectedDistribution = providers.gradleProperty("openTaskerDistribution")
    .orElse("standard")
    .get()
    .lowercase()
require(selectedDistribution in allowedDistributions) {
    "Unsupported OpenTasker distribution '$selectedDistribution'. Expected one of: ${allowedDistributions.joinToString()}."
}
val smsActionAvailable = selectedDistribution != "play"
val hasReleaseSigning = listOf(
    releaseKeystorePath,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "com.opentasker.app"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.opentasker.app"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "DISTRIBUTION", "\"$selectedDistribution\"")
        buildConfigField("Boolean", "SMS_ACTION_AVAILABLE", smsActionAvailable.toString())
        manifestPlaceholders["smsPermissionName"] = if (smsActionAvailable) "android.permission.SEND_SMS" else "android.permission.INTERNET"
        manifestPlaceholders["phoneStatePermissionName"] = if (smsActionAvailable) "android.permission.READ_PHONE_STATE" else "android.permission.ACCESS_NETWORK_STATE"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    lint {
        abortOnError = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    sourceSets {
        getByName("androidTest").assets.directories.add("$projectDir/schemas")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.re2j)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    testImplementation(libs.junit)
    testImplementation("androidx.work:work-testing:2.11.2")
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

abstract class VerifyResolvedDependencyPolicyTask : org.gradle.api.DefaultTask() {
    @get:org.gradle.api.tasks.Input
    abstract val components: org.gradle.api.provider.ListProperty<String>

    @get:org.gradle.api.tasks.InputFile
    @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    abstract val versionCatalog: org.gradle.api.file.RegularFileProperty

    @get:org.gradle.api.tasks.InputFile
    @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    abstract val moduleBuildFile: org.gradle.api.file.RegularFileProperty

    @get:org.gradle.api.tasks.InputFile
    @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    abstract val settingsFile: org.gradle.api.file.RegularFileProperty

    @get:org.gradle.api.tasks.InputFile
    @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    abstract val verificationMetadata: org.gradle.api.file.RegularFileProperty

    @org.gradle.api.tasks.TaskAction
    fun verify() {
        val resolved = components.get().distinct().sorted()
        check(resolved.isNotEmpty()) { "Release runtime dependency graph is empty." }

        val invalidResolved = resolved.filter { coordinate ->
            val version = coordinate.split(':', limit = 3).getOrNull(2).orEmpty()
            version.isBlank() || version.isDynamicVersion()
        }
        check(invalidResolved.isEmpty()) {
            "Resolved dependency graph contains missing or dynamic versions: ${invalidResolved.joinToString()}"
        }

        val catalogVersions = Regex("""(?m)^\s*[A-Za-z0-9_.-]+\s*=\s*"([^"]+)"\s*$""")
            .findAll(versionCatalog.get().asFile.readText())
            .map { match -> match.groupValues[1] }
        val buildVersions = Regex("""["'](?:[A-Za-z0-9_.-]+):(?:[A-Za-z0-9_.-]+):([^"']+)["']""")
            .findAll(moduleBuildFile.get().asFile.readText())
            .map { match -> match.groupValues[1] }
        val invalidDeclared = (catalogVersions + buildVersions)
            .filter { version -> version.isDynamicVersion() }
            .distinct()
            .sorted()
            .toList()
        check(invalidDeclared.isEmpty()) {
            "Dynamic or snapshot dependency declarations are forbidden: ${invalidDeclared.joinToString()}"
        }

        val settings = settingsFile.get().asFile.readText()
        check("RepositoriesMode.FAIL_ON_PROJECT_REPOS" in settings) {
            "Dependency repositories must remain centralized with FAIL_ON_PROJECT_REPOS."
        }
        val repositoryBlocks = Regex(
            """repositories\s*\{([^{}]*)}""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        ).findAll(settings).map { match -> match.groupValues[1] }.toList()
        check(repositoryBlocks.size == 2) {
            "Expected exactly the centralized plugin and dependency repository blocks."
        }
        val allowedRepositoryCalls = setOf("google()", "mavenCentral()", "gradlePluginPortal()")
        val repositoryCalls = repositoryBlocks.flatMap { block ->
            block.lineSequence()
                .map { line -> line.substringBefore("//").trim() }
                .filter(String::isNotEmpty)
                .toList()
        }
        val forbiddenRepositoryCalls = repositoryCalls.filterNot { call -> call in allowedRepositoryCalls }
        check(forbiddenRepositoryCalls.isEmpty()) {
            "Only google(), mavenCentral(), and gradlePluginPortal() repositories are allowed; found ${forbiddenRepositoryCalls.joinToString()}."
        }

        val verification = verificationMetadata.get().asFile.readText()
        check("<verify-metadata>true</verify-metadata>" in verification && "<sha256 value=" in verification) {
            "Gradle dependency verification metadata must enforce SHA-256 checksums."
        }
        println("Resolved dependency policy passed for ${resolved.size} release runtime components.")
    }

    private fun String.isDynamicVersion(): Boolean {
        val normalized = lowercase()
        return '+' in this || normalized.startsWith("latest.") || normalized.endsWith("-snapshot") ||
            startsWith('[') || startsWith('(')
    }
}

abstract class GenerateCycloneDxSbomTask : org.gradle.api.DefaultTask() {
    @get:org.gradle.api.tasks.Input
    abstract val components: org.gradle.api.provider.ListProperty<String>

    @get:org.gradle.api.tasks.Input
    abstract val applicationVersion: org.gradle.api.provider.Property<String>

    @get:org.gradle.api.tasks.OutputFile
    abstract val outputFile: org.gradle.api.file.RegularFileProperty

    @org.gradle.api.tasks.TaskAction
    fun generate() {
        val entries = components.get()
            .distinct()
            .sorted()
            .map { coordinate ->
                val (group, name, version) = coordinate.split(':', limit = 3)
                val purl = "pkg:maven/$group/$name@${URLEncoder.encode(version, Charsets.UTF_8.name()).replace("+", "%20")}"
                """    {"type":"library","bom-ref":${purl.json()},"group":${group.json()},"name":${name.json()},"version":${version.json()},"purl":${purl.json()}}"""
            }
        val version = applicationVersion.get()
        val appPurl = "pkg:generic/OpenTasker@$version"
        val output = outputFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            buildString {
                appendLine("{")
                appendLine("  \"\$schema\": \"https://cyclonedx.org/schema/bom-1.6.schema.json\",")
                appendLine("  \"bomFormat\": \"CycloneDX\",")
                appendLine("  \"specVersion\": \"1.6\",")
                appendLine("  \"version\": 1,")
                appendLine("  \"metadata\": {\"component\": {\"type\":\"application\",\"bom-ref\":${appPurl.json()},\"group\":\"com.opentasker\",\"name\":\"OpenTasker\",\"version\":${version.json()},\"purl\":${appPurl.json()}}},")
                appendLine("  \"components\": [")
                appendLine(entries.joinToString(",\n"))
                appendLine("  ]")
                appendLine("}")
            }
        )
        println("CycloneDX SBOM wrote ${entries.size} components to ${output.absolutePath}")
    }

    private fun String.json(): String = buildString {
        append('"')
        this@json.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }
}

abstract class VerifyJvmTestCountTask : org.gradle.api.DefaultTask() {
    @get:org.gradle.api.tasks.InputDirectory
    @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    abstract val resultsDirectory: org.gradle.api.file.DirectoryProperty

    @get:org.gradle.api.tasks.Input
    abstract val minimumTests: org.gradle.api.provider.Property<Int>

    @org.gradle.api.tasks.TaskAction
    fun verify() {
        val reports = resultsDirectory.get().asFile.listFiles { file ->
            file.isFile && file.name.startsWith("TEST-") && file.extension == "xml"
        }.orEmpty()
        check(reports.isNotEmpty()) { "No JVM test XML reports were produced." }
        fun count(attribute: String): Int = reports.sumOf { report ->
            Regex("""\b$attribute="(\d+)"""").find(report.readText())?.groupValues?.get(1)?.toInt() ?: 0
        }
        val tests = count("tests")
        val failures = count("failures")
        val errors = count("errors")
        check(failures == 0 && errors == 0) { "JVM tests reported $failures failure(s) and $errors error(s)." }
        check(tests >= minimumTests.get()) {
            "JVM test floor regressed: found $tests, expected at least ${minimumTests.get()}."
        }
        println("JVM test floor passed: $tests tests, 0 failures, 0 errors.")
    }
}

abstract class VerifyQualityGateSeedTask : org.gradle.api.DefaultTask() {
    @get:org.gradle.api.tasks.Input
    abstract val seedFailure: org.gradle.api.provider.Property<Boolean>

    @org.gradle.api.tasks.TaskAction
    fun verify() {
        check(!seedFailure.get()) { "Seeded local quality-gate failure." }
    }
}

val releaseRuntimeCoordinates = providers.provider {
    val configuration = configurations.single { candidate ->
        candidate.isCanBeResolved && candidate.name.equals("releaseRuntimeClasspath", ignoreCase = true)
    }
    configuration.incoming.resolutionResult.allComponents.mapNotNull { component ->
        val id = component.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier
        id?.let { "${it.group}:${it.module}:${it.version}" }
    }
}

val verifyResolvedDependencyPolicy = tasks.register<VerifyResolvedDependencyPolicyTask>("verifyResolvedDependencyPolicy") {
    group = "verification"
    description = "Checks the resolved release graph, fixed versions, repositories, and checksum policy."
    components.set(releaseRuntimeCoordinates)
    versionCatalog.set(rootProject.layout.projectDirectory.file("gradle/libs.versions.toml"))
    moduleBuildFile.set(layout.projectDirectory.file("build.gradle.kts"))
    settingsFile.set(rootProject.layout.projectDirectory.file("settings.gradle.kts"))
    verificationMetadata.set(rootProject.layout.projectDirectory.file("gradle/verification-metadata.xml"))
}

val generateCycloneDxSbom = tasks.register<GenerateCycloneDxSbomTask>("generateCycloneDxSbom") {
    group = "reporting"
    description = "Writes a deterministic CycloneDX SBOM from the resolved release runtime graph."
    components.set(releaseRuntimeCoordinates)
    applicationVersion.set(appVersionName)
    outputFile.set(rootProject.layout.buildDirectory.file("reports/opentasker/sbom.cdx.json"))
}

val verifyJvmTestCount = tasks.register<VerifyJvmTestCountTask>("verifyJvmTestCount") {
    group = "verification"
    description = "Fails if the passing JVM test count drops below the release floor."
    dependsOn("testDebugUnitTest")
    resultsDirectory.set(layout.buildDirectory.dir("test-results/testDebugUnitTest"))
    minimumTests.set(522)
}

val qualityGateSeedFailure = providers.gradleProperty("openTaskerQualityGateSeedFailure")
    .map(String::toBoolean)
    .orElse(false)
val verifyQualityGateSeed = tasks.register<VerifyQualityGateSeedTask>("verifyQualityGateSeed") {
    group = "verification"
    description = "Provides an explicit seeded failure used to prove the local gate exits nonzero."
    seedFailure.set(qualityGateSeedFailure)
}

tasks.register("verifyRoomSchema") {
    group = "verification"
    description = "Checks that all Room schema versions up to the current are exported and tracked."

    dependsOn("kspDebugKotlin")
    val schemaDir = file("$projectDir/schemas/com.opentasker.core.storage.AppDatabase")
    inputs.dir(schemaDir)

    doLast {
        check(schemaDir.isDirectory) { "Room schema directory missing: $schemaDir" }
        val currentVersion = 5
        val missing = (1..currentVersion).filter { !File(schemaDir, "$it.json").isFile }
        check(missing.isEmpty()) {
            "Room schema files missing for version(s): ${missing.joinToString()}. Run a build to regenerate, then commit."
        }
        println("Room schema drift gate passed: versions 1..$currentVersion present.")
    }
}

tasks.register("verifyFdroidReadiness") {
    group = "verification"
    description = "Checks the F-Droid distribution profile for known proprietary dependency families."

    doLast {
        val forbiddenGroups = setOf(
            "com.google.android.gms",
            "com.google.firebase",
            "com.android.billingclient",
            "com.facebook.android",
            "com.adjust.sdk",
        )
        val forbiddenNames = setOf(
            "play-services",
            "firebase",
            "billingclient",
            "crashlytics",
            "appsflyer",
        )
        val forbidden = releaseRuntimeCoordinates.get()
            .mapNotNull { coordinate ->
                val (group, name) = coordinate.split(':', limit = 3)
                val blockedGroup = forbiddenGroups.any { forbiddenGroup ->
                    group == forbiddenGroup || group.startsWith("$forbiddenGroup.")
                }
                val blocked = blockedGroup || forbiddenNames.any { token -> token in name.lowercase() }
                coordinate.takeIf { blocked }
            }
            .distinct()
            .sorted()

        check(forbidden.isEmpty()) {
            "F-Droid profile includes dependencies that need policy review: ${forbidden.joinToString()}"
        }
        check(selectedDistribution in allowedDistributions)
        println("F-Droid readiness check passed for distribution=$selectedDistribution")
    }
}

tasks.register("verifyFdroidMetadata") {
    group = "verification"
    description = "Checks that draft fdroiddata metadata matches the current release contract."

    val metadataFile = rootProject.file("fdroid/metadata/com.opentasker.app.yml")
    inputs.file(metadataFile)

    doLast {
        check(metadataFile.isFile) {
            "Missing F-Droid metadata at ${metadataFile.relativeTo(rootProject.projectDir)}"
        }

        val metadata = metadataFile.readText()
        fun valuesFor(key: String): List<String> =
            Regex("""(?m)^\s*(?:-\s*)?$key:\s*(.+?)\s*$""")
                .findAll(metadata)
                .map { match -> match.groupValues[1].trim().trim('"', '\'') }
                .toList()

        fun requireValue(key: String, expected: String) {
            val values = valuesFor(key)
            check(expected in values) {
                "F-Droid metadata key '$key' expected '$expected' but found ${values.ifEmpty { listOf("<missing>") }}"
            }
        }

        requireValue("versionName", appVersionName)
        requireValue("versionCode", appVersionCode.toString())
        requireValue("CurrentVersion", appVersionName)
        requireValue("CurrentVersionCode", appVersionCode.toString())
        requireValue("Changelog", "https://github.com/SysAdminDoc/OpenTasker/releases")

        val commits = valuesFor("commit")
        check(commits.size == 1) {
            "F-Droid metadata must contain exactly one release commit, found ${commits.size}"
        }
        val releaseCommit = commits.single()
        check(Regex("""[0-9a-f]{40}""").matches(releaseCommit)) {
            "F-Droid metadata commit must be a full 40-character lowercase SHA, found '$releaseCommit'"
        }
        check("openTaskerDistribution=fdroid" in metadata) {
            "F-Droid metadata must build with gradleprops openTaskerDistribution=fdroid"
        }
        check(":app:verifyFdroidReadiness" in metadata) {
            "F-Droid metadata must run :app:verifyFdroidReadiness before assembly"
        }
        check("app/build/outputs/apk/release/app-release-unsigned.apk" in metadata) {
            "F-Droid metadata must point to the unsigned release APK output"
        }

        if (rootProject.file(".git").exists()) {
            val process = ProcessBuilder("git", "cat-file", "-e", "$releaseCommit^{commit}")
                .directory(rootProject.projectDir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()
            check(exitCode == 0) {
                "F-Droid metadata commit $releaseCommit is not present in this checkout. $output"
            }
        }

        println("F-Droid metadata check passed for v$appVersionName ($appVersionCode)")
    }
}

tasks.register("verifyPlayManifestPolicy") {
    group = "verification"
    description = "Checks that the Play distribution merged manifest omits SMS and phone-state permissions."
    dependsOn("processReleaseMainManifest")

    doLast {
        check(selectedDistribution == "play") {
            "Run this task with -PopenTaskerDistribution=play"
        }
        val manifest = layout.buildDirectory
            .file("intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml")
            .get()
            .asFile
        check(manifest.isFile) {
            "Release merged manifest not found at ${manifest.relativeTo(projectDir)}"
        }
        val manifestText = manifest.readText()
        check("android.permission.SEND_SMS" !in manifestText) {
            "Play distribution merged manifest must not contain SEND_SMS"
        }
        check("android.permission.READ_PHONE_STATE" !in manifestText) {
            "Play distribution merged manifest must not contain READ_PHONE_STATE"
        }
        println("Play manifest policy check passed: SMS/phone-state permissions are absent.")
    }
}

tasks.register("localQualityGate") {
    group = "verification"
    description = "Runs the deterministic local debug-quality and dependency-report gate."
    dependsOn(
        "lintDebug",
        "compileDebugAndroidTestKotlin",
        "verifyRoomSchema",
        verifyResolvedDependencyPolicy,
        generateCycloneDxSbom,
        verifyJvmTestCount,
        verifyQualityGateSeed,
    )
}
