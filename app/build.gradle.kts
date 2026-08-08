import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Properties
import java.net.URLEncoder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class VerifyDocumentationTruthTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val readmeFile: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val currentDocumentation: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val historicalDocumentation: ConfigurableFileCollection

    @get:Input
    abstract val versionName: Property<String>

    @get:Input
    abstract val actionCount: Property<Int>

    @get:Input
    abstract val contextFamilyCount: Property<Int>

    @get:Input
    abstract val schemaVersion: Property<Int>

    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    @TaskAction
    fun verify() {
        val readme = readmeFile.get().asFile.readText()
        check("version-${versionName.get()}-blue.svg" in readme) {
            "README version badge does not match the current application version."
        }
        check("**${actionCount.get()} built-in actions**" in readme && "**${contextFamilyCount.get()} context families**" in readme) {
            "README capability counts do not match the current release contract."
        }

        val historicalFiles = historicalDocumentation.files
        val staleClaims = historicalFiles.flatMap { file ->
            val text = file.readText()
            val claims = buildList {
                Regex("\\bv\\d+\\.\\d+\\.\\d+\\b").findAll(text).mapTo(this) { it.value }
                Regex("\\b(?:Room schema:|schema v)(\\d+)\\b", RegexOption.IGNORE_CASE)
                    .findAll(text)
                    .mapTo(this) { "schema ${it.groupValues[1]}" }
                Regex("\\b(\\d+) built-in actions\\b").findAll(text)
                    .mapTo(this) { "${it.groupValues[1]} built-in actions" }
            }.distinct()
            claims.filterNot { claim ->
                claim == "v${versionName.get()}" ||
                    claim == "schema ${schemaVersion.get()}" ||
                    claim == "${actionCount.get()} built-in actions"
            }.map { claim -> "${file.relativeTo(repositoryRoot.get().asFile)}: $claim" }
        }
        staleClaims.forEach { claim -> logger.warn("Documentation freshness: historical claim detected: $claim") }

        historicalFiles
            .filter { it.name.contains("iter-1") }
            .forEach { file ->
                check("Historical research snapshot" in file.readText()) {
                    "Historical research file is missing its scope label: ${file.relativeTo(repositoryRoot.get().asFile)}"
                }
            }
        println(
            "Documentation truth passed: current release claims are checked; " +
                "${staleClaims.size} historical claims reported without blocking labeled snapshots.",
        )
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.baselineprofile)
}

