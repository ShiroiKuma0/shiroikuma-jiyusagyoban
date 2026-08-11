plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.opentasker.core.common"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main").kotlin.directories.add("$rootDir/app/src/main/java/com/opentasker/core/logging")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
