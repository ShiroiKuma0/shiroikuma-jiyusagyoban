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
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.testing.jacoco.tasks.JacocoReport
import com.opentasker.build.VerifyLocaleResourcesTask
import com.opentasker.build.VerifyReleaseTruthTask
import com.opentasker.build.VerifyRoomSchemaTask

// The fork's own floor, not upstream's 1200: this tree drops the upstream tests whose subject the
// fork does not ship (direct-boot setup UI, promoted notifications, dependency verification, the
// duplication and semantic-diff wiring) and adds its own for the band, OCR, charts and scenes.
// The fork's own floor, not upstream's 1200: this tree drops the upstream tests whose subject the
// fork does not ship (direct-boot setup UI, promoted notifications, dependency verification, the
// duplication and semantic-diff wiring) and adds its own for the band, OCR, charts and scenes.
// Kept close under the current count, as upstream keeps theirs: a floor far below it lets a large
// batch of tests be deleted silently, and the headroom only absorbs intentional consolidation.
private val JVM_TEST_FLOOR = 1324

private fun deriveSourceValue(file: java.io.File, pattern: String, name: String): String =
    Regex(pattern).find(file.readText())?.groupValues?.get(1)
        ?: error("Could not derive $name from ${file.path}.")

private fun deriveRegisteredActionCount(file: java.io.File): Int =
    Regex("(?m)^\\s*define\\(\\\"").findAll(file.readText()).count()

private fun deriveContextFamilyCount(file: java.io.File): Int {
    val body = deriveSourceValue(
        file,
        "(?s)enum class ContextType\\s*\\{(.*?)\\}",
        "context type enum",
    )
    return Regex("(?m)^\\s+[A-Z][A-Z_]+\\s*(,|//)").findAll(body).count()
}

private fun deriveRoomSchemaVersion(databaseFile: java.io.File): Int =
    deriveSourceValue(
        databaseFile,
        "(?m)^const val OPEN_TASKER_DATABASE_SCHEMA_VERSION\\s*=\\s*(\\d+)",
        "Room schema version",
    ).toInt()