val releaseKeystorePath = System.getenv("OPEN_TASKER_RELEASE_KEYSTORE")
val releaseKeystorePassword = System.getenv("OPEN_TASKER_RELEASE_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("OPEN_TASKER_RELEASE_KEY_ALIAS")
val releaseKeyPassword = System.getenv("OPEN_TASKER_RELEASE_KEY_PASSWORD")
val appVersionCode = 84
val appVersionName = "0.2.82"
// --- shiroikuma fork: per-build version tail ---
// forkVersionName = "<upstreamBase>.<base date>.g<base sha>+NNN", forkVersionCode = <upstreamCode>*10000 + N,
// where N = BUILD_NUMBER from gradle.properties (bumped every build, reset to 1 on upstream sync).
val forkBuildNumber = (project.findProperty("BUILD_NUMBER") as String?)?.trim()?.toIntOrNull() ?: 1

// `providers.exec`, NOT a raw ProcessBuilder: this build has Gradle's configuration cache on, which
// refuses an external process started at configuration time. The provider API is the supported form —
// it also registers the git output as a cache input, so the pin re-resolves when the base moves.
// isIgnoreExitValue: a repo with no local `master` (shallow clone, tarball) must degrade to an empty
// pin, never fail the build.
val repoRootDir = project.rootDir
fun gitOutput(vararg command: String): String = try {
    providers.exec {
        commandLine(*command)
        workingDir = repoRootDir
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
} catch (e: Exception) {
    println("Git command [${command.joinToString(" ")}] failed [$e]")
    ""
}

// shiroikuma fork: upstream-base pin. `custom` is rebased onto every upstream commit, so upstream's
// versionName stands still for months — the 0.2.79 line alone took 10 upstream commits without a
// bump. The sha is what says whether we are behind upstream. It is the merge-base of HEAD and master
// (the upstream mirror), i.e. the upstream commit our patches sit on, NOT our own HEAD, and NOT
// master's tip (which overstates it when custom is not yet rebased).
val upstreamBaseSha = gitOutput("git", "merge-base", "HEAD", "master").take(8)

// That same commit's date, so versions sort chronologically — a bare sha orders them at random.
// The commit's committer date, never build time: every build on one upstream base must share a pin.
val upstreamBaseDate = if (upstreamBaseSha.length == 8) {
    gitOutput("git", "show", "-s", "--format=%cd", "--date=format:%Y-%m-%d", upstreamBaseSha)
} else {
    ""
}

val upstreamPin = when {
    upstreamBaseSha.length != 8 -> ""
    upstreamBaseDate.length == 10 -> ".$upstreamBaseDate.g$upstreamBaseSha"
    else -> ".g$upstreamBaseSha"          // git present but the date lookup failed
}

// Zero-padded in the NAME only, so +002 sorts before +010. versionCode keeps the plain integer.
val paddedBuild = forkBuildNumber.toString().padStart(3, '0')
val forkVersionName = "$appVersionName$upstreamPin+$paddedBuild"
val forkVersionCode = appVersionCode * 10000 + forkBuildNumber

// --- shiroikuma fork: release signing from a gitignored keystore.properties ---
// (falls back to the upstream OPEN_TASKER_* env vars when the file is absent).
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use { load(it) }
}
val useKeystoreProperties = keystorePropertiesFile.exists()

val allowedDistributions = setOf("standard", "fdroid", "play")
val selectedDistribution = providers.gradleProperty("openTaskerDistribution")
    .orElse("standard")
    .get()
    .lowercase()
require(selectedDistribution in allowedDistributions) {
    "Unsupported OpenTasker distribution '$selectedDistribution'. Expected one of: ${allowedDistributions.joinToString()}."
}
val smsActionAvailable = selectedDistribution != "play"
val smsReceiveAvailable = selectedDistribution != "play"
val hasReleaseSigning = useKeystoreProperties || listOf(
    releaseKeystorePath,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "com.opentasker.app"
    compileSdk = 37
    buildToolsVersion = "36.0.0"
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "shiroikuma.jiyusagyoban"
        minSdk = 26
        targetSdk = 37
        versionCode = forkVersionCode
        versionName = forkVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "DISTRIBUTION", "\"$selectedDistribution\"")
        buildConfigField("Boolean", "SMS_ACTION_AVAILABLE", smsActionAvailable.toString())
        buildConfigField("Boolean", "SMS_RECEIVE_AVAILABLE", smsReceiveAvailable.toString())
        manifestPlaceholders["smsPermissionName"] = if (smsActionAvailable) "android.permission.SEND_SMS" else "android.permission.INTERNET"
        manifestPlaceholders["smsReceivePermissionName"] = if (smsReceiveAvailable) "android.permission.RECEIVE_SMS" else "android.permission.INTERNET"
        manifestPlaceholders["smsMmsPermissionName"] = if (smsReceiveAvailable) "android.permission.RECEIVE_MMS" else "android.permission.INTERNET"
        manifestPlaceholders["smsWapPushPermissionName"] = if (smsReceiveAvailable) "android.permission.RECEIVE_WAP_PUSH" else "android.permission.INTERNET"
        manifestPlaceholders["smsTriggerEnabled"] = smsReceiveAvailable.toString()
        manifestPlaceholders["phoneStatePermissionName"] = if (smsActionAvailable) "android.permission.READ_PHONE_STATE" else "android.permission.ACCESS_NETWORK_STATE"
        ndk {
            // The native key-grabber (libevgrab.so) ships arm64 only, matching our single-ABI APK.
            abiFilters += "arm64-v8a"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
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
                if (useKeystoreProperties) {
                    storeFile = file(keystoreProperties.getProperty("storeFile"))
                    storePassword = keystoreProperties.getProperty("storePassword")
                    keyAlias = keystoreProperties.getProperty("keyAlias")
                    keyPassword = keystoreProperties.getProperty("keyPassword")
                } else {
                    storeFile = file(releaseKeystorePath!!)
                    storePassword = releaseKeystorePassword
                    keyAlias = releaseKeyAlias
                    keyPassword = releaseKeyPassword
                }
            }
        } else {
            // Self-host signing identity, checked in deliberately. Distributed builds are
            // unsigned-by-policy in the sense that no private code-signing certificate is
            // acquired, but Android refuses to install an APK with no signature at all, so
            // published artifacts carry this repo-owned key. It lives in the repo rather than
            // in ~/.android/debug.keystore because that file is machine-global and gets
            // regenerated — which is exactly how the key that signed v0.2.79 was lost.
            create("selfhost") {
                storeFile = file("dev_keystore.jks")
                storePassword = "opentasker"
                keyAlias = "opentasker"
                keyPassword = "opentasker"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            isPseudoLocalesEnabled = true
            // Side by side with the installed release build, never on top of it.
            //
            // The release APK is signed with the fork's own key, so a debug APK sharing its
            // applicationId cannot update it — the install fails INSTALL_FAILED_UPDATE_INCOMPATIBLE,
            // and the only way through would be `adb uninstall`, which destroys the workspace
            // database. That made `connectedAndroidTest` unrunnable on a real phone: the one device
            // an instrumented test is worth running on is the one carrying the data.
            //
            // With a distinct id the two coexist and the release build is never touched.
            applicationIdSuffix = ".debug"
        }
    release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("selfhost")
        }
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
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
        aidl = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // Extract libevgrab.so to nativeLibraryDir so the Shizuku UserService (KeyGrabberService) can
        // System.load() it by absolute path from the privileged process.
        jniLibs.useLegacyPackaging = true
    }

    androidResources {
        // float32 ONNX weights barely compress, so deflating them costs build time and first-run CPU
        // for nothing. Stored entries also keep the asset extraction in OcrModels a straight copy.
        noCompress += "onnx"
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
    implementation(libs.androidx.documentfile)
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
    implementation(libs.androidx.profileinstaller)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    // On-device OCR (文字認識): PP-OCRv5 detection + recognition. Only the arm64 native library ships,
    // because defaultConfig.ndk.abiFilters already excludes the rest.
    implementation(libs.onnxruntime.android)
    implementation(libs.re2j)
    implementation(libs.jsoup)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    baselineProfile(project(":baselineprofile"))
    // On-device APK signing for the generated per-target share-relay APKs (core/share/relay).
    implementation(libs.apksig)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
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

abstract class VerifyNativePageAlignmentTask : org.gradle.api.DefaultTask() {
    @get:org.gradle.api.tasks.InputFile
    @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    abstract val apk: org.gradle.api.file.RegularFileProperty

    @org.gradle.api.tasks.TaskAction
    fun verify() {
        val apkFile = apk.get().asFile
        check(apkFile.isFile) { "APK for native page-alignment audit is missing: ${apkFile.absolutePath}" }

        ZipFile(apkFile).use { archive ->
            val nativeEntries = archive.entries().asSequence()
                .filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }
                .toList()
            if (nativeEntries.isEmpty()) {
                println("Native page-alignment/read-only audit passed: APK contains no native libraries.")
                return
            }

            // APK-backed native libraries are loaded from immutable package storage. A compressed
            // entry would be extracted to a mutable file before loading and cannot satisfy Android
            // 17's System.load read-only requirement without an explicit setReadOnly() step.
            val readOnlyViolations = nativeEntries
                .filter { entry -> entry.method != ZipEntry.STORED }
                .map { entry -> "${entry.name}: compressed native entry is not guaranteed read-only" }
            check(readOnlyViolations.isEmpty()) {
                "Android 17 read-only native-library audit failed:\n${readOnlyViolations.joinToString("\n")}"
            }

            val violations = nativeEntries.mapNotNull { entry ->
                check(entry.size <= MAX_NATIVE_LIBRARY_BYTES) {
                    "Native library ${entry.name} exceeds the audit size limit"
                }
                val bytes = archive.getInputStream(entry).use { it.readBytes() }
                val alignment = elfLoadAlignment(bytes)
                if (alignment < REQUIRED_PAGE_ALIGNMENT) {
                    "${entry.name}: PT_LOAD p_align=$alignment, required >= $REQUIRED_PAGE_ALIGNMENT"
                } else {
                    null
                }
            }
            check(violations.isEmpty()) {
                "16 KB native page-alignment audit failed:\n${violations.joinToString("\n")}"
            }
            println(
                "Native page-alignment/read-only audit passed: ${nativeEntries.size} libraries, " +
                    "all APK entries stored read-only, minimum PT_LOAD alignment >= " +
                    "$REQUIRED_PAGE_ALIGNMENT bytes.",
            )
        }
    }

    private fun elfLoadAlignment(bytes: ByteArray): Long {
        check(bytes.size >= 64 && bytes[0] == 0x7f.toByte() && bytes[1] == 'E'.code.toByte() &&
            bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte()) {
            "Native library is not a valid ELF object"
        }
        val elfClass = bytes[4].toInt()
        val littleEndian = bytes[5].toInt() == 1
        check(elfClass == 1 || elfClass == 2) { "Unsupported ELF class: $elfClass" }
        check(littleEndian || bytes[5].toInt() == 2) { "Unsupported ELF byte order" }

        fun number(offset: Int, width: Int): Long {
            var value = 0L
            if (littleEndian) {
                repeat(width) { index -> value = value or ((bytes[offset + index].toLong() and 0xff) shl (index * 8)) }
            } else {
                repeat(width) { index -> value = (value shl 8) or (bytes[offset + index].toLong() and 0xff) }
            }
            return value
        }

        val programHeaderOffset = number(if (elfClass == 1) 28 else 32, if (elfClass == 1) 4 else 8)
        val entrySize = number(if (elfClass == 1) 42 else 54, 2).toInt()
        val entryCount = number(if (elfClass == 1) 44 else 56, 2).toInt()
        val alignmentOffset = if (elfClass == 1) 28 else 48
        val typeOffset = 0
        var minimumAlignment = Long.MAX_VALUE
        var loadSegments = 0
        repeat(entryCount) { index ->
            val header = programHeaderOffset + index.toLong() * entrySize
            check(header + entrySize <= bytes.size) { "ELF program header table is truncated" }
            if (number(header.toInt() + typeOffset, 4) == PT_LOAD) {
                loadSegments += 1
                minimumAlignment = minOf(
                    minimumAlignment,
                    number(header.toInt() + alignmentOffset, if (elfClass == 1) 4 else 8),
                )
            }
        }
        check(loadSegments > 0) { "ELF object has no PT_LOAD segments" }
        return minimumAlignment
    }

    private companion object {
        const val REQUIRED_PAGE_ALIGNMENT = 16 * 1024L
        const val MAX_NATIVE_LIBRARY_BYTES = 100L * 1024 * 1024
        const val PT_LOAD = 1L
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
    minimumTests.set(858)
}

val qualityGateSeedFailure = providers.gradleProperty("openTaskerQualityGateSeedFailure")
    .map(String::toBoolean)
    .orElse(false)
val verifyQualityGateSeed = tasks.register<VerifyQualityGateSeedTask>("verifyQualityGateSeed") {
    group = "verification"
    description = "Provides an explicit seeded failure used to prove the local gate exits nonzero."
    seedFailure.set(qualityGateSeedFailure)
}

abstract class VerifyReleaseTruthTask : org.gradle.api.DefaultTask() {
    @get:org.gradle.api.tasks.InputFile
    @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    abstract val truthFile: org.gradle.api.file.RegularFileProperty

    @get:org.gradle.api.tasks.InputFile
    @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    abstract val readmeFile: org.gradle.api.file.RegularFileProperty

    @get:org.gradle.api.tasks.InputFile
    @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    abstract val metadataFile: org.gradle.api.file.RegularFileProperty

    @get:org.gradle.api.tasks.InputFile
    @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    abstract val moduleBuildFile: org.gradle.api.file.RegularFileProperty

    @get:org.gradle.api.tasks.InputFile
    @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    abstract val runtimeRegistriesFile: org.gradle.api.file.RegularFileProperty

    @get:org.gradle.api.tasks.InputFile
    @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    abstract val contextSpecFile: org.gradle.api.file.RegularFileProperty

    @get:org.gradle.api.tasks.InputFile
    @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    abstract val bundleFile: org.gradle.api.file.RegularFileProperty

    @get:org.gradle.api.tasks.Internal
    abstract val repositoryDirectory: org.gradle.api.file.DirectoryProperty

    @org.gradle.api.tasks.TaskAction
    fun verify() {
        val truth = truthFile.get().asFile.readText()
        fun truthValue(key: String): String = Regex("\\\"$key\\\"\\s*:\\s*(?:\\\"([^\\\"]+)\\\"|(\\d+))")
            .find(truth)
            ?.let { it.groupValues[1].ifBlank { it.groupValues[2] } }
            ?: error("Release truth manifest is missing '$key'.")
        fun requireTruth(key: String, expected: String) {
            check(truthValue(key) == expected) {
                "Release truth '$key' expected '$expected' but found '${truthValue(key)}'."
            }
        }
        fun sourceValue(text: String, pattern: String, name: String): String = Regex(pattern)
            .find(text)?.groupValues?.get(1)
            ?: error("Could not derive $name from shipped source.")

        val moduleSource = moduleBuildFile.get().asFile.readText()
        requireTruth("schemaVersion", "1")
        requireTruth("versionName", sourceValue(moduleSource, "val\\s+appVersionName\\s*=\\s*\"([^\"]+)\"", "version name"))
        requireTruth("versionCode", sourceValue(moduleSource, "val\\s+appVersionCode\\s*=\\s*(\\d+)", "version code"))
        requireTruth("minSdk", sourceValue(moduleSource, "(?m)^\\s*minSdk\\s*=\\s*(\\d+)", "minimum SDK"))
        requireTruth("compileSdk", sourceValue(moduleSource, "(?m)^\\s*compileSdk\\s*=\\s*(\\d+)", "compile SDK"))
        requireTruth("targetSdk", sourceValue(moduleSource, "(?m)^\\s*targetSdk\\s*=\\s*(\\d+)", "target SDK"))
        requireTruth("buildTools", sourceValue(moduleSource, "(?m)^\\s*buildToolsVersion\\s*=\\s*\"([^\"]+)\"", "build tools"))
        requireTruth("bundleSchemaVersion", sourceValue(bundleFile.get().asFile.readText(), "const val OPEN_TASKER_BUNDLE_SCHEMA_VERSION\\s*=\\s*(\\d+)", "bundle schema version"))
        requireTruth("roomSchemaVersion", sourceValue(moduleSource, "(?m)^\\s*val currentVersion\\s*=\\s*(\\d+)", "Room schema version"))

        val runtime = runtimeRegistriesFile.get().asFile.readText()
        requireTruth("registeredActions", Regex("(?m)^\\s+[A-Za-z0-9]+Action\\(\\),").findAll(runtime).count().toString())
        val contextBody = sourceValue(contextSpecFile.get().asFile.readText(), "(?s)enum class ContextType\\s*\\{(.*?)\\}", "context type enum")
        requireTruth("contextFamilies", Regex("(?m)^\\s+[A-Z][A-Z_]+\\s*(,|//)").findAll(contextBody).count().toString())

        val readme = readmeFile.get().asFile.readText()
        check("version-${truthValue("versionName")}-blue.svg" in readme) { "README version badge is stale." }
        check("### Actions (${truthValue("registeredActions")} registered + ${truthValue("engineHandledActions")} engine-handled)" in readme) { "README action count is stale." }
        check("**${truthValue("registeredActions")} built-in actions**" in readme) { "README built-in action count is stale." }
        check("- **${truthValue("contextFamilies")} context families**" in readme) { "README context-family count is stale." }

        val artifactCommit = truthValue("requiredArtifactCommit")
        check(Regex("[0-9a-f]{40}").matches(artifactCommit)) { "Release truth requiredArtifactCommit must be a full lowercase SHA-1." }
        val artifactGradle = git("show", "$artifactCommit:app/build.gradle.kts")
        check("val appVersionName = \"${truthValue("versionName")}\"" in artifactGradle) { "Required artifact commit has a different version." }
        check("val appVersionCode = ${truthValue("versionCode")}" in artifactGradle) { "Required artifact commit has a different version code." }

        val metadata = metadataFile.get().asFile.readText()
        fun metadataValue(key: String): String = Regex("(?m)^\\s*(?:-\\s*)?$key:\\s*(.+?)\\s*$")
            .find(metadata)?.groupValues?.get(1)?.trim()?.trim('"', '\'')
            ?: error("F-Droid metadata is missing '$key'.")
        check(metadataValue("versionName") == truthValue("versionName")) { "F-Droid versionName is stale." }
        check(metadataValue("versionCode") == truthValue("versionCode")) { "F-Droid versionCode is stale." }
        check(metadataValue("commit") == artifactCommit) { "F-Droid commit does not match release truth." }
        println("Release truth passed for v${truthValue("versionName")} (${truthValue("versionCode")}); artifact $artifactCommit")
    }

    private fun git(vararg args: String): String {
        val process = ProcessBuilder("git", *args)
            .directory(repositoryDirectory.get().asFile)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor() == 0) { "Git ${args.joinToString(" ")} failed: $output" }
        return output
    }
}

