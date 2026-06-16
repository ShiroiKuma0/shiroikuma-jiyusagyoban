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
val appVersionCode = 70
val appVersionName = "0.2.68"
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
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.opentasker.app"
        minSdk = 26
        targetSdk = 36
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
        baseline = file("lint-baseline.xml")
        disable += listOf("MissingPermission", "CoarseFineLocation")
        abortOnError = false
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
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.re2j)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.room.testing)
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
        val forbidden = configurations
            .flatMap { configuration ->
                configuration.dependencies.mapNotNull { dependency ->
                    val group = dependency.group.orEmpty()
                    val name = dependency.name.lowercase()
                    val blocked = group in forbiddenGroups || forbiddenNames.any { token -> token in name }
                    if (blocked) "${configuration.name}:$group:${dependency.name}" else null
                }
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