private val LOCALE_COMPLETION_THRESHOLD = 0.80

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

        // These files were declared as inputs but never read, so the task invalidated its own
        // cache on every CHANGELOG edit while verifying nothing in it - and its description
        // claimed to check current release claims.
        val currentFiles = currentDocumentation.files.filter { it != readmeFile.get().asFile }
        currentFiles.forEach { file ->
            val relative = file.relativeTo(repositoryRoot.get().asFile)
            val text = file.readText()
            if (file.name == "CHANGELOG.md") {
                check("## v${versionName.get()}" in text) {
                    "$relative has no '## v${versionName.get()}' section for the current release."
                }
            }
            check(versionName.get() in text) {
                "$relative does not mention the current version ${versionName.get()}."
            }
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
    alias(libs.plugins.screenshot)
    jacoco
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

val releaseKeystorePath = System.getenv("OPEN_TASKER_RELEASE_KEYSTORE")
val releaseKeystorePassword = System.getenv("OPEN_TASKER_RELEASE_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("OPEN_TASKER_RELEASE_KEY_ALIAS")
val releaseKeyPassword = System.getenv("OPEN_TASKER_RELEASE_KEY_PASSWORD")
val appVersionCode = 88
val appVersionName = "0.2.86"
// F-Droid store listing limits, from the F-Droid build metadata reference.
val FDROID_SHORT_DESCRIPTION_MAX_CHARS = 80
val FDROID_CHANGELOG_MAX_CHARS = 500
val FDROID_MIN_SCREENSHOTS = 4
/** Store metadata files are LF regardless of the build host. */
val NEWLINE = 10.toChar().toString()

// --- shiroikuma fork: per-build version tail ---
// forkVersionName = "<upstreamBase>.<base date>.g<base sha>+NNN", forkVersionCode = <upstreamCode>*10000 + N,
// where N = BUILD_NUMBER from gradle.properties, bumped by every build.
//
// N runs MONOTONICALLY. It is reset to 1 only when appVersionCode itself moves — never merely because
// a sync moved the .g<sha> pin. An installer compares versionCode and nothing else; the date and sha
// in versionName are cosmetic. Since upstream leaves appVersionCode standing for months at a time
// (0.2.79 took ten commits without a bump; 0.2.82 is on its third sync), resetting on every sync made
// every sync a downgrade: 840030 installed, 840002 offered. buildFork enforces the invariant below.
val forkBuildNumber = (project.findProperty("BUILD_NUMBER") as String?)?.trim()?.toIntOrNull() ?: 1
val lastBuiltVersionCode = (project.findProperty("LAST_BUILT_VERSION_CODE") as String?)?.trim()?.toIntOrNull() ?: 0

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
/**
 * F-Droid signs the APKs it builds, so that distribution ships unsigned. This also decides the
 * artifact filename AGP produces, which the build recipe and reproducibility harness both name -
 * so the release signing config and the expected output path are derived from this one value.
 */
val releaseBuildIsUnsigned = selectedDistribution == "fdroid"
val expectedReleaseApkPath = if (releaseBuildIsUnsigned) {
    "app/build/outputs/apk/release/app-release-unsigned.apk"
} else {
    "app/build/outputs/apk/release/app-release.apk"
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
    experimentalProperties["android.experimental.enableScreenshotTest"] = true
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
            // F-Droid builds from source and applies its own signature, so that distribution must
            // stay unsigned — it is also what makes AGP name the artifact
            // `app-release-unsigned.apk`, the path the build recipe and the reproducibility
            // harness both expect. Every other distribution falls back to the repo-owned key.
            signingConfig = if (releaseBuildIsUnsigned) {
                null
            } else {
                signingConfigs.findByName("release") ?: signingConfigs.getByName("selfhost")
            }
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
        aidl = true
        compose = true
        buildConfig = true
        aidl = true
    }

    androidResources {
        generateLocaleConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // Extract libevgrab.so to nativeLibraryDir so the Shizuku UserService (KeyGrabberService) can
        // System.load() it by absolute path from the privileged process.
        jniLibs.useLegacyPackaging = true
    }

    sourceSets {
        getByName("androidTest").assets.directories.add("$projectDir/schemas")
    }
}

// Owned and compiled by the core/* modules; see the Android source-set filter above.
val MODULE_OWNED_SOURCES: List<String> = listOf(
            "com/opentasker/core/logging/AppLogger.kt",
            "com/opentasker/core/storage/**",
            "com/opentasker/core/engine/ActiveExecutionRegistry.kt",
            "com/opentasker/core/engine/AutomationLiveConditionState.kt",
            "com/opentasker/core/engine/CausalExecutionTracker.kt",
            "com/opentasker/core/engine/CooldownReservations.kt",
            "com/opentasker/core/engine/CooldownStore.kt",
            "com/opentasker/core/engine/EngineHeartbeatStore.kt",
            "com/opentasker/core/engine/ExecutionEnvelope.kt",
            "com/opentasker/core/engine/RunLogSource.kt",
            "com/opentasker/core/engine/TaskFailure.kt",
            "com/opentasker/core/model/ContextSpec.kt",
            "com/opentasker/core/model/Profile.kt",
            "com/opentasker/core/model/ProfileConcurrencyPolicy.kt",
            "com/opentasker/core/model/Project.kt",
            "com/opentasker/core/model/RunLogEntry.kt",
            "com/opentasker/core/model/Scene.kt",
            "com/opentasker/core/model/Task.kt",
            "com/opentasker/core/model/Variable.kt",
            "com/opentasker/core/model/VariableNamePolicy.kt",
)

// NOTE ON THE STAGED MODULE SPLIT
//
// The core/* modules point their source sets at files that still live under app/src/main/java,
// so :app and those modules compile the same sources. Neither
// `kotlin { sourceSets { configureEach { kotlin.exclude(...) } } }` nor a compile-task filter
// suppresses that under AGP's built-in Kotlin compilation — both were tried and both are inert,
// which is why MODULE_OWNED_SOURCES below documents intent rather than enforcing it.
//
// :app therefore holds the only copy of every core class that ships. Those module dependencies
// are compileOnly so their duplicate jars are never merged into the APK: D8 tolerated the
// duplicate types in debug, but R8 rejects them, and every release build failed from the split
// until this was corrected. :feature:automation genuinely owns its source and must remain an
// implementation dependency so its classes are packaged. Making core modules the real owners is
// the XL item in ROADMAP.md.

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
    compileOnly(project(":core:common"))
    compileOnly(project(":core:model"))
    compileOnly(project(":core:storage"))
    compileOnly(project(":core:engine"))
    implementation(project(":feature:automation"))
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
    implementation(libs.shizuku.aidl)
    implementation(libs.shizuku.provider)
    implementation(libs.unifiedpush.connector)
    baselineProfile(project(":baselineprofile"))
    // On-device APK signing for the generated per-target share-relay APKs (core/share/relay).
    implementation(libs.apksig)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.bouncycastle.bcpkix)
    testImplementation(libs.work.testing)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4-accessibility")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    screenshotTestImplementation(libs.screenshot.validation.api)
    screenshotTestImplementation(libs.androidx.compose.ui.tooling)
}

// Fuzzing is deliberately isolated from every Android and release configuration. The target
// source lives under src/fuzzTest, the dependency is resolved only by these opt-in tasks, and the
// normal localQualityGate never depends on either task.
val fuzzRuntimeClasspath = configurations.create("fuzzRuntimeClasspath") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    add(fuzzRuntimeClasspath.name, libs.jazzer)
    add(fuzzRuntimeClasspath.name, libs.junit)
}

val debugKotlinClasses = layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")
val debugJavaClasses = layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes")
val unitTestKotlinClasses = layout.buildDirectory.dir("intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes")
val unitTestJavaClasses = layout.buildDirectory.dir("intermediates/javac/debugUnitTest/compileDebugUnitTestJavaWithJavac/classes")
val fuzzSourceDirectory = layout.projectDirectory.dir("src/fuzzTest/java")
val fuzzClassesDirectory = layout.buildDirectory.dir("intermediates/fuzzTest/classes")
val fuzzCorpusDirectory = layout.projectDirectory.dir("src/fuzzTest/corpus/external-decoders")
val fuzzRegressionDirectory = layout.projectDirectory.dir("src/fuzzTest/regression/external-decoders")
val fuzzWorkingCorpusDirectory = layout.buildDirectory.dir("fuzz/corpus")
val fuzzClasspath = files(
    debugKotlinClasses,
    debugJavaClasses,
    unitTestKotlinClasses,
    unitTestJavaClasses,
    fuzzRuntimeClasspath,
)