tasks.register("verifyRoomSchema") {
    group = "verification"
    description = "Checks that all Room schema versions up to the current are exported and tracked."

    dependsOn("kspDebugKotlin")
    val schemaDir = file("$projectDir/schemas/com.opentasker.core.storage.AppDatabase")
    inputs.dir(schemaDir)

    doLast {
        check(schemaDir.isDirectory) { "Room schema directory missing: $schemaDir" }
        val currentVersion = 10
        val missing = (1..currentVersion).filter { !File(schemaDir, "$it.json").isFile }
        check(missing.isEmpty()) {
            "Room schema files missing for version(s): ${missing.joinToString()}. Run a build to regenerate, then commit."
        }
        println("Room schema drift gate passed: versions 1..$currentVersion present.")
    }
}

val verifyPerformanceEvidence = tasks.register("verifyPerformanceEvidence") {
    group = "verification"
    description = "Checks the baseline-profile artifact and macrobenchmark evidence harness."
    dependsOn("compileReleaseArtProfile", ":baselineprofile:compileBenchmarkReleaseKotlin")

    val profileFile = layout.projectDirectory.file("src/main/baseline-prof.txt")
    val baselineSource = rootProject.layout.projectDirectory.file(
        "baselineprofile/src/main/java/com/opentasker/baselineprofile/OpenTaskerBaselineProfile.kt",
    )
    val macrobenchmarkSource = rootProject.layout.projectDirectory.file(
        "baselineprofile/src/main/java/com/opentasker/baselineprofile/OpenTaskerMacrobenchmark.kt",
    )
    inputs.files(profileFile, baselineSource, macrobenchmarkSource)

    if (providers.gradleProperty("openTaskerRequirePerformanceRun").orNull?.toBoolean() == true) {
        dependsOn("generateBaselineProfile")
    }

    doLast {
        val profile = profileFile.asFile.readLines().map(String::trim).filter(String::isNotEmpty)
        check(profile.any { it == "Lcom/opentasker/app/MainActivity;" }) {
            "Baseline profile must include the launcher activity class rule."
        }
        check(profile.any { it == "Lcom/opentasker/app/OpenTaskerApp_NoHilt;" }) {
            "Baseline profile must include the application class rule."
        }

        val baselineSourceText = baselineSource.asFile.readText()
        check("BaselineProfileRule" in baselineSourceText && "startActivityAndWait" in baselineSourceText) {
            "Baseline profile generator must exercise cold start."
        }
        val macrobenchmarkSourceText = macrobenchmarkSource.asFile.readText()
        check("StartupTimingMetric" in macrobenchmarkSourceText && "FrameTimingMetric" in macrobenchmarkSourceText) {
            "Macrobenchmark suite must cover startup and first-navigation timing."
        }
        println(
            "Performance evidence harness passed: ${profile.size} profile rules; " +
                "API 35+ device evidence is collected explicitly with :app:generateBaselineProfile " +
                "and :baselineprofile:connectedBenchmarkReleaseAndroidTest.",
        )
    }
}

