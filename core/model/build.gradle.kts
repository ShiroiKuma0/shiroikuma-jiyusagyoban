plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

sourceSets {
    main {
        java.srcDir("$rootDir/app/src/main/java/com/opentasker/core/model")
        java.exclude("ProfileLifecyclePolicy.kt", "ProfileLifecycleStrings.kt")
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)
}
