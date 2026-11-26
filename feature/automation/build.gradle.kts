plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

val lifecycleVersion = libs.versions.lifecycle.get()

configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "androidx.lifecycle") {
            useVersion(lifecycleVersion)
        }
    }
}

android {
    namespace = "com.sysadmindoc.ruledeck.feature.automation"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    constraints {
        // Compose asks for an older androidx.activity than the app ships, and only the app's
        // version is in gradle/verification-metadata.xml, so this module resolves that one too.
        implementation(libs.androidx.activity.compose)
    }
}