// The capability counts, derived from source exactly as verifyReleaseTruth derives them.
//
// verifyDocumentationTruth used to carry `actionCount.set(74)` as a literal while verifyReleaseTruth
// recomputed 168 from the registry, so the two gates demanded contradictory README text and no README
// could satisfy both — which is why the documentation gate had been red for a long time. One source now.
val registeredActionCount = Regex("(?m)^\\s+[A-Za-z0-9]+Action\\(\\),")
    .findAll(rootProject.file("app/src/main/java/com/opentasker/core/RuntimeRegistries.kt").readText())
    .count()
val contextFamilyCountFromSource = Regex("(?s)enum class ContextType\\s*\\{(.*?)\\}")
    .find(rootProject.file("app/src/main/java/com/opentasker/core/model/ContextSpec.kt").readText())
    ?.groupValues?.get(1)
    ?.let { body -> Regex("(?m)^\\s+[A-Z][A-Z_]+\\s*(,|//)").findAll(body).count() }
    ?: error("could not count the ContextType families")

val verifyDocumentationTruth = tasks.register<VerifyDocumentationTruthTask>("verifyDocumentationTruth") {
    group = "verification"
    description = "Checks current release claims and reports stale local historical documentation claims."

    val repositoryRootPath = rootProject.layout.projectDirectory.asFile
    val readmeFilePath = repositoryRootPath.resolve("README.md")
    val currentDocumentationPaths = listOf(
        readmeFilePath,
        repositoryRootPath.resolve("CHANGELOG.md"),
        repositoryRootPath.resolve("tools/release-truth.json"),
    )
    val historicalDocumentationPaths = listOf(
        repositoryRootPath.resolve("CLAUDE.md"),
        repositoryRootPath.resolve("docs/research/raw-research-output.txt"),
        repositoryRootPath.resolve("docs/research/iter-1-roadmap-recommendations.md"),
    ).filter(File::isFile)
    readmeFile.set(readmeFilePath)
    currentDocumentation.from(currentDocumentationPaths)
    historicalDocumentation.from(historicalDocumentationPaths)
    versionName.set(appVersionName)
    actionCount.set(registeredActionCount)
    contextFamilyCount.set(contextFamilyCountFromSource)
    schemaVersion.set(10)
    repositoryRoot.set(repositoryRootPath)
}