val compileFuzzTestJava = tasks.register<JavaCompile>("compileFuzzTestJava") {
    group = "verification"
    description = "Compiles the opt-in JVM fuzz target without adding it to an Android variant."
    dependsOn(
        "compileDebugKotlin",
        "compileDebugJavaWithJavac",
        "compileDebugUnitTestKotlin",
        "compileDebugUnitTestJavaWithJavac",
    )
    source(fileTree(fuzzSourceDirectory))
    destinationDirectory.set(fuzzClassesDirectory)
    classpath = fuzzClasspath
    doFirst {
        classpath = files(
            fuzzClasspath,
            tasks.named<JavaCompile>("compileDebugJavaWithJavac").get().classpath,
        )
    }
    options.encoding = "UTF-8"
    options.release.set(17)
}

val fuzzSeconds = providers.gradleProperty("fuzzSeconds").orElse("30")
val fuzzArtifactsDirectory = layout.buildDirectory.dir("fuzz/artifacts")
val fuzzStderrFile = layout.buildDirectory.file("fuzz/stderr.log")
val fuzzJavaExecClasspath = files(fuzzClassesDirectory, fuzzClasspath)
val prepareFuzzCorpus = tasks.register<Sync>("prepareFuzzCorpus") {
    group = "verification"
    description = "Copies the checked-in decoder seeds into the ignored fuzz workspace."
    from(fuzzCorpusDirectory)
    into(fuzzWorkingCorpusDirectory)
}

tasks.register<JavaExec>("fuzzExternalDecoders") {
    group = "verification"
    description = "Runs the opt-in coverage-guided fuzz target for external decoders."
    dependsOn(compileFuzzTestJava, prepareFuzzCorpus)
    mainClass.set("com.code_intelligence.jazzer.Jazzer")
    classpath = fuzzJavaExecClasspath
    doFirst {
        fuzzArtifactsDirectory.get().asFile.mkdirs()
        fuzzStderrFile.get().asFile.parentFile.mkdirs()
        errorOutput = fuzzStderrFile.get().asFile.outputStream()
        classpath = files(
            fuzzJavaExecClasspath,
            tasks.named<JavaCompile>("compileDebugJavaWithJavac").get().classpath,
        )
    }
    workingDir = rootProject.projectDir
    args(
        "--target_class=com.opentasker.fuzz.ExternalDecoderFuzzTarget",
        "--instrumentation_includes=com.opentasker.**",
        "-max_total_time=${fuzzSeconds.get()}",
        "-artifact_prefix=${fuzzArtifactsDirectory.get().asFile.absolutePath}${File.separator}",
        fuzzWorkingCorpusDirectory.get().asFile.absolutePath,
    )
}