tasks.register<VerifyReleaseTruthTask>("verifyReleaseTruth") {
    truthFile.set(rootProject.layout.projectDirectory.file("tools/release-truth.json"))
    readmeFile.set(rootProject.layout.projectDirectory.file("README.md"))
    metadataFile.set(rootProject.layout.projectDirectory.file("fdroid/metadata/com.opentasker.app.yml"))
    moduleBuildFile.set(layout.projectDirectory.file("build.gradle.kts"))
    runtimeRegistriesFile.set(layout.projectDirectory.file("src/main/java/com/opentasker/core/RuntimeRegistries.kt"))
    contextSpecFile.set(layout.projectDirectory.file("src/main/java/com/opentasker/core/model/ContextSpec.kt"))
    bundleFile.set(layout.projectDirectory.file("src/main/java/com/opentasker/core/transfer/OpenTaskerBundle.kt"))
    repositoryDirectory.set(rootProject.layout.projectDirectory)
}

tasks.register("verifyFdroidReadiness") {
    group = "verification"
    description = "Checks the F-Droid distribution profile for known proprietary dependency families."

    // Read at CONFIGURATION time. Touching a script-level val (or declaring a local `fun`) inside
    // doLast makes the task capture the script object, which the configuration cache cannot serialize —
    // the checks all passed while the build failed on that alone.
    val coordinates = releaseRuntimeCoordinates
    val distribution = selectedDistribution
    val distributions = allowedDistributions

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
        val forbidden = coordinates.get()
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
        check(distribution in distributions)
        println("F-Droid readiness check passed for distribution=$distribution")
    }
}

tasks.register("verifyFdroidMetadata") {
    group = "verification"
    description = "Checks that draft fdroiddata metadata matches the current release contract."
    dependsOn("verifyReleaseTruth")

    // Resolved at CONFIGURATION time and closed over as plain values. Reaching for `rootProject`
    // (or any script reference) inside doLast serializes a Project into the configuration cache,
    // which Gradle refuses — every check here passed while the build still failed on that alone.
    val metadataFile = rootProject.file("fdroid/metadata/com.opentasker.app.yml")
    val metadataLabel = metadataFile.relativeTo(rootProject.projectDir).path
    val expectedVersionName = appVersionName
    val expectedVersionCode = appVersionCode.toString()
    val repositoryDir = rootProject.projectDir
    val gitDir = rootProject.file(".git")
    inputs.file(metadataFile)

    doLast {
        check(metadataFile.isFile) { "Missing F-Droid metadata at $metadataLabel" }

        val metadata = metadataFile.readText()
        // Lambdas, not local `fun`s: a local function inside doLast compiles to a method on the build
        // script and drags the whole script into the configuration cache.
        val valuesFor: (String) -> List<String> = { key ->
            Regex("""(?m)^\s*(?:-\s*)?$key:\s*(.+?)\s*$""")
                .findAll(metadata)
                .map { match -> match.groupValues[1].trim().trim('"', '\'') }
                .toList()
        }
        val requireValue: (String, String) -> Unit = { key, expected ->
            val values = valuesFor(key)
            check(expected in values) {
                "F-Droid metadata key '$key' expected '$expected' but found ${values.ifEmpty { listOf("<missing>") }}"
            }
        }

        requireValue("versionName", expectedVersionName)
        requireValue("versionCode", expectedVersionCode)
        requireValue("CurrentVersion", expectedVersionName)
        requireValue("CurrentVersionCode", expectedVersionCode)
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

        // ProcessBuilder is fine HERE — this is execution time, not configuration time. What is not
        // fine is reaching for `rootProject` to find the directory; both paths are captured above.
        if (gitDir.exists()) {
            val process = ProcessBuilder("git", "cat-file", "-e", "$releaseCommit^{commit}")
                .directory(repositoryDir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()
            check(exitCode == 0) {
                "F-Droid metadata commit $releaseCommit is not present in this checkout. $output"
            }
        }

        println("F-Droid metadata check passed for v$expectedVersionName ($expectedVersionCode)")
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
        "verifyReleaseTruth",
        verifyResolvedDependencyPolicy,
        generateCycloneDxSbom,
        verifyJvmTestCount,
        verifyQualityGateSeed,
        "verifyNativePageAlignment",
        verifyPerformanceEvidence,
        verifyDocumentationTruth,
    )
}

tasks.register<VerifyNativePageAlignmentTask>("verifyNativePageAlignment") {
    group = "verification"
    description = "Checks that packaged native ELFs are read-only and have 16 KB PT_LOAD alignment."
    dependsOn("packageDebug")
    apk.set(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
}

// --- shiroikuma fork: archive naming + one-shot build task ---
base {
    archivesName = "shiroikuma-jiyusagyoban_${forkVersionName}_arm64-v8a"
}

tasks.register("buildFork") {
    group = "build"
    description = "Build the signed release APK, copy it to ~/tmp, and bump BUILD_NUMBER for next time."
    dependsOn("assembleRelease")
    // Configuration-cache-safe: capture every project-derived value HERE (configuration time) —
    // the doLast lambda must not touch `layout` / `rootProject` / other project services.
    val apkName = "shiroikuma-jiyusagyoban_${forkVersionName}_arm64-v8a.apk"
    val outputDirProvider = layout.buildDirectory.dir("outputs/apk/release")
    val propsFile = rootProject.file("gradle.properties")
    val versionCode = forkVersionCode
    val nextBuildNumber = forkBuildNumber + 1
    doLast {
        val outputDir = outputDirProvider.get().asFile
        val targetDir = File(System.getProperty("user.home"), "tmp").apply { mkdirs() }
        val apk = outputDir.listFiles { _, name -> name.endsWith(".apk") }?.firstOrNull()
            ?: throw GradleException("No APK found in $outputDir")
        val target = File(targetDir, apkName)
        apk.copyTo(target, overwrite = true)
        println("\u001b[1;36m>>> ${target.absolutePath}\u001b[0m")
        println("\u001b[1;36m>>> versionCode $versionCode\u001b[0m")

        // Auto-increment BUILD_NUMBER for the next build.
        val text = propsFile.readText()
        propsFile.writeText(
            if (Regex("(?m)^BUILD_NUMBER=").containsMatchIn(text))
                text.replace(Regex("(?m)^BUILD_NUMBER=.*$"), "BUILD_NUMBER=$nextBuildNumber")
            else text.trimEnd() + "\n\n# shiroikuma fork: per-build version tail\nBUILD_NUMBER=$nextBuildNumber\n"
        )
        println("\u001b[1;36m>>> BUILD_NUMBER bumped to $nextBuildNumber\u001b[0m")
    }
}

// ---------------------------------------------------------------------------------------------
// 文字認識 (OCR) models — PP-OCRv5, Apache-2.0.
//
// Kept OUT of git (see .gitignore): ~100 MB of ONNX weights would bloat every clone forever, and the
// recognition model sits close enough to GitHub's 100 MB hard per-file limit to be fragile. Instead
// they are fetched once per machine and verified by SHA-256, so a tampered or truncated download
// fails the build rather than silently producing garbage text.
//
// The URLs are pinned to immutable commit revisions. BEFORE THE FIRST GITHUB RELEASE, re-upload these
// exact blobs as assets on a tag of our own repo and point `url` there — an upstream deletion must not
// be able to break our builds.
// ---------------------------------------------------------------------------------------------
val ocrAssetsDir = layout.projectDirectory.dir("src/main/assets/ocr")

data class OcrModelAsset(val name: String, val url: String, val sha256: String)

val ocrModelAssets = listOf(
    // PP-OCRv5_mobile_det — shared by every script. The server detector is 18x the size and buys
    // nothing on the high-contrast text a screenshot is made of.
    OcrModelAsset(
        "det.onnx",
        "https://huggingface.co/bukuroo/PPOCRv5-ONNX/resolve/47b3e1b4e90c79737cb71f562a6c85809067c7a5/ppocrv5-mobile-det.onnx",
        "d7fe3ea74652890722c0f4d02458b7261d9f5ae6c92904d05707c9eb155c7924",
    ),
    // PP-OCRv5_server_rec — Chinese + Japanese + English in one model, so the default script needs no
    // switching. 白い熊 chose the server tier over mobile on 2026-08-08.
    OcrModelAsset(
        "rec_jpn.onnx",
        "https://huggingface.co/bukuroo/PPOCRv5-ONNX/resolve/47b3e1b4e90c79737cb71f562a6c85809067c7a5/ppocrv5-server-rec.onnx",
        "bcdc8836fe5fb70f18c768f2b99cc37f5d45705a9dc78e65f6dc3af869cb2e40",
    ),
    // PP-OCRv5_mobile_rec — the same three languages, a fifth of the size and ~2.5x faster. Measured on
    // the Phase 0 corpus it was not worse on clean screenshot text (1.24% CER against the server model's
    // 1.35%); the server model's headroom is for photographed and handwritten text. Both ship so the
    // choice is a setting rather than a build (白い熊, 2026-08-08). Shares the dictionary above.
    OcrModelAsset(
        "rec_jpn_mobile.onnx",
        "https://huggingface.co/bukuroo/PPOCRv5-ONNX/resolve/47b3e1b4e90c79737cb71f562a6c85809067c7a5/ppocrv5-mobile-rec.onnx",
        "bf66820f48fa99f779974c4df78e5274a9d8e0458c4137e8c5357e40e2c3faf2",
    ),
    // NOTE: this dictionary is CRLF. OcrCharset strips the trailing '\r'; without that every
    // recognised character carries one.
    OcrModelAsset(
        "dict_jpn.txt",
        "https://huggingface.co/bukuroo/PPOCRv5-ONNX/resolve/47b3e1b4e90c79737cb71f562a6c85809067c7a5/ppocrv5_dict.txt",
        "1ea29636956177e400af712d9782e7693f3fb25f98617bed10479d2965a836fd",
    ),
    // latin_PP-OCRv5_mobile_rec — German, Czech, Polish (44 Latin-script languages).
    OcrModelAsset(
        "rec_latin.onnx",
        "https://huggingface.co/monkt/paddleocr-onnx/resolve/7b02d0a30a07ba2b92ad1ff5a8941ae2c633de65/languages/latin/rec.onnx",
        "614ffc2d6d3902d360fad7f1b0dd455ee45e877069d14c4e51a99dc4ef144409",
    ),
    OcrModelAsset(
        "dict_latin.txt",
        "https://huggingface.co/monkt/paddleocr-onnx/resolve/7b02d0a30a07ba2b92ad1ff5a8941ae2c633de65/languages/latin/dict.txt",
        "3c0a8a79b612653c25f765271714f71281e4e955962c153e272b7b8c1d2b13ff",
    ),
    // eslav_PP-OCRv5_mobile_rec — Russian specifically (East Slavic), tuned tighter than the generic
    // 34-language Cyrillic model.
    OcrModelAsset(
        "rec_eslav.onnx",
        "https://huggingface.co/monkt/paddleocr-onnx/resolve/7b02d0a30a07ba2b92ad1ff5a8941ae2c633de65/languages/eslav/rec.onnx",
        "dc6bf0e855247decce214ba6dae5bc135fa0ad725a5918a7fcfb59fad6c9cdee",
    ),
    OcrModelAsset(
        "dict_eslav.txt",
        "https://huggingface.co/monkt/paddleocr-onnx/resolve/7b02d0a30a07ba2b92ad1ff5a8941ae2c633de65/languages/eslav/dict.txt",
        "3e95f1581557162870cacdba5af91a4c6be2890710d395b0c3c7578e7ee5e6eb",
    ),
)

val downloadOcrModels = tasks.register("downloadOcrModels") {
    group = "build setup"
    description = "Fetch the pinned PP-OCRv5 ONNX models into src/main/assets/ocr (SHA-256 verified)."

    // Captured at configuration time so the task action closes over nothing but serializable values —
    // the configuration cache stays valid.
    val targetDir = ocrAssetsDir.asFile
    val assets = ocrModelAssets
    outputs.files(assets.map { File(targetDir, it.name) })
    outputs.upToDateWhen { assets.all { File(targetDir, it.name).isFile } }

    doLast {
        fun digestOf(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        targetDir.mkdirs()
        assets.forEach { asset ->
            val file = File(targetDir, asset.name)
            if (file.isFile && digestOf(file) == asset.sha256) return@forEach

            println(">>> OCR model: fetching ${asset.name}")
            val temporary = File(targetDir, "${asset.name}.part")
            URI(asset.url).toURL().openStream().use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            val actual = digestOf(temporary)
            if (actual != asset.sha256) {
                temporary.delete()
                throw GradleException(
                    "OCR model ${asset.name} failed verification.\n" +
                        "  expected ${asset.sha256}\n  actual   $actual"
                )
            }
            temporary.renameTo(file)
        }
    }
}

tasks.named("preBuild") { dependsOn(downloadOcrModels) }