tasks.register<JavaExec>("fuzzExternalDecoderRegression") {
    group = "verification"
    description = "Runs the checked-in deterministic external-decoder crash regression corpus."
    dependsOn(compileFuzzTestJava)
    mainClass.set("org.junit.runner.JUnitCore")
    classpath = fuzzJavaExecClasspath
    doFirst {
        check(fuzzRegressionDirectory.asFile.isDirectory) {
            "Missing decoder regression corpus at ${fuzzRegressionDirectory.asFile}"
        }
        classpath = files(
            fuzzJavaExecClasspath,
            tasks.named<JavaCompile>("compileDebugJavaWithJavac").get().classpath,
        )
    }
    workingDir = rootProject.projectDir
    args("com.opentasker.fuzz.ExternalDecoderRegressionTest")
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

        val forbiddenNetty = resolved.filter { coordinate ->
            coordinate.startsWith("io.netty:") || coordinate.startsWith("io.grpc:grpc-netty:")
        }
        check(forbiddenNetty.isEmpty()) {
            "Release runtime must not ship Netty or grpc-netty components: ${forbiddenNetty.joinToString()}"
        }

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
        check("<verify-signatures>true</verify-signatures>" in verification) {
            "Gradle dependency verification metadata must enforce signature verification."
        }
        check("<trusted-key " in verification && "<trusted-artifacts" !in verification) {
            "Dependency verification must use an explicit trusted-key set without blanket trust."
        }
        val checksumCount = Regex("<sha256\\b").findAll(verification).count()
        val provenanceCount = Regex("<sha256\\b[^>]*\\borigin=\"([^\"]+)\"").findAll(verification).count()
        check(checksumCount > 0 && checksumCount == provenanceCount && "Generated by Gradle" !in verification) {
            "Every dependency checksum must carry reviewed upstream provenance; Gradle-generated origins are forbidden."
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

    @get:org.gradle.api.tasks.OutputFile
    abstract val reportFile: org.gradle.api.file.RegularFileProperty

    @org.gradle.api.tasks.TaskAction
    fun verify() {
        val reports = resultsDirectory.get().asFile.listFiles { file ->
            file.isFile && file.name.startsWith("TEST-") && file.extension == "xml"
        }.orEmpty()
        check(reports.isNotEmpty()) { "No JVM test XML reports were produced." }
        fun count(attribute: String): Int = reports.sumOf { report ->
            Regex("""\b$attribute="(\d+)"""").find(report.readText())?.groupValues?.get(1)?.toInt() ?: 0
        }
        // JUnit's tests= attribute includes skipped tests, so an assumption-skip or @Ignore could
        // satisfy the floor while asserting nothing.
        val tests = count("tests") - count("skipped")
        val failures = count("failures")
        val errors = count("errors")
        val floor = minimumTests.get()
        val report = reportFile.get().asFile
        report.parentFile.mkdirs()
        report.writeText(
            """{
  "schemaVersion": 1,
  "observedTests": $tests,
  "configuredFloor": $floor,
  "failures": $failures,
  "errors": $errors,
  "status": "${if (failures == 0 && errors == 0 && tests >= floor) "passed" else "failed"}"
}
""",
        )
        check(failures == 0 && errors == 0) { "JVM tests reported $failures failure(s) and $errors error(s)." }
        check(tests >= floor) {
            "JVM test floor regressed: observed $tests, configured floor $floor."
        }
        println("JVM test gate passed: observed $tests tests, configured floor $floor, 0 failures, 0 errors.")
    }
}

abstract class VerifyCoverageFloorTask : org.gradle.api.DefaultTask() {
    @get:org.gradle.api.tasks.InputFile
    @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    abstract val reportFile: org.gradle.api.file.RegularFileProperty

    @get:org.gradle.api.tasks.Input
    abstract val areaFloors: org.gradle.api.provider.MapProperty<String, Double>

    @org.gradle.api.tasks.TaskAction
    fun verify() {
        val report = reportFile.get().asFile
        check(report.isFile) { "JaCoCo XML report is missing: ${report.absolutePath}" }

        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val document = factory.newDocumentBuilder().parse(report)
        val packages = document.getElementsByTagName("package")

        areaFloors.get().forEach { (area, minimumPercent) ->
            var missed = 0L
            var covered = 0L
            var matched = false
            for (index in 0 until packages.length) {
                val packageNode = packages.item(index)
                val packageName = packageNode.attributes?.getNamedItem("name")?.nodeValue ?: continue
                if (packageName != area && !packageName.startsWith("$area/")) continue
                matched = true
                val counters = packageNode.childNodes
                for (counterIndex in 0 until counters.length) {
                    val counter = counters.item(counterIndex)
                    if (counter.nodeName != "counter" ||
                        counter.attributes?.getNamedItem("type")?.nodeValue != "INSTRUCTION"
                    ) continue
                    missed += counter.attributes?.getNamedItem("missed")?.nodeValue?.toLongOrNull() ?: 0L
                    covered += counter.attributes?.getNamedItem("covered")?.nodeValue?.toLongOrNull() ?: 0L
                }
            }
            check(matched) { "JaCoCo report contains no classes for coverage area '$area'." }
            val total = missed + covered
            check(total > 0L) { "JaCoCo report contains no instructions for coverage area '$area'." }
            val percent = covered.toDouble() * 100.0 / total
            check(percent + 0.000_001 >= minimumPercent) {
                "Coverage floor regressed for $area: %.2f%%, expected at least %.2f%%.".format(
                    percent,
                    minimumPercent,
                )
            }
            println("Coverage floor passed: $area %.2f%% >= %.2f%%.".format(percent, minimumPercent))
        }
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

abstract class VerifyPackagedTypeCompletenessTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apks: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        val apkFiles = apks.files.sortedBy { it.name }
        check(apkFiles.isNotEmpty()) { "No APKs were supplied for packaged-type verification." }

        apkFiles.forEach { apkFile ->
            check(apkFile.isFile) {
                "APK for packaged-type verification is missing: ${apkFile.absolutePath}"
            }
            val referenced = linkedSetOf<String>()
            val defined = linkedSetOf<String>()
            var dexCount = 0

            ZipFile(apkFile).use { archive ->
                archive.entries().asSequence()
                    .filter { entry -> DEX_ENTRY_PATTERN.matches(entry.name) }
                    .sortedBy { entry -> entry.name }
                    .forEach { entry ->
                        check(entry.size in 1..MAX_DEX_BYTES) {
                            "${apkFile.name}/${entry.name} has an invalid audit size: ${entry.size} bytes"
                        }
                        val inventory = dexTypeInventory(
                            archive.getInputStream(entry).use { stream -> stream.readBytes() },
                            "${apkFile.name}/${entry.name}",
                        )
                        referenced += inventory.referenced
                        defined += inventory.defined
                        dexCount += 1
                    }
            }

            check(dexCount > 0) { "${apkFile.name} contains no classes*.dex entries." }
            val missing = (referenced - defined).sorted()
            check(missing.isEmpty()) {
                buildString {
                    appendLine("${apkFile.name} references OpenTasker types that it does not define:")
                    missing.forEach { descriptor -> appendLine("  $descriptor") }
                    append("Every Lcom/opentasker/ reference must be packaged in the same APK.")
                }
            }
            println(
                "Packaged-type completeness passed for ${apkFile.name}: " +
                    "$dexCount dex file(s), ${defined.size} OpenTasker type(s) defined.",
            )
        }
    }

    private fun dexTypeInventory(bytes: ByteArray, source: String): DexTypeInventory {
        check(bytes.size >= DEX_HEADER_BYTES) { "$source is too small to be a DEX file." }
        check(bytes[0] == 'd'.code.toByte() && bytes[1] == 'e'.code.toByte() &&
            bytes[2] == 'x'.code.toByte() && bytes[3] == '\n'.code.toByte()) {
            "$source does not use the standard DEX format."
        }

        fun uint(offset: Int): Long {
            check(offset >= 0 && offset + 4 <= bytes.size) { "$source has a truncated DEX integer." }
            return (bytes[offset].toLong() and 0xff) or
                ((bytes[offset + 1].toLong() and 0xff) shl 8) or
                ((bytes[offset + 2].toLong() and 0xff) shl 16) or
                ((bytes[offset + 3].toLong() and 0xff) shl 24)
        }

        fun table(sizeOffset: Int, dataOffset: Int, width: Int, label: String): Pair<Int, Int> {
            val count = uint(sizeOffset)
            val offset = uint(dataOffset)
            check(count <= Int.MAX_VALUE && offset <= Int.MAX_VALUE) {
                "$source has an oversized $label table."
            }
            check(offset + count * width <= bytes.size.toLong()) {
                "$source has a truncated $label table."
            }
            return count.toInt() to offset.toInt()
        }

        val (stringCount, stringOffset) = table(56, 60, 4, "string-id")
        val (typeCount, typeOffset) = table(64, 68, 4, "type-id")
        val (classCount, classOffset) = table(96, 100, 32, "class-def")
        val stringCache = arrayOfNulls<String>(stringCount)

        fun stringAt(index: Int): String {
            check(index in 0 until stringCount) { "$source references invalid string index $index." }
            stringCache[index]?.let { return it }
            val dataOffset = uint(stringOffset + index * 4)
            check(dataOffset < bytes.size) { "$source references a string outside the DEX file." }
            var cursor = dataOffset.toInt()
            var lengthBytes = 0
            var value: Int
            do {
                check(cursor < bytes.size && lengthBytes < 5) {
                    "$source has a malformed string length."
                }
                value = bytes[cursor].toInt() and 0xff
                cursor += 1
                lengthBytes += 1
            } while (value and 0x80 != 0)
            var end = cursor
            while (end < bytes.size && bytes[end] != 0.toByte()) end += 1
            check(end < bytes.size) { "$source has an unterminated DEX string." }
            return String(bytes, cursor, end - cursor, Charsets.UTF_8).also { stringCache[index] = it }
        }

        val typeDescriptors = ArrayList<String>(typeCount)
        repeat(typeCount) { index ->
            val descriptorIndex = uint(typeOffset + index * 4)
            check(descriptorIndex <= Int.MAX_VALUE) { "$source has an oversized descriptor index." }
            typeDescriptors += stringAt(descriptorIndex.toInt())
        }
        val referenced = typeDescriptors.mapNotNullTo(linkedSetOf(), ::openTaskerDescriptor)
        val defined = linkedSetOf<String>()
        repeat(classCount) { index ->
            val classIndex = uint(classOffset + index * 32)
            check(classIndex < typeDescriptors.size) { "$source has an invalid class type index." }
            openTaskerDescriptor(typeDescriptors[classIndex.toInt()])?.let(defined::add)
        }
        return DexTypeInventory(referenced = referenced, defined = defined)
    }

    private fun openTaskerDescriptor(descriptor: String): String? {
        val component = descriptor.dropWhile { character -> character == '[' }
        return component.takeIf { value -> value.startsWith(OPEN_TASKER_DESCRIPTOR_PREFIX) }
    }

    private data class DexTypeInventory(
        val referenced: Set<String>,
        val defined: Set<String>,
    )

    private companion object {
        val DEX_ENTRY_PATTERN = Regex("classes(?:\\d+)?\\.dex")
        const val DEX_HEADER_BYTES = 112
        const val MAX_DEX_BYTES = 256L * 1024 * 1024
        const val OPEN_TASKER_DESCRIPTOR_PREFIX = "Lcom/opentasker/"
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

tasks.register("verifyFuzzDependencyIsolation") {
    group = "verification"
    description = "Checks that the opt-in fuzzing dependency stays out of release runtime resolution."
    doLast {
        check(releaseRuntimeCoordinates.get().none { it.startsWith("com.code-intelligence:") }) {
            "Jazzer must not appear in the release runtime dependency graph."
        }
        println("Fuzz dependency isolation passed: Jazzer is absent from release runtime resolution.")
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
    reportFile.set(rootProject.layout.buildDirectory.file("reports/opentasker/jvm-test-count.json"))
    minimumTests.set(JVM_TEST_FLOOR)
}

val debugCoverageXml = layout.buildDirectory.file("reports/jacoco/debugCoverage/debugCoverage.xml")
val generateDebugCoverage = tasks.register<JacocoReport>("generateDebugCoverage") {
    group = "verification"
    description = "Produces the deterministic debug JVM JaCoCo coverage report."
    dependsOn("testDebugUnitTest")
    executionData.setFrom(layout.buildDirectory.file("jacoco/testDebugUnitTest.exec"))

    classDirectories.setFrom(
        layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes").map { directory ->
            fileTree(directory.asFile) {
                exclude("**/R.class", "**/R\$*.class", "**/BuildConfig.*", "**/Manifest*.*")
            }
        },
        layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes").map { directory ->
            fileTree(directory.asFile) {
                exclude("**/R.class", "**/R\$*.class", "**/BuildConfig.*", "**/Manifest*.*")
            }
        },
    )
    sourceDirectories.setFrom(files("src/main/java", "src/main/kotlin"))
    reports {
        xml.required.set(true)
        xml.outputLocation.set(debugCoverageXml)
        html.required.set(true)
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/debugCoverage/html"))
        csv.required.set(false)
    }
}

val verifyCoverageFloor = tasks.register<VerifyCoverageFloorTask>("verifyCoverageFloor") {
    group = "verification"
    description = "Fails if coverage regresses in the previously untested runtime areas."
    dependsOn(generateDebugCoverage)
    reportFile.set(debugCoverageXml)
    areaFloors.set(
        mapOf(
            "com/opentasker/core/scheduling" to 50.0,
            // VariableExpander feeds runtime action arguments through VariableStore; silent
            // regressions here can corrupt every profile that composes or transforms variables.
            "com/opentasker/core/engine/variables" to 70.0,
            "com/opentasker/automation/receiver" to 15.0,
            "com/opentasker/ui/utils" to 15.0,
        ),
    )
}

val verifyLocaleResources = tasks.register<VerifyLocaleResourcesTask>("verifyLocaleResources") {
    group = "verification"
    description = "Fails if an alternate locale is below the release translation threshold."
    resourcesDirectory.set(layout.projectDirectory.dir("src/main/res"))
    completionThreshold.set(LOCALE_COMPLETION_THRESHOLD)
}

val qualityGateSeedFailure = providers.gradleProperty("openTaskerQualityGateSeedFailure")
    .map(String::toBoolean)
    .orElse(false)
val verifyQualityGateSeed = tasks.register<VerifyQualityGateSeedTask>("verifyQualityGateSeed") {
    group = "verification"
    description = "Provides an explicit seeded failure used to prove the local gate exits nonzero."
    seedFailure.set(qualityGateSeedFailure)
}

tasks.register<VerifyRoomSchemaTask>("verifyRoomSchema") {
    group = "verification"
    description = "Checks that all Room schema versions up to the current are exported and tracked."

    dependsOn("kspDebugKotlin")
    schemaDirectory.set(layout.projectDirectory.dir("schemas/com.opentasker.core.storage.AppDatabase"))
    databaseFile.set(rootProject.layout.projectDirectory.file(
        "app/src/main/java/com/opentasker/core/storage/AppDatabase.kt",
    ))
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

        // Freshness, on the same principle as the store screenshots: the checks above are
        // substring greps over sources in the same trust domain as the thing they certify, so a
        // profile captured many releases ago passed forever. The recorded version code is what
        // makes "this profile describes this build" falsifiable.
        val capturedAt = profileFile.asFile.resolveSibling("baseline-prof-captured-at-version-code.txt")
        check(capturedAt.isFile) {
            "Missing ${capturedAt.name}; regenerate the baseline profile " +
                "(:app:generateBaselineProfile on an API 35+ device) and record the version code."
        }
        val recorded = capturedAt.readText().trim()
        check(recorded == appVersionCode.toString()) {
            "The baseline profile was captured at version code $recorded but this release is " +
                "$appVersionCode. Regenerate it so the shipped profile matches the shipped code."
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
    val actionCatalogFilePath = projectDir.resolve("src/main/java/com/opentasker/core/actions/ActionCatalog.kt")
    val contextSpecFilePath = projectDir.resolve("src/main/java/com/opentasker/core/model/ContextSpec.kt")
    val databaseFilePath = projectDir.resolve("src/main/java/com/opentasker/core/storage/AppDatabase.kt")
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
    actionCount.set(deriveRegisteredActionCount(actionCatalogFilePath))
    contextFamilyCount.set(deriveContextFamilyCount(contextSpecFilePath))
    schemaVersion.set(deriveRoomSchemaVersion(databaseFilePath))
    repositoryRoot.set(repositoryRootPath)
}

tasks.register<VerifyReleaseTruthTask>("verifyReleaseTruth") {
    truthFile.set(rootProject.layout.projectDirectory.file("tools/release-truth.json"))
    readmeFile.set(rootProject.layout.projectDirectory.file("README.md"))
    metadataFile.set(rootProject.layout.projectDirectory.file("fdroid/metadata/com.opentasker.app.yml"))
    moduleBuildFile.set(layout.projectDirectory.file("build.gradle.kts"))
    actionCatalogFile.set(layout.projectDirectory.file("src/main/java/com/opentasker/core/actions/ActionCatalog.kt"))
    contextSpecFile.set(layout.projectDirectory.file("src/main/java/com/opentasker/core/model/ContextSpec.kt"))
    bundleFile.set(layout.projectDirectory.file("src/main/java/com/opentasker/core/transfer/OpenTaskerBundle.kt"))
    versionCatalogFile.set(rootProject.layout.projectDirectory.file("gradle/libs.versions.toml"))
    wrapperFile.set(rootProject.layout.projectDirectory.file("gradle/wrapper/gradle-wrapper.properties"))
    flowControlFile.set(layout.projectDirectory.file("src/main/java/com/opentasker/core/engine/FlowStructure.kt"))
    taskRunnerFile.set(layout.projectDirectory.file("src/main/java/com/opentasker/core/engine/TaskRunner.kt"))
    databaseFile.set(layout.projectDirectory.file("src/main/java/com/opentasker/core/storage/AppDatabase.kt"))
    changelogFile.set(rootProject.layout.projectDirectory.file("CHANGELOG.md"))
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
        // Upstream dropped a `check(distribution in distributions)` here: the value is already
        // validated by the require() at the top of this script, so the assertion could never fail
        // and the readiness check was reporting a pass it had not earned.
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

        // This task describes the artifact the F-Droid distribution produces, so running it against
        // any other distribution previously printed "F-Droid metadata check passed" for a build
        // whose output does not match the recipe at all.
        check(selectedDistribution == "fdroid") {
            "verifyFdroidMetadata checks the F-Droid build recipe; run it with " +
                "-PopenTaskerDistribution=fdroid (current distribution: $selectedDistribution)"
        }

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
        // Derived from the release signing decision rather than hard-coded, so re-signing the
        // F-Droid build fails this check instead of silently renaming the artifact out from under
        // the recipe. AGP only emits the "-unsigned" name when the build type has no signing
        // config at all.
        check(expectedReleaseApkPath in metadata) {
            "F-Droid metadata must point to $expectedReleaseApkPath, the artifact this build " +
                "configuration actually produces for distribution=$selectedDistribution"
        }

        // Upstream also verifies the F-Droid store listing (title/description length,
        // >=4 screenshots, a changelog per version code) here. Not taken: this fork ships the
        // `standard` distribution and publishes no F-Droid listing, and those checks reach for
        // rootProject inside doLast, which this build's configuration cache forbids.
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

val verifyPackagedTypeCompleteness = tasks.register<VerifyPackagedTypeCompletenessTask>(
    "verifyPackagedTypeCompleteness",
) {
    group = "verification"
    description = "Checks debug and release DEX files for referenced OpenTasker types missing from the APK."
    dependsOn("packageDebug", "packageRelease")
    apks.from(
        layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"),
        rootProject.layout.projectDirectory.file(expectedReleaseApkPath),
    )
}

tasks.register("localQualityGate") {
    group = "verification"
    description = "Runs the deterministic local debug-quality and dependency-report gate."
    dependsOn(
        "lintDebug",
        "compileDebugAndroidTestKotlin",
        "connectedDebugAndroidTest",
        "verifyRoomSchema",
        "verifyReleaseTruth",
        verifyResolvedDependencyPolicy,
        generateCycloneDxSbom,
        verifyJvmTestCount,
        verifyCoverageFloor,
        verifyLocaleResources,
        verifyQualityGateSeed,
        verifyPackagedTypeCompleteness,
        "verifyNativePageAlignment",
        "verifyFuzzDependencyIsolation",
        verifyPerformanceEvidence,
        verifyDocumentationTruth,
        "validateDebugScreenshotTest",
    )
}

tasks.register<VerifyNativePageAlignmentTask>("verifyNativePageAlignment") {
    group = "verification"
    description = "Checks that packaged native ELFs are read-only and have 16 KB PT_LOAD alignment."
    dependsOn("packageDebug")
    apk.set(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
}

/**
 * The same audit against the artifact that actually ships. Packaging is identical across build
 * types today, so the debug check transfers - but that is a coincidence the release must not rely
 * on, and a release-only packaging change would otherwise escape the 16 KB gate entirely.
 */
tasks.register<VerifyNativePageAlignmentTask>("verifyReleaseNativePageAlignment") {
    group = "verification"
    description = "Checks 16 KB PT_LOAD alignment in the release APK that ships."
    dependsOn("packageRelease")
    // Same single source of truth the build recipe and reproducibility harness use.
    apk.set(rootProject.layout.projectDirectory.file(expectedReleaseApkPath))
}

/**
 * Writes the F-Droid per-version changelog from the current CHANGELOG section.
 *
 * F-Droid renders one file per version code and truncates hard, so the section is condensed to its
 * bullet leads rather than copied verbatim. Review the result before releasing; this produces a
 * starting point, it does not replace judgement.
 */
tasks.register("generateFdroidChangelog") {
    group = "release"
    description = "Writes fastlane/metadata/android/en-US/changelogs/<versionCode>.txt from CHANGELOG.md."

    val changelogFile = rootProject.file("CHANGELOG.md")
    val versionName = appVersionName
    val versionCode = appVersionCode
    val outputFile = rootProject.file("fastlane/metadata/android/en-US/changelogs/$versionCode.txt")
    inputs.file(changelogFile)
    outputs.file(outputFile)

    doLast {
        val section = changelogFile.readText()
            .substringAfter("## v$versionName", "")
            .substringBefore(NEWLINE + "## ")
        check(section.isNotBlank()) {
            "CHANGELOG.md has no '## v$versionName' section to generate a store changelog from"
        }
        val bullets = section.lines()
            .map(String::trim)
            .filter { it.startsWith("- ") }
            .map { it.removePrefix("- ").replace(Regex("[`*]"), "") }
        check(bullets.isNotEmpty()) { "The '## v$versionName' CHANGELOG section has no entries" }

        val body = StringBuilder()
        for (bullet in bullets) {
            val line = bullet.substringBefore(". ").trimEnd('.') + "."
            if (body.length + line.length + 1 > FDROID_CHANGELOG_MAX_CHARS) break
            if (body.isNotEmpty()) body.append(NEWLINE)
            body.append(line)
        }
        outputFile.parentFile.mkdirs()
        outputFile.writeText(body.toString().trim() + NEWLINE)
        println("Wrote ${outputFile.relativeTo(rootProject.projectDir)} (${outputFile.readText().length} chars)")
    }
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
    // The guard that makes the monotonic rule a fact rather than a convention: a build whose
    // versionCode does not exceed the highest one this repo has already produced cannot install over
    // it, so refuse to produce it at all rather than discover it on the phone.
    check(versionCode > lastBuiltVersionCode) {
        "versionCode $versionCode would not exceed the last built $lastBuiltVersionCode — an installer " +
            "reads this as a downgrade and refuses the update. Raise BUILD_NUMBER in gradle.properties " +
            "(it must keep running upward while appVersionCode stays at $appVersionCode); reset it to 1 " +
            "only when appVersionCode itself moves."
    }
    doLast {
        val outputDir = outputDirProvider.get().asFile
        val targetDir = File(System.getProperty("user.home"), "tmp").apply { mkdirs() }
        val apk = outputDir.listFiles { _, name -> name.endsWith(".apk") }?.firstOrNull()
            ?: throw GradleException("No APK found in $outputDir")
        val target = File(targetDir, apkName)
        apk.copyTo(target, overwrite = true)
        println("\u001b[1;36m>>> ${target.absolutePath}\u001b[0m")
        println("\u001b[1;36m>>> versionCode $versionCode\u001b[0m")

        // Auto-increment BUILD_NUMBER, and record the code just built as the new floor.
        val text = propsFile.readText()
        val bumped =
            if (Regex("(?m)^BUILD_NUMBER=").containsMatchIn(text))
                text.replace(Regex("(?m)^BUILD_NUMBER=.*$"), "BUILD_NUMBER=$nextBuildNumber")
            else text.trimEnd() + "\n\n# shiroikuma fork: per-build version tail\nBUILD_NUMBER=$nextBuildNumber\n"
        propsFile.writeText(
            if (Regex("(?m)^LAST_BUILT_VERSION_CODE=").containsMatchIn(bumped))
                bumped.replace(Regex("(?m)^LAST_BUILT_VERSION_CODE=.*$"), "LAST_BUILT_VERSION_CODE=$versionCode")
            else bumped.trimEnd() + "\nLAST_BUILT_VERSION_CODE=$versionCode\n"
        )
        println("\u001b[1;36m>>> BUILD_NUMBER bumped to $nextBuildNumber\u001b[0m")
    }
}

// ---------------------------------------------------------------------------------------------
// 文字認識 (OCR) dictionaries — PP-OCRv5, Apache-2.0.
//
// The DICTIONARIES ship (95 KB all told) and the ~100 MB of ONNX weights do NOT (白い熊, 2026-08-08).
// A dictionary has to match its model exactly, so bundling them removes a whole failure mode and
// costs nothing; the weights are chosen in 「文字認識」 settings and read from wherever they live.
// That takes the APK from 129 MB back to about 5 MB, which matters because no build is ever deleted
// from the phone — twenty of them is 2.5 GB at the old size.
//
// Fetched once per machine, pinned to immutable revisions and verified by SHA-256.
val ocrAssetsDir = layout.projectDirectory.dir("src/main/assets/ocr")

data class OcrModelAsset(val name: String, val url: String, val sha256: String)

val ocrModelAssets = listOf(
    // NOTE: CRLF. OcrCharset strips the trailing '\r'; without that every recognised character carries one.
    OcrModelAsset(
        "dict_jpn.txt",
        "https://huggingface.co/bukuroo/PPOCRv5-ONNX/resolve/47b3e1b4e90c79737cb71f562a6c85809067c7a5/ppocrv5_dict.txt",
        "1ea29636956177e400af712d9782e7693f3fb25f98617bed10479d2965a836fd",
    ),
    OcrModelAsset(
        "dict_latin.txt",
        "https://huggingface.co/monkt/paddleocr-onnx/resolve/7b02d0a30a07ba2b92ad1ff5a8941ae2c633de65/languages/latin/dict.txt",
        "3c0a8a79b612653c25f765271714f71281e4e955962c153e272b7b8c1d2b13ff",
    ),
    OcrModelAsset(
        "dict_eslav.txt",
        "https://huggingface.co/monkt/paddleocr-onnx/resolve/7b02d0a30a07ba2b92ad1ff5a8941ae2c633de65/languages/eslav/dict.txt",
        "3e95f1581557162870cacdba5af91a4c6be2890710d395b0c3c7578e7ee5e6eb",
    ),
)

val downloadOcrModels = tasks.register("downloadOcrModels") {
    group = "build setup"
    description = "Fetch the pinned PP-OCRv5 dictionaries into src/main/assets/ocr (SHA-256 verified)."

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
        // Weights left behind by an older build would be packaged for nothing.
        targetDir.listFiles()?.forEach { if (it.name.endsWith(".onnx")) it.delete() }

        assets.forEach { asset ->
            val file = File(targetDir, asset.name)
            if (file.isFile && digestOf(file) == asset.sha256) return@forEach

            println(">>> OCR dictionary: fetching ${asset.name}")
            val temporary = File(targetDir, "${asset.name}.part")
            URI(asset.url).toURL().openStream().use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            val actual = digestOf(temporary)
            if (actual != asset.sha256) {
                temporary.delete()
                throw GradleException(
                    "OCR dictionary ${asset.name} failed verification.\n" +
                        "  expected ${asset.sha256}\n  actual   $actual"
                )
            }
            temporary.renameTo(file)
        }
    }
}

tasks.named("preBuild") { dependsOn(downloadOcrModels) }
